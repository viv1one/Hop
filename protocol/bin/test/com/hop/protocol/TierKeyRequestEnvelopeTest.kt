package com.hop.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Covers [TierKeyRequestEnvelope]'s own field encoding: fixed-size
 * `contentId`, a `reachTier` byte, length-prefixed `geohashPrefix`, and a
 * fixed `claimedAtMs` tail -- matching [PreKeyBundleEnvelopeTest]/
 * [DontRelayFlagEnvelopeTest]'s round-trip/malformed-input rigor. Also
 * covers the decode-time validation delegated to [TierMembershipClaim]'s own
 * construction-time checks (LOCALITY rejection, geohashPrefix/precision
 * mismatch), which this envelope surfaces as its own decode exception rather
 * than letting [IllegalArgumentException] leak through.
 */
class TierKeyRequestEnvelopeTest {

    private val random = SecureRandom()

    private fun randomBytes(size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }

    private val sanFranciscoLat = 37.7749
    private val sanFranciscoLon = -122.4194

    private fun sampleClaim(tier: ReachTier = ReachTier.TOWN, claimedAtMs: Long = 1_700_000_000_000L): TierMembershipClaim {
        val precision = ReachTierGeohash.precisionFor(tier)
        val prefix = Geohash.encode(sanFranciscoLat, sanFranciscoLon, precision)
        return TierMembershipClaim(reachTier = tier, geohashPrefix = prefix, claimedAtMs = claimedAtMs)
    }

    private fun sampleEnvelope(
        contentId: ByteArray = randomBytes(Frame.CLIP_HASH_SIZE),
        claim: TierMembershipClaim = sampleClaim(),
    ) = TierKeyRequestEnvelope(contentId = contentId, claim = claim)

    // --- Round trip ---

    @Test
    fun `round trip preserves contentId and every claim field`() {
        val original = sampleEnvelope()

        val decoded = TierKeyRequestEnvelope.decode(original.encode())

        assertEquals(original, decoded)
        assertTrue(original.contentId.contentEquals(decoded.contentId))
        assertEquals(original.claim.reachTier, decoded.claim.reachTier)
        assertEquals(original.claim.geohashPrefix, decoded.claim.geohashPrefix)
        assertEquals(original.claim.claimedAtMs, decoded.claim.claimedAtMs)
    }

    @Test
    fun `round trip works for every non-LOCALITY reach tier`() {
        for (tier in listOf(ReachTier.TOWN, ReachTier.CITY, ReachTier.COUNTRY)) {
            val original = sampleEnvelope(claim = sampleClaim(tier = tier))
            val decoded = TierKeyRequestEnvelope.decode(original.encode())
            assertEquals(tier, decoded.claim.reachTier)
            assertEquals(original.claim.geohashPrefix, decoded.claim.geohashPrefix)
        }
    }

    @Test
    fun `encoded size is contentId plus 1 plus 4 plus geohashPrefix byte length plus 8`() {
        val claim = sampleClaim()
        val envelope = sampleEnvelope(claim = claim)
        val expectedSize = Frame.CLIP_HASH_SIZE + 1 + 4 + claim.geohashPrefix.toByteArray(Charsets.UTF_8).size + 8
        assertEquals(expectedSize, envelope.encode().size)
    }

    // --- Constructor-time validation ---

    @Test
    fun `constructing with a wrong-size contentId throws`() {
        assertFailsWith<IllegalArgumentException> {
            TierKeyRequestEnvelope(contentId = randomBytes(Frame.CLIP_HASH_SIZE - 1), claim = sampleClaim())
        }
    }

    // --- Malformed / truncated input rejection ---

    @Test
    fun `decoding empty bytes throws`() {
        assertFailsWith<TierKeyRequestEnvelopeDecodeException> { TierKeyRequestEnvelope.decode(ByteArray(0)) }
    }

