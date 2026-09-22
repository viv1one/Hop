package com.hop.topics

import com.hop.dht.NodeId

/**
 * The ONE place in the codebase that says "a geohash-prefix string is a DHT
 * key computed this way." `dht/`'s [NodeId.fromKeyMaterial] is deliberately
 * domain-agnostic (see its own doc) -- it knows nothing about geohashes.
 * `protocol/`'s `Geohash`/`ReachTierGeohash` know nothing about `dht/` (zero
 * dependency in either direction, by design -- see this module's
 * `TopicSubscription.kt` doc for why the bridge lives here instead). This
 * object is that bridge's key-derivation half: it owns the one decision that
 * has to exist somewhere -- "this exact geohash-prefix string maps to this
 * exact [NodeId]" -- so every peer resolving the same geohash cell computes
 * the identical DHT key.
 *
 * Per ADR 0003 and `ReachTierGeohash`'s own doc: a geohash-prefix topic key
 * is NOT secret -- any client, honest or not, can compute this exact same
 * [NodeId] for any prefix string it chooses and query for it. This function
 * is topic-*routing* plumbing (who to ask), not an access-control mechanism;
 * reach-tier restriction above Locality is enforced separately, by
 * `crypto/`'s per-tier key-wrapping (ADR 0003), not by keeping this mapping
 * or its inputs secret.
 */
object GeohashTopicKey {

    /**
     * Maps a single geohash-prefix string (e.g. one entry of
     * `ReachTierGeohash.targetCellPrefixes`) to the [NodeId] used as its DHT
     * topic-subscription key. Two different prefix strings (even ones that
     * differ only in tier precision -- a Town-tier prefix and the City-tier
     * prefix it's nested under) hash to unrelated [NodeId]s, since
     * [NodeId.fromKeyMaterial] operates on the raw string bytes with no
     * tier-aware structure -- deliberate: this codebase has no requirement
     * that a City-tier subscriber overlap topic-space with a Town-tier
     * subscriber inside the same physical cell, and inventing that
     * relationship here would be scope creep past what this slice needs.
     */
    fun toNodeId(geohashPrefix: String): NodeId = NodeId.fromKeyMaterial(geohashPrefix)
}
