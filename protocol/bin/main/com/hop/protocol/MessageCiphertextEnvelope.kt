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
 * [WireEnvelope]: sender/recipient identification, relay/decay metadata, and
 * opaque Double Ratchet ciphertext bytes.
 *
 * [hopCount]/[originatedAtMs] were added in Phase 2 Slice 3 (store-and-forward
 * for offline 1:1 recipients) — a breaking change to this envelope's shape,
 * accepted under the same "no real users yet" posture that justified
 * [WireEnvelope]'s own introduction as a breaking change to the socket
 * framing (see /protocol/WIRE_FORMAT.md). They exist for exactly one reason:
 * feeding [RelayPolicy.isEligibleForRelay]/[RelayPolicy.isExpired] the same
 * eligibility/expiry inputs posts' own `Frame.hopCount`/`Frame.originatedAtMs`
 * already provide, so a message relay-custody row can be evaluated with the
 * same policy object, unmodified. [DEFAULT_TTL_SECONDS] is the TTL these are
 * checked against — see its own doc for why it isn't a wire field.
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
    /**
     * Number of relay hops so far, mirroring `Frame.hopCount`'s own
     * semantics/range exactly: `0` at the sender, incremented by one on each
     * carrier's outgoing re-offer (see
     * `com.hop.repository.PendingMessageRepository.buildOutgoingBacklog`).
     * Fits a uint8 on the wire (0..255), same bound [Frame.hopCount] enforces.
     */
    val hopCount: Int,
    /** Epoch millis this message was originally sent, mirroring `Frame.originatedAtMs`'s own semantics. Input to [RelayPolicy.isExpired]/[RelayPolicy.expiresAtMs] against [DEFAULT_TTL_SECONDS]. */
    val originatedAtMs: Long,
    val ciphertext: ByteArray,
) {
    init {
        require(hopCount in 0..0xFF) { "hopCount must fit in a uint8 (0..255), was $hopCount" }
    }

    /**
     * Encodes as `[4-byte senderPeerId-UTF8-byte-length][senderPeerId UTF-8 bytes]
     * [4-byte recipientPeerId-UTF8-byte-length][recipientPeerId UTF-8 bytes]
     * [1-byte hopCount][8-byte originatedAtMs][ciphertext]`, mirroring how
     * [Frame] length-prefixes its own variable-length `payload` field and how
     * [PreKeyBundleEnvelope] length-prefixes `peerId`.
     */
    fun encode(): ByteArray {
        val senderBytes = senderPeerId.toByteArray(StandardCharsets.UTF_8)
        val recipientBytes = recipientPeerId.toByteArray(StandardCharsets.UTF_8)
        val buffer = ByteBuffer.allocate(
            4 + senderBytes.size + 4 + recipientBytes.size + 1 + 8 + ciphertext.size,
        )
        buffer.order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(senderBytes.size)
        buffer.put(senderBytes)
        buffer.putInt(recipientBytes.size)
        buffer.put(recipientBytes)
        buffer.put(hopCount.toByte())
        buffer.putLong(originatedAtMs)
        buffer.put(ciphertext)
        return buffer.array()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MessageCiphertextEnvelope) return false
        return senderPeerId == other.senderPeerId &&
            recipientPeerId == other.recipientPeerId &&
            hopCount == other.hopCount &&
            originatedAtMs == other.originatedAtMs &&
            ciphertext.contentEquals(other.ciphertext)
    }

    override fun hashCode(): Int {
        var result = senderPeerId.hashCode()
        result = 31 * result + recipientPeerId.hashCode()
        result = 31 * result + hopCount
        result = 31 * result + originatedAtMs.hashCode()
        result = 31 * result + ciphertext.contentHashCode()
        return result
    }

    companion object {
        /**
         * Unmeasured placeholder, matching [RelayPolicy.DEFAULT_MAX_HOPS]'s own
         * "not tuned against real data" posture -- not tuned against real
         * mesh-latency/store-and-forward-duration data. Deliberately **not**
         * carried on the wire, unlike posts' reach-tier-driven `Frame.ttlSeconds`:
         * no per-message configurability is needed, so every implementation
         * (this reference client, and any independent relay-node
         * implementation, per ADR 0001/0002) must independently agree on this
         * value, same posture as [RelayPolicy.DEFAULT_MAX_HOPS] being an
         * assumption baked into each implementation rather than negotiated.
         * Deliberately shorter than posts' typical TTL placeholder
         * (`PostComposerViewModel.PLACEHOLDER_DECAY_WINDOW_SECONDS`, 24h):
         * messages are more sensitive to lingering carrier-side metadata
         * exposure than posts are (see `com.hop.repository.PendingMessageRepository`'s
         * own "Limits" doc for why).
         */
        const val DEFAULT_TTL_SECONDS: Long = 4L * 60 * 60

        /**
         * Decodes [bytes] into a [MessageCiphertextEnvelope].
         *
         * Throws [MessageCiphertextEnvelopeDecodeException] on:
         * - fewer than 4 bytes remaining wherever a length prefix is expected
         *   (truncated `senderPeerId` or `recipientPeerId` length prefix),
         * - a declared peer id byte length longer than the bytes actually
         *   available (truncated `senderPeerId`/`recipientPeerId`),
         * - fewer than 9 bytes remaining after `recipientPeerId` (truncated
         *   `hopCount`/`originatedAtMs`).
         *
         * Everything after `hopCount`/`originatedAtMs` is [ciphertext], with no
         * further length prefix or validation — this module treats it as fully
         * opaque.
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

            if (buffer.remaining() < 9) {
                throw MessageCiphertextEnvelopeDecodeException(
                    "Truncated message ciphertext envelope: only ${buffer.remaining()} bytes remain, need at least 9 for hopCount+originatedAtMs"
                )
            }
            val hopCount = buffer.get().toInt() and 0xFF
            val originatedAtMs = buffer.long

            val ciphertext = ByteArray(buffer.remaining()).also { buffer.get(it) }

            return MessageCiphertextEnvelope(
                senderPeerId = String(senderBytes, StandardCharsets.UTF_8),
                recipientPeerId = String(recipientBytes, StandardCharsets.UTF_8),
                hopCount = hopCount,
                originatedAtMs = originatedAtMs,
                ciphertext = ciphertext,
            )
        }
    }
}
