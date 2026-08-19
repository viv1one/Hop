package com.hop.protocol

import java.security.SecureRandom
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Covers [PreKeyBundleEnvelope]'s own field encoding: `peerId` string
 * length-prefixing plus the opaque `bundleBytes` payload. This module never
 * deserializes `bundleBytes` as a libsignal-client `PreKeyBundle` -- these
 * tests only exercise this envelope's own wire shape, matching [FrameTest]'s
 * round-trip/malformed-input rigor.
 */
class PreKeyBundleEnvelopeTest {

    private val random = SecureRandom()

    private fun randomBytes(size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }

    @Test
    fun `round trip preserves peerId and bundleBytes`() {
        val original = PreKeyBundleEnvelope(peerId = "0123456789abcdef", bundleBytes = randomBytes(512))

        val decoded = PreKeyBundleEnvelope.decode(original.encode())

        assertEquals(original.peerId, decoded.peerId)
        assertTrue(original.bundleBytes.contentEquals(decoded.bundleBytes))
        assertEquals(original, decoded)
    }

    @Test
    fun `round trip works with empty bundleBytes`() {
        val original = PreKeyBundleEnvelope(peerId = "deadbeef", bundleBytes = ByteArray(0))

        val decoded = PreKeyBundleEnvelope.decode(original.encode())

        assertEquals("deadbeef", decoded.peerId)
        assertEquals(0, decoded.bundleBytes.size)
    }

    @Test
    fun `round trip works with an empty peerId`() {
        val original = PreKeyBundleEnvelope(peerId = "", bundleBytes = randomBytes(16))

        val decoded = PreKeyBundleEnvelope.decode(original.encode())

        assertEquals("", decoded.peerId)
        assertTrue(original.bundleBytes.contentEquals(decoded.bundleBytes))
    }

    @Test
    fun `round trip works with a large bundleBytes payload`() {
        val original = PreKeyBundleEnvelope(peerId = "large-bundle-peer", bundleBytes = randomBytes(65_536))

        val decoded = PreKeyBundleEnvelope.decode(original.encode())

        assertTrue(original.bundleBytes.contentEquals(decoded.bundleBytes))
    }

    @Test
    fun `encoded size is 4 plus peerId UTF-8 byte length plus bundleBytes length`() {
        val envelope = PreKeyBundleEnvelope(peerId = "peer-id-123", bundleBytes = randomBytes(77))
        val expectedSize = 4 + "peer-id-123".toByteArray(Charsets.UTF_8).size + 77
        assertEquals(expectedSize, envelope.encode().size)
    }

    @Test
    fun `decoding empty bytes throws`() {
        assertFailsWith<PreKeyBundleEnvelopeDecodeException> { PreKeyBundleEnvelope.decode(ByteArray(0)) }
    }

    @Test
    fun `decoding bytes shorter than the peerId length prefix throws`() {
        assertFailsWith<PreKeyBundleEnvelopeDecodeException> { PreKeyBundleEnvelope.decode(ByteArray(3)) }
    }

    @Test
    fun `decoding an envelope whose declared peerId length exceeds available bytes throws`() {
        val full = PreKeyBundleEnvelope(peerId = "abc", bundleBytes = randomBytes(10)).encode()
        // Drop enough trailing bytes to make the declared peerId length exceed what's left.
        val truncated = full.copyOf(4 + 1)

        assertFailsWith<PreKeyBundleEnvelopeDecodeException> { PreKeyBundleEnvelope.decode(truncated) }
    }
}
