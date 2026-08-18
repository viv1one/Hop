package com.hop.transport

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import com.hop.crypto.DecayKeyStore
import com.hop.data.PostEntity
import com.hop.protocol.Frame
import com.hop.repository.PostRepository
import kotlinx.coroutines.runBlocking
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Real (non-spike) WiFi Direct transport. Group formation/negotiation
 * handling, the multi-client accept-loop server ([activeServerSocket]/
 * [stopServer]), the [activeGroupKey] duplicate-event guard, and continuous
 * discovery are ported near-verbatim from `com.hop.spike.WifiDirectSpike`
 * (left untouched, not imported from -- see that file's own doc for the
 * hardware-validated findings behind these mechanisms, and BUILD_PLAN.md
 * open decision #5 for the density concerns that shaped the accept loop and
 * the duplicate-connection-event guard).
 *
 * Two structural differences from the spike:
 * - Send path is a session-scoped outbox ([broadcastPost]) instead of a
 *   single `pendingClip` -- every currently-queued post is offered to every
 *   peer this device connects to, not just the most recently queued one.
 * - Receive path decodes and stores every queued frame a peer sends (loops
 *   until the socket closes/EOF), and stores ciphertext + key separately
 *   (matching [PostRepository]'s on-demand-decrypt design) instead of
 *   force-decrypting and handing off to a system viewer.
 */
class WifiDirectTransport(
    context: Context,
    private val channel: WifiP2pManager.Channel,
    postRepository: PostRepository,
    decayKeyStore: DecayKeyStore,
    private val onLog: (String) -> Unit = {},
) {
    companion object {
        const val TRANSFER_PORT = 8988

        /**
         * See [connectWithRetry]'s doc for the real-hardware race this guards
         * against. Total budget (~20s: 20 attempts, ~1s apart) is deliberately
         * generous -- real-hardware testing found the gap between two
         * devices' app launches (and therefore between one side's server bind
         * and the other's client connect) can exceed 4 seconds even under
         * normal use, not just contrived timing. A short budget here doesn't
         * just fail this one connection attempt: see [activeGroupKey]'s doc
         * for why exhausting it used to leave the device permanently stuck.
         */
        private const val CONNECT_RETRY_ATTEMPTS = 20
        private const val CONNECT_RETRY_TIMEOUT_MS = 3_000
        private const val CONNECT_RETRY_DELAY_MS = 1_000L
    }

    private val manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    private val appContext = context.applicationContext

    private val receivedFrameStore = ReceivedFrameStore(
        postRepository = postRepository,
        decayKeyStore = decayKeyStore,
        postsDir = File(appContext.filesDir, "posts"),
    )

    /**
     * Session-scoped, in-memory-only send outbox. [broadcastPost] appends and
     * immediately pushes the new frame to every currently-open connection (see
     * [activeConnections]); a peer that connects *after* the post was queued
     * gets the full backlog as soon as it connects (see
     * [registerConnectionAndGetBacklog]). Real-hardware finding: a WiFi Direct
     * group commonly stays formed for the lifetime of both apps' foreground
     * session, so "only send on connect" (the original design here) silently
     * dropped every post made after the first connection -- confirmed on real
     * hardware, the receiving phone never got a second post sent over an
     * already-open group. [outboxLock] closes that gap without double-sending:
     * see its doc.
     *
     * Deliberately no ack/retry, and nothing here re-sends a post queued in a
     * *previous* app process/session -- this matches Phase 1's explicit scope
     * ("direct peer-to-peer only, no relay/store-and-forward yet" --
     * BUILD_PLAN.md Phase 1 vs. Phase 2). A post posted while this device has
     * no peers, or while every current peer already has it, is simply never
     * delivered to a peer that connects only after the process has died and
     * restarted -- that gap is Phase 2's store-and-forward relay to close,
     * not something this class should paper over.
     */
    private val outbox = mutableListOf<ByteArray>()

    /**
     * Every socket connection currently open to a peer (this device may be
     * the group owner serving several clients, or the lone client of another
     * device's group -- see [handleConnection]). [broadcastPost] iterates
     * this to push new posts live; entries are removed as connections close
     * (see [receivePosts]'s `finally`). [CopyOnWriteArrayList] since reads
     * (broadcastPost's iteration) are far more frequent than writes (one add
     * per connection, one remove per disconnect), and iteration must never
     * throw `ConcurrentModificationException` while a send is in flight on
     * another thread.
     */
    private val activeConnections = CopyOnWriteArrayList<PeerConnection>()

    /**
     * Guards [outbox] mutation together with [activeConnections] reads (in
     * [broadcastPost]) and, symmetrically, [activeConnections] mutation
     * together with [outbox] reads (in [registerConnectionAndGetBacklog]) --
     * the two operations that must never interleave. Without a single lock
     * spanning both collections, a post queued exactly as a new connection is
     * being registered could land in neither the new connection's backlog nor
     * its live-push set (silently dropped) or in both (double-sent). Because
     * both critical sections are tiny (a list add/read, no I/O), holding this
     * lock never blocks on network or disk work -- the actual socket writes
     * happen after release, in [PeerConnection.trySend].
     */
    private val outboxLock = Any()

    /**
     * Queues [encoded] (an already `EncryptedFrameCodec`-encoded frame, built
     * by the caller -- this class never constructs a [Frame] on the send
     * path) to be sent to every peer this device connects to for the rest of
     * this session, and immediately pushes it to every peer already
     * connected right now. See [outbox]'s doc for what "for the rest of this
     * session" does and doesn't mean.
     *
     * Note for future maintainers: this class does not build frames on send,
     * so the Phase 0 spike's `senderDeviceId`-regenerated-per-send bug
     * ([com.hop.spike.WifiDirectSpike.sendPayload]) has no structural
     * equivalent here -- the caller ([com.hop.app.composer.PostComposerViewModel])
     * already builds the frame once, using a stable per-install id from
     * `SettingsRepository.getOrCreateStableSenderDeviceId()`. Don't
     * reintroduce per-send frame construction (and therefore a fresh id) in
     * this class later.
     */
    fun broadcastPost(encoded: ByteArray) {
        val connectionsSnapshot: List<PeerConnection>
        val outboxSize: Int
        synchronized(outboxLock) {
            outbox.add(encoded)
            outboxSize = outbox.size
            connectionsSnapshot = activeConnections.toList()
        }
        onLog("Queued post for broadcast (${encoded.size} bytes); outbox size=$outboxSize")
        for (connection in connectionsSnapshot) {
            if (!connection.trySend(encoded)) {
                onLog("Live send failed to a connected peer; dropping that connection")
                activeConnections.remove(connection)
            }
        }
    }

    /**
     * Atomically registers [connection] as active and returns every post
     * queued before this call -- see [outboxLock]'s doc for why this must
     * share a lock with [broadcastPost] rather than read [outbox] separately.
     */
    private fun registerConnectionAndGetBacklog(connection: PeerConnection): List<ByteArray> =
        synchronized(outboxLock) {
            activeConnections.add(connection)
            outbox.toList()
        }

    /** Thread-safe wrapper around one peer socket's output stream -- see [activeConnections]'s doc. */
    private class PeerConnection(socket: Socket) {
        private val out = DataOutputStream(socket.getOutputStream())
        private val writeLock = Any()

        /** Writes one length-prefixed frame. Returns `false` (never throws) on any I/O failure. */
        fun trySend(encoded: ByteArray): Boolean = synchronized(writeLock) {
            try {
                out.writeInt(encoded.size)
                out.write(encoded)
                out.flush()
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    @Volatile
    private var continuousDiscoveryActive = false

    /**
     * Identifies the group formation [onConnected] last actually acted on, as
     * `"$isGroupOwner:$groupOwnerAddress"`. WIFI_P2P_CONNECTION_CHANGED_ACTION
     * can fire more than once for the same real connection (observed on real
     * hardware in the spike) -- without this guard, [onConnected] would call
     * [runServer] twice for the same group, and the second bind to
     * [TRANSFER_PORT] would fail with EADDRINUSE.
     */
    @Volatile
    private var activeGroupKey: String? = null

    /**
     * The group owner's listening socket, held here (rather than only as a
     * local val in [runServer]) so [stopServer] can close it from another
     * thread to unblock a pending [ServerSocket.accept] call -- the only way
     * to interrupt a blocking accept() in Java.
     */
    @Volatile
    private var activeServerSocket: ServerSocket? = null

    @SuppressLint("MissingPermission")
    fun discoverPeers() {
        manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = onLog("WiFi Direct discovery initiated")
            override fun onFailure(reason: Int) = onLog("WiFi Direct discovery failed: reason $reason")
        })
    }

    /**
     * See `WifiDirectSpike.startContinuousDiscovery`'s original doc for the
     * bilateral-rediscovery finding this addresses: a single discovery burst
     * routinely misses another phone's own discovery window, so this device
     * stays in a continuously-discovering state for as long as it's running
     * (i.e. app foregrounded -- see [TransportManager]'s lifecycle binding),
     * matching the product's "open the app to see what's nearby" posture
     * rather than inventing always-on background scanning.
     */
    @SuppressLint("MissingPermission")
    fun startContinuousDiscovery(intervalMs: Long = 10_000) {
        if (continuousDiscoveryActive) return
        continuousDiscoveryActive = true
        onLog("Continuous WiFi Direct discovery started (every ${intervalMs}ms)")
        Thread({
            while (continuousDiscoveryActive) {
                discoverPeers()
                Thread.sleep(intervalMs)
            }
        }, "hop-wifi-discovery").start()
    }

    fun stopContinuousDiscovery() {
        if (!continuousDiscoveryActive) return
        continuousDiscoveryActive = false
        onLog("Continuous WiFi Direct discovery stopped")
    }

    @SuppressLint("MissingPermission")
    fun connectTo(device: WifiP2pDevice) {
        val config = WifiP2pConfig().apply { deviceAddress = device.deviceAddress }
        manager.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = onLog("Connect initiated to ${device.deviceAddress}")
            override fun onFailure(reason: Int) = onLog("Connect failed: reason $reason")
        })
    }

    /**
     * `true` once [onConnected] has handled a formed group and `false` again
     * once it's torn down -- [TransportManager.maybeConnect] checks this
     * before calling [connectTo]. Real-hardware finding: a classic WiFi
     * Direct device can only belong to one group at a time, so calling
     * `WifiP2pManager.connect()` again while already grouped doesn't start a
     * fresh negotiation -- the platform routes it through an "invite to
     * existing group" path instead, observed on real hardware to fail
     * outright (`SupplicantP2pIfaceHalAidlImpl.invite` returning a service
     * -specific exception) while doing nothing useful, and spamming logs with
     * misleading "Connect failed" lines every cooldown cycle even though the
     * device is, in fact, already connected.
     */
    fun hasActiveGroup(): Boolean = activeGroupKey != null

    /** Call on every WIFI_P2P_CONNECTION_CHANGED_ACTION event ([TransportManager] does this). */
    fun onConnected(info: WifiP2pInfo) {
        if (!info.groupFormed) {
            activeGroupKey = null
            stopServer()
            return
        }
        val groupKey = "${info.isGroupOwner}:${info.groupOwnerAddress?.hostAddress}"
        if (groupKey == activeGroupKey) {
            // Same group formation already being handled -- see activeGroupKey's doc.
            return
        }
        activeGroupKey = groupKey
        if (info.isGroupOwner) {
            runServer()
        } else {
            val host = info.groupOwnerAddress?.hostAddress ?: return
            runClient(host, groupKey)
        }
    }

    /**
     * Accepts and serves every client that joins this device's WiFi Direct
     * group, not just the first -- see [WifiDirectTransport]'s class doc /
     * the ported spike's own note on why a single-accept version silently
     * drops a 3rd+ phone joining the same group.
     */
    private fun runServer() {
        if (activeServerSocket != null) return
        Thread({
            try {
                val server = ServerSocket(TRANSFER_PORT)
                activeServerSocket = server
                onLog("Transfer server listening on port $TRANSFER_PORT")
                while (true) {
                    val socket = try {
                        server.accept()
                    } catch (e: SocketException) {
                        // Expected: stopServer() closes the socket to unblock accept()
                        // once this device's group is torn down. Not an error.
                        break
                    }
                    onLog("Transfer server: accepted a client (${socket.inetAddress?.hostAddress})")
                    handleConnection(socket)
                }
            } catch (e: Exception) {
                onLog("Server error: ${e.message}")
            } finally {
                activeServerSocket = null
                onLog("Transfer server stopped")
            }
        }, "hop-wifi-server").start()
    }

    /** Closes the listening socket (if any) to unblock [runServer]'s accept() loop and end it. */
    private fun stopServer() {
        activeServerSocket?.let {
            try {
                it.close()
            } catch (e: Exception) {
                onLog("Error closing transfer server: ${e.message}")
            }
        }
    }

    /**
     * Retries the initial connect, backing off between attempts -- observed
     * on real hardware (Motorola client / Realme group owner):
     * WIFI_P2P_CONNECTION_CHANGED_ACTION fires on both devices at roughly the
     * same time, but [runServer]'s `ServerSocket(TRANSFER_PORT)` bind is not
     * guaranteed to complete before this device's own client-side `connect()`
     * fires -- a client that loses that race gets an immediate
     * `ECONNREFUSED` (nothing listening yet), not a slow timeout, so a single
     * connect attempt is not reliable. See [CONNECT_RETRY_ATTEMPTS]'s doc for
     * why the total budget is generous, and [connectWithRetry]'s doc for what
     * happens if it's exhausted anyway.
     */
    private fun runClient(hostAddress: String, groupKey: String) {
        Thread({ connectWithRetry(hostAddress, groupKey) }, "hop-wifi-client").start()
    }

    /**
     * [groupKey] is the value [activeGroupKey] held when this connection
     * attempt started. If every retry fails, this clears [activeGroupKey]
     * back to `null` -- but only if it still equals [groupKey], i.e. only if
     * nothing newer has superseded it in the meantime.
     *
     * Real-hardware bug this closes: before this guard existed, exhausting
     * [CONNECT_RETRY_ATTEMPTS] left [activeGroupKey] set forever (the OS-level
     * WiFi Direct group genuinely was still formed, so nothing else would
     * ever clear it) while no working socket existed and none of this
     * device's queued posts could ever be sent. Worse,
     * [TransportManager.maybeConnect] treats [hasActiveGroup] as "already
     * connected, don't bother reconnecting" -- so the device was stuck
     * permanently, with `dumpsys wifip2p` showing a healthy connection and no
     * error anywhere, until something external (toggling WiFi) forced a fresh
     * group teardown/reformation. That's not a workaround a real user knows
     * to reach for; a genuine failure here must be discoverable/recoverable
     * from the same opportunistic-connect loop everything else uses.
     */
    private fun connectWithRetry(hostAddress: String, groupKey: String) {
        var lastError: Exception? = null
        for (attempt in 1..CONNECT_RETRY_ATTEMPTS) {
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(hostAddress, TRANSFER_PORT), CONNECT_RETRY_TIMEOUT_MS)
                handleConnection(socket)
                return
            } catch (e: Exception) {
                lastError = e
                if (attempt < CONNECT_RETRY_ATTEMPTS) Thread.sleep(CONNECT_RETRY_DELAY_MS)
            }
        }
        onLog("Client error: ${lastError?.message}; giving up on this connection attempt")
        synchronized(this) {
            if (activeGroupKey == groupKey) activeGroupKey = null
        }
    }

    /**
     * Sends and receives over [socket] concurrently, independent of which
     * side WiFi Direct negotiated as group owner vs. client (ported
     * reasoning: group-owner assignment isn't something either device
     * controls, and tying "who sends" to a fixed role silently drops posts
     * queued on whichever side loses that coin flip). Both directions run on
     * background threads -- never the main thread -- since both do blocking
     * socket I/O plus (on receive) file/Room I/O.
     */
    private fun handleConnection(socket: Socket) {
        val connection = PeerConnection(socket)
        val backlog = registerConnectionAndGetBacklog(connection)
        Thread({ sendBacklog(connection, backlog) }, "hop-send").start()
        Thread({ receivePosts(socket, connection) }, "hop-receive").start()
    }

    /** Flushes every post queued before this connection registered -- new posts arrive via [broadcastPost]'s live push instead. */
    private fun sendBacklog(connection: PeerConnection, backlog: List<ByteArray>) {
        onLog("Sending ${backlog.size} queued post(s) to connected peer")
        for (encoded in backlog) {
            if (!connection.trySend(encoded)) {
                onLog("Send error while flushing backlog to a connected peer")
                break
            }
        }
    }

    /**
     * Reads length-prefixed frames from [socket] until it closes/EOF -- a
     * peer may have several queued posts to send in one connection, not just
     * one. Each frame is handed to [ReceivedFrameStore.handle].
     *
     * [connection] is removed from [activeConnections] once this loop ends
     * (peer disconnect, EOF, or error) -- this is the only place a
     * [PeerConnection] is deregistered, so [broadcastPost]'s live push never
     * targets a socket that's already gone.
     */
    private fun receivePosts(socket: Socket, connection: PeerConnection) {
        try {
            val input = DataInputStream(socket.getInputStream())
            while (true) {
                val size = try {
                    input.readInt()
                } catch (e: EOFException) {
                    break // peer closed the connection / sent everything it had
                }
                if (size < 0) {
                    onLog("Receive: invalid frame length $size -- ending this connection's receive loop")
                    break
                }
                val buffer = ByteArray(size)
                input.readFully(buffer)
                try {
                    val stored = receivedFrameStore.handle(buffer)
                    onLog(if (stored) "Received and stored a new post" else "Received a frame, not stored (dedupe or decode failure)")
                } catch (e: Exception) {
                    // Don't let one bad frame kill the loop -- framing is already
                    // correctly consumed above (we read exactly `size` bytes), so
                    // the socket's byte stream is still in a valid state to keep
                    // reading the next frame.
                    onLog("Error handling received frame: ${e.message}")
                }
            }
        } catch (e: SocketException) {
            // Expected on peer disconnect / stopServer() teardown.
        } catch (e: Exception) {
            onLog("Receive error: ${e.message}")
        } finally {
            activeConnections.remove(connection)
        }
    }
}

