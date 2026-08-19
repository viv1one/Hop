package com.hop.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Thrown when a byte array cannot be decoded as a valid [PreKeyBundleEnvelope]:
 * truncated `peerId` length/bytes, or a truncated header generally. Decoding
 * must fail loudly rather than silently misparse — see /protocol/WIRE_FORMAT.md.
 */
class PreKeyBundleEnvelopeDecodeException(message: String) : Exception(message)

/**
 * The payload carried inside a [WirePayloadType.PREKEY_BUNDLE]-typed
 * [WireEnvelope]: identifies which peer is announcing a Double Ratchet
 * prekey bundle, plus the bundle bytes themselves.
 *
 * [bundleBytes] is treated as **fully opaque** by this class and by all of
 * `protocol/` — it is a serialized libsignal-client `PreKeyBundle`, but this
 * module never imports libsignal-client types or attempts to deserialize it.
 * That opacity is deliberate: it preserves ADR 0001's one-way dependency
 * rule (`protocol/` may depend on `crypto/`, never the reverse) without
 * adding any *new* dependency on libsignal-client specifically. The caller
 * in `crypto/`/app code is responsible for serializing/deserializing the
 * actual `PreKeyBundle`.
 *
 * [peerId] is likewise treated as an opaque identifying string by this
 * module — it happens to be the hex-encoded `senderDeviceId` per the
 * messaging design, but [WireEnvelope]/`protocol/` doesn't need to know
 * that semantic.
 */
data class PreKeyBundleEnvelope(
    val peerId: String,
    val bundleBytes: ByteArray,
) {
    /**
     * Encodes as `[4-byte peerId-UTF8-byte-length][peerId UTF-8 bytes][bundleBytes]`,
     * mirroring how [Frame] length-prefixes its own variable-length `payload`
     * field.
     */
    fun encode(): ByteArray {
        val peerIdBytes = peerId.toByteArray(StandardCharsets.UTF_8)
        val buffer = ByteBuffer.allocate(4 + peerIdBytes.size + bundleBytes.size)
        buffer.order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(peerIdBytes.size)
        buffer.put(peerIdBytes)
        buffer.put(bundleBytes)
        return buffer.array()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PreKeyBundleEnvelope) return false
        return peerId == other.peerId && bundleBytes.contentEquals(other.bundleBytes)
    }

    override fun hashCode(): Int {
        var result = peerId.hashCode()
        result = 31 * result + bundleBytes.contentHashCode()
        return result
    }

    companion object {
        /**
         * Decodes [bytes] into a [PreKeyBundleEnvelope].
         *
         * Throws [PreKeyBundleEnvelopeDecodeException] on:
         * - fewer than 4 bytes (truncated `peerId` length prefix),
         * - a declared `peerId` byte length longer than the bytes actually
         *   available (truncated `peerId`).
         *
         * Everything after `peerId` is [bundleBytes], with no further length
         * prefix or validation — this module treats it as fully opaque.
         */
        fun decode(bytes: ByteArray): PreKeyBundleEnvelope {
            if (bytes.size < 4) {
                throw PreKeyBundleEnvelopeDecodeException(
                    "Truncated prekey bundle envelope: got ${bytes.size} bytes, need at least 4 for the peerId length prefix"
                )
            }

            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val peerIdLength = buffer.int.toLong() and 0xFFFFFFFFL
            val remainingAfterLength = buffer.remaining().toLong()
            if (peerIdLength > remainingAfterLength) {
                throw PreKeyBundleEnvelopeDecodeException(
                    "Truncated prekey bundle envelope: declared peerId length=$peerIdLength but only $remainingAfterLength bytes remain"
                )
            }

            val peerIdBytes = ByteArray(peerIdLength.toInt()).also { buffer.get(it) }
            val bundleBytes = ByteArray(buffer.remaining()).also { buffer.get(it) }

            return PreKeyBundleEnvelope(
                peerId = String(peerIdBytes, StandardCharsets.UTF_8),
                bundleBytes = bundleBytes,
            )
        }
    }
}
