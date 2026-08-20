package com.hop.protocol

/**
 * A locally-verifiable assertion that its holder was within a given
 * [reachTier]'s geohash cell at [claimedAtMs] -- ADR 0003's own wording: a
 * "geohash-prefix ... assertion," not a high-precision location truncated
 * later at verification time. [geohashPrefix] is truncated to
 * [ReachTierGeohash.precisionFor] for [reachTier] at construction time
 * (enforced by [init] below, which throws otherwise), matching this app's
 * established data-minimization convention (BLE's `neverForLocation`
 * scanning, ADR 0004's "prove the minimum necessary fact, nothing more") --
 * a claim never carries more location precision than the tier check it's
 * for actually needs.
 *
 * No wire encoding yet: a peer presenting this claim to another peer needs a
 * transport, which is a future (crypto-adjacent) slice's job, likely a new
 * `WirePayloadType`. Flagged here so it isn't rediscovered as a surprise
 * later -- data shape and construction-time validation only in this slice.
 */
data class TierMembershipClaim(
    val reachTier: ReachTier,
    val geohashPrefix: String,
    val claimedAtMs: Long,
) {
    init {
        require(reachTier != ReachTier.LOCALITY) { "Locality never needs a tier-membership claim -- ADR 0003" }
        require(geohashPrefix.length == ReachTierGeohash.precisionFor(reachTier)) {
            "geohashPrefix must already be truncated to this tier's precision -- ADR 0003 describes a claim as " +
                "a geohash-prefix assertion, not a high-precision point; construction-time validation matches " +
                "this app's established minimization convention (ADR 0004)"
        }
    }
}
