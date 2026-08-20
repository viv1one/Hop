package com.hop.dht

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Thrown when a byte array cannot be decoded as a valid [DhtMessage]: wrong
 * length, unknown/future version, or unknown type. Decoding must fail loudly
 * rather than silently misparse -- mirrors `Frame.decode`'s exact posture
 * (`/protocol/WIRE_FORMAT.md`'s hard versioning convention, applied here even
 * though this wire format lives in `dht/`, not `protocol/`).
 */
class DhtMessageDecodeException(message: String) : Exception(message)

/** The RPC types this slice defines. FIND_NODE/FIND_VALUE/STORE are later slices. */
enum class DhtMessageType(val wireValue: Int) {
    PING(0),
    PONG(1),
    ;

    companion object {
        fun fromWireValue(value: Int): DhtMessageType =
            values().find { it.wireValue == value }
                ?: throw DhtMessageDecodeException("Unknown DhtMessage type: $value")
    }
}

/**
 * The DHT liveness-RPC wire message: a fixed-size 42-byte PING or PONG.
 *
 * Both message types are the same fixed size on purpose -- a PONG is never
 * larger than the PING that provoked it, so this format carries no UDP
 * amplification/reflection concern (a common pitfall for UDP-based services);
 * no mitigation is needed this slice.
 *
 * Deliberately carries no address field: a contact's stored address must
 * always come from the UDP packet's *observed* source address
 * (`DatagramPacket.address`/`.port`), never a self-reported field inside the
 * message -- see [DhtUdpTransport]'s own doc for where that rule is enforced.
 */
data class DhtMessage(
    val type: DhtMessageType,
    val transactionId: TransactionId,
    val senderId: NodeId,
) {
    /** `[1B version][1B type][8B transactionId][32B senderId]`. */
    fun encode(): ByteArray {
        val buffer = ByteBuffer.allocate(WIRE_SIZE).order(ByteOrder.BIG_ENDIAN)
        buffer.put(CURRENT_VERSION.toByte())
        buffer.put(type.wireValue.toByte())
        buffer.put(transactionId.bytes)
        buffer.put(senderId.bytes)
        return buffer.array()
    }

    companion object {
        const val CURRENT_VERSION = 1
        const val WIRE_SIZE = 1 + 1 + TransactionId.SIZE_BYTES + NodeId.SIZE_BYTES

        /**
         * Rejects wrong length, unknown version, unknown type -- mirrors
         * `Frame.decode`'s exact posture (`/protocol/WIRE_FORMAT.md`'s hard
         * versioning convention, applied here even though this format lives
         * in `dht/`, not `protocol/`).
         */
        fun decode(bytes: ByteArray): DhtMessage {
            if (bytes.size != WIRE_SIZE) {
                throw DhtMessageDecodeException("DhtMessage must be $WIRE_SIZE bytes, was ${bytes.size}")
            }

            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)

            val version = buffer.get().toInt() and 0xFF
            if (version != CURRENT_VERSION) {
                throw DhtMessageDecodeException(
                    "Unsupported DhtMessage version: $version (this decoder only understands version $CURRENT_VERSION)"
                )
            }

            val type = DhtMessageType.fromWireValue(buffer.get().toInt() and 0xFF)
            val transactionId = TransactionId(ByteArray(TransactionId.SIZE_BYTES).also { buffer.get(it) })
            val senderId = NodeId(ByteArray(NodeId.SIZE_BYTES).also { buffer.get(it) })

            return DhtMessage(type = type, transactionId = transactionId, senderId = senderId)
        }
    }
}