    @Test
    fun `decoding bytes shorter than contentId plus reachTier plus length prefix throws`() {
        assertFailsWith<TierKeyRequestEnvelopeDecodeException> {
            TierKeyRequestEnvelope.decode(randomBytes(Frame.CLIP_HASH_SIZE))
        }
    }

    @Test
    fun `decoding an envelope whose declared geohashPrefix length exceeds available bytes throws`() {
        val full = sampleEnvelope().encode()
        // Truncate right after the geohashPrefix length prefix so the declared length can't be satisfied.
        val truncated = full.copyOf(Frame.CLIP_HASH_SIZE + 1 + 4 + 1)

        assertFailsWith<TierKeyRequestEnvelopeDecodeException> { TierKeyRequestEnvelope.decode(truncated) }
    }

    @Test
    fun `decoding an envelope truncated inside claimedAtMs throws`() {
        val full = sampleEnvelope().encode()
        // Drop a few trailing bytes so claimedAtMs (the last 8 bytes) can't be fully read.
        val truncated = full.copyOf(full.size - 3)

        assertFailsWith<TierKeyRequestEnvelopeDecodeException> { TierKeyRequestEnvelope.decode(truncated) }
    }

    @Test
    fun `decoding an unrecognized reachTier byte throws`() {
        val full = sampleEnvelope().encode()
        val mutated = full.copyOf()
        mutated[Frame.CLIP_HASH_SIZE] = 99 // not a defined ReachTier value

        assertFailsWith<TierKeyRequestEnvelopeDecodeException> { TierKeyRequestEnvelope.decode(mutated) }
    }

    @Test
    fun `decoding a LOCALITY reachTier byte throws, since Locality never needs a tier-membership claim`() {
        // Hand-build raw bytes with reachTier=LOCALITY(0) -- sampleEnvelope() can
        // never produce this, since TierMembershipClaim's own constructor already
        // rejects LOCALITY. This proves the envelope decoder surfaces that same
        // rejection as its own decode exception rather than letting an
        // IllegalArgumentException leak through undocumented.
        val contentId = randomBytes(Frame.CLIP_HASH_SIZE)
        val geohashPrefixBytes = "abcde".toByteArray(Charsets.UTF_8) // TOWN-tier-length prefix, irrelevant here
        val buffer = ByteBuffer.allocate(Frame.CLIP_HASH_SIZE + 1 + 4 + geohashPrefixBytes.size + 8)
        buffer.order(ByteOrder.BIG_ENDIAN)
        buffer.put(contentId)
        buffer.put(ReachTier.LOCALITY.wireValue.toByte())
        buffer.putInt(geohashPrefixBytes.size)
        buffer.put(geohashPrefixBytes)
        buffer.putLong(0L)

        assertFailsWith<TierKeyRequestEnvelopeDecodeException> { TierKeyRequestEnvelope.decode(buffer.array()) }
    }

    @Test
    fun `decoding a geohashPrefix whose length doesn't match its reachTier's precision throws`() {
        // TOWN's precision is 5 (ReachTierGeohash.precisionFor); hand-build a
        // request claiming TOWN with a 3-character prefix, which
        // TierMembershipClaim's own constructor rejects.
        val contentId = randomBytes(Frame.CLIP_HASH_SIZE)
        val geohashPrefixBytes = "abc".toByteArray(Charsets.UTF_8)
        val buffer = ByteBuffer.allocate(Frame.CLIP_HASH_SIZE + 1 + 4 + geohashPrefixBytes.size + 8)
        buffer.order(ByteOrder.BIG_ENDIAN)
        buffer.put(contentId)
        buffer.put(ReachTier.TOWN.wireValue.toByte())
        buffer.putInt(geohashPrefixBytes.size)
        buffer.put(geohashPrefixBytes)
        buffer.putLong(0L)

        assertFailsWith<TierKeyRequestEnvelopeDecodeException> { TierKeyRequestEnvelope.decode(buffer.array()) }
    }
}
