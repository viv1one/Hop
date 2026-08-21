package com.hop.dht

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * FIND_NODE request/response wire messages: "who do you know that's close to
 * [FindNodeRequestMessage.targetId]?"
 *
 * Two independent top-level types, deliberately **not** a retrofit of
 * [DhtMessage] into a sealed hierarchy -- that would be a breaking change to
 * already-tested Slice 3 code ([DhtMessageTest], every existing
 * [DhtUdpTransportTest] construction site) for no functional gain this slice
 * needs. Both reuse [DhtMessageDecodeException] and the shared
 * `[1B version][1B type]...` type-byte-at-offset-1 convention
 * ([DhtMessageType]) every message kind in this module uses.
 */

/**
 * `[1B version][1B type=FIND_NODE_REQUEST][8B transactionId][32B senderId][32B targetId]`
 * = 74 bytes fixed.
 */
data class FindNodeRequestMessage(
    val transactionId: TransactionId,
    val senderId: NodeId,
    val targetId: NodeId,
) {
    fun encode(): ByteArray {
        val buffer = ByteBuffer.allocate(WIRE_SIZE).order(ByteOrder.BIG_ENDIAN)
        buffer.put(DhtMessage.CURRENT_VERSION.toByte())
        buffer.put(DhtMessageType.FIND_NODE_REQUEST.wireValue.toByte())
        buffer.put(transactionId.bytes)
        buffer.put(senderId.bytes)
        buffer.put(targetId.bytes)
        return buffer.array()
    }

    companion object {
        const val WIRE_SIZE = 1 + 1 + TransactionId.SIZE_BYTES + NodeId.SIZE_BYTES + NodeId.SIZE_BYTES

        /** Rejects wrong length, unknown version, or a type byte other than FIND_NODE_REQUEST. */
        fun decode(bytes: ByteArray): FindNodeRequestMessage {
            if (bytes.size != WIRE_SIZE) {
                throw DhtMessageDecodeException("FindNodeRequestMessage must be $WIRE_SIZE bytes, was ${bytes.size}")
            }

            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)

            val version = buffer.get().toInt() and 0xFF
            if (version != DhtMessage.CURRENT_VERSION) {
                throw DhtMessageDecodeException(
                    "Unsupported FindNodeRequestMessage version: $version (this decoder only understands version ${DhtMessage.CURRENT_VERSION})"
                )
            }

            val type = DhtMessageType.fromWireValue(buffer.get().toInt() and 0xFF)
            if (type != DhtMessageType.FIND_NODE_REQUEST) {
                throw DhtMessageDecodeException("Expected FIND_NODE_REQUEST type byte, got $type")
            }

            val transactionId = TransactionId(ByteArray(TransactionId.SIZE_BYTES).also { buffer.get(it) })
            val senderId = NodeId(ByteArray(NodeId.SIZE_BYTES).also { buffer.get(it) })
            val targetId = NodeId(ByteArray(NodeId.SIZE_BYTES).also { buffer.get(it) })

            return FindNodeRequestMessage(transactionId = transactionId, senderId = senderId, targetId = targetId)
        }
    }
}

/**
 * `[1B version][1B type=FIND_NODE_RESPONSE][8B transactionId][32B senderId][1B contactCount]
 * [repeated: 32B NodeId + 1B addressLength + addressLength bytes]`.
 *
 * Every contact entry carries its own explicit address-length prefix --
 * following `Frame.payloadLength`'s precedent, deliberately not an implicit
 * "derive the length from [PeerAddress]'s family byte" trick, matching this
 * codebase's "never inferred, explicit wire field" wire-format philosophy
 * (`/protocol/WIRE_FORMAT.md`).
 *
 * [Contact.lastSeenAtMs] is never carried on the wire (like [DhtMessage],
 * this format has no self-reported liveness/timestamp field) -- a decoded
 * contact's `lastSeenAtMs` is stamped at decode time, matching
 * [DhtUdpTransport.handlePacket]'s own "the timestamp reflects when *we*
 * observed this" convention.
 */