/**
 * Pure receive-path logic: decode a frame, dedupe against already-stored
 * posts, and (if new) store its decay key + ciphertext + [PostEntity].
 *
 * Factored out of [WifiDirectTransport] specifically so it's unit-testable
 * without a real Android `Context`/`WifiP2pManager` -- it depends only on
 * [PostRepository], [DecayKeyStore], and a plain [postsDir] `File`, the same
 * testability seam [com.hop.app.composer.PostComposerViewModel] already uses
 * for its own `postsDir: File` parameter.
 *
 * `internal` (not `private`) so a JVM unit test in this module can construct
 * it directly against a temp folder and hand-rolled fakes, without going
 * through sockets or [WifiDirectTransport] at all.
 */
internal class ReceivedFrameStore(
    private val postRepository: PostRepository,
    private val decayKeyStore: DecayKeyStore,
    private val postsDir: File,
) {
    /**
     * Decodes [bytes] as a version-2 [Frame]. If a post with the same
     * `clipHash` is already stored (this device has already seen this post,
     * whether via this same peer reconnecting or a different peer), skips it
     * without touching [decayKeyStore] or the filesystem and returns `false`.
     * Otherwise stores the wrapped content-encryption key on the frame's own
     * `ttlSeconds` decay window, writes the ciphertext payload to
     * `$postsDir/$clipHash.enc`, inserts a [PostEntity] with
     * `receivedAtMs = now`, and returns `true`.
     *
     * This never decrypts -- it stores ciphertext + key separately, matching
     * [PostRepository]'s on-demand-decrypt design (`PostRepository.decrypt`),
     * not a force-decrypt-on-receive design.
     *
     * Runs real blocking I/O (file write, Room via [postRepository]/
     * [decayKeyStore]) synchronously via `runBlocking` -- callers must invoke
     * this off the main thread. Returns `false` (without throwing) if [bytes]
     * doesn't decode as a valid frame.
     */
    fun handle(bytes: ByteArray): Boolean {
        val frame = try {
            Frame.decode(bytes)
        } catch (e: Exception) {
            return false
        }

        val clipHashHex = frame.clipHash.toHexString()

        return runBlocking {
            if (postRepository.getByClipHash(clipHashHex) != null) {
                return@runBlocking false
            }

            decayKeyStore.store(
                contentId = clipHashHex,
                wrappedCek = frame.contentEncryptionKey,
                decayWindow = Duration.ofSeconds(frame.ttlSeconds),
            )

            postsDir.mkdirs()
            val payloadFile = File(postsDir, "$clipHashHex.enc")
            payloadFile.writeBytes(frame.payload)

            postRepository.insert(
                PostEntity(
                    clipHash = clipHashHex,
                    senderDeviceId = frame.senderDeviceId.toHexString(),
                    contentType = frame.contentType.name,
                    originatedAtMs = frame.originatedAtMs,
                    ttlSeconds = frame.ttlSeconds,
                    reachTier = frame.reachTier.name,
                    dontRelay = frame.dontRelay,
                    receivedAtMs = System.currentTimeMillis(),
                    encryptedPayloadFilePath = payloadFile.absolutePath,
                ),
            )
            true
        }
    }

    private fun ByteArray.toHexString(): String = joinToString(separator = "") { "%02x".format(it) }
}
