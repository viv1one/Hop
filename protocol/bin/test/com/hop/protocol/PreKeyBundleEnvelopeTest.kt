package com.hop.protocol

import java.security.SecureRandom
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Covers [PreKeyBundleEnvelope]'s own field encoding: `peerId` string
 * length-prefixing, the `hopCount`/`originatedAtMs` fields added for prekey-
 * bundle mesh flood relay, and the opaque `bundleBytes` payload. This module
 * never deserializes `bundleBytes` as a libsignal-client `PreKeyBundle` --
 * these tests only exercise this envelope's own wire shape, matching
 * [FrameTest]/[MessageCiphertextEnvelopeTest]'s round-trip/malformed-input
 * rigor.
 */
class PreKeyBundleEnvelopeTest {

    private val random = SecureRandom()

    private fun randomBytes(size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }

    @Test
    fun `round trip preserves peerId, hopCount, originatedAtMs, and bundleBytes`() {
        val original = PreKeyBundleEnvelope(
            peerId = "0123456789abcdef",
            hopCount = 3,
            originatedAtMs = 1_700_000_000_000L,
            bundleBytes = randomBytes(512),
        )

        val decoded = PreKeyBundleEnvelope.decode(original.encode())

        assertEquals(original.peerId, decoded.peerId)
        assertEquals(original.hopCount, decoded.hopCount)
        assertEquals(original.originatedAtMs, decoded.originatedAtMs)
        assertTrue(original.bundleBytes.contentEquals(decoded.bundleBytes))
        assertEquals(original, decoded)
    }

    @Test
    fun `round trip preserves hopCount at its boundary values`() {
        val min = PreKeyBundleEnvelope(peerId = "p", hopCount = 0, originatedAtMs = 0L, bundleBytes = randomBytes(8))
        val max = min.copy(hopCount = 0xFF)

        assertEquals(0, PreKeyBundleEnvelope.decode(min.encode()).hopCount)
        assertEquals(0xFF, PreKeyBundleEnvelope.decode(max.encode()).hopCount)
    }

    @Test
    fun `constructing with a hopCount outside uint8 range throws`() {
        assertFailsWith<IllegalArgumentException> {
            PreKeyBundleEnvelope(peerId = "p", hopCount = 256, originatedAtMs = 0L, bundleBytes = ByteArray(0))
        }
        assertFailsWith<IllegalArgumentException> {
            PreKeyBundleEnvelope(peerId = "p", hopCount = -1, originatedAtMs = 0L, bundleBytes = ByteArray(0))
        }
    }

    @Test
    fun `round trip works with empty bundleBytes`() {
        val original = PreKeyBundleEnvelope(peerId = "deadbeef", hopCount = 0, originatedAtMs = 1_700_000_000_000L, bundleBytes = ByteArray(0))

        val decoded = PreKeyBundleEnvelope.decode(original.encode())

        assertEquals("deadbeef", decoded.peerId)
        assertEquals(0, decoded.bundleBytes.size)
    }

    @Test
    fun `round trip works with an empty peerId`() {
        val original = PreKeyBundleEnvelope(peerId = "", hopCount = 0, originatedAtMs = 1_700_000_000_000L, bundleBytes = randomBytes(16))

        val decoded = PreKeyBundleEnvelope.decode(original.encode())

        assertEquals("", decoded.peerId)
        assertTrue(original.bundleBytes.contentEquals(decoded.bundleBytes))
    }

    @Test
    fun `round trip works with a large bundleBytes payload`() {
        val original = PreKeyBundleEnvelope(
            peerId = "large-bundle-peer",
            hopCount = 1,
            originatedAtMs = 1_700_000_000_000L,
            bundleBytes = randomBytes(65_536),
        )

        val decoded = PreKeyBundleEnvelope.decode(original.encode())

        assertTrue(original.bundleBytes.contentEquals(decoded.bundleBytes))
    }

    @Test
    fun `encoded size is 4 plus peerId UTF-8 byte length plus 9 plus bundleBytes length`() {
        val envelope = PreKeyBundleEnvelope(peerId = "peer-id-123", hopCount = 0, originatedAtMs = 1_700_000_000_000L, bundleBytes = randomBytes(77))
        val expectedSize = 4 + "peer-id-123".toByteArray(Charsets.UTF_8).size + 1 + 8 + 77
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
        val full = PreKeyBundleEnvelope(peerId = "abc", hopCount = 0, originatedAtMs = 0L, bundleBytes = randomBytes(10)).encode()
        // Drop enough trailing bytes to make the declared peerId length exceed what's left.
        val truncated = full.copyOf(4 + 1)

        assertFailsWith<PreKeyBundleEnvelopeDecodeException> { PreKeyBundleEnvelope.decode(truncated) }
    }

    @Test
    fun `decoding an envelope truncated inside hopCount plus originatedAtMs throws`() {
        val full = PreKeyBundleEnvelope(peerId = "abc", hopCount = 7, originatedAtMs = 1_700_000_000_000L, bundleBytes = randomBytes(10)).encode()
        val peerIdBytes = "abc".toByteArray(Charsets.UTF_8)
        // Keep through peerId's bytes, plus a few bytes of hopCount/originatedAtMs, but not all 9.
        val truncated = full.copyOf(4 + peerIdBytes.size + 3)

        assertFailsWith<PreKeyBundleEnvelopeDecodeException> { PreKeyBundleEnvelope.decode(truncated) }
    }
}
