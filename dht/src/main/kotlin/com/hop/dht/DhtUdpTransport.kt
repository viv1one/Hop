package com.hop.dht

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A minimal Kademlia liveness RPC (PING/PONG) over UDP.
 *
 * **UDP, not TCP -- deliberately.** [RoutingTable] holds up to thousands of
 * *known-of* contacts, not *connected-to* peers: TCP would force either
 * holding one socket open per routing-table entry (doesn't scale) or a fresh
 * handshake per liveness check (real per-ping cost). UDP's connectionless
 * request/response shape is what a k-bucket ping-then-evict check
 * ([KBucket.InsertResult.PendingReplacement]) was built for. This also keeps
 * this slice aligned with BUILD_PLAN.md's next-sequenced Phase 4 step, NAT
 * hole-punching, which is UDP-native -- landing PING/PONG on TCP now would
 * mean maintaining two separate socket lifecycles once that lands.
 *
 * The receive loop runs on a dedicated background [Thread], matching
 * `com.hop.transport.WifiDirectTransport`'s established thread-per-receive-
 * loop pattern -- not a coroutine dispatcher, since there's no need for one
 * just to block on a socket read. Only [ping] itself is a suspend function,
 * using a coroutine's timeout/cancellation machinery for the
 * correlation-then-timeout logic. This hybrid is deliberate, not an
 * inconsistency to "clean up."
 *
 * **Hard rule**: a contact's stored address always comes from the UDP
 * packet's *observed* source address ([DatagramPacket.getAddress]/
 * [DatagramPacket.getPort]), never from any field inside the decoded
 * [DhtMessage] -- the wire format correctly has no such field, and this must
 * stay true. See [handlePacket].
 *
 * PING/PONG only -- no bootstrap-join / "discover the network" flow lives
 * here. A real join needs FIND_NODE at minimum (a later slice); this class
 * only answers "is this specific, already-known contact still alive."
 */
class DhtUdpTransport(
    /** Caller creates and binds this (loopback + port 0 in tests, for testability). */
    private val socket: DatagramSocket,
    private val ownId: NodeId,
    /**
     * Fires for EVERY valid inbound message (PING or PONG), keyed to the
     * packet's OBSERVED source address -- never a self-reported field, per
     * this class's hard rule. This is the actual routing-table
     * self-population mechanism: a device that only ever receives PINGs (and
     * never itself calls [ping]) still learns about its peers through this
     * callback.
     */
    private val onMessageObserved: (Contact) -> Unit,
    private val requestTimeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS,
) {
    /**
     * Pending outbound PINGs awaiting a matching PONG, keyed by [TransactionId]
     * (content-based equality -- see that class's own doc for why a raw
     * `ByteArray` key would silently break every correlation lookup).
     * [ConcurrentHashMap] since the receive thread and any number of
     * concurrent [ping] callers touch this map independently.
     */
    private val pendingPings = ConcurrentHashMap<TransactionId, CompletableDeferred<Unit>>()

    @Volatile
    private var running = false

    @Volatile
    private var receiveThread: Thread? = null

    /** Spawns the receive thread. Safe to call once; a second call is a no-op. */
    fun start() {
        if (running) return
        running = true
        receiveThread = Thread({ receiveLoop() }, "hop-dht-receive").also { it.start() }
    }

    /** Stops the receive loop and closes [socket], unblocking any pending [DatagramSocket.receive] call. */
    fun stop() {
        running = false
        socket.close()
    }

    /**
     * Sends a PING to [contact] and suspends until either a matching PONG
     * arrives (`true`) or [requestTimeoutMs] elapses (`false`). Never throws
     * on a timeout or an unreachable/non-listening peer -- only a genuine
     * local error (e.g. an unparseable [Contact.address]) propagates.
     */
    suspend fun ping(contact: Contact): Boolean {
        val transactionId = TransactionId.random()
        val deferred = CompletableDeferred<Unit>()
        pendingPings[transactionId] = deferred
        try {
            val message = DhtMessage(type = DhtMessageType.PING, transactionId = transactionId, senderId = ownId)
            val bytes = message.encode()
            val destination = PeerAddress.decode(contact.address).toInetSocketAddress()
            socket.send(DatagramPacket(bytes, bytes.size, destination))
            val result = withTimeoutOrNull(requestTimeoutMs) { deferred.await() }
            return result != null
        } finally {
            pendingPings.remove(transactionId)
        }
    }

    private fun receiveLoop() {
        val buffer = ByteArray(RECEIVE_BUFFER_SIZE)
        while (running) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (e: Exception) {
                // Expected on stop() closing the socket to unblock a pending
                // receive() call. If we're still supposed to be running, this
                // was some other transient I/O error -- keep serving rather
                // than letting one hiccup kill the whole receive loop.
                if (!running) break
                continue
            }
            try {
                handlePacket(packet)
            } catch (e: Exception) {
                // Malformed/garbage packet (wrong size, unknown version, unknown
                // type, or anything else DhtMessage.decode/PeerAddress.decode
                // rejects) -- never let one bad packet crash the receive loop.
                // Framing here is inherently self-contained per-datagram (unlike
                // a length-prefixed stream), so there's no partial-read state to
                // worry about recovering from.
            }
        }
    }

    /**
     * Decodes [packet] as a [DhtMessage], reports the sender via
     * [onMessageObserved] using the packet's *observed* source address (never
     * a self-reported field -- this class's hard rule), then answers a PING
     * with a PONG or completes a matching pending [ping] for a PONG.
     *
     * A PONG whose transaction ID doesn't match a currently-pending outbound
     * request (never issued, or already completed/timed out) is silently
     * dropped for correlation purposes -- it still triggered
     * [onMessageObserved] above (harmless "I heard from this peer"
     * information), but it never completes a different pending request and
     * never throws.
     */
    private fun handlePacket(packet: DatagramPacket) {
        val payload = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
        val message = DhtMessage.decode(payload)

        val observedAddress = PeerAddress.from(packet.address, packet.port)
        val contact = Contact(
            id = message.senderId,
            address = observedAddress.encode(),
            lastSeenAtMs = System.currentTimeMillis(),
        )
        onMessageObserved(contact)

        when (message.type) {
            DhtMessageType.PING -> {
                val pong = DhtMessage(type = DhtMessageType.PONG, transactionId = message.transactionId, senderId = ownId)
                val bytes = pong.encode()
                socket.send(DatagramPacket(bytes, bytes.size, packet.address, packet.port))
            }
            DhtMessageType.PONG -> {
                val pending = pendingPings[message.transactionId] ?: return
                pending.complete(Unit)
            }
        }
    }

    companion object {
        /** Unmeasured placeholder, same posture as `KBucket.DEFAULT_K`. */
        const val DEFAULT_REQUEST_TIMEOUT_MS = 2000L

        /** Comfortably larger than DhtMessage.WIRE_SIZE (42 bytes); any legitimate datagram fits with room to spare. */
        private const val RECEIVE_BUFFER_SIZE = 512
    }
}
