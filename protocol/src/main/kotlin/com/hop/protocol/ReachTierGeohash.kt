package com.hop.protocol

/**
 * The single tier-to-geohash-precision source of truth. Per the hop-dev
 * skill's own instruction, this mapping lives in exactly one place in
 * `protocol/` -- not duplicated per platform.
 *
 * Precision choices are a uniform-grid approximation of PRD §4.2's named
 * tiers, not a geographic/political boundary lookup:
 *  - TOWN = 5 (~4.9km x 4.9km cells)
 *  - CITY = 4 (~39km x 19.5km cells)
 *  - COUNTRY = 2 (~1,252km x 624km cells)
 * LOCALITY has no entry -- it never touches the DHT (ADR 0003) and resolves
 * entirely offline via BLE/WiFi Direct.
 *
 * **Country-tier fidelity limit -- permanent, acknowledged, not fixed by
 * picking a different precision number.** Geohash precision is a uniform
 * grid; political country boundaries are not. Precision 2 is coarse enough
 * that large countries (Russia, Canada, USA, China, Brazil, Australia) span
 * multiple cells even with neighbor expansion, so target-cell-plus-neighbors
 * alone does not give a Country-tier subscriber in one part of such a
 * country coverage of the whole country. Conversely, small/medium countries
 * routinely share a cell with their immediate neighbors at this precision.
 * This is a structural mismatch between a uniform grid and irregular
 * political shapes, not a bug in this mapping -- a real fix (country-code-
 * keyed topics backed by boundary polygon data) is a materially bigger,
 * separate architectural decision, out of scope here. Whoever builds the DHT
 * topic-subscription slice needs to actually solve "how many topics does a
 * Country-tier subscriber for a large country need to join for real
 * national coverage" (probably more than target+neighbors) -- that is that
 * slice's problem to solve, not rediscovered from scratch, and it is not
 * solved here.
 *
 * Per ADR 0003: geohash prefixes are not secret -- any client, honest or
 * not, can subscribe to any topic. This mapping (and [targetCellPrefixes])
 * raise the cost of casual/scripted topic scraping for the stock client;
 * they are **not** equivalent to server-side access control.
 */
object ReachTierGeohash {

    /**
     * Geohash-prefix length used as this tier's DHT topic-subscription key.
     * Throws for [ReachTier.LOCALITY] -- it already never touches the DHT
     * (ADR 0003) and needs no geohash resolution at all.
     */
    fun precisionFor(tier: ReachTier): Int = when (tier) {
        ReachTier.LOCALITY -> throw IllegalArgumentException(
            "Locality never touches the DHT and needs no geohash precision -- ADR 0003; " +
                "it resolves entirely offline via BLE/WiFi Direct"
        )
        ReachTier.TOWN -> 5
        ReachTier.CITY -> 4
        ReachTier.COUNTRY -> 2
    }

    /**
     * The target cell's own prefix (at [tier]'s precision) plus its
     * neighbor cell prefixes -- PRD §6's "target cell plus its neighbor
     * cells," which avoids boundary-edge misses between adjacent cells.
     *
     * See this object's class-level KDoc for the Country-tier fidelity
     * limit and the "not equivalent to server-side access control" reminder
     * -- both apply directly here, since this is the function that decides
     * what a peer will actually subscribe to / check a claim against.
     */
    fun targetCellPrefixes(latitude: Double, longitude: Double, tier: ReachTier): Set<String> {
        val precision = precisionFor(tier)
        val target = Geohash.encode(latitude, longitude, precision)
        return (Geohash.neighbors(target) + target).toSet()
    }
}
