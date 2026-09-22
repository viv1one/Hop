package com.hop.protocol

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RelayPolicyTest {

    private val originatedAtMs = 1_700_000_000_000L
    private val ttlSeconds = 3600L

    private fun clockAt(epochMs: Long): Clock = Clock.fixed(Instant.ofEpochMilli(epochMs), ZoneOffset.UTC)

    // --- expiresAtMs ---

    @Test
    fun `expiresAtMs is originatedAtMs plus ttlSeconds in milliseconds`() {
        val policy = RelayPolicy()
        assertEquals(originatedAtMs + ttlSeconds * 1000, policy.expiresAtMs(originatedAtMs, ttlSeconds))
    }

    // --- isExpired boundary ---

    @Test
    fun `isExpired is false one millisecond before the boundary`() {
        val policy = RelayPolicy()
        val expiresAtMs = policy.expiresAtMs(originatedAtMs, ttlSeconds)
        assertFalse(policy.isExpired(originatedAtMs, ttlSeconds, nowMs = expiresAtMs - 1))
    }

    @Test
    fun `isExpired is true exactly at the boundary (matches DecayKeyStore's not-isBefore convention)`() {
        val policy = RelayPolicy()
        val expiresAtMs = policy.expiresAtMs(originatedAtMs, ttlSeconds)
        assertTrue(policy.isExpired(originatedAtMs, ttlSeconds, nowMs = expiresAtMs))
    }

    @Test
    fun `isExpired is true one millisecond after the boundary`() {
        val policy = RelayPolicy()
        val expiresAtMs = policy.expiresAtMs(originatedAtMs, ttlSeconds)
        assertTrue(policy.isExpired(originatedAtMs, ttlSeconds, nowMs = expiresAtMs + 1))
    }

    @Test
    fun `isExpired uses the injected clock's now when nowMs is not given explicitly`() {
        val expiresAtMs = originatedAtMs + ttlSeconds * 1000
        val beforePolicy = RelayPolicy(clock = clockAt(expiresAtMs - 1))
        val atPolicy = RelayPolicy(clock = clockAt(expiresAtMs))
        assertFalse(beforePolicy.isExpired(originatedAtMs, ttlSeconds))
        assertTrue(atPolicy.isExpired(originatedAtMs, ttlSeconds))
    }

    // --- isEligibleForRelay ---

    @Test
    fun `a fresh frame well under maxHops, not dontRelay, not expired is eligible`() {
        val policy = RelayPolicy(maxHops = 6, clock = clockAt(originatedAtMs))
        assertTrue(policy.isEligibleForRelay(storedHopCount = 0, dontRelay = false, originatedAtMs = originatedAtMs, ttlSeconds = ttlSeconds))
    }

    @Test
    fun `a frame at exactly maxHops minus one is still eligible`() {
        val policy = RelayPolicy(maxHops = 6, clock = clockAt(originatedAtMs))
        assertTrue(policy.isEligibleForRelay(storedHopCount = 5, dontRelay = false, originatedAtMs = originatedAtMs, ttlSeconds = ttlSeconds))
    }

    @Test
    fun `a frame at exactly maxHops is not eligible`() {
        val policy = RelayPolicy(maxHops = 6, clock = clockAt(originatedAtMs))
        assertFalse(policy.isEligibleForRelay(storedHopCount = 6, dontRelay = false, originatedAtMs = originatedAtMs, ttlSeconds = ttlSeconds))
    }

    @Test
    fun `a frame past maxHops is not eligible`() {
        val policy = RelayPolicy(maxHops = 6, clock = clockAt(originatedAtMs))
        assertFalse(policy.isEligibleForRelay(storedHopCount = 200, dontRelay = false, originatedAtMs = originatedAtMs, ttlSeconds = ttlSeconds))
    }

    @Test
    fun `a dontRelay frame is not eligible regardless of hop count or expiry`() {
        val policy = RelayPolicy(maxHops = 6, clock = clockAt(originatedAtMs))
        assertFalse(policy.isEligibleForRelay(storedHopCount = 0, dontRelay = true, originatedAtMs = originatedAtMs, ttlSeconds = ttlSeconds))
    }

    @Test
    fun `an expired frame is not eligible even at hopCount zero and dontRelay false`() {
        val expiresAtMs = originatedAtMs + ttlSeconds * 1000
        val policy = RelayPolicy(maxHops = 6, clock = clockAt(expiresAtMs))
        assertFalse(policy.isEligibleForRelay(storedHopCount = 0, dontRelay = false, originatedAtMs = originatedAtMs, ttlSeconds = ttlSeconds))
    }

    @Test
    fun `eligibility at the exact expiry boundary is false, consistent with isExpired's own boundary`() {
        val expiresAtMs = originatedAtMs + ttlSeconds * 1000
        val policy = RelayPolicy(maxHops = 6)
        assertFalse(
            policy.isEligibleForRelay(
                storedHopCount = 0,
                dontRelay = false,
                originatedAtMs = originatedAtMs,
                ttlSeconds = ttlSeconds,
                nowMs = expiresAtMs,
            ),
        )
        assertTrue(
            policy.isEligibleForRelay(
                storedHopCount = 0,
                dontRelay = false,
                originatedAtMs = originatedAtMs,
                ttlSeconds = ttlSeconds,
                nowMs = expiresAtMs - 1,
            ),
        )
    }

    @Test
    fun `default maxHops is DEFAULT_MAX_HOPS`() {
        val policy = RelayPolicy(clock = clockAt(originatedAtMs))
        assertTrue(
            policy.isEligibleForRelay(
                storedHopCount = RelayPolicy.DEFAULT_MAX_HOPS - 1,
                dontRelay = false,
                originatedAtMs = originatedAtMs,
                ttlSeconds = ttlSeconds,
            ),
        )
        assertFalse(
            policy.isEligibleForRelay(
                storedHopCount = RelayPolicy.DEFAULT_MAX_HOPS,
                dontRelay = false,
                originatedAtMs = originatedAtMs,
                ttlSeconds = ttlSeconds,
            ),
        )
    }
}
