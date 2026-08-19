package com.hop.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Thrown when a byte array cannot be decoded as a valid [MessageCiphertextEnvelope]:
 * truncated length prefix/bytes for either peer id field, or a truncated
 * header generally. Decoding must fail loudly rather than silently misparse
 * — see /protocol/WIRE_FORMAT.md.
 */
class MessageCiphertextEnvelopeDecodeException(message: String) : Exception(message)

/**
 * The payload carried inside a [WirePayloadType.MESSAGE_CIPHERTEXT]-typed
 * [WireEnvelope]: sender/recipient identification plus opaque Double Ratchet
 * ciphertext bytes.
 *
 * [ciphertext] is treated as **fully opaque** by this class and by all of
 * `protocol/` — it is Double Ratchet output from `crypto/`, but this module
 * never imports libsignal-client types or attempts to decrypt it. Same
 * opacity rule as [PreKeyBundleEnvelope.bundleBytes]: this preserves ADR
 * 0001's one-way dependency rule without adding a new dependency on
 * libsignal-client specifically.
 *
 * [senderPeerId]/[recipientPeerId] are opaque identifying strings as far as
 * this module is concerned — see [PreKeyBundleEnvelope.peerId]'s doc for the
 * same note.
 */
data class MessageCiphertextEnvelope(
    val senderPeerId: String,
    val recipientPeerId: String,
    val ciphertext: ByteArray,
) {
    /**
     * Encodes as `[4-byte senderPeerId-UTF8-byte-length][senderPeerId UTF-8 bytes]
     * [4-byte recipientPeerId-UTF8-byte-length][recipientPeerId UTF-8 bytes][ciphertext]`,
     * mirroring how [Frame] length-prefixes its own variable-length `payload`
     * field and how [PreKeyBundleEnvelope] length-prefixes `peerId`.
     */
    fun encode(): ByteArray {
        val senderBytes = senderPeerId.toByteArray(StandardCharsets.UTF_8)
        val recipientBytes = recipientPeerId.toByteArray(StandardCharsets.UTF_8)
        val buffer = ByteBuffer.allocate(4 + senderBytes.size + 4 + recipientBytes.size + ciphertext.size)
        buffer.order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(senderBytes.size)
        buffer.put(senderBytes)
        buffer.putInt(recipientBytes.size)
        buffer.put(recipientBytes)
        buffer.put(ciphertext)
        return buffer.array()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MessageCiphertextEnvelope) return false
        return senderPeerId == other.senderPeerId &&
            recipientPeerId == other.recipientPeerId &&
            ciphertext.contentEquals(other.ciphertext)
    }

    override fun hashCode(): Int {
        var result = senderPeerId.hashCode()
        result = 31 * result + recipientPeerId.hashCode()
        result = 31 * result + ciphertext.contentHashCode()
        return result
    }

    companion object {
        /**
         * Decodes [bytes] into a [MessageCiphertextEnvelope].
         *
         * Throws [MessageCiphertextEnvelopeDecodeException] on:
         * - fewer than 4 bytes remaining wherever a length prefix is expected
         *   (truncated `senderPeerId` or `recipientPeerId` length prefix),
         * - a declared peer id byte length longer than the bytes actually
         *   available (truncated `senderPeerId`/`recipientPeerId`).
         *
         * Everything after both peer ids is [ciphertext], with no further
         * length prefix or validation — this module treats it as fully opaque.
         */
        fun decode(bytes: ByteArray): MessageCiphertextEnvelope {
            if (bytes.size < 4) {
                throw MessageCiphertextEnvelopeDecodeException(
                    "Truncated message ciphertext envelope: got ${bytes.size} bytes, need at least 4 for the senderPeerId length prefix"
                )
            }

            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)

            val senderLength = buffer.int.toLong() and 0xFFFFFFFFL
            var remaining = buffer.remaining().toLong()
            if (senderLength > remaining) {
                throw MessageCiphertextEnvelopeDecodeException(
                    "Truncated message ciphertext envelope: declared senderPeerId length=$senderLength but only $remaining bytes remain"
                )
            }
            val senderBytes = ByteArray(senderLength.toInt()).also { buffer.get(it) }

            if (buffer.remaining() < 4) {
                throw MessageCiphertextEnvelopeDecodeException(
                    "Truncated message ciphertext envelope: missing recipientPeerId length prefix"
                )
            }
            val recipientLength = buffer.int.toLong() and 0xFFFFFFFFL
            remaining = buffer.remaining().toLong()
            if (recipientLength > remaining) {
                throw MessageCiphertextEnvelopeDecodeException(
                    "Truncated message ciphertext envelope: declared recipientPeerId length=$recipientLength but only $remaining bytes remain"
                )
            }
            val recipientBytes = ByteArray(recipientLength.toInt()).also { buffer.get(it) }

            val ciphertext = ByteArray(buffer.remaining()).also { buffer.get(it) }

            return MessageCiphertextEnvelope(
                senderPeerId = String(senderBytes, StandardCharsets.UTF_8),
                recipientPeerId = String(recipientBytes, StandardCharsets.UTF_8),
                ciphertext = ciphertext,
            )
        }
    }
}
