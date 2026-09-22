package com.hop.protocol

import com.hop.crypto.ContentEncryption
import com.hop.crypto.DecayKeyStore
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

/**
 * A [Clock] whose instant can be advanced under test control, so claim
 * staleness and decay-window expiry can be tested deterministically instead
 * of via a real sleep. Same shape as `crypto/`'s `DecayKeyStoreTest.MutableClock`
 * and [EncryptedFrameCodecTest]'s own `MutableClock` -- named differently here
 * only because Kotlin top-level classes collide by simple name at the JVM
 * class-file level within a package even when file-`private`, so a second
 * `MutableClock` in this same module/package is a real redeclaration error,
 * not just a source-visibility question.
 */
private class AdvanceableTestClock(private var current: Instant) : Clock() {
    override fun getZone(): java.time.ZoneId = ZoneOffset.UTC
    override fun withZone(zone: java.time.ZoneId?): Clock = this
    override fun instant(): Instant = current
    fun advanceBy(duration: Duration) {
        current = current.plus(duration)
    }
}

/**
 * Covers [ReachTierKeyDistribution.releaseKeyFor] -- ADR 0003's
 * key-distribution decision. Per the hop-dev skill's explicit instruction for
 * decay/reach-tier code, this prioritizes the negative cases (wrong cell,
 * stale claim, decayed key) over the happy path, and proves the positive
 * case actually yields a usable key (round-trips through
 * [ContentEncryption]), not just a non-null return value.
 */
class ReachTierKeyDistributionTest {

    private val targetLat = 37.7749 // San Francisco
    private val targetLon = -122.4194
    private val tier = ReachTier.TOWN
    private val precision = ReachTierGeohash.precisionFor(tier)
    private val contentId = "clip-abc123"

    private fun inTierPrefix(): String = Geohash.encode(targetLat, targetLon, precision)

    private fun freshClaim(claimedAtMs: Long, geohashPrefix: String = inTierPrefix(), reachTier: ReachTier = tier): TierMembershipClaim =
        TierMembershipClaim(reachTier = reachTier, geohashPrefix = geohashPrefix, claimedAtMs = claimedAtMs)

    // --- Positive case ---

    @Test
    fun `a valid, fresh, in-tier claim gets back the correct wrapped CEK, decryptable by ContentEncryption`() {
        val clock = AdvanceableTestClock(Instant.parse("2026-01-01T00:00:00Z"))
        val decayKeyStore = DecayKeyStore(clock)
        val relayPolicy = RelayPolicy(clock = clock)

        val cek = ContentEncryption.generateKey()
        val plaintext = "a town-tier post's plaintext".toByteArray()
        val ciphertext = ContentEncryption.encrypt(cek, plaintext)

        decayKeyStore.store(
            ReachTierKeyDistribution.decayKeyStorageKey(contentId, tier),
            cek.encoded,
            Duration.ofHours(1),
        )

        val claim = freshClaim(claimedAtMs = clock.instant().toEpochMilli())

        val wrappedCek = ReachTierKeyDistribution.releaseKeyFor(
            claim = claim,
            contentId = contentId,
            targetLatitude = targetLat,
            targetLongitude = targetLon,
            decayKeyStore = decayKeyStore,
            relayPolicy = relayPolicy,
        )

        assertNotNull(wrappedCek)
        assertContentEquals(cek.encoded, wrappedCek)
        val decrypted = ContentEncryption.decrypt(ContentEncryption.keyFromBytes(wrappedCek), ciphertext)
        assertContentEquals(plaintext, decrypted)
    }

    // --- Negative case: wrong cell ---

