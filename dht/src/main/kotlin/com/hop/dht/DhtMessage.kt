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

/**
 * The RPC types across every message kind in this module -- shared because
 * every wire format here (this file's [DhtMessage] and, as of Slice 4,
 * `FindNodeMessage.kt`'s independent `FindNodeRequestMessage`/
 * `FindNodeResponseMessage`) puts its type byte at the same fixed offset 1
 * (`[1B version][1B type]...`), letting a receiver dispatch on this one byte
 * before choosing which type's decoder to invoke. FIND_NODE_REQUEST/
 * FIND_NODE_RESPONSE are wire-value constants only -- [DhtMessage] itself
 * (this class's `encode`/`decode`, its fixed 42-byte [WIRE_SIZE]) is
 * untouched by Slice 4 and never constructed with those two type values;
 * their actual encode/decode logic lives entirely in `FindNodeMessage.kt`'s
 * own types, deliberately not a retrofit of [DhtMessage] into a sealed
 * hierarchy. FIND_VALUE/STORE are still later slices.
 */
enum class DhtMessageType(val wireValue: Int) {
    PING(0),
    PONG(1),
    FIND_NODE_REQUEST(2),
    FIND_NODE_RESPONSE(3),
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
