package com.hop.protocol

import com.hop.crypto.DecayKeyStore

/**
 * ADR 0003's key-distribution decision: given an inbound [TierMembershipClaim]
 * (transported over the wire as a [TierKeyRequestEnvelope]) and the [contentId]
 * it's asking about, decide whether to hand back the wrapped content-encryption
 * key (CEK) for a Town/City/Country-tagged post -- or `null`, meaning deny.
 *
 * This is the piece [TierClaimVerifier]'s own doc pointed at ("belongs to a
 * later slice that wires this to actual key distribution") and
 * [EncryptedFrameCodec]'s own doc pointed at ("Town/City/Country's DHT-gated
 * key-distribution... is Phase 4 scope and deliberately not built here").
 * Lives in `protocol/`, not `crypto/`, per ADR 0001's one-way dependency rule:
 * this logic needs [ReachTier] ([Frame.kt], `protocol/`) and
 * [TierMembershipClaim]/[TierClaimVerifier] (`protocol/`), so it cannot live in
 * `crypto/` without creating a `crypto/` -> `protocol/` import, which is never
 * allowed. `protocol/` already depends on `crypto/` (via [EncryptedFrameCodec]),
 * so this file doing the same for [DecayKeyStore] is consistent with the
 * existing, established direction -- not a new dependency shape.
 *
 * ## Per-tier keying, without changing [DecayKeyStore]'s API
 *
 * [DecayKeyStore] is keyed by a single `contentId: String` with one expiry per
 * entry -- it has no notion of [ReachTier] at all, and per this slice's own
 * scoping, it stays that way: [ReachTier] is a `protocol/` type, so teaching
 * `crypto/`'s [DecayKeyStore] about it would be the exact `crypto/` ->
 * `protocol/` dependency ADR 0001 forbids. Instead, per-tier keying is
 * composed entirely in `protocol/` via [decayKeyStorageKey] -- a plain string
 * concatenation (`"$contentId:${tier.wireValue}"`) passed as the ordinary
 * `contentId` argument [DecayKeyStore] already accepts. [DecayKeyStore] itself
 * never needs to know this convention exists; it just sees another string key.
 * Whoever stores a Town/City/Country post's per-tier wrapped keys at publish
 * time MUST use this same composition (call [decayKeyStorageKey] to build the
 * key passed to `DecayKeyStore.store()`), or a legitimate claim will look up
 * the wrong entry and be denied. Locality never calls into this at all --
 * per ADR 0003 and [EncryptedFrameCodec]'s existing Locality-only
 * `keyIncluded = true` path, which is untouched by this file.
 *
 * ## Limits (state plainly, per ADR 0003)
 *
 * This is a deterrent against casual/stock-client scraping, not a
 * cryptographic access-control guarantee:
 * - [TierClaimVerifier.isWithinTier] and the staleness check below both trust
 *   [claim] at face value -- there is no server to verify a claimed location
 *   or timestamp against, so a determined custom client can fabricate either
 *   and this function cannot detect that.
 * - A `null` result here only means *this* function declines to hand back the
 *   key. It says nothing about whether the requester already obtained the key
 *   some other way (e.g. captured it while genuinely in-tier and now replaying
 *   an old claim past its own staleness bound -- see [claimMaxAgeSeconds]).
 * - A non-null result reflects the *stock* client's policy; nothing here binds
 *   what a modified relay/responder does with a request.
 */
object ReachTierKeyDistribution {

    /**
     * Unmeasured placeholder, matching [RelayPolicy.DEFAULT_MAX_HOPS]/
     * [PreKeyBundleEnvelope.DEFAULT_TTL_SECONDS]'s own "not tuned against real
     * data" posture. This bounds how old a presented [TierMembershipClaim] may
     * be before it's treated as stale and rejected -- deliberately short (a
     * few minutes), since a claim asserts "I was in this cell *now*," not
     * "I was in this cell at some point during the post's whole decay window."
     * This is a **distinct lifetime** from the post's own `Frame.ttlSeconds`/
     * decay window (fed to [DecayKeyStore] separately, checked separately
     * below) -- conflating the two would let a claim captured once stay valid
     * for as long as the post itself does, which defeats the point of asking
     * for a fresh claim per request.
     */
    const val DEFAULT_CLAIM_MAX_AGE_SECONDS: Long = 5L * 60

    /**
     * Composes the [DecayKeyStore] storage key for [contentId]'s wrapped CEK
     * under [tier] -- the per-tier keying convention described in this
     * object's class doc. Both the publish-time `DecayKeyStore.store()` call
     * and this file's [releaseKeyFor] lookup MUST use this exact composition
     * to agree on the same entry.
     */
    fun decayKeyStorageKey(contentId: String, tier: ReachTier): String = "$contentId:${tier.wireValue}"