    @Test
    fun `a claim for the wrong geohash cell is denied even though the key is live and fresh`() {
        val clock = AdvanceableTestClock(Instant.parse("2026-01-01T00:00:00Z"))
        val decayKeyStore = DecayKeyStore(clock)
        val relayPolicy = RelayPolicy(clock = clock)

        decayKeyStore.store(
            ReachTierKeyDistribution.decayKeyStorageKey(contentId, tier),
            ContentEncryption.generateKey().encoded,
            Duration.ofHours(1),
        )

        // New York, far enough from San Francisco that at TOWN precision it
        // cannot be the target cell or one of its 8 immediate neighbors.
        val farAwayPrefix = Geohash.encode(40.7128, -74.0060, precision)
        val claim = freshClaim(claimedAtMs = clock.instant().toEpochMilli(), geohashPrefix = farAwayPrefix)

        val result = ReachTierKeyDistribution.releaseKeyFor(
            claim = claim,
            contentId = contentId,
            targetLatitude = targetLat,
            targetLongitude = targetLon,
            decayKeyStore = decayKeyStore,
            relayPolicy = relayPolicy,
        )
        assertNull(result)
    }

    // --- Negative case: stale claim ---

    @Test
    fun `a claim older than the claim-staleness bound is denied even though the cell matches and the key is live`() {
        val clock = AdvanceableTestClock(Instant.parse("2026-01-01T00:00:00Z"))
        val decayKeyStore = DecayKeyStore(clock)
        val relayPolicy = RelayPolicy(clock = clock)

        decayKeyStore.store(
            ReachTierKeyDistribution.decayKeyStorageKey(contentId, tier),
            ContentEncryption.generateKey().encoded,
            Duration.ofHours(1),
        )

        val claim = freshClaim(claimedAtMs = clock.instant().toEpochMilli())

        // Advance past the default claim-staleness bound -- the claim itself
        // is now too old to trust, independent of the post's own decay window.
        clock.advanceBy(Duration.ofSeconds(ReachTierKeyDistribution.DEFAULT_CLAIM_MAX_AGE_SECONDS + 1))

        val result = ReachTierKeyDistribution.releaseKeyFor(
            claim = claim,
            contentId = contentId,
            targetLatitude = targetLat,
            targetLongitude = targetLon,
            decayKeyStore = decayKeyStore,
            relayPolicy = relayPolicy,
        )
        assertNull(result)
    }

    @Test
    fun `a claim exactly at the staleness boundary is denied, matching RelayPolicy's own not-isBefore convention`() {
        val clock = AdvanceableTestClock(Instant.parse("2026-01-01T00:00:00Z"))
        val decayKeyStore = DecayKeyStore(clock)
        val relayPolicy = RelayPolicy(clock = clock)

        decayKeyStore.store(
            ReachTierKeyDistribution.decayKeyStorageKey(contentId, tier),
            ContentEncryption.generateKey().encoded,
            Duration.ofHours(1),
        )

        val claim = freshClaim(claimedAtMs = clock.instant().toEpochMilli())
        clock.advanceBy(Duration.ofSeconds(ReachTierKeyDistribution.DEFAULT_CLAIM_MAX_AGE_SECONDS))

        val result = ReachTierKeyDistribution.releaseKeyFor(
            claim = claim,
            contentId = contentId,
            targetLatitude = targetLat,
            targetLongitude = targetLon,
            decayKeyStore = decayKeyStore,
            relayPolicy = relayPolicy,
        )
        assertNull(result)
    }

    // --- Negative case: the stored key has already decayed ---

    @Test
    fun `a fresh, in-tier claim presented after the stored key's own decay window has closed is denied`() {
        val clock = AdvanceableTestClock(Instant.parse("2026-01-01T00:00:00Z"))
        val decayKeyStore = DecayKeyStore(clock)
        val relayPolicy = RelayPolicy(clock = clock)

        decayKeyStore.store(
            ReachTierKeyDistribution.decayKeyStorageKey(contentId, tier),
            ContentEncryption.generateKey().encoded,
            Duration.ofMinutes(1),
        )

        // Let the stored key's own decay window elapse (independent of claim
        // staleness -- the claim below is presented fresh, right now).
        clock.advanceBy(Duration.ofMinutes(2))

        val claim = freshClaim(claimedAtMs = clock.instant().toEpochMilli())

        val result = ReachTierKeyDistribution.releaseKeyFor(
            claim = claim,
            contentId = contentId,
            targetLatitude = targetLat,
            targetLongitude = targetLon,
            decayKeyStore = decayKeyStore,
            relayPolicy = relayPolicy,
        )
        assertNull(result)
    }

