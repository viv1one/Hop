package com.hop.relaynode

import com.hop.dht.NodeId
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * BUILD_PLAN.md Phase 4's volunteer relay-node fallback -- the last-resort
 * path for two peers who could not reach each other by direct dial or by
 * NAT hole-punching (address self-discovery + rendezvous-relayed
 * introduction, both already built -- see [com.hop.dht.DhtUdpTransport]'s
 * `reflectOwnAddress`/`introduce`), typically because both are behind
 * symmetric NAT. This class is only the standalone relay server's own
 * bridging primitive -- proven here via direct tests with plain test-side
 * sockets playing the role of two clients. Wiring an actual client-side
 * dial path that falls back to a relay (`p2p/`'s `PeerDialer`/
 * `InternetPeerConnectionManager`) is separate, later, client-side work --
 * this class deliberately does not know either of those types exist.
 *
 * ## The trust model this class is built on
 *
 * A volunteer relay's dishonesty cannot compromise confidentiality, because
 * every byte it ever forwards is already end-to-end ciphertext by the time
 * it reaches the relay -- this app's entire content/message pipeline is
 * encrypted before it ever touches any transport (ADR 0001's `crypto/`/
 * `protocol/` split; Double Ratchet for 1:1 messages, per-post
 * content-encryption keys for posts). This is the exact same guarantee
 * every ordinary mesh peer already provides when it relays someone else's
 * content opportunistically. So a *dedicated* volunteer relay node is not a
 * new confidentiality risk -- it's architecturally the same relay role the
 * mesh already has, just running as always-on internet infrastructure
 * instead of an incidental phone-to-phone hop.
 *
 * **Two real, distinct limitations a dedicated relay introduces (state
 * these plainly wherever this class is described -- do not paper over
 * them):**
 * 1. **Availability.** A relay can refuse to forward, or simply be offline
 *    -- the same best-effort, no-ack posture this codebase already uses
 *    everywhere else (mesh relay, internet-mode fanout). Nothing here
 *    retries on a caller's behalf or guarantees a bridge will ever form.
 * 2. **Traffic-analysis / metadata exposure.** Unlike incidental mesh relay
 *    (where a peer only sees whatever happens to flow through it as part
 *    of normal flood propagation), a dedicated relay bridge is a
 *    *targeted* pairing: two specific peers both deliberately connect to
 *    it asking to be bridged to *each other*. That is a sharper metadata
 *    signal ("these two peers wanted to talk, at this time, for this
 *    long") than incidental relay ever exposes. Do not describe this as
 *    "the same as mesh relay" -- it is not.
 *
 * **A third limitation this class deliberately does NOT solve, and must
 * not be mistaken for solving:** an unauthenticated relay with no
 * rate-limiting is an open proxy anyone could abuse for arbitrary traffic,
 * not just this app's mesh -- a real cost/liability concern for whoever
 * volunteers to run one. Real abuse-resistance (e.g. ADR 0004-style
 * attested-device-identity gating, per-source-IP limits, bandwidth caps)
 * is NOT implemented here and is unsolved future work -- ADR 0004's
 * attestation mechanism is Android-app-specific (Play Integrity/App
 * Attest) and does not naturally extend to a plain-JVM standalone tool
 * with no attestation mechanism of its own. What this class DOES include,
 * so a trivial flood of unmatched connection attempts can't exhaust the
 * process's own resources, is a basic resource bound: [maxConcurrentSlots]
 * (a cap on total concurrent bridged pairs + waiting connections) and
 * [waitTimeoutMs] (an idle/unmatched-connection timeout, also used as the
 * handshake-read timeout so a connection that never even finishes sending
 * its handshake can't hold a thread/socket open forever either). Neither
 * of those is abuse-resistance against a determined attacker -- they are
 * only a resource-exhaustion floor.
 *
 * ## Why this class has zero dependency on `protocol/`
 *
 * This module depends on `dht/` for [NodeId] only, and must NEVER depend on
 * `protocol/`, directly or transitively -- see `build.gradle.kts`'s own
 * comment and [RelayNodeTest]'s zero-`protocol/`-dependency check. This is
 * what makes "this module cannot read content" true by construction rather
 * than by policy, the same discipline `rendezvous/`'s own `RendezvousNode`
 * class doc already established for that module's narrower "cannot answer
 * content/topic queries" guarantee. This class does not know
 * `WireEnvelope`/`Frame`/`WirePayloadType` exist at all: whatever bytes it
 * forwards are, to this class, meaningless opaque data. It never inspects,
 * decodes, or logs the bytes flowing through a bridged pair -- only the
 * 64-byte handshake it reads for itself, which carries nothing but two raw
 * [NodeId]s.
 *
 * ## Wire shape (this module's own, NOT `protocol/`'s [com.hop.protocol.WireEnvelope])
 *
 * The very first thing read on an accepted connection, before anything else
 * is read or written on that socket, is exactly [HANDSHAKE_SIZE_BYTES]
 * bytes: `[32B ownId][32B bridgeToId]`, both raw [NodeId] bytes. Deliberately
 * NOT [com.hop.protocol.WireEnvelope]'s framing -- reusing that would
 * reintroduce a `protocol/` dependency, exactly what this module must never
 * have.
 *
 * ## Pairing
 *
 * A connection declaring `ownId=A, bridgeToId=B` is paired with a
 * previously-registered connection declaring the exact mutual reverse,
 * `ownId=B, bridgeToId=A` -- never a partial match (e.g. A is never bridged
 * to some other connection that merely also declared `bridgeToId=A`,
 * without that connection's own `ownId` being the exact `B` this connection
 * asked for). Once matched, both connections are removed from the waiting
 * registry and bidirectional blind byte-forwarding starts between their two
 * sockets (two threads, one per direction, until either side closes -- then
 * both sockets are closed and forwarding stops). A connection that never
 * finds a match within [waitTimeoutMs] is closed. All access to the waiting
 * registry happens under a single monitor ([lock]), so the match-or-register
 * decision and the timeout-driven self-removal are mutually exclusive --
 * there's no window in which two threads could each believe they own the
 * same waiting slot.
 *
 * `start()`/`stop()` lifecycle, matching `RendezvousNode`'s own shape.
 */
class RelayNode(
    private val serverSocket: ServerSocket,
    private val maxConcurrentSlots: Int = DEFAULT_MAX_CONCURRENT_SLOTS,
    private val waitTimeoutMs: Long = DEFAULT_WAIT_TIMEOUT_MS,
) {
    /** A connection that has handshaked but has no mutual match yet. */
    private class WaitingSlot(
        val socket: Socket,
        val bridgeToId: NodeId,
        /** Completed `true` by whichever thread finds and claims a mutual match for this slot, `false` if evicted by a duplicate re-registration under the same [NodeId]. Never completed by this slot's own timeout path -- that path only fires if the future is still incomplete after [waitTimeoutMs]. */
        val matched: CompletableFuture<Boolean> = CompletableFuture(),
    )

    /** Guards all reads/writes of [waiting] -- see this class's "Pairing" doc above for why this single monitor is what makes the pairing race-free. */
    private val lock = Object()

    /** Connections that have handshaked but have no mutual match yet, keyed by their own declared `ownId`. */
    private val waiting = HashMap<NodeId, WaitingSlot>()

    /** Count of currently-bridged pairs (each pair counts as ONE slot, not two, per this class's cap semantics -- see [maxConcurrentSlots]'s doc). */
    private val bridgedPairCount = AtomicInteger(0)

    /** Every socket this node has ever accepted and not yet closed -- tracked purely so [stop] can close everything still open, mirroring `DhtUdpTransport.stop()`'s "closing unblocks whatever's pending" posture but for a multi-socket server. */
    private val trackedSockets: MutableSet<Socket> = Collections.newSetFromMap(ConcurrentHashMap())

    @Volatile
    private var running = false

    @Volatile
    private var acceptThread: Thread? = null

    /** Spawns the accept loop. Safe to call once; a second call is a no-op. */
    fun start() {
        if (running) return
        running = true
        acceptThread = Thread({ acceptLoop() }, "hop-relay-accept").also { it.isDaemon = true; it.start() }
    }

    /**
     * Stops accepting new connections, closes [serverSocket] (unblocking a
     * pending `accept()`), and closes every socket this node currently has
     * open -- whether still waiting for a match or mid-bridge. Any thread
     * blocked in [handleConnection]'s handshake read or match-wait will
     * observe the resulting `IOException`/closed future and exit on its own.
     */
    fun stop() {
        running = false
        closeQuietly(serverSocket)

        synchronized(lock) {
            waiting.values.forEach { it.matched.complete(false) }
            waiting.clear()
        }

        val snapshot = trackedSockets.toList()
        snapshot.forEach { closeAndUntrack(it) }
    }

    private fun acceptLoop() {
        while (running) {
            val socket = try {
                serverSocket.accept()
            } catch (e: IOException) {
                // Expected on stop() closing serverSocket to unblock a pending
                // accept() call -- if we're still supposed to be running, this
                // was some other transient I/O error; keep serving rather than
                // letting one hiccup kill the whole accept loop.
                if (!running) break
                continue
            }
            trackedSockets.add(socket)
            Thread({ handleConnection(socket) }, "hop-relay-handler").also { it.isDaemon = true; it.start() }
        }
    }

    /**
     * One accepted connection's full lifecycle: read its fixed 64-byte
     * handshake, then either join it to a waiting mutual match (starting a
     * bridge) or register it as waiting (subject to [maxConcurrentSlots] and
     * [waitTimeoutMs]).
     */
    private fun handleConnection(socket: Socket) {
        val handshake = try {
            // Bounds how long this thread will block waiting for a peer to
            // finish sending its handshake -- otherwise a connection that
            // connects and then sends nothing would hold a thread and socket
            // open forever, exactly the trivial resource-exhaustion this
            // class's basic resource bound exists to prevent. Reusing
            // waitTimeoutMs here rather than a third constant: a real client
            // attempting relay fallback should already have this handshake
            // ready to send immediately upon connecting, so the same
            // "tens of seconds" order-of-magnitude budget applies to both.
            socket.soTimeout = waitTimeoutMs.toInt()
            readHandshake(socket)
        } catch (e: IOException) {
            closeAndUntrack(socket)
            return
        }
        if (handshake == null) {
            closeAndUntrack(socket)
            return
        }
        val (ownId, bridgeToId) = handshake

        // Bridging itself is expected to run far longer than waitTimeoutMs
        // (an ordinary chat/relay session, possibly idle for stretches) --
        // an active bridge must never be killed by a read timeout, so this
        // is cleared the moment the handshake is done, before any further
        // read/write happens on this socket.
        socket.soTimeout = 0

        val matchedPeerSocket: Socket?
        var newSlot: WaitingSlot? = null
        synchronized(lock) {
            val candidate = waiting[bridgeToId]
            // A plain nullable local, checked directly below via `!= null`,
            // rather than a Boolean flag -- so the compiler can smart-cast
            // it to non-null inside the branch that uses it, instead of
            // needing a manual !! there.
            val mutualMatch = if (candidate != null && candidate.bridgeToId == ownId) candidate else null
            if (mutualMatch != null) {
                waiting.remove(bridgeToId)
                mutualMatch.matched.complete(true)
                matchedPeerSocket = mutualMatch.socket
            } else if (waiting.size + bridgedPairCount.get() >= maxConcurrentSlots) {
                // This connection, if accepted, would become a NEW waiting
                // slot -- matching an existing waiter above does NOT grow
                // the total (one waiting slot removed, one bridged-pair slot
                // added -- net zero), so the cap only needs checking here.
                // See maxConcurrentSlots's doc.
                matchedPeerSocket = null
            } else {
                // Defensive: a second connection declaring the same ownId as
                // an already-waiting one is a client bug/misbehavior, not a
                // protocol case this class needs to support -- last one
                // wins, and the stale one is evicted rather than left to
                // leak a socket/thread.
                waiting.remove(ownId)?.let { stale ->
                    stale.matched.complete(false)
                    closeAndUntrack(stale.socket)
                }
                newSlot = WaitingSlot(socket = socket, bridgeToId = bridgeToId)
                waiting[ownId] = newSlot!!
                matchedPeerSocket = null
            }
        }

        if (matchedPeerSocket != null) {
            bridgedPairCount.incrementAndGet()
            bridge(socket, matchedPeerSocket)
            return
        }

        val slot = newSlot
        if (slot == null) {
            // Rejected: either over-cap, or (defensively) some other
            // unmatched non-registration path. Close rather than leave the
            // client hanging on a socket nobody will ever service.
            closeAndUntrack(socket)
            return
        }

        val gotMatched = try {
            slot.matched.get(waitTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            false
        } catch (e: Exception) {
            false
        }
        if (gotMatched) {
            // The matching thread already claimed this slot's socket and
            // started (or is starting) the bridge using it -- this thread's
            // job is done; it must not touch the socket again.
            return
        }

        synchronized(lock) {
            if (waiting[ownId] === slot) {
                waiting.remove(ownId)
            } else {
                // Already removed by a matcher between the timeout firing
                // above and this thread acquiring the lock -- that matcher
                // now owns this socket's lifecycle; don't close out from
                // under it.
                return
            }
        }
        closeAndUntrack(socket)
    }

    /** Reads exactly [HANDSHAKE_SIZE_BYTES] bytes and splits them into `(ownId, bridgeToId)`, or `null` if the peer closed before sending a complete handshake. */
    private fun readHandshake(socket: Socket): Pair<NodeId, NodeId>? {
        val buffer = ByteArray(HANDSHAKE_SIZE_BYTES)
        var offset = 0
        val input = socket.getInputStream()
        while (offset < HANDSHAKE_SIZE_BYTES) {
            val read = input.read(buffer, offset, HANDSHAKE_SIZE_BYTES - offset)
            if (read < 0) return null
            offset += read
        }
        val ownId = NodeId(buffer.copyOfRange(0, NodeId.SIZE_BYTES))
        val bridgeToId = NodeId(buffer.copyOfRange(NodeId.SIZE_BYTES, HANDSHAKE_SIZE_BYTES))
        return ownId to bridgeToId
    }

    /**
     * Starts bidirectional blind byte-forwarding between [a] and [b] -- two
     * threads, one per direction, plain `InputStream.copyTo(OutputStream)`
     * forwarding, until either side closes. Neither this method nor the
     * threads it starts ever inspect the bytes forwarded -- see this class's
     * doc on why that's load-bearing, not incidental.
     */
    private fun bridge(a: Socket, b: Socket) {
        val done = CountDownLatch(2)

        fun forwardAndSignal(from: Socket, to: Socket) {
            try {
                from.getInputStream().copyTo(to.getOutputStream())
            } catch (e: IOException) {
                // Expected once either side closes or resets mid-forward.
            } finally {
                closeAndUntrack(a)
                closeAndUntrack(b)
                done.countDown()
            }
        }

        Thread({ forwardAndSignal(a, b) }, "hop-relay-forward").also { it.isDaemon = true; it.start() }
        Thread({ forwardAndSignal(b, a) }, "hop-relay-forward").also { it.isDaemon = true; it.start() }

        Thread({
            done.await()
            bridgedPairCount.decrementAndGet()
        }, "hop-relay-bridge-cleanup").also { it.isDaemon = true; it.start() }
    }

    private fun closeAndUntrack(socket: Socket) {
        trackedSockets.remove(socket)
        closeQuietly(socket)
    }

    private fun closeQuietly(socket: Socket) {
        try {
            socket.close()
        } catch (e: IOException) {
            // Already closed, or never fully connected -- nothing to do.
        }
    }

    private fun closeQuietly(serverSocket: ServerSocket) {
        try {
            serverSocket.close()
        } catch (e: IOException) {
            // Already closed.
        }
    }

    companion object {
        /** `[32B ownId][32B bridgeToId]`, both raw [NodeId] bytes -- see this class's "Wire shape" doc above. */
        const val HANDSHAKE_SIZE_BYTES = NodeId.SIZE_BYTES * 2

        /**
         * Unmeasured placeholder, same posture as this codebase's other such
         * constants (`DhtUdpTransport.DEFAULT_REQUEST_TIMEOUT_MS`,
         * `RendezvousRegistry.DEFAULT_CAPACITY`) -- revisit once a real
         * volunteer relay's actual concurrent-load profile is known. Counts
         * total slots as (waiting connections) + (bridged pairs), NOT raw
         * sockets -- a bridged pair holds two sockets but counts as one slot,
         * since it represents one relayed conversation, not two independent
         * resource consumers. 256 is a low, deliberately conservative
         * starting point for a single volunteer-operated JVM process (each
         * bridged pair costs two long-lived forwarding threads plus one
         * cleanup thread; 256 pairs is ~768 threads worst case, comfortably
         * within a single JVM's default thread budget on ordinary consumer
         * hardware) -- not a claim that this is the right number for a
         * production relay fleet.
         */
        const val DEFAULT_MAX_CONCURRENT_SLOTS = 256

        /**
         * Unmeasured placeholder, same posture as [DEFAULT_MAX_CONCURRENT_SLOTS]
         * -- "tens of seconds," not measured against real relay-fallback
         * latency data. Used both as the handshake-read timeout (a
         * connection that doesn't even finish its 64-byte handshake this
         * quickly is almost certainly not a real client attempting relay
         * fallback) and as the unmatched-wait timeout (a real client
         * attempting relay fallback should already know it needs one and
         * connect to it promptly -- it is not expected to sit waiting for an
         * unbounded amount of time for its counterpart to show up).
         */
        const val DEFAULT_WAIT_TIMEOUT_MS = 30_000L
    }
}
