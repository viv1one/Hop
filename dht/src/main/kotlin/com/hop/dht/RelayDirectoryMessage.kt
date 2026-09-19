package com.hop.dht

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * RELAY_ANNOUNCE/RELAY_ANNOUNCE_ACK/RELAY_QUERY/RELAY_QUERY_RESPONSE wire
 * messages: the discovery half of BUILD_PLAN.md's Phase 4 volunteer
 * relay-node fallback -- how a client learns a `tools/relay-node/`
 * `RelayNode`'s real TCP bridge address at all. `RelayNode`'s own
 * bridging/pairing logic is entirely untouched by this file; this file only
 * builds the wire-level "how do I find a relay's address" mechanism. Actually
 * falling back to a discovered relay when direct dial and NAT hole-punching
 * both fail (`InternetPeerConnectionManager`/`PeerDialer`) is separate, later,
 * client-side work -- not built here, and nothing here assumes it exists yet.
 *
 * **Why this can't reuse STORE_REQUEST/FIND_VALUE_REQUEST -- read this before
 * changing anything here.** [DhtStore]'s announce/get-peers primitive
 * ([StoreRequestMessage]) is built entirely on [DhtUdpTransport]'s hard rule
 * that a [Contact]'s address is ALWAYS the UDP packet's *observed* source,
 * never a self-reported field (see that class's own class doc, and
 * [StoreRequestMessage]'s own trust-model doc: "a peer can only ever announce
 * itself," using the packet's observed source, never message content). A
 * relay node's real bridging address is a **TCP** address on a port that has
 * nothing to do with whatever UDP source port it happened to send this
 * announcement from -- reusing STORE_REQUEST here would silently record the
 * wrong address (the relay's incidental UDP source port, never its real TCP
 * bridge port). [DhtStore]/[StoreRequestMessage] must not be touched or
 * reused for this.
 *
 * The shape that actually fits is [IntroduceRequestMessage.ownAddress]'s
 * precedent: a field that is *explicitly, deliberately self-reported*.
 * [RelayAnnounceRequestMessage.relayAddress] is the same kind of field, with
 * the exact same accepted limitation, stated there for the same reason:
 * **whoever later answers a RELAY_QUERY with an announced entry has no
 * independent way to verify the relay's claimed address is real, reachable,
 * or actually running a real `RelayNode`** -- the same trust level
 * [IntroduceRequestMessage.ownAddress]/[IntroductionMessage.claimedAddress]
 * already carry. Real abuse-resistance for who can announce as a relay (today,
 * anyone can announce a bogus address) is explicitly NOT solved here -- same
 * unauthenticated-discovery posture as every other address-only RPC this
 * module exposes to `rendezvous/`'s bootstrap node.
 *
 * Four independent top-level shapes, same reasoning as every other RPC group
 * in this module: [RELAY_ANNOUNCE]/[RELAY_ANNOUNCE_ACK]/[RELAY_QUERY]/
 * [RELAY_QUERY_RESPONSE] (new [DhtMessageType] wire values 13-16, next free
 * after [DhtMessageType.INTRODUCTION]) are wire-value constants only --
 * [DhtMessage] itself is never constructed with [RELAY_ANNOUNCE]'s,
 * [RELAY_QUERY]'s, or [RELAY_QUERY_RESPONSE]'s value.
 *
 * [RELAY_ANNOUNCE_ACK] IS the one exception, mirroring [DhtMessageType.STORE_RESPONSE]'s
 * own bare-ack shape exactly: a relay-announce ack carries nothing beyond
 * "registered," so it reuses [DhtMessage]'s existing fixed shape (the same
 * wire shape as PONG/STORE_RESPONSE) with this new type value, rather than
 * getting its own dedicated class.
 */

/**
 * `[1B version][1B type=RELAY_ANNOUNCE][8B transactionId][32B senderId][relayAddress: PeerAddress.encode()]`
 * = 49 bytes (IPv4 [relayAddress]) or 61 bytes (IPv6 [relayAddress]) -- the
 * same total shape as [AddressReflectionResponseMessage], since both carry
 * exactly one trailing [PeerAddress] after an identical
 * `[1B version][1B type][8B transactionId][32B senderId]` header.
 *
 * Sent by a volunteer relay node -> a rendezvous/DHT contact it's registering
 * with: "here is my own id, and my real TCP bridge address -- please
 * remember me for later RELAY_QUERY answers." [senderId] is the relay's own
 * [NodeId] (this module's standard sender-id convention, also used by the
 * receiver's ordinary [DhtUdpTransport.observe] call for the relay itself,
 * since this packet genuinely, observably arrived from it).
 *
 * **[relayAddress] is genuinely self-reported -- see this file's own doc for
 * why that's unavoidable here** (a TCP bridge port has no relationship to
 * this UDP packet's own source port, unlike every ordinary [Contact] this
 * module otherwise builds only from a packet's *observed* source) **and what
 * trust limitation that implies.** A dishonest or buggy relay could announce
 * any address here; neither the receiving rendezvous/DHT node nor a later
 * querier can verify it from this message alone.
 */
