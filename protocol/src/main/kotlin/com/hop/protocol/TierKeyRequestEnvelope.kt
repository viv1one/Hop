package com.hop.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Thrown when a byte array cannot be decoded as a valid [TierKeyRequestEnvelope]:
 * truncated `contentId`, truncated `geohashPrefix` length/bytes, a truncated
 * fixed-size tail, or a decoded [TierMembershipClaim] that fails its own
 * construction-time validation (e.g. a `geohashPrefix` whose length doesn't
 * match its claimed tier's precision, or a `reachTier` of `LOCALITY`, which
 * never needs a claim at all). Decoding must fail loudly rather than silently
 * misparse -- see /protocol/WIRE_FORMAT.md.
 */
class TierKeyRequestEnvelopeDecodeException(message: String) : Exception(message)

/**
 * The payload carried inside a [WirePayloadType.TIER_KEY_REQUEST]-typed
 * [WireEnvelope]: one peer presenting a [TierMembershipClaim] to another
 * peer, asking for the decay-key-store-wrapped content-encryption key (CEK)
 * for [contentId] -- ADR 0003's key-distribution half for Town/City/Country
 * reach tiers, transported over the wire per [TierMembershipClaim]'s own doc
 * ("a peer presenting this claim to another peer needs a transport").
 *
 * [contentId] is the same 32-byte content-addressed hash as [Frame.clipHash]
 * -- this envelope names the post whose key is being requested, it doesn't
 * carry the post itself.
 *
 * The receiving peer runs [ReachTierKeyDistribution] against the decoded
 * [claim] and [contentId] to decide whether to answer with a
 * [TierKeyResponseEnvelope] carrying the key, or a denial. This envelope
 * itself makes no decision -- it's a pure wire shape.
 *
 * **Limit (state plainly, per ADR 0003):** [claim] is a locally-verifiable,
 * self-asserted geohash-prefix claim, not a server-verified one -- there is
 * no server to ask. Presenting this envelope raises the cost of casual/
 * scripted out-of-tier key requests against the stock client; it is not
 * equivalent to server-side access control, and a determined custom client
 * can fabricate [claim] outright.
 */
data class TierKeyRequestEnvelope(
    /** 32 bytes, matches [Frame.CLIP_HASH_SIZE]. */
    val contentId: ByteArray,
    val claim: TierMembershipClaim,
) {
    init {
        require(contentId.size == Frame.CLIP_HASH_SIZE) {
            "contentId must be ${Frame.CLIP_HASH_SIZE} bytes, was ${contentId.size}"
        }
    }

    /**
     * Encodes as `[32B contentId][1B reachTier][4B geohashPrefix UTF-8 byte
     * length][geohashPrefix UTF-8 bytes][8B claimedAtMs]`, per
     * /protocol/WIRE_FORMAT.md. `geohashPrefix` is length-prefixed rather
     * than fixed-size even though its length is determined by `reachTier`
     * ([ReachTierGeohash.precisionFor]) -- this keeps the decoder from
     * needing to know that mapping just to find the header boundary, mirroring
     * [PreKeyBundleEnvelope]'s length-prefixed `peerId` convention.
     */
    fun encode(): ByteArray {
        val geohashPrefixBytes = claim.geohashPrefix.toByteArray(Charsets.UTF_8)
        val buffer = ByteBuffer.allocate(Frame.CLIP_HASH_SIZE + 1 + 4 + geohashPrefixBytes.size + 8)
        buffer.order(ByteOrder.BIG_ENDIAN)
        buffer.put(contentId)
        buffer.put(claim.reachTier.wireValue.toByte())
        buffer.putInt(geohashPrefixBytes.size)
        buffer.put(geohashPrefixBytes)
        buffer.putLong(claim.claimedAtMs)
        return buffer.array()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TierKeyRequestEnvelope) return false
        return contentId.contentEquals(other.contentId) && claim == other.claim
    }

    override fun hashCode(): Int {
        var result = contentId.contentHashCode()
        result = 31 * result + claim.hashCode()
        return result
    }

    companion object {
        /** Fixed portion of the header before the variable-length `geohashPrefix`. */
        private const val FIXED_HEAD_SIZE = 1 + 4 // reachTier + geohashPrefix length prefix

        /** Fixed portion of the header after `geohashPrefix`: `claimedAtMs`. */
        private const val FIXED_TAIL_SIZE = 8

        /**
         * Decodes [bytes] into a [TierKeyRequestEnvelope].
         *
         * Throws [TierKeyRequestEnvelopeDecodeException] on:
         * - fewer than [Frame.CLIP_HASH_SIZE] + [FIXED_HEAD_SIZE] bytes (truncated
         *   `contentId`, `reachTier`, or `geohashPrefix` length prefix),
         * - an unrecognized `reachTier` byte,
         * - a declared `geohashPrefix` byte length longer than the bytes
         *   actually available (truncated `geohashPrefix`),
         * - fewer than [FIXED_TAIL_SIZE] bytes remaining after `geohashPrefix`
         *   (truncated `claimedAtMs`),
         * - a decoded [TierMembershipClaim] that fails its own construction-time
         *   validation (wrong `geohashPrefix` length for `reachTier`, or
         *   `reachTier == LOCALITY`) -- see [TierMembershipClaim]'s own doc.
         */
        fun decode(bytes: ByteArray): TierKeyRequestEnvelope {
            if (bytes.size < Frame.CLIP_HASH_SIZE + FIXED_HEAD_SIZE) {
                throw TierKeyRequestEnvelopeDecodeException(
                    "Truncated tier-key request envelope: got ${bytes.size} bytes, need at least " +
                        "${Frame.CLIP_HASH_SIZE + FIXED_HEAD_SIZE} for contentId + reachTier + geohashPrefix length prefix"
                )
            }

            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val contentId = ByteArray(Frame.CLIP_HASH_SIZE).also { buffer.get(it) }

            val reachTierByte = buffer.get().toInt() and 0xFF
            val reachTier = try {
                ReachTier.fromWireValue(reachTierByte)
            } catch (e: FrameDecodeException) {
                throw TierKeyRequestEnvelopeDecodeException(
                    "Invalid reachTier value in tier-key request envelope: $reachTierByte"
                )
            }

            val geohashPrefixLength = buffer.int.toLong() and 0xFFFFFFFFL
            val remainingAfterLength = buffer.remaining().toLong()
            if (geohashPrefixLength > remainingAfterLength) {
                throw TierKeyRequestEnvelopeDecodeException(
                    "Truncated tier-key request envelope: declared geohashPrefix length=$geohashPrefixLength " +
                        "but only $remainingAfterLength bytes remain"
                )
            }
            val geohashPrefixBytes = ByteArray(geohashPrefixLength.toInt()).also { buffer.get(it) }

            if (buffer.remaining() < FIXED_TAIL_SIZE) {
                throw TierKeyRequestEnvelopeDecodeException(
                    "Truncated tier-key request envelope: need $FIXED_TAIL_SIZE more bytes for claimedAtMs, " +
                        "only ${buffer.remaining()} remain"
                )
            }
            val claimedAtMs = buffer.long

            val claim = try {
                TierMembershipClaim(
                    reachTier = reachTier,
                    geohashPrefix = String(geohashPrefixBytes, Charsets.UTF_8),
                    claimedAtMs = claimedAtMs,
                )
            } catch (e: IllegalArgumentException) {
                throw TierKeyRequestEnvelopeDecodeException(
                    "Decoded tier-membership claim failed validation: ${e.message}"
                )
            }

            return TierKeyRequestEnvelope(contentId = contentId, claim = claim)
        }
    }
}
