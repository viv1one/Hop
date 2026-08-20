package com.hop.protocol

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReachTierGeohashTest {

    @Test
    fun `precisionFor returns the specified precision per tier`() {
        assertEquals(5, ReachTierGeohash.precisionFor(ReachTier.TOWN))
        assertEquals(4, ReachTierGeohash.precisionFor(ReachTier.CITY))
        assertEquals(2, ReachTierGeohash.precisionFor(ReachTier.COUNTRY))
    }

    @Test
    fun `precisionFor throws for LOCALITY -- it never touches the DHT, ADR 0003`() {
        assertFailsWith<IllegalArgumentException> { ReachTierGeohash.precisionFor(ReachTier.LOCALITY) }
    }

    @Test
    fun `targetCellPrefixes contains the target cell's own prefix plus its neighbors`() {
        val lat = 37.7749
        val lon = -122.4194
        val prefixes = ReachTierGeohash.targetCellPrefixes(lat, lon, ReachTier.TOWN)
        val target = Geohash.encode(lat, lon, ReachTierGeohash.precisionFor(ReachTier.TOWN))

        assertTrue(target in prefixes)
        assertEquals(Geohash.neighbors(target).toSet() + target, prefixes)
    }

    // --- Test plan case 7: Country-tier fidelity limit, made concrete ---
    //
    // A passing test that documents an acknowledged, permanent architectural
    // limit -- not a bug to fix. See this plan's Context section (Phase 4
    // Slice 1) and ADR 0003: geohash precision is a uniform grid, political
    // country boundaries are not. Precision 2 (COUNTRY tier) is coarse
    // enough to avoid over-sharing for small/medium countries, but a country
    // as large as Russia still fragments across cells the target-cell-plus-
    // neighbors scheme does not bridge. Solving this (e.g. country-code-
    // keyed topics) is explicitly the DHT topic-subscription slice's job,
    // not this slice's.
    @Test
    fun `Moscow and Vladivostok, both Russia and both COUNTRY tier, do not share cell coverage`() {
        val moscowLat = 55.75
        val moscowLon = 37.62
        val vladivostokLat = 43.12
        val vladivostokLon = 131.88

        val moscowPrefixes = ReachTierGeohash.targetCellPrefixes(moscowLat, moscowLon, ReachTier.COUNTRY)
        val vladivostokPrefixes = ReachTierGeohash.targetCellPrefixes(vladivostokLat, vladivostokLon, ReachTier.COUNTRY)

        val moscowTarget = Geohash.encode(moscowLat, moscowLon, precision = 2)
        val vladivostokTarget = Geohash.encode(vladivostokLat, vladivostokLon, precision = 2)

        assertFalse(
            moscowTarget in vladivostokPrefixes,
            "Moscow's precision-2 cell unexpectedly overlapped Vladivostok's target+neighbor set -- " +
                "this would be a surprising result given the Country-tier fidelity limit, not an expected pass",
        )
        assertFalse(
            vladivostokTarget in moscowPrefixes,
            "Vladivostok's precision-2 cell unexpectedly overlapped Moscow's target+neighbor set",
        )
    }
}
