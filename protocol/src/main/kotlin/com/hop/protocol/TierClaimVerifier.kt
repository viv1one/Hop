package com.hop.protocol

/**
 * Locally-verifiable check of whether a [TierMembershipClaim] places its
 * claimant within a target location's tier-appropriate geohash cell set.
 *
 * Per ADR 0003: this is a locally-verifiable geohash-prefix assertion, not a
 * server-verified one -- no server exists to ask. It raises the cost of
 * casual/scripted out-of-tier scraping for the stock client; it is **not**
 * equivalent to server-side access control -- a determined custom client can
 * fabricate a location claim outright, and nothing here can detect that.
 */
object TierClaimVerifier {

    /**
     * Whether [claim]'s geohash prefix (already truncated to its tier's
     * precision -- see [TierMembershipClaim]'s construction-time
     * validation) falls within the target location's tier-appropriate cell
     * set (target cell + neighbors; see [ReachTierGeohash.targetCellPrefixes]).
     *
     * Explicitly out of scope here: timestamp/staleness (decay-window)
     * enforcement. [TierMembershipClaim.claimedAtMs] is carried on the claim
     * but not consulted by this function -- decay-window expiry is ADR
     * 0003's separate job, belongs to a later slice that wires this to
     * actual key distribution, and should reuse [RelayPolicy]-style expiry
     * math once it exists rather than duplicating that logic here.
     */
    fun isWithinTier(claim: TierMembershipClaim, targetLatitude: Double, targetLongitude: Double): Boolean =
        claim.geohashPrefix in ReachTierGeohash.targetCellPrefixes(targetLatitude, targetLongitude, claim.reachTier)
}