    /**
     * Encapsulates the `LOCALITY` special-case every current call site
     * ([com.hop.app.composer.PostComposerViewModel], `WifiDirectTransport`'s
     * `ReceivedFrameStore`, [EncryptedFrameCodec.decryptFromStore]) otherwise
     * re-implements independently as its own inline
     * `if (tier == LOCALITY) contentId else decayKeyStorageKey(contentId, tier)`
     * branch: `LOCALITY` posts never touch this tiered scheme at all -- their
     * decay key is stored/looked-up under the plain [contentId], unchanged
     * pre-Slice-9 behavior -- while every other tier uses [decayKeyStorageKey]'s
     * ordinary per-tier composition. A future fourth call site that calls
     * [decayKeyStorageKey] directly instead of this function would silently
     * use the wrong storage key for a `LOCALITY` post; calling this function
     * instead makes that mistake structurally impossible.
     *
     * [decayKeyStorageKey] itself is deliberately left unchanged and still
     * directly callable -- this is a pure additive wrapper, not a
     * replacement, since [decayKeyStorageKey]'s own existing contract (a
     * per-tier composition with no `LOCALITY` awareness of its own) is relied
     * on directly by [releaseKeyFor] above, which only ever runs for a
     * Town/City/Country claim (`LOCALITY` never reaches a `TierMembershipClaim`
     * in the first place -- see that class's own `init`).
     */
    fun decayKeyStoreKeyFor(contentId: String, tier: ReachTier): String =
        if (tier == ReachTier.LOCALITY) contentId else decayKeyStorageKey(contentId, tier)

    /**
     * Decides whether to release the wrapped CEK for [contentId] to whoever
     * presented [claim], given the post's known origin location
     * ([targetLatitude]/[targetLongitude] -- the original poster's location
     * for this post, supplied by the caller; this function has no location
     * source of its own).
     *
     * Returns the wrapped CEK bytes on success, `null` on any policy failure
     * (wrong cell, stale claim, or the tier-specific key has already decayed)
     * -- never throws for a rejected claim, matching [DecayKeyStore.retrieve]'s
     * own null-on-expiry-or-missing convention. This function does throw for
     * genuinely malformed input the type system doesn't already prevent, but
     * there is none here: [claim] is a valid [TierMembershipClaim] by
     * construction (its own `init` already rejects `LOCALITY` and mismatched
     * `geohashPrefix` precision) by the time it reaches this function.
     *
     * Checks, all of which must pass:
     * 1. [TierClaimVerifier.isWithinTier] -- [claim]'s geohash prefix falls
     *    within [targetLatitude]/[targetLongitude]'s tier-appropriate cell set
     *    (target cell + neighbors).
     * 2. [claim] itself is not stale: [claim.claimedAtMs] is within
     *    [claimMaxAgeSeconds] of now, reusing [relayPolicy]'s
     *    [RelayPolicy.isExpired] expiry math rather than duplicating it (per
     *    [TierClaimVerifier]'s own instruction) -- note this treats
     *    `claim.claimedAtMs` as an "originatedAtMs" input to that same
     *    boundary arithmetic, not because a claim has hops or relay eligibility
     *    of its own, purely to reuse the arithmetic.
     * 3. The stored key for `(contentId, claim.reachTier)` -- looked up via
     *    [decayKeyStorageKey] -- has not itself decayed
     *    ([DecayKeyStore.retrieve] returns non-null).
     */
    fun releaseKeyFor(
        claim: TierMembershipClaim,
        contentId: String,
        targetLatitude: Double,
        targetLongitude: Double,
        decayKeyStore: DecayKeyStore,
        relayPolicy: RelayPolicy = RelayPolicy(),
        claimMaxAgeSeconds: Long = DEFAULT_CLAIM_MAX_AGE_SECONDS,
    ): ByteArray? {
        if (!TierClaimVerifier.isWithinTier(claim, targetLatitude, targetLongitude)) {
            return null
        }

        val claimIsStale = relayPolicy.isExpired(
            originatedAtMs = claim.claimedAtMs,
            ttlSeconds = claimMaxAgeSeconds,
        )
        if (claimIsStale) {
            return null
        }

        return decayKeyStore.retrieve(decayKeyStorageKey(contentId, claim.reachTier))
    }

    /**
     * Same decision as the lat/lon [releaseKeyFor] overload above, but for a
     * responder that only holds the post's own [Frame.originGeohashPrefix] --
     * the shape [mobile/android/]'s transport layer actually has on hand
     * (see [PostEntity]'s own `originGeohashPrefix` column), since it never
     * has and never needs the post's raw origin latitude/longitude (which
     * never travels on the wire, see [Frame]'s own doc). Delegates to
     * [TierClaimVerifier]'s matching String overload for the cell check;
     * every other check (claim staleness, the tier-specific key's own decay)
     * is identical to the lat/lon overload.
     */
    fun releaseKeyFor(
        claim: TierMembershipClaim,
        contentId: String,
        originGeohashPrefix: String,
        decayKeyStore: DecayKeyStore,
        relayPolicy: RelayPolicy = RelayPolicy(),
        claimMaxAgeSeconds: Long = DEFAULT_CLAIM_MAX_AGE_SECONDS,
    ): ByteArray? {
        if (!TierClaimVerifier.isWithinTier(claim, originGeohashPrefix)) {
            return null
        }

        val claimIsStale = relayPolicy.isExpired(
            originatedAtMs = claim.claimedAtMs,
            ttlSeconds = claimMaxAgeSeconds,
        )
        if (claimIsStale) {
            return null
        }

        return decayKeyStore.retrieve(decayKeyStorageKey(contentId, claim.reachTier))
    }
}
