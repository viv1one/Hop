package com.hop.dht

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * STORE request wire message: "I am announcing myself as a holder of [key]."
 * Part of the BitTorrent-Mainline-DHT-style announce/get-peers primitive
 * (BEP 5's `announce_peer`) [DhtStore] backs -- never a generic "store this
 * arbitrary value" RPC.
 *
 * The response is a bare ack and deliberately gets **no new class** -- unlike
 * [FindValueRequestMessage]/[FindValueResponseMessage] below, which do get
 * independent types. A STORE ack carries nothing beyond "acknowledged," so it
 * reuses [DhtMessage]'s existing fixed shape (the same wire shape as PONG)
 * with the new [DhtMessageType.STORE_RESPONSE] value -- this is the case
 * where reuse is warranted, in contrast to FIND_VALUE's response, which must
 * distinguish holders-found from closer-routing-candidates and so cannot be
 * represented by [DhtMessage] alone.
 *
 * **Trust-model boundary, deliberate:** [senderId] (cross-checked against the
 * packet's *observed* source address, per [DhtUdpTransport]'s hard rule) is
 * the ONLY holder identity this message can announce -- there is no separate
 * "holderId" field, so a peer can only ever announce itself as a holder,
 * never a third party. This does not, by itself, prove the sender actually
 * holds the content behind [key] -- see [DhtStore]'s own doc for that stated
 * limit (unauthenticated STORE acceptance): a hostile/Sybil peer can announce
 * itself as a holder of content it doesn't have, at zero cost, wasting a
 * legitimate querier's later fetch attempt. A real mitigation
 * (proof-of-possession challenge, attestation-gated rate limiting) is future
 * work, not built this slice.
 */
data class StoreRequestMessage(
    val transactionId: TransactionId,
    val senderId: NodeId,
    val key: NodeId,
) {
    /** `[1B version][1B type=STORE_REQUEST][8B transactionId][32B senderId][32B key]` = 74 bytes fixed. */
    fun encode(): ByteArray {
        val buffer = ByteBuffer.allocate(WIRE_SIZE).order(ByteOrder.BIG_ENDIAN)
        buffer.put(DhtMessage.CURRENT_VERSION.toByte())
        buffer.put(DhtMessageType.STORE_REQUEST.wireValue.toByte())
        buffer.put(transactionId.bytes)
        buffer.put(senderId.bytes)
        buffer.put(key.bytes)
        return buffer.array()
    }

    companion object {
        const val WIRE_SIZE = 1 + 1 + TransactionId.SIZE_BYTES + NodeId.SIZE_BYTES + NodeId.SIZE_BYTES

        /** Rejects wrong length, unknown version, or a type byte other than STORE_REQUEST. */
        fun decode(bytes: ByteArray): StoreRequestMessage {
            if (bytes.size != WIRE_SIZE) {
                throw DhtMessageDecodeException("StoreRequestMessage must be $WIRE_SIZE bytes, was ${bytes.size}")
            }

            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)

            val version = buffer.get().toInt() and 0xFF
            if (version != DhtMessage.CURRENT_VERSION) {
                throw DhtMessageDecodeException(
                    "Unsupported StoreRequestMessage version: $version (this decoder only understands version ${DhtMessage.CURRENT_VERSION})"
                )
            }

            val type = DhtMessageType.fromWireValue(buffer.get().toInt() and 0xFF)
            if (type != DhtMessageType.STORE_REQUEST) {
                throw DhtMessageDecodeException("Expected STORE_REQUEST type byte, got $type")
            }

            val transactionId = TransactionId(ByteArray(TransactionId.SIZE_BYTES).also { buffer.get(it) })
            val senderId = NodeId(ByteArray(NodeId.SIZE_BYTES).also { buffer.get(it) })
            val key = NodeId(ByteArray(NodeId.SIZE_BYTES).also { buffer.get(it) })

            return StoreRequestMessage(transactionId = transactionId, senderId = senderId, key = key)
        }
    }
}
