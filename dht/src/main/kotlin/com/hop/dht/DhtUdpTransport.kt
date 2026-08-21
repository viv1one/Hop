package com.hop.dht

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The Kademlia RPCs this slice speaks over UDP: liveness (PING/PONG, Slice 3)
 * and FIND_NODE (Slice 4).
 *
 * **UDP, not TCP -- deliberately.** [RoutingTable] holds up to thousands of
 * *known-of* contacts, not *connected-to* peers: TCP would force either
 * holding one socket open per routing-table entry (doesn't scale) or a fresh
 * handshake per liveness check (real per-ping cost). UDP's connectionless
 * request/response shape is what a k-bucket ping-then-evict check
 * ([KBucket.InsertResult.PendingReplacement]) was built for. This also keeps
 * this slice aligned with BUILD_PLAN.md's next-sequenced Phase 4 step, NAT
 * hole-punching, which is UDP-native -- landing PING/PONG/FIND_NODE on TCP
 * now would mean maintaining two separate socket lifecycles once that lands.
 *
 * The receive loop runs on a dedicated background [Thread], matching
 * `com.hop.transport.WifiDirectTransport`'s established thread-per-receive-
 * loop pattern -- not a coroutine dispatcher, since there's no need for one
 * just to block on a socket read. Only [ping]/[findNode] themselves are
 * suspend functions, using a coroutine's timeout/cancellation machinery for
 * the correlation-then-timeout logic. This hybrid is deliberate, not an
 * inconsistency to "clean up."
 *
 * **Hard rule**: a contact's stored address always comes from the UDP
 * packet's *observed* source address ([DatagramPacket.getAddress]/
 * [DatagramPacket.getPort]), never from any field inside a decoded message --
 * no wire format here has such a field, and this must stay true. See
 * [handlePacket].
 */
