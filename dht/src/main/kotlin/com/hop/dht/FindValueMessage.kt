package com.hop.dht

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * FIND_VALUE request/response wire messages: "who has announced holding
 * [FindValueRequestMessage.key]? If you don't know, who's closer than you?"
 * -- BitTorrent Mainline DHT's `get_peers` shape (BEP 5), applied to HOP's
 * announce/get-peers-for-a-key model (see [DhtStore]'s own doc for the
 * "content-hash -> holder now, geohash-prefix -> topic-subscriber later,
 * never a generic KV store" framing, confirmed against memo.md/PRD §6).
 *
 * Deliberately its OWN top-level type, not a reuse of [FindNodeRequestMessage]
 * despite an identical byte layout: every RPC kind needs its own
 * [DhtMessageType] regardless (the receive loop dispatches on it), and the
 * field means something different -- [FindValueRequestMessage.key] means
 * "find holders of," [FindNodeRequestMessage.targetId] means "get close to"
 * -- matching this wire format's "never inferred, always explicit"
 * philosophy, not worth blurring for a few duplicated lines.
 */

/**
 * `[1B version][1B type=FIND_VALUE_REQUEST][8B transactionId][32B senderId][32B key]`
 * = 74 bytes fixed.
 */
data class FindValueRequestMessage(
    val transactionId: TransactionId,
    val senderId: NodeId,
    val key: NodeId,
) {
    fun encode(): ByteArray {
        val buffer = ByteBuffer.allocate(WIRE_SIZE).order(ByteOrder.BIG_ENDIAN)
        buffer.put(DhtMessage.CURRENT_VERSION.toByte())
        buffer.put(DhtMessageType.FIND_VALUE_REQUEST.wireValue.toByte())
        buffer.put(transactionId.bytes)
        buffer.put(senderId.bytes)
        buffer.put(key.bytes)
        return buffer.array()
    }

    companion object {
        const val WIRE_SIZE = 1 + 1 + TransactionId.SIZE_BYTES + NodeId.SIZE_BYTES + NodeId.SIZE_BYTES

        /** Rejects wrong length, unknown version, or a type byte other than FIND_VALUE_REQUEST. */
        fun decode(bytes: ByteArray): FindValueRequestMessage {
            if (bytes.size != WIRE_SIZE) {
                throw DhtMessageDecodeException("FindValueRequestMessage must be $WIRE_SIZE bytes, was ${bytes.size}")
            }

            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)

            val version = buffer.get().toInt() and 0xFF
            if (version != DhtMessage.CURRENT_VERSION) {
                throw DhtMessageDecodeException(
                    "Unsupported FindValueRequestMessage version: $version (this decoder only understands version ${DhtMessage.CURRENT_VERSION})"
                )
            }

            val type = DhtMessageType.fromWireValue(buffer.get().toInt() and 0xFF)
            if (type != DhtMessageType.FIND_VALUE_REQUEST) {
                throw DhtMessageDecodeException("Expected FIND_VALUE_REQUEST type byte, got $type")
            }

            val transactionId = TransactionId(ByteArray(TransactionId.SIZE_BYTES).also { buffer.get(it) })
            val senderId = NodeId(ByteArray(NodeId.SIZE_BYTES).also { buffer.get(it) })
            val key = NodeId(ByteArray(NodeId.SIZE_BYTES).also { buffer.get(it) })

            return FindValueRequestMessage(transactionId = transactionId, senderId = senderId, key = key)
        }
    }
}

/**
 * `[1B version][1B type=FIND_VALUE_RESPONSE][8B transactionId][32B senderId]
 * [1B found: 0 or 1][1B contactCount][repeated: 32B NodeId + 1B addressLength
 * + addressLength bytes]`.
 *
 * [found]`=true` -> [contacts] are known holders of
 * [FindValueRequestMessage.key] (the querier should stop searching and try
 * these directly, per [DhtNode.findValue]'s early-termination behavior).
 * [found]`=false` -> [contacts] are closer routing candidates (same
 * shape/meaning as [FindNodeResponseMessage.contacts] -- keep iterating).
 * Mutually exclusive, matching BitTorrent's own BEP 5 `get_peers` response
 * shape (`values` xor `nodes`).
 *
 * Shares [ContactListCodec] with [FindNodeResponseMessage] for the repeated
 * per-contact portion -- see that object's own doc.
 */
data class FindValueResponseMessage(
    val transactionId: TransactionId,
    val senderId: NodeId,
    val found: Boolean,
    val contacts: List<Contact>,
) {
    fun encode(): ByteArray {
        val bodySize = ContactListCodec.encodedSize(contacts)
        val buffer = ByteBuffer.allocate(HEADER_SIZE + bodySize).order(ByteOrder.BIG_ENDIAN)
        buffer.put(DhtMessage.CURRENT_VERSION.toByte())
        buffer.put(DhtMessageType.FIND_VALUE_RESPONSE.wireValue.toByte())
        buffer.put(transactionId.bytes)
        buffer.put(senderId.bytes)
        buffer.put(if (found) 1.toByte() else 0.toByte())
        ContactListCodec.encode(buffer, contacts, MAX_CONTACTS, "FindValueResponseMessage")
        return buffer.array()
    }

    companion object {
        /** Same ceiling and rationale as [FindNodeResponseMessage.MAX_CONTACTS]. */
        const val MAX_CONTACTS = FindNodeResponseMessage.MAX_CONTACTS

        private const val HEADER_SIZE = 1 + 1 + TransactionId.SIZE_BYTES + NodeId.SIZE_BYTES + 1 + 1

        /**
         * Rejects a truncated header, an unknown version, a type byte other
         * than FIND_VALUE_RESPONSE, a truncated per-contact id/addressLength,
         * or a declared `addressLength` exceeding the bytes actually
         * remaining.
         */
        fun decode(bytes: ByteArray): FindValueResponseMessage {
            if (bytes.size < HEADER_SIZE) {
                throw DhtMessageDecodeException(
                    "Truncated FindValueResponseMessage: got ${bytes.size} bytes, need at least $HEADER_SIZE for the header"
                )
            }

            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)

            val version = buffer.get().toInt() and 0xFF
            if (version != DhtMessage.CURRENT_VERSION) {
                throw DhtMessageDecodeException(
                    "Unsupported FindValueResponseMessage version: $version (this decoder only understands version ${DhtMessage.CURRENT_VERSION})"
                )
            }

            val type = DhtMessageType.fromWireValue(buffer.get().toInt() and 0xFF)
            if (type != DhtMessageType.FIND_VALUE_RESPONSE) {
                throw DhtMessageDecodeException("Expected FIND_VALUE_RESPONSE type byte, got $type")
            }

            val transactionId = TransactionId(ByteArray(TransactionId.SIZE_BYTES).also { buffer.get(it) })
            val senderId = NodeId(ByteArray(NodeId.SIZE_BYTES).also { buffer.get(it) })
            val found = (buffer.get().toInt() and 0xFF) != 0
            val contactCount = buffer.get().toInt() and 0xFF
            val contacts = ContactListCodec.decode(buffer, contactCount, "FindValueResponseMessage")

            return FindValueResponseMessage(transactionId = transactionId, senderId = senderId, found = found, contacts = contacts)
        }
    }
}
