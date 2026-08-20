package com.hop.protocol

import java.security.SecureRandom
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Covers [DontRelayFlagEnvelope]'s own field encoding: fixed-size `clipHash`,
 * length-prefixed `attestedDeviceKey`, and the fixed
 * `flaggedAtMs`/`originatedAtMs`/`ttlSeconds` tail -- matching
 * [PreKeyBundleEnvelopeTest]'s round-trip/malformed-input rigor.
 */
class DontRelayFlagEnvelopeTest {

    private val random = SecureRandom()

    private fun randomBytes(size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }

    private fun sampleEnvelope(
        clipHash: ByteArray = randomBytes(Frame.CLIP_HASH_SIZE),
        attestedDeviceKey: ByteArray = randomBytes(32),
        flaggedAtMs: Long = 1_700_000_100_000L,
        originatedAtMs: Long = 1_700_000_000_000L,
        ttlSeconds: Long = 3600L,
    ) = DontRelayFlagEnvelope(
        clipHash = clipHash,
        attestedDeviceKey = attestedDeviceKey,
        flaggedAtMs = flaggedAtMs,
        originatedAtMs = originatedAtMs,
        ttlSeconds = ttlSeconds,
    )

    @Test
    fun `round trip preserves every field`() {
        val original = sampleEnvelope()

        val decoded = DontRelayFlagEnvelope.decode(original.encode())

        assertEquals(original, decoded)
        assertTrue(original.clipHash.contentEquals(decoded.clipHash))
        assertTrue(original.attestedDeviceKey.contentEquals(decoded.attestedDeviceKey))
        assertEquals(original.flaggedAtMs, decoded.flaggedAtMs)
        assertEquals(original.originatedAtMs, decoded.originatedAtMs)
        assertEquals(original.ttlSeconds, decoded.ttlSeconds)
    }

    @Test
    fun `round trip works with a differently-sized attestedDeviceKey than the stub nonce size`() {
        // A real Play Integrity/App Attest key will not be the same size as
        // StubAttestationProvider's 32-byte nonce echo -- this is exactly why
        // attestedDeviceKey is length-prefixed, not fixed-size.
        val original = sampleEnvelope(attestedDeviceKey = randomBytes(91))

        val decoded = DontRelayFlagEnvelope.decode(original.encode())

        assertTrue(original.attestedDeviceKey.contentEquals(decoded.attestedDeviceKey))
    }

    @Test
    fun `round trip works with an empty attestedDeviceKey`() {
        val original = sampleEnvelope(attestedDeviceKey = ByteArray(0))

        val decoded = DontRelayFlagEnvelope.decode(original.encode())

        assertEquals(0, decoded.attestedDeviceKey.size)
    }

    @Test
    fun `encoded size is clipHash plus 4 plus keyLength plus 20`() {
        val envelope = sampleEnvelope(attestedDeviceKey = randomBytes(50))
        val expectedSize = Frame.CLIP_HASH_SIZE + 4 + 50 + 8 + 8 + 4
        assertEquals(expectedSize, envelope.encode().size)
    }

    @Test
    fun `constructing with a wrong-size clipHash throws`() {
        assertFailsWith<IllegalArgumentException> {
            sampleEnvelope(clipHash = randomBytes(Frame.CLIP_HASH_SIZE - 1))
        }
    }

    @Test
    fun `decoding empty bytes throws`() {
        assertFailsWith<DontRelayFlagEnvelopeDecodeException> { DontRelayFlagEnvelope.decode(ByteArray(0)) }
    }

    @Test
    fun `decoding bytes shorter than clipHash plus keyLength prefix throws`() {
        assertFailsWith<DontRelayFlagEnvelopeDecodeException> {
            DontRelayFlagEnvelope.decode(randomBytes(Frame.CLIP_HASH_SIZE))
        }
    }

    @Test
    fun `decoding an envelope whose declared attestedDeviceKey length exceeds available bytes throws`() {
        val full = sampleEnvelope(attestedDeviceKey = randomBytes(10)).encode()
        // Truncate right after the key-length prefix so the declared length can't be satisfied.
        val truncated = full.copyOf(Frame.CLIP_HASH_SIZE + 4 + 1)

        assertFailsWith<DontRelayFlagEnvelopeDecodeException> { DontRelayFlagEnvelope.decode(truncated) }
    }

    @Test
    fun `decoding an envelope truncated in the fixed tail throws`() {
        val full = sampleEnvelope(attestedDeviceKey = randomBytes(10)).encode()
        // Drop the last few bytes of the flaggedAtMs/originatedAtMs/ttlSeconds tail.
        val truncated = full.copyOf(full.size - 3)

        assertFailsWith<DontRelayFlagEnvelopeDecodeException> { DontRelayFlagEnvelope.decode(truncated) }
    }
}