    // --- Negative case: claim's tier doesn't match the tier the key was stored under ---

    @Test
    fun `a claim for a different tier than the one the key was stored under is denied`() {
        val clock = AdvanceableTestClock(Instant.parse("2026-01-01T00:00:00Z"))
        val decayKeyStore = DecayKeyStore(clock)
        val relayPolicy = RelayPolicy(clock = clock)

        // Key stored under TOWN only.
        decayKeyStore.store(
            ReachTierKeyDistribution.decayKeyStorageKey(contentId, ReachTier.TOWN),
            ContentEncryption.generateKey().encoded,
            Duration.ofHours(1),
        )

        // Claim presents CITY for the same physical area.
        val cityPrecision = ReachTierGeohash.precisionFor(ReachTier.CITY)
        val cityPrefix = Geohash.encode(targetLat, targetLon, cityPrecision)
        val claim = freshClaim(claimedAtMs = clock.instant().toEpochMilli(), geohashPrefix = cityPrefix, reachTier = ReachTier.CITY)

        val result = ReachTierKeyDistribution.releaseKeyFor(
            claim = claim,
            contentId = contentId,
            targetLatitude = targetLat,
            targetLongitude = targetLon,
            decayKeyStore = decayKeyStore,
            relayPolicy = relayPolicy,
        )
        assertNull(result)
    }

    // --- Negative case: no key was ever stored for this contentId/tier at all ---

    @Test
    fun `a claim for a contentId with no stored key at all is denied`() {
        val clock = AdvanceableTestClock(Instant.parse("2026-01-01T00:00:00Z"))
        val decayKeyStore = DecayKeyStore(clock)
        val relayPolicy = RelayPolicy(clock = clock)

        val claim = freshClaim(claimedAtMs = clock.instant().toEpochMilli())

        val result = ReachTierKeyDistribution.releaseKeyFor(
            claim = claim,
            contentId = "never-published",
            targetLatitude = targetLat,
            targetLongitude = targetLon,
            decayKeyStore = decayKeyStore,
            relayPolicy = relayPolicy,
        )
        assertNull(result)
    }

    // --- decayKeyStorageKey composition ---

    @Test
    fun `decayKeyStorageKey composes contentId and the tier's wireValue`() {
        assertContentEquals(
            "clip-1:1".toByteArray(),
            ReachTierKeyDistribution.decayKeyStorageKey("clip-1", ReachTier.TOWN).toByteArray(),
        )
        assertContentEquals(
            "clip-1:2".toByteArray(),
            ReachTierKeyDistribution.decayKeyStorageKey("clip-1", ReachTier.CITY).toByteArray(),
        )
        assertContentEquals(
            "clip-1:3".toByteArray(),
            ReachTierKeyDistribution.decayKeyStorageKey("clip-1", ReachTier.COUNTRY).toByteArray(),
        )
    }

    // --- decayKeyStoreKeyFor: encapsulates the LOCALITY special-case ---

    @Test
    fun `decayKeyStoreKeyFor returns the plain contentId unchanged for LOCALITY`() {
        assertEquals("clip-1", ReachTierKeyDistribution.decayKeyStoreKeyFor("clip-1", ReachTier.LOCALITY))
    }

    @Test
    fun `decayKeyStoreKeyFor matches decayKeyStorageKey's own output exactly for every non-LOCALITY tier`() {
        for (nonLocalityTier in listOf(ReachTier.TOWN, ReachTier.CITY, ReachTier.COUNTRY)) {
            assertEquals(
                ReachTierKeyDistribution.decayKeyStorageKey("clip-1", nonLocalityTier),
                ReachTierKeyDistribution.decayKeyStoreKeyFor("clip-1", nonLocalityTier),
                "decayKeyStoreKeyFor must match decayKeyStorageKey exactly for $nonLocalityTier",
            )
        }
    }
}