data class RelayAnnounceRequestMessage(
    val transactionId: TransactionId,
    val senderId: NodeId,
    val relayAddress: PeerAddress,
) {
    fun encode(): ByteArray {
        val addressBytes = relayAddress.encode()
        val buffer = ByteBuffer.allocate(HEADER_SIZE + addressBytes.size).order(ByteOrder.BIG_ENDIAN)
        buffer.put(DhtMessage.CURRENT_VERSION.toByte())
        buffer.put(DhtMessageType.RELAY_ANNOUNCE.wireValue.toByte())
        buffer.put(transactionId.bytes)
        buffer.put(senderId.bytes)
        buffer.put(addressBytes)
        return buffer.array()
    }

    companion object {
        private const val HEADER_SIZE = 1 + 1 + TransactionId.SIZE_BYTES + NodeId.SIZE_BYTES

        /**
         * Rejects a truncated header, an unknown version, a type byte other
         * than RELAY_ANNOUNCE, or a malformed/truncated trailing
         * [relayAddress] (converted from [PeerAddressDecodeException] into
         * [DhtMessageDecodeException], same posture as every other
         * trailing-address decoder in this module).
         */
        fun decode(bytes: ByteArray): RelayAnnounceRequestMessage {
            if (bytes.size < HEADER_SIZE) {
                throw DhtMessageDecodeException(
                    "Truncated RelayAnnounceRequestMessage: got ${bytes.size} bytes, need at least $HEADER_SIZE for the header"
                )
            }

            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)

            DhtMessageHeader.requireVersionAndType(
                buffer,
                messageTypeName = "RelayAnnounceRequestMessage",
                expectedType = DhtMessageType.RELAY_ANNOUNCE,
            )

            val transactionId = TransactionId(ByteArray(TransactionId.SIZE_BYTES).also { buffer.get(it) })
            val senderId = NodeId(ByteArray(NodeId.SIZE_BYTES).also { buffer.get(it) })
            val addressBytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
            val relayAddress = DhtMessageHeader.decodePeerAddress(
                addressBytes,
                fieldName = "relayAddress",
                messageTypeName = "RelayAnnounceRequestMessage",
            )

            return RelayAnnounceRequestMessage(transactionId = transactionId, senderId = senderId, relayAddress = relayAddress)
        }
    }
}

/**
 * `[1B version][1B type=RELAY_QUERY][8B transactionId][32B senderId]` = 42
 * bytes fixed -- the identical bare-ack shape as [DhtMessage]'s PING and
 * [AddressReflectionRequestMessage], carrying nothing beyond "who's asking,"
 * since the responder needs nothing else: it answers with whatever relay
 * entries it currently knows about ([DhtUdpTransport.onRelayQueryRequested]),
 * never anything the requester supplies.
 *
 * Gets its own dedicated type/class rather than reusing [DhtMessage] directly
 * -- same reasoning as [AddressReflectionRequestMessage]'s own doc: the
 * responder side needs a distinct type byte to dispatch on in
 * [DhtUdpTransport.handlePacket] (a PING must still get a PONG, not a relay
 * list).
 */
