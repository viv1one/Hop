package com.hop.protocol

import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

class TierMembershipClaimTest {

    // --- Test plan case 6: construction-time validation ---

    @Test
    fun `throws for reachTier LOCALITY regardless of geohashPrefix`() {
        assertFailsWith<IllegalArgumentException> {
            TierMembershipClaim(reachTier = ReachTier.LOCALITY, geohashPrefix = "", claimedAtMs = 0L)
        }
    }

    @Test
    fun `throws when geohashPrefix length does not match the tier's precision`() {
        // TOWN needs precision 5; give it a 4-character prefix (CITY's precision).
        assertFailsWith<IllegalArgumentException> {
            TierMembershipClaim(reachTier = ReachTier.TOWN, geohashPrefix = "9q8y", claimedAtMs = 0L)
        }
    }

    @Test
    fun `throws when geohashPrefix is longer than the tier's precision (not just shorter)`() {
        // COUNTRY needs precision 2; give it 5 characters (TOWN's precision) --
        // this is the "full-precision point truncated later" anti-pattern the
        // construction-time init block exists specifically to reject.
        assertFailsWith<IllegalArgumentException> {
            TierMembershipClaim(reachTier = ReachTier.COUNTRY, geohashPrefix = "9q8yy", claimedAtMs = 0L)
        }
    }

    @Test
    fun `accepts a correctly-truncated prefix for each non-LOCALITY tier`() {
        TierMembershipClaim(reachTier = ReachTier.TOWN, geohashPrefix = "9q8yy", claimedAtMs = 0L)
        TierMembershipClaim(reachTier = ReachTier.CITY, geohashPrefix = "9q8y", claimedAtMs = 0L)
        TierMembershipClaim(reachTier = ReachTier.COUNTRY, geohashPrefix = "9q", claimedAtMs = 0L)
    }
}