class DhtUdpTransport(
    /** Caller creates and binds this (loopback + port 0 in tests, for testability). */
    private val socket: DatagramSocket,
    private val ownId: NodeId,
    /**
     * Fires for EVERY valid inbound message (PING, PONG, FIND_NODE_REQUEST,
     * or FIND_NODE_RESPONSE), keyed to the packet's OBSERVED source address
     * -- never a self-reported field, per this class's hard rule. This is
     * the actual routing-table self-population mechanism.
     *
     * `var` with a no-op default (not `val`) so [DhtNode] can wire itself in
     * after construction (see [DhtNode]'s `init` block) without forcing
     * every construction site -- including every already-shipped Slice 3
     * test -- to pass a real callback up front.
     */
    var onMessageObserved: (Contact) -> Unit = {},
    /**
     * Answers an inbound FIND_NODE_REQUEST: given the requested [NodeId] and
     * the id to exclude (the requester itself), returns up to `k` contacts
     * this device knows of that are closest to it. `var` with a no-op
     * default for the same reason as [onMessageObserved] -- wired by
     * [DhtNode]'s `init` block.
     */
    var onFindNodeRequested: (targetId: NodeId, excludeId: NodeId) -> List<Contact> = { _, _ -> emptyList() },
    private val requestTimeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS,
) {
    /**
     * Pending outbound requests (PING or FIND_NODE_REQUEST) awaiting a
     * matching response, keyed by [TransactionId] (content-based equality --
     * see that class's own doc for why a raw `ByteArray` key would silently
     * break every correlation lookup). Deferred with the raw response bytes,
     * not a shared typed message -- [FindNodeResponseMessage] is not a
     * [DhtMessage], so parsing is left to each caller ([ping]/[findNode])
     * rather than forced into one shared decode. [ConcurrentHashMap] since
     * the receive thread and any number of concurrent callers touch this map
     * independently.
     */
    private val pendingRequests = ConcurrentHashMap<TransactionId, CompletableDeferred<ByteArray>>()

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
     * on a timeout, an unreachable/non-listening peer, or a malformed/
     * type-mismatched response (e.g. a FIND_NODE_RESPONSE-shaped payload
     * answering a PING's transaction id) -- all treated the same as a
     * timeout. Only a genuine local error (e.g. an unparseable
     * [Contact.address]) propagates.
     */
    suspend fun ping(contact: Contact): Boolean {
        val transactionId = TransactionId.random()
        val message = DhtMessage(type = DhtMessageType.PING, transactionId = transactionId, senderId = ownId)
        val destination = PeerAddress.decode(contact.address).toInetSocketAddress()
        val responseBytes = sendAndAwait(destination, transactionId, message.encode()) ?: return false
        return try {
            DhtMessage.decode(responseBytes).type == DhtMessageType.PONG
        } catch (e: DhtMessageDecodeException) {
            false
        }
    }

    /**
     * Sends a FIND_NODE_REQUEST for [targetId] to [contact] and suspends
     * until either a matching FIND_NODE_RESPONSE arrives (its contact list)
     * or [requestTimeoutMs] elapses. Returns `null` on timeout, a malformed
     * response, or a response that doesn't decode as a FIND_NODE_RESPONSE --
     * never trusts payload shape from the transaction id match alone, and
     * never throws for any of these cases.
     */
    suspend fun findNode(contact: Contact, targetId: NodeId): List<Contact>? {
        val transactionId = TransactionId.random()
        val message = FindNodeRequestMessage(transactionId = transactionId, senderId = ownId, targetId = targetId)
        val destination = PeerAddress.decode(contact.address).toInetSocketAddress()
        val responseBytes = sendAndAwait(destination, transactionId, message.encode()) ?: return null
        return try {
            FindNodeResponseMessage.decode(responseBytes).contacts
        } catch (e: DhtMessageDecodeException) {
            null
        }
    }

    /**
     * Shared primitive both [ping] and [findNode] build on: registers a
     * pending deferred for [transactionId], sends [requestBytes] to
     * [destination], and suspends up to [requestTimeoutMs] for a matching
     * response's raw bytes (or `null` on timeout). Always cleans up the
     * pending-request entry, success or not.
     */
    private suspend fun sendAndAwait(
        destination: InetSocketAddress,
        transactionId: TransactionId,
        requestBytes: ByteArray,
    ): ByteArray? {
        val deferred = CompletableDeferred<ByteArray>()
        pendingRequests[transactionId] = deferred
        try {
            socket.send(DatagramPacket(requestBytes, requestBytes.size, destination))
            return withTimeoutOrNull(requestTimeoutMs) { deferred.await() }
        } finally {
            pendingRequests.remove(transactionId)
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
                // type, truncated FIND_NODE_RESPONSE contacts, or anything else
                // a decode() rejects) -- never let one bad packet crash the
                // receive loop. Framing here is inherently self-contained
                // per-datagram (unlike a length-prefixed stream), so there's no
                // partial-read state to worry about recovering from.
            }
        }
    }

    /**
     * Peeks the type byte every wire format here shares at fixed offset 1
     * (`[1B version][1B type]...`) to decide which type's decoder to invoke,
     * reports the sender via [onMessageObserved] using the packet's
     * *observed* source address (never a self-reported field -- this class's
     * hard rule), then either answers a request or completes a matching
     * pending call.
     *
     * A response whose transaction ID doesn't match a currently-pending
     * outbound request (never issued, or already completed/timed out) is
     * silently dropped for correlation purposes -- it still triggered
     * [onMessageObserved] above (harmless "I heard from this peer"
     * information), but it never completes a different pending request and
     * never throws.
     */
    private fun handlePacket(packet: DatagramPacket) {
        val payload = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
        if (payload.size < 2) {
            throw DhtMessageDecodeException("Packet too short to contain a version+type header: ${payload.size} bytes")
        }
        val type = DhtMessageType.fromWireValue(payload[1].toInt() and 0xFF)
        val observedAddress = PeerAddress.from(packet.address, packet.port)

        when (type) {
            DhtMessageType.PING -> {
                val message = DhtMessage.decode(payload)
                observe(message.senderId, observedAddress)
                val pong = DhtMessage(type = DhtMessageType.PONG, transactionId = message.transactionId, senderId = ownId)
                val bytes = pong.encode()
                socket.send(DatagramPacket(bytes, bytes.size, packet.address, packet.port))
            }
            DhtMessageType.PONG -> {
                val message = DhtMessage.decode(payload)
                observe(message.senderId, observedAddress)
                pendingRequests[message.transactionId]?.complete(payload)
            }
            DhtMessageType.FIND_NODE_REQUEST -> {
                val message = FindNodeRequestMessage.decode(payload)
                observe(message.senderId, observedAddress)
                val closest = onFindNodeRequested(message.targetId, message.senderId)
                val response = FindNodeResponseMessage(
                    transactionId = message.transactionId,
                    senderId = ownId,
                    contacts = closest,
                )
                val bytes = response.encode()
                socket.send(DatagramPacket(bytes, bytes.size, packet.address, packet.port))
            }
            DhtMessageType.FIND_NODE_RESPONSE -> {
                val message = FindNodeResponseMessage.decode(payload)
                observe(message.senderId, observedAddress)
                pendingRequests[message.transactionId]?.complete(payload)
            }
        }
    }

    private fun observe(senderId: NodeId, observedAddress: PeerAddress) {
        onMessageObserved(
            Contact(id = senderId, address = observedAddress.encode(), lastSeenAtMs = System.currentTimeMillis())
        )
    }

    companion object {
        /** Unmeasured placeholder, same posture as `KBucket.DEFAULT_K`. */
        const val DEFAULT_REQUEST_TIMEOUT_MS = 2000L

        /**
         * Comfortably larger than the largest legitimate datagram this slice
         * sends: a FIND_NODE_RESPONSE with [FindNodeResponseMessage.MAX_CONTACTS]
         * all-IPv6 contacts is ~1291 bytes (see that constant's doc comment);
         * this buffer leaves generous headroom above that.
         */
        private const val RECEIVE_BUFFER_SIZE = 2048
    }
}
