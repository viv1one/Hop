package com.hop.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Covers [TierKeyResponseEnvelope]'s own field encoding: fixed-size
 * `contentId`, an explicit `granted` flag byte, and length-prefixed
 * `wrappedCek` -- matching [PreKeyBundleEnvelopeTest]/[DontRelayFlagEnvelopeTest]'s
 * round-trip/malformed-input rigor. Also covers the granted/denied invariant
 * (a denial must never carry key bytes) at both construction time and decode
 * time.
 */
class TierKeyResponseEnvelopeTest {

    private val random = SecureRandom()

    private fun randomBytes(size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }

    private fun sampleContentId(): ByteArray = randomBytes(Frame.CLIP_HASH_SIZE)

    // --- Round trip: granted ---

    @Test
    fun `round trip preserves contentId, granted, and wrappedCek for a granted response`() {
        val contentId = sampleContentId()
        val wrappedCek = randomBytes(32)
        val original = TierKeyResponseEnvelope(contentId = contentId, granted = true, wrappedCek = wrappedCek)

        val decoded = TierKeyResponseEnvelope.decode(original.encode())

        assertEquals(original, decoded)
        assertTrue(original.contentId.contentEquals(decoded.contentId))
        assertTrue(decoded.granted)
        assertTrue(original.wrappedCek.contentEquals(decoded.wrappedCek))
    }

    @Test
    fun `round trip works with a wrappedCek larger than a raw 32-byte AES key`() {
        // Length-prefixed on purpose: a real key-wrap scheme may add overhead
        // beyond today's raw 32-byte CEK -- see class doc.
        val original = TierKeyResponseEnvelope(contentId = sampleContentId(), granted = true, wrappedCek = randomBytes(48))

        val decoded = TierKeyResponseEnvelope.decode(original.encode())

        assertTrue(original.wrappedCek.contentEquals(decoded.wrappedCek))
    }

    // --- Round trip: denied ---

    @Test
    fun `round trip preserves a denied response with an empty wrappedCek`() {
        val contentId = sampleContentId()
        val original = TierKeyResponseEnvelope(contentId = contentId, granted = false, wrappedCek = ByteArray(0))

        val decoded = TierKeyResponseEnvelope.decode(original.encode())

        assertFalse(decoded.granted)
        assertEquals(0, decoded.wrappedCek.size)
    }

    // --- Convenience factories ---

    @Test
    fun `granted factory produces a granted response carrying the given key`() {
        val contentId = sampleContentId()
        val wrappedCek = randomBytes(32)
        val envelope = TierKeyResponseEnvelope.granted(contentId, wrappedCek)

        assertTrue(envelope.granted)
        assertTrue(envelope.wrappedCek.contentEquals(wrappedCek))
    }

    @Test
    fun `denied factory produces a denial carrying no key material`() {
        val contentId = sampleContentId()
        val envelope = TierKeyResponseEnvelope.denied(contentId)

        assertFalse(envelope.granted)
        assertEquals(0, envelope.wrappedCek.size)
    }

    // --- Encoded size ---

    @Test
    fun `encoded size is contentId plus 1 plus 4 plus wrappedCek length`() {
        val envelope = TierKeyResponseEnvelope(contentId = sampleContentId(), granted = true, wrappedCek = randomBytes(40))
        val expectedSize = Frame.CLIP_HASH_SIZE + 1 + 4 + 40
        assertEquals(expectedSize, envelope.encode().size)
    }

    // --- Constructor-time validation ---

    @Test
    fun `constructing with a wrong-size contentId throws`() {
        assertFailsWith<IllegalArgumentException> {
            TierKeyResponseEnvelope(contentId = randomBytes(Frame.CLIP_HASH_SIZE - 1), granted = true, wrappedCek = randomBytes(32))
        }
    }

    @Test
    fun `constructing a denial with a non-empty wrappedCek throws`() {
        assertFailsWith<IllegalArgumentException> {
            TierKeyResponseEnvelope(contentId = sampleContentId(), granted = false, wrappedCek = randomBytes(32))
        }
    }

    // --- Malformed / truncated input rejection ---

    @Test
    fun `decoding empty bytes throws`() {
        assertFailsWith<TierKeyResponseEnvelopeDecodeException> { TierKeyResponseEnvelope.decode(ByteArray(0)) }
    }

    @Test
    fun `decoding bytes shorter than contentId plus granted plus length prefix throws`() {
        assertFailsWith<TierKeyResponseEnvelopeDecodeException> {
            TierKeyResponseEnvelope.decode(randomBytes(Frame.CLIP_HASH_SIZE))
        }
    }

    @Test
    fun `decoding an invalid granted byte throws`() {
        val full = TierKeyResponseEnvelope(contentId = sampleContentId(), granted = true, wrappedCek = randomBytes(10)).encode()
        val mutated = full.copyOf()
        mutated[Frame.CLIP_HASH_SIZE] = 7 // not 0 or 1

        assertFailsWith<TierKeyResponseEnvelopeDecodeException> { TierKeyResponseEnvelope.decode(mutated) }
    }

    @Test
    fun `decoding an envelope whose declared wrappedCek length exceeds available bytes throws`() {
        val full = TierKeyResponseEnvelope(contentId = sampleContentId(), granted = true, wrappedCek = randomBytes(10)).encode()
        // Truncate right after the wrappedCek length prefix so the declared length can't be satisfied.
        val truncated = full.copyOf(Frame.CLIP_HASH_SIZE + 1 + 4 + 1)

        assertFailsWith<TierKeyResponseEnvelopeDecodeException> { TierKeyResponseEnvelope.decode(truncated) }
    }

    @Test
    fun `decoding a hand-built envelope with granted=false but non-empty wrappedCek throws`() {
        // Hand-build bytes the real encode() path can never produce (its own
        // init already rejects this combination) to prove the decoder itself
        // rejects a malformed/hostile wire value rather than silently
        // accepting it with the key bytes dropped.
        val contentId = sampleContentId()
        val wrappedCekBytes = randomBytes(16)
        val buffer = ByteBuffer.allocate(Frame.CLIP_HASH_SIZE + 1 + 4 + wrappedCekBytes.size)
        buffer.order(ByteOrder.BIG_ENDIAN)
        buffer.put(contentId)
        buffer.put(0.toByte()) // granted = false
        buffer.putInt(wrappedCekBytes.size)
        buffer.put(wrappedCekBytes)

        assertFailsWith<TierKeyResponseEnvelopeDecodeException> { TierKeyResponseEnvelope.decode(buffer.array()) }
    }
}
