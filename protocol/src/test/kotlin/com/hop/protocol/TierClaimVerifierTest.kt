package com.hop.protocol

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TierClaimVerifierTest {

    // --- Test plan case 5: claim verification ---

    private val targetLat = 37.7749
    private val targetLon = -122.4194
    private val tier = ReachTier.TOWN
    private val precision = ReachTierGeohash.precisionFor(tier)

    @Test
    fun `a claim equal to the target cell's own prefix passes`() {
        val targetPrefix = Geohash.encode(targetLat, targetLon, precision)
        val claim = TierMembershipClaim(reachTier = tier, geohashPrefix = targetPrefix, claimedAtMs = 0L)
        assertTrue(TierClaimVerifier.isWithinTier(claim, targetLat, targetLon))
    }

    @Test
    fun `a claim equal to one of the target cell's neighbors passes`() {
        val targetPrefix = Geohash.encode(targetLat, targetLon, precision)
        val neighborPrefix = Geohash.neighbors(targetPrefix).first()
        val claim = TierMembershipClaim(reachTier = tier, geohashPrefix = neighborPrefix, claimedAtMs = 0L)
        assertTrue(TierClaimVerifier.isWithinTier(claim, targetLat, targetLon))
    }

    @Test
    fun `a claim two cells outside the target-plus-neighbor set fails`() {
        // Far enough away (roughly on the other side of the US) that at
        // TOWN-tier (~4.9km cells) it cannot possibly be the target cell or
        // one of its 8 immediate neighbors.
        val farAwayLat = 40.7128 // New York
        val farAwayLon = -74.0060
        val farAwayPrefix = Geohash.encode(farAwayLat, farAwayLon, precision)

        val claim = TierMembershipClaim(reachTier = tier, geohashPrefix = farAwayPrefix, claimedAtMs = 0L)
        assertFalse(TierClaimVerifier.isWithinTier(claim, targetLat, targetLon))
    }
}