data class RelayQueryRequestMessage(
    val transactionId: TransactionId,
    val senderId: NodeId,
) {
    fun encode(): ByteArray {
        val buffer = ByteBuffer.allocate(WIRE_SIZE).order(ByteOrder.BIG_ENDIAN)
        buffer.put(DhtMessage.CURRENT_VERSION.toByte())
        buffer.put(DhtMessageType.RELAY_QUERY.wireValue.toByte())
        buffer.put(transactionId.bytes)
        buffer.put(senderId.bytes)
        return buffer.array()
    }

    companion object {
        const val WIRE_SIZE = 1 + 1 + TransactionId.SIZE_BYTES + NodeId.SIZE_BYTES

        /** Rejects wrong length, unknown version, or a type byte other than RELAY_QUERY. */
        fun decode(bytes: ByteArray): RelayQueryRequestMessage {
            if (bytes.size != WIRE_SIZE) {
                throw DhtMessageDecodeException("RelayQueryRequestMessage must be $WIRE_SIZE bytes, was ${bytes.size}")
            }

            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)

            DhtMessageHeader.requireVersionAndType(
                buffer,
                messageTypeName = "RelayQueryRequestMessage",
                expectedType = DhtMessageType.RELAY_QUERY,
            )

            val transactionId = TransactionId(ByteArray(TransactionId.SIZE_BYTES).also { buffer.get(it) })
            val senderId = NodeId(ByteArray(NodeId.SIZE_BYTES).also { buffer.get(it) })

            return RelayQueryRequestMessage(transactionId = transactionId, senderId = senderId)
        }
    }
}

/**
 * `[1B version][1B type=RELAY_QUERY_RESPONSE][8B transactionId][32B senderId][1B relayCount]
 * [repeated: 32B NodeId + 1B addressLength + addressLength bytes]`.
 *
 * Reuses [ContactListCodec] byte-for-byte -- an announced relay entry is
 * structurally identical to an ordinary [Contact] (an id plus an opaque
 * address blob), same shape [FindNodeResponseMessage] already carries a list
 * of, so this mirrors that message's own multi-contact list-encoding
 * verbatim rather than hand-rolling a second copy. [relays] is populated from
 * whatever a [Contact] happens to carry, whether or not it's genuinely a
 * relay -- callers (`RelayDirectory`/[DhtUdpTransport.onRelayQueryRequested])
 * own that policy, this class merely carries the list.
 */
data class RelayQueryResponseMessage(
    val transactionId: TransactionId,
    val senderId: NodeId,
    val relays: List<Contact>,
) {
    fun encode(): ByteArray {
        val bodySize = ContactListCodec.encodedSize(relays)
        val buffer = ByteBuffer.allocate(HEADER_SIZE + bodySize).order(ByteOrder.BIG_ENDIAN)
        buffer.put(DhtMessage.CURRENT_VERSION.toByte())
        buffer.put(DhtMessageType.RELAY_QUERY_RESPONSE.wireValue.toByte())
        buffer.put(transactionId.bytes)
        buffer.put(senderId.bytes)
        ContactListCodec.encode(buffer, relays, MAX_RELAYS, "RelayQueryResponseMessage")
        return buffer.array()
    }

    companion object {
        /**
         * Same wire-safety-ceiling reasoning as [FindNodeResponseMessage.MAX_CONTACTS]
         * (identical per-entry wire shape, so the same UDP-MTU-headroom math
         * applies verbatim) -- defense-in-depth, not a live constraint given
         * [RelayDirectory.DEFAULT_RESPONSE_CAP]'s much smaller bound.
         */
        const val MAX_RELAYS = 24

        private const val HEADER_SIZE = 1 + 1 + TransactionId.SIZE_BYTES + NodeId.SIZE_BYTES + 1

        /**
         * Rejects a truncated header, an unknown version, a type byte other
         * than RELAY_QUERY_RESPONSE, a truncated per-entry id/addressLength,
         * or a declared `addressLength` exceeding the bytes actually
         * remaining -- identical rejection posture to
         * [FindNodeResponseMessage.decode], since both share [ContactListCodec].
         */
        fun decode(bytes: ByteArray): RelayQueryResponseMessage {
            if (bytes.size < HEADER_SIZE) {
                throw DhtMessageDecodeException(
                    "Truncated RelayQueryResponseMessage: got ${bytes.size} bytes, need at least $HEADER_SIZE for the header"
                )
            }

            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)

            DhtMessageHeader.requireVersionAndType(
                buffer,
                messageTypeName = "RelayQueryResponseMessage",
                expectedType = DhtMessageType.RELAY_QUERY_RESPONSE,
            )

            val transactionId = TransactionId(ByteArray(TransactionId.SIZE_BYTES).also { buffer.get(it) })
            val senderId = NodeId(ByteArray(NodeId.SIZE_BYTES).also { buffer.get(it) })
            val relayCount = buffer.get().toInt() and 0xFF
            val relays = ContactListCodec.decode(buffer, relayCount, "RelayQueryResponseMessage")

            return RelayQueryResponseMessage(transactionId = transactionId, senderId = senderId, relays = relays)
        }
    }
}
