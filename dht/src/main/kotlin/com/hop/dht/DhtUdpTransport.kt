package com.hop.dht

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Answers an inbound FIND_VALUE_REQUEST: either this device knows of
 * announced holders for the requested key ([Holders]) or it doesn't and
 * offers closer routing candidates instead ([CloserNodes]) -- mirrors
 * [FindValueResponseMessage]'s own found/not-found duality (BitTorrent
 * Mainline DHT's `get_peers` "values xor nodes" shape).
 */
sealed class FindValueOutcome {
    data class Holders(val contacts: List<Contact>) : FindValueOutcome()
    data class CloserNodes(val contacts: List<Contact>) : FindValueOutcome()
}

/**
 * The Kademlia RPCs this slice speaks over UDP: liveness (PING/PONG, Slice 3),
 * FIND_NODE (Slice 4), STORE/FIND_VALUE (Slice 5) -- the announce/
 * get-peers primitive [DhtStore] backs -- and, as of Phase 4's NAT
 * hole-punching address-self-discovery slice, ADDRESS_REFLECTION (see
 * [reflectOwnAddress] and the `ADDRESS_REFLECTION_REQUEST` case in
 * [handlePacket]): a STUN-style "what address did you observe me at" RPC.
 * That slice only builds step (1) of NAT hole-punching -- a device learning
 * its own public-facing address -- never the harder peer-introduction/
 * simultaneous-connect signaling step, which is separate, later work.
 *
 * Phase 4's rendezvous-relayed-introduction slice adds that step (2), in the
 * deliberately conservative shape `IntroductionMessage.kt`'s own file doc
 * describes: [introduce] (INTRODUCE_REQUEST/INTRODUCE_RESPONSE, request/
 * response-shaped via [sendAndAwait], same as every RPC above) lets a device
 * ask a rendezvous/DHT contact to look up a target it can't yet reach; that
 * same contact, if it finds the target, ALSO fires an unsolicited
 * INTRODUCTION at the target via [sendUnsolicited] -- the first fire-and-
 * forget send this class has (every RPC above is request/response-shaped).
 * This slice builds only the wire-level relay mechanism: receiving an
 * INTRODUCTION fires [onIntroductionReceived] and stops there -- nothing in
 * this class attempts an actual connection. That's separate, later work, and
 * so is any volunteer relay-node fallback for the symmetric-NAT case this
 * mechanism doesn't solve.
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
     * FIND_NODE_RESPONSE, STORE_REQUEST, STORE_RESPONSE, FIND_VALUE_REQUEST,
     * FIND_VALUE_RESPONSE, ADDRESS_REFLECTION_REQUEST,
     * ADDRESS_REFLECTION_RESPONSE, INTRODUCE_REQUEST, INTRODUCE_RESPONSE, or
     * INTRODUCTION), keyed to the packet's OBSERVED source address -- never a
     * self-reported field, per this class's hard rule. This is the actual
     * routing-table self-population mechanism.
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
    /**
     * Answers an inbound STORE_REQUEST: [announcer] is built from the
     * requested key plus the packet's *observed* source address, never a
     * self-reported field -- [StoreRequestMessage] carries no address of its
     * own, only [StoreRequestMessage.senderId], per this class's hard rule
     * and that message's own trust-model doc. `var` with a no-op default for
     * the same reason as [onMessageObserved] -- wired by [DhtNode]'s `init`
     * block.
     */
    var onStoreRequested: (key: NodeId, announcer: Contact) -> Unit = { _, _ -> },
    /**
     * Answers an inbound FIND_VALUE_REQUEST: given the requested key and the
     * id to exclude (the requester itself), returns either known holders or
     * closer routing candidates. `var` with a no-op default for the same
     * reason as [onMessageObserved] -- wired by [DhtNode]'s `init` block.
     */
    var onFindValueRequested: (key: NodeId, excludeId: NodeId) -> FindValueOutcome =
        { _, _ -> FindValueOutcome.CloserNodes(emptyList()) },
    /**
     * Answers an inbound INTRODUCE_REQUEST: given the requested [NodeId],
     * returns this device's live-known [Contact] for it, or `null` if
     * unknown. `var` with a no-op default ({ null }) for the same reason as
     * [onFindNodeRequested] -- wired by [DhtNode]'s/`RendezvousNode`'s own
     * `init` block to their own registry/routing-table lookup. See
     * `IntroductionMessage.kt`'s own file doc for the mechanism this backs.
     */
    var onIntroduceRequested: (targetId: NodeId) -> Contact? = { null },
    /**
     * Fires when this device receives an unsolicited INTRODUCTION: [fromId]
     * is the introduced peer's id, [claimedAddress] is that peer's
     * self-reported reflected address, relayed through by whichever
     * rendezvous/DHT contact sent this -- see [IntroductionMessage]'s own
     * doc for why [claimedAddress] carries only as much trust as the
     * introduced peer itself, never independently verified by the relay or
     * this device. `var` with a no-op default -- **this slice deliberately
     * goes no further than firing this callback.** Attempting an actual
     * connection in response (e.g. `InternetPeerConnection.connectTo`) is
     * explicitly out of scope here; see `IntroductionMessage.kt`'s own file
     * doc.
     */
    var onIntroductionReceived: (fromId: NodeId, claimedAddress: PeerAddress) -> Unit = { _, _ -> },
    private val requestTimeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS,
) {
    /**
     * Pending outbound requests (PING, FIND_NODE_REQUEST, STORE_REQUEST,
     * FIND_VALUE_REQUEST, ADDRESS_REFLECTION_REQUEST, or INTRODUCE_REQUEST)
     * awaiting a matching response, keyed by [TransactionId] (content-based
     * equality -- see that class's own doc for why a raw `ByteArray` key
     * would silently break every correlation lookup). Deferred with the raw
     * response bytes, not a shared typed message -- most response types here
     * are not a [DhtMessage] (the STORE_RESPONSE ack is the one exception),
     * so parsing is left to each caller ([ping]/[findNode]/[store]/
     * [findValue]/[reflectOwnAddress]/[introduce]) rather than forced into
     * one shared decode. Never registered for INTRODUCTION -- that's the
     * one fire-and-forget send this class makes (via [sendUnsolicited]), and
     * has no response to correlate. [ConcurrentHashMap] since the receive
     * thread and any number of concurrent callers touch this map
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
        val destination = firstDialableAddress(contact)
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
        val destination = firstDialableAddress(contact)
        val responseBytes = sendAndAwait(destination, transactionId, message.encode()) ?: return null
        return try {
            FindNodeResponseMessage.decode(responseBytes).contacts
        } catch (e: DhtMessageDecodeException) {
            null
        }
    }

    /**
     * Sends a STORE_REQUEST announcing this device as a holder of [key] to
     * [contact] and suspends until either a matching STORE_RESPONSE ack
     * arrives (`true`) or [requestTimeoutMs] elapses (`false`). Same
     * never-throw-on-timeout/malformed-response posture as [ping]/[findNode].
     */
    suspend fun store(contact: Contact, key: NodeId): Boolean {
        val transactionId = TransactionId.random()
        val message = StoreRequestMessage(transactionId = transactionId, senderId = ownId, key = key)
        val destination = firstDialableAddress(contact)
        val responseBytes = sendAndAwait(destination, transactionId, message.encode()) ?: return false
        return try {
            DhtMessage.decode(responseBytes).type == DhtMessageType.STORE_RESPONSE
        } catch (e: DhtMessageDecodeException) {
            false
        }
    }

    /**
     * Sends a FIND_VALUE_REQUEST for [key] to [contact] and suspends until
     * either a matching FIND_VALUE_RESPONSE arrives (decoded into a
     * [FindValueOutcome]) or [requestTimeoutMs] elapses. Returns `null` on
     * timeout, a malformed response, or a response that doesn't decode as a
     * FIND_VALUE_RESPONSE -- never trusts payload shape from the transaction
     * id match alone, and never throws for any of these cases. Same posture
     * as [findNode].
     */
    suspend fun findValue(contact: Contact, key: NodeId): FindValueOutcome? {
        val transactionId = TransactionId.random()
        val message = FindValueRequestMessage(transactionId = transactionId, senderId = ownId, key = key)
        val destination = firstDialableAddress(contact)
        val responseBytes = sendAndAwait(destination, transactionId, message.encode()) ?: return null
        return try {
            val response = FindValueResponseMessage.decode(responseBytes)
            if (response.found) {
                FindValueOutcome.Holders(response.contacts)
            } else {
                FindValueOutcome.CloserNodes(response.contacts)
            }
        } catch (e: DhtMessageDecodeException) {
            null
        }
    }

    /**
     * Sends an ADDRESS_REFLECTION_REQUEST to [contact] and suspends until
     * either a matching ADDRESS_REFLECTION_RESPONSE arrives (its
     * [AddressReflectionResponseMessage.reflectedAddress]) or
     * [requestTimeoutMs] elapses. Returns `null` on timeout, a malformed
     * response, or a response that doesn't decode as an
     * ADDRESS_REFLECTION_RESPONSE -- never trusts payload shape from the
     * transaction id match alone, and never throws for any of these cases.
     * Same posture as [ping]/[findNode]/[store]/[findValue].
     *
     * This is Phase 4's NAT hole-punching step (1) only -- "a device learns
     * its own public-facing address as observed from outside its NAT" -- and
     * nothing more. The returned [PeerAddress] is genuinely the address
     * [contact] observed this request arriving from, not anything this
     * device asserts about itself: [AddressReflectionRequestMessage] carries
     * no address field for a dishonest/buggy requester to inject, and
     * [handlePacket]'s `ADDRESS_REFLECTION_REQUEST` case answers exclusively
     * from its own packet-observed source, never from message content --
     * see that case's own doc. Callers must still remember [contact] itself
     * is an arbitrary, possibly-dishonest peer: this call trusts *that peer*
     * to answer honestly about what it saw, the same trust level as every
     * other RPC in this class extends to whichever contact it dials. A
     * hostile [contact] could lie about the reflected address entirely (or
     * simply not answer) -- nothing here authenticates the *responder's*
     * honesty, only that its answer wasn't tampered with in transit or
     * type-confused with a different RPC's response.
     */
    suspend fun reflectOwnAddress(contact: Contact): PeerAddress? {
        val transactionId = TransactionId.random()
        val message = AddressReflectionRequestMessage(transactionId = transactionId, senderId = ownId)
        val destination = firstDialableAddress(contact)
        val responseBytes = sendAndAwait(destination, transactionId, message.encode()) ?: return null
        return try {
            AddressReflectionResponseMessage.decode(responseBytes).reflectedAddress
        } catch (e: DhtMessageDecodeException) {
            null
        }
    }

    /**
     * Sends an INTRODUCE_REQUEST to [via] (a rendezvous/DHT contact this
     * device already talks to) asking it to introduce this device to
     * [targetId], and suspends until either a matching INTRODUCE_RESPONSE
     * arrives or [requestTimeoutMs] elapses. Returns the found [PeerAddress]
     * on a `found=true` response; returns `null` on timeout, a `found=false`
     * response, a malformed response, or a response that doesn't decode as
     * an INTRODUCE_RESPONSE -- never trusts payload shape from the
     * transaction id match alone, and never throws for any of these cases
     * (same posture as every other RPC above).
     *
     * [ownReflectedAddress] should be this device's own address as learned
     * via [reflectOwnAddress] -- it's carried inside the request so [via]
     * has something correct to relay onward to [targetId] if it's found; see
     * [IntroduceRequestMessage]'s own doc for why that field is genuinely
     * self-reported (the one deliberate exception to this class's "never
     * self-reported" rule) and what trust limitation that implies.
     *
     * This is Phase 4's rendezvous-relayed-introduction primitive -- see
     * `IntroductionMessage.kt`'s own file doc for the full design (why this
     * shape, not simultaneous-open TCP hole punching) and its scope (this
     * function and [via]'s own [handlePacket] handling are the entire
     * mechanism; nothing here or in [onIntroductionReceived] attempts an
     * actual connection).
     */
    suspend fun introduce(via: Contact, targetId: NodeId, ownReflectedAddress: PeerAddress): PeerAddress? {
        val transactionId = TransactionId.random()
        val message = IntroduceRequestMessage(
            transactionId = transactionId,
            senderId = ownId,
            targetId = targetId,
            ownAddress = ownReflectedAddress,
        )
        val destination = firstDialableAddress(via)
        val responseBytes = sendAndAwait(destination, transactionId, message.encode()) ?: return null
        return try {
            val response = IntroduceResponseMessage.decode(responseBytes)
            if (response.found) response.address else null
        } catch (e: DhtMessageDecodeException) {
            null
        }
    }

    /**
     * Resolves the single [InetSocketAddress] [ping]/[findNode]/[store]/
     * [findValue]/[reflectOwnAddress] each dial [contact] at. `contact.address` may now hold more
     * than one [PeerAddress] (Phase 4's dual-stack self-registration, see
     * [Contact]'s own class doc) -- this UDP RPC layer has no per-request
     * IPv6-first/IPv4-fallback race the way `p2p/`'s `PeerDialer` does for TCP
     * content connections, so it deliberately just dials the *first* entry
     * [PeerAddress.decodeList] recovers, rather than throwing the way an
     * unconditional [PeerAddress.decode] call would on a multi-entry blob.
     * [PeerAddress.decodeList] still throws [PeerAddressDecodeException] for
     * genuinely malformed bytes (truncated entry, unknown family byte) or a
     * zero-address contact -- that's still a genuine local error and still
     * propagates, matching every one of the four callers' own "only a
     * genuine local error propagates" doc.
     */
    private fun firstDialableAddress(contact: Contact): InetSocketAddress {
        val addresses = PeerAddress.decodeList(contact.address)
        if (addresses.isEmpty()) {
            throw PeerAddressDecodeException("Contact ${contact.id} has no addresses to dial")
        }
        return addresses.first().toInetSocketAddress()
    }

    /**
     * Shared primitive [ping], [findNode], [store], [findValue],
     * [reflectOwnAddress], and [introduce] all build on: registers a pending
     * deferred for [transactionId], sends
     * [requestBytes] to [destination], and suspends up to [requestTimeoutMs]
     * for a matching response's raw bytes (or `null` on timeout). Always
     * cleans up the pending-request entry, success or not.
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

    /**
     * Fire-and-forget send: writes [bytes] to [destination] with no
     * pending-request registration and nothing awaited -- the first
     * fire-and-forget outbound send this class makes (every function above
     * is request/response-shaped via [sendAndAwait]). Used by
     * [handlePacket]'s `INTRODUCE_REQUEST` case to deliver an unsolicited
     * INTRODUCTION to a found target -- see [IntroductionMessage]'s own doc
     * for why that message expects no reply. A genuine local I/O error (e.g.
     * a closed socket) still propagates; there is no response to time out
     * on, so there is nothing to swallow here the way [ping]/[findNode]/etc.
     * swallow a timeout.
     */
    fun sendUnsolicited(destination: PeerAddress, bytes: ByteArray) {
        socket.send(DatagramPacket(bytes, bytes.size, destination.toInetSocketAddress()))
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
     * hard rule), then either answers a request, completes a matching
     * pending call, or -- INTRODUCTION's own case, the one exception --
     * neither: it fires [onIntroductionReceived] and sends nothing back,
     * since INTRODUCTION expects no reply (see [sendUnsolicited]).
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
            DhtMessageType.STORE_REQUEST -> {
                val message = StoreRequestMessage.decode(payload)
                observe(message.senderId, observedAddress)
                // The announcer's address is ALWAYS the packet's observed source,
                // never a self-reported field -- StoreRequestMessage carries no
                // address of its own, only senderId (this message's own
                // trust-model doc: a peer can only ever announce itself).
                val announcer = Contact(
                    id = message.senderId,
                    address = observedAddress.encode(),
                    lastSeenAtMs = System.currentTimeMillis(),
                )
                onStoreRequested(message.key, announcer)
                val ack = DhtMessage(type = DhtMessageType.STORE_RESPONSE, transactionId = message.transactionId, senderId = ownId)
                val bytes = ack.encode()
                socket.send(DatagramPacket(bytes, bytes.size, packet.address, packet.port))
            }
            DhtMessageType.STORE_RESPONSE -> {
                val message = DhtMessage.decode(payload)
                observe(message.senderId, observedAddress)
                pendingRequests[message.transactionId]?.complete(payload)
            }
            DhtMessageType.FIND_VALUE_REQUEST -> {
                val message = FindValueRequestMessage.decode(payload)
                observe(message.senderId, observedAddress)
                val outcome = onFindValueRequested(message.key, message.senderId)
                val response = when (outcome) {
                    is FindValueOutcome.Holders -> FindValueResponseMessage(
                        transactionId = message.transactionId,
                        senderId = ownId,
                        found = true,
                        contacts = outcome.contacts,
                    )
                    is FindValueOutcome.CloserNodes -> FindValueResponseMessage(
                        transactionId = message.transactionId,
                        senderId = ownId,
                        found = false,
                        contacts = outcome.contacts,
                    )
                }
                val bytes = response.encode()
                socket.send(DatagramPacket(bytes, bytes.size, packet.address, packet.port))
            }
            DhtMessageType.FIND_VALUE_RESPONSE -> {
                val message = FindValueResponseMessage.decode(payload)
                observe(message.senderId, observedAddress)
                pendingRequests[message.transactionId]?.complete(payload)
            }
            DhtMessageType.ADDRESS_REFLECTION_REQUEST -> {
                val message = AddressReflectionRequestMessage.decode(payload)
                observe(message.senderId, observedAddress)
                // Answered unconditionally, for free, on every node running
                // this transport -- no app-level callback hook, unlike
                // FIND_NODE/STORE/FIND_VALUE, which need RoutingTable/DhtStore
                // data this transport class doesn't own itself. This is what
                // makes both dht/'s DhtNode and rendezvous/'s RendezvousNode
                // automatically gain this capability just by sitting on this
                // same transport class -- nothing to wire in either of those
                // classes specifically.
                //
                // `observedAddress` -- computed once above from THIS packet's
                // own DatagramPacket source, never from anything inside
                // `message` -- is echoed back verbatim as the answer. This is
                // the entire mechanism: there is no self-reported address
                // field anywhere in AddressReflectionRequestMessage for a
                // requester to inject, so the reflected value in the response
                // below is always genuinely observed, matching this class's
                // hard rule stated in its own class doc.
                val response = AddressReflectionResponseMessage(
                    transactionId = message.transactionId,
                    senderId = ownId,
                    reflectedAddress = observedAddress,
                )
                val bytes = response.encode()
                socket.send(DatagramPacket(bytes, bytes.size, packet.address, packet.port))
            }
            DhtMessageType.ADDRESS_REFLECTION_RESPONSE -> {
                val message = AddressReflectionResponseMessage.decode(payload)
                observe(message.senderId, observedAddress)
                pendingRequests[message.transactionId]?.complete(payload)
            }
            DhtMessageType.INTRODUCE_REQUEST -> {
                val message = IntroduceRequestMessage.decode(payload)
                observe(message.senderId, observedAddress)
                // App-level callback hook, same shape as onFindNodeRequested --
                // this transport class doesn't own any registry/routing-table
                // data itself. Wired by DhtNode/RendezvousNode's own init block
                // to their respective lookup-by-id.
                val target = onIntroduceRequested(message.targetId)
                // A found Contact may carry more than one PeerAddress (Phase 4's
                // dual-stack self-registration) -- dial the first, same posture
                // as firstDialableAddress, but without throwing on a genuinely
                // empty/malformed address blob: that's treated the same as
                // "not found" rather than crashing this handler.
                val targetAddress = target?.let { PeerAddress.decodeList(it.address).firstOrNull() }
                val response = if (targetAddress != null) {
                    IntroduceResponseMessage.found(transactionId = message.transactionId, senderId = ownId, address = targetAddress)
                } else {
                    IntroduceResponseMessage.notFound(transactionId = message.transactionId, senderId = ownId)
                }
                val bytes = response.encode()
                socket.send(DatagramPacket(bytes, bytes.size, packet.address, packet.port))

                // Separately, and only when found: fire an unsolicited
                // INTRODUCTION at the target, relaying the requester's
                // self-reported address THROUGH, unmodified -- see
                // IntroduceRequestMessage.ownAddress's own doc for why this
                // device (R) has no way to independently verify that address
                // either. If not found, nothing is sent to anyone.
                if (targetAddress != null) {
                    val introduction = IntroductionMessage(
                        transactionId = TransactionId.random(),
                        senderId = ownId,
                        fromId = message.senderId,
                        claimedAddress = message.ownAddress,
                    )
                    sendUnsolicited(targetAddress, introduction.encode())
                }
            }
            DhtMessageType.INTRODUCE_RESPONSE -> {
                val message = IntroduceResponseMessage.decode(payload)
                observe(message.senderId, observedAddress)
                pendingRequests[message.transactionId]?.complete(payload)
            }
            DhtMessageType.INTRODUCTION -> {
                val message = IntroductionMessage.decode(payload)
                // message.senderId is R (the relay that genuinely, observably
                // sent this packet) -- observed the ordinary way. message.fromId/
                // message.claimedAddress describe A, and are NOT observed from
                // this packet's source (that would just be R's own address) --
                // they're handed to onIntroductionReceived below instead, never
                // fed into observe().
                observe(message.senderId, observedAddress)
                onIntroductionReceived(message.fromId, message.claimedAddress)
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
