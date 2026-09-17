package com.hop.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Thrown when a byte array cannot be decoded as a valid [TierKeyResponseEnvelope]:
 * truncated `contentId`, truncated `granted`/`wrappedCek` length, a truncated
 * `wrappedCek` payload, or a decoded combination where `granted = false` but
 * `wrappedCek` is non-empty (see this class's own doc for why that's rejected
 * rather than silently ignored). Decoding must fail loudly rather than
 * silently misparse -- see /protocol/WIRE_FORMAT.md.
 */
class TierKeyResponseEnvelopeDecodeException(message: String) : Exception(message)

/**
 * The payload carried inside a [WirePayloadType.TIER_KEY_RESPONSE]-typed
 * [WireEnvelope]: the answer to a [TierKeyRequestEnvelope], as decided by
 * [ReachTierKeyDistribution] -- either the wrapped content-encryption key
 * (CEK) for [contentId], or an explicit denial.
 *
 * [granted] is an explicit flag, not inferred from `wrappedCek`'s length --
 * per this slice's own instruction, encoding "denied" as a zero-length key
 * would be ambiguous with a hypothetical future zero-length wrapped-key
 * encoding, and every other boolean-ish field on this wire (`Frame.dontRelay`,
 * `Frame.keyIncluded`) already uses an explicit flag byte rather than
 * inferring state from an adjacent field's shape. [wrappedCek] must be empty
 * when [granted] is `false` -- a denial must never carry key bytes, even
 * stale/leftover ones, so a caller can never accidentally use `wrappedCek`
 * without checking `granted` first.
 *
 * [wrappedCek] is length-prefixed rather than fixed-size like
 * [Frame.contentEncryptionKey] -- today's [com.hop.crypto.ContentEncryption]
 * CEK happens to be a fixed 32 raw AES-256 key bytes, but ADR 0003 describes
 * the key as "wrapped separately per reach tier... and per decay window,"
 * which implies a real wrap operation (e.g. key-wrap overhead, or envelope
 * encryption under a recipient key) is still to come. Length-prefixing now
 * avoids a future wire-format bump once that wrap scheme is real, the same
 * bet [DontRelayFlagEnvelope.attestedDeviceKey] makes for the same reason.
 *
 * **Limit (state plainly, per ADR 0003):** a `granted = true` response is a
 * deterrent-backed decision by the responding peer's [ReachTierKeyDistribution]
 * (a valid tier claim within its own staleness bound, and the tier-specific
 * key hasn't decayed) -- it is not a cryptographic access-control guarantee,
 * and a modified responding client could grant to anyone regardless of this
 * envelope's own shape. This envelope only carries the decision; it doesn't
 * enforce anything about how the decision was made.
 */
data class TierKeyResponseEnvelope(
    /** 32 bytes, matches [Frame.CLIP_HASH_SIZE]. Echoes the request's `contentId`. */
    val contentId: ByteArray,
    val granted: Boolean,
    /** Must be empty when [granted] is `false` -- see class doc. */
    val wrappedCek: ByteArray,
) {
    init {
        require(contentId.size == Frame.CLIP_HASH_SIZE) {
            "contentId must be ${Frame.CLIP_HASH_SIZE} bytes, was ${contentId.size}"
        }
        require(granted || wrappedCek.isEmpty()) {
            "wrappedCek must be empty when granted=false -- a denial must never carry key bytes"
        }
    }

    /**
     * Encodes as `[32B contentId][1B granted][4B wrappedCek byte
     * length][wrappedCek bytes]`, per /protocol/WIRE_FORMAT.md.
     */
    fun encode(): ByteArray {
        val buffer = ByteBuffer.allocate(Frame.CLIP_HASH_SIZE + 1 + 4 + wrappedCek.size)
        buffer.order(ByteOrder.BIG_ENDIAN)
        buffer.put(contentId)
        buffer.put(if (granted) 1.toByte() else 0.toByte())
        buffer.putInt(wrappedCek.size)
        buffer.put(wrappedCek)
        return buffer.array()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TierKeyResponseEnvelope) return false
        return contentId.contentEquals(other.contentId) &&
            granted == other.granted &&
            wrappedCek.contentEquals(other.wrappedCek)
    }

    override fun hashCode(): Int {
        var result = contentId.contentHashCode()
        result = 31 * result + granted.hashCode()
        result = 31 * result + wrappedCek.contentHashCode()
        return result
    }

    companion object {
        /** Fixed portion of the header: `granted` flag + `wrappedCek` length prefix. */
        private const val FIXED_HEAD_SIZE = 1 + 4

        /** Convenience: a granted response carrying [wrappedCek] for [contentId]. */
        fun granted(contentId: ByteArray, wrappedCek: ByteArray): TierKeyResponseEnvelope =
            TierKeyResponseEnvelope(contentId = contentId, granted = true, wrappedCek = wrappedCek)

        /** Convenience: a denial for [contentId], carrying no key material. */
        fun denied(contentId: ByteArray): TierKeyResponseEnvelope =
            TierKeyResponseEnvelope(contentId = contentId, granted = false, wrappedCek = ByteArray(0))

        /**
         * Decodes [bytes] into a [TierKeyResponseEnvelope].
         *
         * Throws [TierKeyResponseEnvelopeDecodeException] on:
         * - fewer than [Frame.CLIP_HASH_SIZE] + [FIXED_HEAD_SIZE] bytes (truncated
         *   `contentId`, `granted`, or `wrappedCek` length prefix),
         * - an invalid `granted` byte (expected 0 or 1),
         * - a declared `wrappedCek` byte length longer than the bytes actually
         *   available (truncated `wrappedCek`),
         * - a decoded combination of `granted = false` with a non-empty
         *   `wrappedCek` (see class doc -- this is rejected rather than silently
         *   accepted with the key bytes dropped, since that could hide a
         *   confused/hostile sender's bug).
         */
        fun decode(bytes: ByteArray): TierKeyResponseEnvelope {
            if (bytes.size < Frame.CLIP_HASH_SIZE + FIXED_HEAD_SIZE) {
                throw TierKeyResponseEnvelopeDecodeException(
                    "Truncated tier-key response envelope: got ${bytes.size} bytes, need at least " +
                        "${Frame.CLIP_HASH_SIZE + FIXED_HEAD_SIZE} for contentId + granted + wrappedCek length prefix"
                )
            }

            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val contentId = ByteArray(Frame.CLIP_HASH_SIZE).also { buffer.get(it) }

            val grantedByte = buffer.get().toInt() and 0xFF
            val granted = when (grantedByte) {
                0 -> false
                1 -> true
                else -> throw TierKeyResponseEnvelopeDecodeException(
                    "Invalid granted byte: $grantedByte (expected 0 or 1)"
                )
            }

            val wrappedCekLength = buffer.int.toLong() and 0xFFFFFFFFL
            val remainingAfterLength = buffer.remaining().toLong()
            if (wrappedCekLength > remainingAfterLength) {
                throw TierKeyResponseEnvelopeDecodeException(
                    "Truncated tier-key response envelope: declared wrappedCek length=$wrappedCekLength " +
                        "but only $remainingAfterLength bytes remain"
                )
            }
            val wrappedCek = ByteArray(wrappedCekLength.toInt()).also { buffer.get(it) }

            if (!granted && wrappedCek.isNotEmpty()) {
                throw TierKeyResponseEnvelopeDecodeException(
                    "Malformed tier-key response: granted=false but wrappedCek is non-empty (${wrappedCek.size} bytes)"
                )
            }

            return TierKeyResponseEnvelope(contentId = contentId, granted = granted, wrappedCek = wrappedCek)
        }
    }
}