data class FindNodeResponseMessage(
    val transactionId: TransactionId,
    val senderId: NodeId,
    val contacts: List<Contact>,
) {
    fun encode(): ByteArray {
        require(contacts.size <= MAX_CONTACTS) {
            "FindNodeResponseMessage cannot carry more than $MAX_CONTACTS contacts, had ${contacts.size}"
        }

        val bodySize = contacts.sumOf { NodeId.SIZE_BYTES + 1 + it.address.size }
        val buffer = ByteBuffer.allocate(HEADER_SIZE + bodySize).order(ByteOrder.BIG_ENDIAN)
        buffer.put(DhtMessage.CURRENT_VERSION.toByte())
        buffer.put(DhtMessageType.FIND_NODE_RESPONSE.wireValue.toByte())
        buffer.put(transactionId.bytes)
        buffer.put(senderId.bytes)
        buffer.put(contacts.size.toByte())
        for (contact in contacts) {
            require(contact.address.size <= 0xFF) {
                "Contact address must fit in a uint8 length prefix, was ${contact.address.size} bytes for ${contact.id}"
            }
            buffer.put(contact.id.bytes)
            buffer.put(contact.address.size.toByte())
            buffer.put(contact.address)
        }
        return buffer.array()
    }

    companion object {
        /**
         * Defense-in-depth ceiling, not a live constraint today at
         * `RoutingTable.k`'s default of 20 -- worst case (20 all-IPv6
         * contacts, each explicit-length-prefixed: 32B id + 1B length + 19B
         * address = 52 bytes/contact) is `20 * 52 + 43B header` = ~1083
         * bytes, comfortably under IPv6's *mandatory* minimum link MTU (RFC
         * 8200: 1280 bytes) minus IPv6/UDP headers -- a 1232-byte safe UDP
         * payload budget. (The oft-cited 508-byte figure is an obsolete
         * "any path, however constrained" IPv4-only bound, irrelevant to a
         * modern mobile app.) This constant guards against a future
         * `KBucket.DEFAULT_K` bump silently reintroducing fragmentation
         * risk -- unmeasured/placeholder posture matching
         * `KBucket.DEFAULT_K` itself, not a promise that 24 contacts always
         * fits (24 * 52 + 43 = ~1291 bytes, over budget; if `DEFAULT_K` is
         * ever raised near this ceiling, revisit this constant too).
         */
        const val MAX_CONTACTS = 24

        private const val HEADER_SIZE = 1 + 1 + TransactionId.SIZE_BYTES + NodeId.SIZE_BYTES + 1

        /**
         * Rejects a truncated header, an unknown version, a type byte other
         * than FIND_NODE_RESPONSE, a truncated per-contact id/addressLength,
         * or a declared `addressLength` exceeding the bytes actually
         * remaining.
         */
        fun decode(bytes: ByteArray): FindNodeResponseMessage {
            if (bytes.size < HEADER_SIZE) {
                throw DhtMessageDecodeException(
                    "Truncated FindNodeResponseMessage: got ${bytes.size} bytes, need at least $HEADER_SIZE for the header"
                )
            }

            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)

            val version = buffer.get().toInt() and 0xFF
            if (version != DhtMessage.CURRENT_VERSION) {
                throw DhtMessageDecodeException(
                    "Unsupported FindNodeResponseMessage version: $version (this decoder only understands version ${DhtMessage.CURRENT_VERSION})"
                )
            }

            val type = DhtMessageType.fromWireValue(buffer.get().toInt() and 0xFF)
            if (type != DhtMessageType.FIND_NODE_RESPONSE) {
                throw DhtMessageDecodeException("Expected FIND_NODE_RESPONSE type byte, got $type")
            }

            val transactionId = TransactionId(ByteArray(TransactionId.SIZE_BYTES).also { buffer.get(it) })
            val senderId = NodeId(ByteArray(NodeId.SIZE_BYTES).also { buffer.get(it) })
            val contactCount = buffer.get().toInt() and 0xFF

            val receivedAtMs = System.currentTimeMillis()
            val contacts = ArrayList<Contact>(contactCount)
            for (index in 0 until contactCount) {
                if (buffer.remaining() < NodeId.SIZE_BYTES + 1) {
                    throw DhtMessageDecodeException(
                        "Truncated FindNodeResponseMessage: not enough bytes remaining for contact ${index + 1}'s id+addressLength"
                    )
                }
                val contactId = NodeId(ByteArray(NodeId.SIZE_BYTES).also { buffer.get(it) })
                val addressLength = buffer.get().toInt() and 0xFF
                if (buffer.remaining() < addressLength) {
                    throw DhtMessageDecodeException(
                        "Truncated FindNodeResponseMessage: declared addressLength=$addressLength for contact ${index + 1} but only ${buffer.remaining()} bytes remain"
                    )
                }
                val address = ByteArray(addressLength).also { buffer.get(it) }
                contacts.add(Contact(id = contactId, address = address, lastSeenAtMs = receivedAtMs))
            }

            return FindNodeResponseMessage(transactionId = transactionId, senderId = senderId, contacts = contacts)
        }
    }
}
