package com.hop.protocol

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GeohashTest {

    // --- Test plan case 1: encode correctness ---

    @Test
    fun `encode matches the canonical Wikipedia known-answer vector`() {
        // lat 42.6, lon -5.6 -> "ezs42", the standard hand-verifiable geohash
        // example. Conveniently exactly Town-tier's precision-5 length.
        assertEquals("ezs42", Geohash.encode(42.6, -5.6, precision = 5))
    }

    // --- Test plan case 2: neighbor round-trip, no external oracle needed ---

    @Test
    fun `neighbor relation is symmetric for an arbitrary non-boundary cell (covers N-S and E-W both)`() {
        // An arbitrary point nowhere near a pole or the antimeridian.
        val origin = Geohash.encode(37.7749, -122.4194, precision = 6) // San Francisco
        val neighbors = Geohash.neighbors(origin)

        assertEquals(8, neighbors.size, "a non-boundary cell should have all 8 neighbors")
        // Round-trip: every neighbor of `origin` must itself list `origin` as
        // one of its own neighbors -- this directly exercises "northern
        // neighbor's southern neighbor is the original cell" and the same
        // for east/west, without needing direction labels, and it catches
        // bit-interleaving bugs directly (a bug in either encode or decode's
        // bit math breaks this symmetry).
        for (neighbor in neighbors) {
            assertTrue(
                origin in Geohash.neighbors(neighbor),
                "neighbor $neighbor of $origin does not list $origin back as its own neighbor",
            )
        }
    }

    // --- Test plan case 3: antimeridian wrap ---

    @Test
    fun `east neighbor near the antimeridian wraps to the other side, and east-then-west round-trips home`() {
        val precision = 6
        val origin = Geohash.encode(10.0, 179.99, precision)
        val originBox = Geohash.decode(origin)

        // Up to 3 "eastward" neighbors (dLon = +1: NE, E, SE) should have
        // wrapped past +180 into strongly negative longitude territory.
        val neighbors = Geohash.neighbors(origin)
        val wrapped = neighbors.filter { Geohash.decode(it).lonCenter < -170.0 }
        assertTrue(wrapped.isNotEmpty(), "expected at least one neighbor to wrap across the antimeridian")
        for (neighborHash in wrapped) {
            val box = Geohash.decode(neighborHash)
            assertTrue(box.lonCenter < -179.0, "wrapped neighbor lonCenter=${box.lonCenter} should be near -179.9")
        }

        // The pure "east" neighbor (same latitude band as origin, dLat = 0)
        // is the one the plan's vector describes ("lon near 179.99 -> its
        // eastern neighbor decodes near -179.9"); isolate it by latitude
        // match rather than assuming list order.
        val pureEastNeighbor = wrapped.first { kotlin.math.abs(Geohash.decode(it).latCenter - originBox.latCenter) < 0.0001 }

        // East-then-west round trip: origin must be a neighbor of its own
        // wrapped eastern neighbor (the symmetric relation from the interior
        // round-trip test holds across the wrap too).
        assertTrue(
            origin in Geohash.neighbors(pureEastNeighbor),
            "east-then-west should round-trip back to $origin",
        )
    }

    // --- Test plan case 4: pole clamp ---

    @Test
    fun `neighbors of a north-pole-touching cell omits, not wraps, the northern-slot neighbors`() {
        // Repeated "upper half" choices at every latitude-bit step walk the
        // cell all the way to latMax = 90.0 exactly.
        val nearPole = Geohash.encode(89.9999, 10.0, precision = 6)
        val box = Geohash.decode(nearPole)
        assertEquals(90.0, box.latMax, 0.0, "expected a cell whose bounding box touches the pole exactly")

        val neighbors = Geohash.neighbors(nearPole)
        // A pole-touching cell has real east/west neighbors and real
        // southern neighbors, but no northern ones -- so strictly fewer
        // than 8 results (never 8, and never a wrapped-but-wrong entry).
        assertTrue(neighbors.size < 8, "pole-touching cell must not report a full 8 neighbors")

        // None of the returned neighbors' bounding boxes should be north of
        // (or touch) the pole again via a wrapped-into-southern-hemisphere
        // trick -- every returned neighbor must have latMax <= 90.0 and,
        // critically, none should have silently landed in the southern
        // hemisphere (which is what the known ch.hsr:geohash bug does: mod-
        // masking wraps the "northern" neighbor's latitude bits around to
        // the opposite pole instead of omitting it).
        for (neighborHash in neighbors) {
            val neighborBox = Geohash.decode(neighborHash)
            assertFalse(
                neighborBox.latMax <= 0.0,
                "neighbor $neighborHash of pole-touching cell wrapped into the southern hemisphere",
            )
        }
    }

    @Test
    fun `neighbors of a south-pole-touching cell omits, not wraps, the southern-slot neighbors`() {
        val nearPole = Geohash.encode(-89.9999, 10.0, precision = 6)
        val box = Geohash.decode(nearPole)
        assertEquals(-90.0, box.latMin, 0.0, "expected a cell whose bounding box touches the south pole exactly")

        val neighbors = Geohash.neighbors(nearPole)
        assertTrue(neighbors.size < 8, "pole-touching cell must not report a full 8 neighbors")
        for (neighborHash in neighbors) {
            val neighborBox = Geohash.decode(neighborHash)
            assertFalse(
                neighborBox.latMin >= 0.0,
                "neighbor $neighborHash of south-pole-touching cell wrapped into the northern hemisphere",
            )
        }
    }
}
