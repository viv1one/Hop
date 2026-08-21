package com.hop.topics

import com.hop.dht.Contact
import com.hop.dht.DhtNode
import com.hop.dht.FindValueResult
import com.hop.protocol.Geohash
import com.hop.protocol.ReachTier
import com.hop.protocol.ReachTierGeohash
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Wires PRD §4.2/§6's "geohash-prefix precision as DHT topic-subscription
 * key" onto the real Kademlia [DhtNode] built in Slices 2-5, for the three
 * tiers that actually touch the DHT (Town/City/Country -- Locality never
 * does, ADR 0003, and has no method here for that reason).
 *
 * **Why this class lives in its own module (`topics/`), not `dht/`,
 * `protocol/`, or the Android app:** `dht/`'s [com.hop.dht.NodeId] doc and
 * `protocol/`'s `ReachTierGeohash` doc both state the same constraint from
 * their own side -- `dht/` and `protocol/` must never depend on each other,
 * in either direction (unlike `protocol/` -> `crypto/`, which ADR 0001
 * explicitly allows one-way). This class needs BOTH: [DhtNode]/[NodeId]
 * ([GeohashTopicKey]) from `dht/`, and [ReachTierGeohash]/[Geohash] from
 * `protocol/`. Something has to own that bridge, and it can't be either
 * module without breaking their mutual zero-dependency invariant. The
 * existing precedent in this codebase for "glue that needs two modules
 * `protocol/` and `crypto/` can't glue themselves" is `mobile/android/app`'s
 * `com.hop.repository` package (e.g. `PostRepository` importing both
 * `EncryptedFrameCodec` from `protocol/` and `DecayKeyStore` from `crypto/`
 * directly) -- but that precedent is app-layer glue for app-layer concerns
 * (a repository backing a ViewModel). This bridge is different: it's pure
 * DHT-topic-routing logic with no Android/UI dependency at all, and it's
 * exactly the kind of thing a future plain-JVM `tools/relay-node/` process
 * (BUILD_PLAN.md Phase 4) will need too, without wanting to pull in an
 * entire Android application module as a library dependency just to reach
 * two classes. A small standalone module, matching `protocol/`/`crypto/`/
 * `dht/`'s own "plain Kotlin JVM library" shape, is the more honest fit --
 * consumed BY `mobile/android/app` (once this slice is wired into it, a
 * later slice's job) and, eventually, `tools/relay-node/`, never consumed
 * by `protocol/` or `dht/` themselves.
 *
 * **What this class is NOT:** it is topic-subscription *plumbing* --
 * deciding which DHT keys to announce/query so peers can find each other for
 * a given geo cell -- not a content-fetch or access-control mechanism.
 * [DhtNode.store]/[DhtNode.findValue] only ever exchange [Contact]s (who
 * claims to hold something), never the content itself, and per ADR 0003 a
 * geohash-prefix topic key is not secret -- anyone can compute it
 * ([GeohashTopicKey]) and query it. Reach-tier restriction above Locality is
 * enforced separately, by `crypto/`'s per-tier key-wrapping on the actual
 * clip payload -- not built here, and not something this class's behavior
 * should ever be described as providing.
 *
 * **Country-tier fidelity: still open, doubly acknowledged, not solved
 * here either.** `ReachTierGeohash`'s own class-level doc already states
 * this precisely and flags it as this slice's problem to pick up -- restated
 * here rather than silently dropped: geohash precision is a uniform grid,
 * political country boundaries are not, so at COUNTRY tier's precision 2,
 * [ReachTierGeohash.targetCellPrefixes] (target cell + its immediate
 * neighbors) does not give a subscriber in one part of a large country
 * (Russia, Canada, USA, China, Brazil, Australia) real coverage of the whole
 * country -- e.g. `ReachTierGeohashTest`'s own committed test proves Moscow
 * and Vladivostok, both COUNTRY-tier Russia, don't share cell coverage at
 * this precision. [publish]/[browse] below inherit this limit as-is: they
 * announce/query exactly [ReachTierGeohash.targetCellPrefixes]'s cells, no
 * more. A real fix (country-code-keyed topics backed by boundary polygon
 * data, as `ReachTierGeohash`'s doc suggests) is a materially bigger,
 * separate architectural decision -- it needs a geographic boundary dataset
 * this codebase has no dependency on today, a decision about where that data
 * comes from and how it's kept current, and a wire-format change to carry a
 * country code instead of (or alongside) a geohash prefix at this tier. That
 * is genuinely out of scope for wiring topic subscription onto the DHT that
 * now exists -- carried forward, not silently resolved, exactly per this
 * task's own instruction to either solve it or explicitly re-flag it.
 */
class TopicSubscription(private val dhtNode: DhtNode) {

    /**
     * Announces this device as a holder/subscriber for its OWN target cell at
     * [tier]'s precision -- deliberately NOT the neighbor cells too.
     * Publishing to neighbor cells as well would mean announcing presence in
     * places this device isn't actually located, which is both wrong (a
     * peer browsing a neighbor cell would be told to look for content here
     * that was never meant for that cell) and needless amplification (up to
     * 8x more STORE traffic for zero correctness benefit -- neighbor
     * inclusion exists on the BROWSE side, in [browse]/
     * [ReachTierGeohash.targetCellPrefixes], specifically so a browsing
     * device near a cell boundary doesn't need every publisher on the other
     * side to over-publish into cells they're not in).
     *
     * Delegates entirely to [DhtNode.store], which already does both
     * required things (unconditional local self-registration plus a real
     * network STORE to the externally-known-closest peers) -- see that
     * function's own doc.
     */
    suspend fun publish(latitude: Double, longitude: Double, tier: ReachTier) {
        val precision = ReachTierGeohash.precisionFor(tier)
        val ownCell = Geohash.encode(latitude, longitude, precision)
        dhtNode.store(GeohashTopicKey.toNodeId(ownCell))
    }

    /**
     * Queries every cell in [ReachTierGeohash.targetCellPrefixes] for
     * [latitude]/[longitude] at [tier] -- the target cell's own prefix PLUS
     * its neighbor cells, per PRD §6's "resolved against the target cell
     * plus its neighbor cells to avoid boundary-edge misses between adjacent
     * cells" -- and merges every holder found across all of them into one
     * deduplicated list. Queried concurrently (one [DhtNode.findValue] per
     * prefix, raced together via [kotlinx.coroutines.async]/[awaitAll]) since
     * each prefix is an independent DHT lookup with no data dependency on any
     * other -- sequential querying would just add up to 9 lookups' worth of
     * latency (Town/City tier: 1 target + up to 8 neighbors) for no benefit.
     *
     * Deduplication is by [Contact.id] ([distinctBy]) rather than full
     * [Contact] equality: the same device can legitimately be returned as a
     * holder of more than one queried prefix (e.g. it published into a cell
     * that happens to be a shared neighbor of two of this browse's queried
     * prefixes), and callers care about distinct holder identities, not
     * how many of the queried prefixes happened to know about each one.
     *
     * Returns an empty list, never throws or hangs, when nobody holds
     * anything in the target cell or any neighbor cell (mirrors
     * [DhtNode.findValue]'s own "never fabricate a holder" posture).
     */
    suspend fun browse(latitude: Double, longitude: Double, tier: ReachTier): List<Contact> = coroutineScope {
        val prefixes = ReachTierGeohash.targetCellPrefixes(latitude, longitude, tier)

        val outcomes = prefixes
            .map { prefix -> async { dhtNode.findValue(GeohashTopicKey.toNodeId(prefix)) } }
            .awaitAll()

        outcomes
            .filterIsInstance<FindValueResult.Found>()
            .flatMap { it.holders }
            .distinctBy { it.id }
    }
}
