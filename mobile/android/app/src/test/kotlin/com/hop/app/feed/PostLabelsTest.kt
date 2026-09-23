package com.hop.app.feed

import com.hop.protocol.ReachTier
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * JVM tests for the feed overlay's plain-language labels. Same shape as
 * [PostPagerItemTest]: the decisions are extracted as pure functions so they
 * are testable without a Compose UI harness, which this repo doesn't have set
 * up yet.
 */
class PostLabelsTest {

    private val now = 1_700_000_000_000L
    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour

    @Test
    fun `reach labels never leak protocol vocabulary`() {
        // hop-dev invariant #5: no "tier"/"geohash" in user-facing strings.
        // Every tier must map to a plain place word.
        ReachTier.entries.forEach { tier ->
            val label = reachLabel(tier.name)
            requireNotNull(label) { "no label for $tier" }
            listOf("tier", "geohash", "locality", "relay").forEach { banned ->
                check(!label.lowercase().contains(banned)) { "$label leaks '$banned'" }
            }
        }
    }

    @Test
    fun `unknown reach tier renders nothing rather than a raw enum name`() {
        assertNull(reachLabel("SOMETHING_NEW"))
    }

    @Test
    fun `post age reads in the largest sensible unit`() {
        assertEquals("Just now", postAgeLabel(now - 30_000L, now))
        assertEquals("12m", postAgeLabel(now - 12 * minute, now))
        assertEquals("3h", postAgeLabel(now - 3 * hour, now))
        assertEquals("2d", postAgeLabel(now - 2 * day, now))
    }

    @Test
    fun `post age clamps rather than showing a negative for a peer whose clock ran ahead`() {
        // No server means no shared time source, so mild forward skew from a
        // peer is expected rather than exceptional.
        assertEquals("Just now", postAgeLabel(now + 5 * minute, now))
    }

    @Test
    fun `fade label counts down in the largest sensible unit`() {
        assertEquals("Fades in 40m", fadeLabel(now, ttlSeconds = 40 * 60, nowMs = now))
        assertEquals("Fades in 6h", fadeLabel(now, ttlSeconds = 6 * 60 * 60, nowMs = now))
        assertEquals("Fades in 2d", fadeLabel(now, ttlSeconds = 2 * 24 * 60 * 60, nowMs = now))
    }

    @Test
    fun `fade label never renders zero minutes`() {
        // Under a minute left still reads as "1m" -- "Fades in 0m" next to a
        // post that is visibly still there reads as a bug.
        assertEquals("Fades in 1m", fadeLabel(now, ttlSeconds = 30, nowMs = now))
    }

    @Test
    fun `fade label disappears once the window has passed`() {
        // At that point the post renders as DecayedPostPlaceholder anyway, so
        // an expiry chin beside it would be noise.
        assertNull(fadeLabel(now - 2 * hour, ttlSeconds = 60 * 60, nowMs = now))
    }
}
