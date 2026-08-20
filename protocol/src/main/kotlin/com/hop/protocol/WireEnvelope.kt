package com.hop.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Thrown when a byte array cannot be decoded as a valid [WireEnvelope]:
 * unknown type byte, truncated length, or a declared payload length longer
 * than the bytes actually available. Decoding must fail loudly rather than
 * silently misparse — see /protocol/WIRE_FORMAT.md.
 */
class WireEnvelopeDecodeException(message: String) : Exception(message)

/**
 * The socket-level payload types this envelope layer can carry, per
 * /protocol/WIRE_FORMAT.md's envelope section. Explicit byte values (not
 * ordinal position) are part of the wire contract, matching how [Frame]'s
 * own [ContentType]/[ReachTier] enums are encoded.
 */
enum class WirePayloadType(val wireValue: Int) {
    /** Payload is exactly today's [Frame.encode] output, byte-for-byte unchanged. */
    POST_FRAME(0),

    /** Payload is a [PreKeyBundleEnvelope]-encoded prekey bundle announcement. */
    PREKEY_BUNDLE(1),

    /** Payload is a [MessageCiphertextEnvelope]-encoded Double Ratchet ciphertext. */
    MESSAGE_CIPHERTEXT(2),

    /**
     * Payload is a [DontRelayFlagEnvelope]-encoded "don't relay" signal
     * (Phase 2 Slice 2) -- one attested device's flag that a given clipHash
     * should stop being relayed further. Propagates through the mesh the
     * same way [POST_FRAME] does, but never touches [Frame]'s own on-wire
     * `dontRelay` bit -- see [DontRelayFlagEnvelope]'s own doc.
     */
    DONT_RELAY_FLAG(3),
    ;

    companion object {
        fun fromWireValue(value: Int): WirePayloadType =
            values().find { it.wireValue == value }
                ?: throw WireEnvelopeDecodeException("Unknown WirePayloadType value: $value")
    }
}

/**
 * The socket-level wire envelope wrapping every blob sent over a WiFi Direct
 * transfer socket, as of the version noted in /protocol/WIRE_FORMAT.md's
 * envelope section:
 *
 * ```
 * [1-byte WirePayloadType][4-byte big-endian length][payload bytes]
 * ```
 *
 * This replaces the previous bare `[4-byte length][Frame bytes]` socket
 * framing with a tagged version, so a receiver can dispatch on [type] before
 * deciding how to interpret [payload] — a post [Frame], a [PreKeyBundleEnvelope],
 * a [MessageCiphertextEnvelope], or a [DontRelayFlagEnvelope]. This is a breaking change to the socket
 * wire format: see /protocol/WIRE_FORMAT.md for why that's acceptable now.
 *
 * [WireEnvelope] never touches [Frame]'s internals — a [WirePayloadType.POST_FRAME]-
 * typed envelope's [payload] is simply whatever [Frame.encode] already
 * produces, passed through unchanged. It has no dependency on `crypto/`:
 * [payload] is opaque bytes as far as this class is concerned, exactly like
 * [Frame.payload].
 */
data class WireEnvelope(
    val type: WirePayloadType,
    val payload: ByteArray,
) {
    init {
        require(payload.size.toLong() <= 0xFFFFFFFFL) {
            "payload length must fit in a uint32, was ${payload.size}"
        }
    }

    /** Encodes this envelope as `[type][length][payload]` per /protocol/WIRE_FORMAT.md. */
    fun encode(): ByteArray {
        val buffer = ByteBuffer.allocate(HEADER_SIZE + payload.size)
        buffer.order(ByteOrder.BIG_ENDIAN)
        buffer.put(type.wireValue.toByte())
        buffer.putInt(payload.size)
        buffer.put(payload)
        return buffer.array()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WireEnvelope) return false
        return type == other.type && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }

    companion object {
        /** 1-byte type tag + 4-byte length prefix. */
        const val HEADER_SIZE: Int = 1 + 4

        /**
         * Convenience wrapper equivalent to `WireEnvelope(type, payload).encode()`.
         */
        fun encode(type: WirePayloadType, payload: ByteArray): ByteArray =
            WireEnvelope(type, payload).encode()

        /**
         * Decodes [bytes] into a [WireEnvelope].
         *
         * Throws [WireEnvelopeDecodeException] on:
         * - fewer than [HEADER_SIZE] bytes (truncated header — this includes the
         *   old bare `[4-byte length][Frame bytes]` framing this envelope
         *   replaces, since that framing has no leading type byte to align on),
         * - an unrecognized type byte,
         * - a declared payload length longer than the bytes actually available
         *   (truncated payload).
         */
        fun decode(bytes: ByteArray): WireEnvelope {
            if (bytes.size < HEADER_SIZE) {
                throw WireEnvelopeDecodeException(
                    "Truncated envelope: got ${bytes.size} bytes, need at least $HEADER_SIZE for the header"
                )
            }

            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)

            val type = WirePayloadType.fromWireValue(buffer.get().toInt() and 0xFF)

            val payloadLength = buffer.int.toLong() and 0xFFFFFFFFL
            val remaining = buffer.remaining().toLong()
            if (payloadLength > remaining) {
                throw WireEnvelopeDecodeException(
                    "Truncated envelope: declared payload length=$payloadLength but only $remaining bytes remain"
                )
            }

            val payload = ByteArray(payloadLength.toInt()).also { buffer.get(it) }
            return WireEnvelope(type, payload)
        }
    }
}
