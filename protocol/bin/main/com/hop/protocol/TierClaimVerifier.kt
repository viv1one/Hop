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
     * but not consulted by this function -- decay-window/claim-staleness
     * expiry is checked by [ReachTierKeyDistribution.releaseKeyFor], which
     * calls this function for the geohash-cell check and separately reuses
     * [RelayPolicy]'s expiry math for staleness, rather than duplicating
     * either kind of check here.
     */
    fun isWithinTier(claim: TierMembershipClaim, targetLatitude: Double, targetLongitude: Double): Boolean =
        claim.geohashPrefix in ReachTierGeohash.targetCellPrefixes(targetLatitude, targetLongitude, claim.reachTier)

    /**
     * Same check as the lat/lon overload above, but for a responder that only
     * holds the post's own [Frame.originGeohashPrefix] (a peer relaying/
     * storing someone else's post never has, and never needs, that post's
     * raw origin coordinates -- see [ReachTierGeohash.targetCellPrefixes]'s
     * String overload). [targetGeohashPrefix] is trusted as already being at
     * [claim]'s tier's own precision, matching [Frame.originGeohashPrefix]'s
     * own documented invariant; this function does not itself validate that
     * the two precisions agree, so callers responsible for that (Frame
     * carries `reachTier` alongside `originGeohashPrefix` for exactly this
     * reason) should confirm `claim.reachTier` matches the post's own
     * `reachTier` before calling this.
     */
    fun isWithinTier(claim: TierMembershipClaim, targetGeohashPrefix: String): Boolean =
        claim.geohashPrefix in ReachTierGeohash.targetCellPrefixes(targetGeohashPrefix)
}
