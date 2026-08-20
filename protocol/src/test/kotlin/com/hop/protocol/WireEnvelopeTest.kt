package com.hop.protocol

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.SecureRandom
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Covers the `[1-byte WirePayloadType][4-byte length][payload]` socket
 * envelope layer, per /protocol/WIRE_FORMAT.md's envelope section. Mirrors
 * [FrameTest]'s rigor: round-trip per type, truncated/malformed input
 * rejection, and an explicit proof that this is a *breaking* change to the
 * old bare `[4-byte length][Frame bytes]` socket framing (not just a
 * theoretical claim in the doc).
 */
class WireEnvelopeTest {

    private val random = SecureRandom()

    private fun randomBytes(size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }

    private fun sampleFrame(payload: ByteArray = randomBytes(256)): Frame = Frame(
        clipHash = randomBytes(Frame.CLIP_HASH_SIZE),
        senderDeviceId = randomBytes(Frame.SENDER_DEVICE_ID_SIZE),
        contentType = ContentType.PHOTO,
        hopCount = 0,
        originatedAtMs = 1_700_000_000_000L,
        ttlSeconds = 3600L,
        reachTier = ReachTier.LOCALITY,
        dontRelay = false,
        keyIncluded = true,
        contentEncryptionKey = randomBytes(Frame.CONTENT_ENCRYPTION_KEY_SIZE),
        payload = payload,
    )

    // --- Round trip per WirePayloadType ---

    @Test
    fun `round trip preserves type and payload for POST_FRAME`() {
        val payload = sampleFrame().encode()
        val encoded = WireEnvelope.encode(WirePayloadType.POST_FRAME, payload)

        val decoded = WireEnvelope.decode(encoded)

        assertEquals(WirePayloadType.POST_FRAME, decoded.type)
        assertTrue(payload.contentEquals(decoded.payload))
    }

    @Test
    fun `round trip preserves type and payload for PREKEY_BUNDLE`() {
        val payload = PreKeyBundleEnvelope(peerId = "abcdef0123456789", hopCount = 0, originatedAtMs = 1_700_000_000_000L, bundleBytes = randomBytes(128)).encode()
        val encoded = WireEnvelope.encode(WirePayloadType.PREKEY_BUNDLE, payload)

        val decoded = WireEnvelope.decode(encoded)

        assertEquals(WirePayloadType.PREKEY_BUNDLE, decoded.type)
        assertTrue(payload.contentEquals(decoded.payload))
    }

    @Test
    fun `round trip preserves type and payload for MESSAGE_CIPHERTEXT`() {
        val payload = MessageCiphertextEnvelope(
            senderPeerId = "sender-peer",
            recipientPeerId = "recipient-peer",
            hopCount = 0,
            originatedAtMs = 1_700_000_000_000L,
            ciphertext = randomBytes(64),
        ).encode()
        val encoded = WireEnvelope.encode(WirePayloadType.MESSAGE_CIPHERTEXT, payload)

        val decoded = WireEnvelope.decode(encoded)

        assertEquals(WirePayloadType.MESSAGE_CIPHERTEXT, decoded.type)
        assertTrue(payload.contentEquals(decoded.payload))
    }

    @Test
    fun `round trip works with empty payload`() {
        val encoded = WireEnvelope.encode(WirePayloadType.POST_FRAME, ByteArray(0))
        val decoded = WireEnvelope.decode(encoded)
        assertEquals(WirePayloadType.POST_FRAME, decoded.type)
        assertEquals(0, decoded.payload.size)
    }

    @Test
    fun `encoded size is header size plus payload size`() {
        val payload = randomBytes(999)
        val encoded = WireEnvelope.encode(WirePayloadType.MESSAGE_CIPHERTEXT, payload)
        assertEquals(WireEnvelope.HEADER_SIZE + payload.size, encoded.size)
    }

    // --- Byte-identity: a POST_FRAME-typed envelope is purely additive over Frame ---

    @Test
    fun `a POST_FRAME-typed envelope decodes to a Frame identical to the original`() {
        val original = sampleFrame(payload = randomBytes(4096))
        val originalEncoded = original.encode()

        val envelopeEncoded = WireEnvelope.encode(WirePayloadType.POST_FRAME, originalEncoded)
        val envelope = WireEnvelope.decode(envelopeEncoded)
        assertEquals(WirePayloadType.POST_FRAME, envelope.type)

        val decodedFrame = Frame.decode(envelope.payload)

        assertEquals(original, decodedFrame)
        assertTrue(original.clipHash.contentEquals(decodedFrame.clipHash))
        assertTrue(original.senderDeviceId.contentEquals(decodedFrame.senderDeviceId))
        assertEquals(original.contentType, decodedFrame.contentType)
        assertEquals(original.hopCount, decodedFrame.hopCount)
        assertEquals(original.originatedAtMs, decodedFrame.originatedAtMs)
        assertEquals(original.ttlSeconds, decodedFrame.ttlSeconds)
        assertEquals(original.reachTier, decodedFrame.reachTier)
        assertEquals(original.dontRelay, decodedFrame.dontRelay)
        assertEquals(original.keyIncluded, decodedFrame.keyIncluded)
        assertTrue(original.contentEncryptionKey.contentEquals(decodedFrame.contentEncryptionKey))
        assertTrue(original.payload.contentEquals(decodedFrame.payload))
    }

    // --- Breaking change: old bare [length][Frame] framing is no longer valid ---

    @Test
    fun `decoding old-style bare length-prefixed Frame bytes without a type tag fails or misparses`() {
        // This is the exact old socket framing WifiDirectTransport used before this
        // envelope layer existed: [4-byte length][Frame bytes], no leading type byte.
        // Reproduce it exactly (including the length prefix itself, which the old
        // receive loop consumed via DataInputStream.readInt() before ever touching
        // Frame.decode) and prove decoding it as a WireEnvelope is either a clean
        // failure or -- if it doesn't fail -- unambiguously wrong (proving the break
        // is real, not just assumed from the design).
        val frameBytes = sampleFrame(payload = randomBytes(200)).encode()
        val oldStyleBare = ByteArrayOutputStream().use { baos ->
            DataOutputStream(baos).use { out ->
                out.writeInt(frameBytes.size)
                out.write(frameBytes)
            }
            baos.toByteArray()
        }

        val result = runCatching { WireEnvelope.decode(oldStyleBare) }

        if (result.isFailure) {
            assertTrue(result.exceptionOrNull() is WireEnvelopeDecodeException)
        } else {
            // If it didn't throw, the misparse must be detectable: the old framing's
            // first byte is the high byte of a 4-byte big-endian length (frameBytes.size
            // is well under 2^24, so that high byte is 0x00), which WirePayloadType
            // would only recognize by coincidence as POST_FRAME's wire value (0). Even
            // in that coincidental case, the recovered "payload" must NOT equal the
            // original frameBytes, since the byte alignment is off by the type tag
            // WireEnvelope now expects but the old sender never sent.
            val decoded = result.getOrThrow()
            assertTrue(
                !frameBytes.contentEquals(decoded.payload),
                "Old bare framing must not decode to the same payload bytes as the new tagged framing -- " +
                    "the break must be real, not silently compatible",
            )
        }
    }

    @Test
    fun `decoding new-style tagged bytes as old-style bare framing would misalign`() {
        // Symmetric proof in the other direction: bytes produced by the NEW
        // WireEnvelope.encode() are not valid input to the OLD raw
        // DataInputStream.readInt()-then-Frame.decode() path either, since the
        // leading type byte throws off the length field's byte alignment.
        val frameBytes = sampleFrame(payload = randomBytes(50)).encode()
        val newStyleTagged = WireEnvelope.encode(WirePayloadType.POST_FRAME, frameBytes)

        // Simulate the OLD receive loop: read a 4-byte big-endian length, then hand
        // the rest straight to Frame.decode().
        val buffer = java.nio.ByteBuffer.wrap(newStyleTagged).order(java.nio.ByteOrder.BIG_ENDIAN)
        val oldStyleDeclaredLength = buffer.int
        // The old loop would try to read exactly this many bytes as the frame. It is
        // not equal to the real payload length any more (it's now shifted by the
        // leading type byte), proving the two framings are not silently compatible.
        assertTrue(
            oldStyleDeclaredLength != frameBytes.size,
            "New tagged framing's first 4 bytes must not coincidentally equal the true payload length under the old framing's interpretation",
        )
    }

    // --- Malformed / unknown input rejection ---

    @Test
    fun `decoding empty bytes throws`() {
        assertFailsWith<WireEnvelopeDecodeException> { WireEnvelope.decode(ByteArray(0)) }
    }

    @Test
    fun `decoding bytes shorter than the header throws`() {
        val truncated = ByteArray(WireEnvelope.HEADER_SIZE - 1)
        assertFailsWith<WireEnvelopeDecodeException> { WireEnvelope.decode(truncated) }
    }

    @Test
    fun `decoding an unknown type byte throws`() {
        val encoded = WireEnvelope.encode(WirePayloadType.POST_FRAME, randomBytes(10))
        val mutated = encoded.copyOf()
        mutated[0] = 99 // not a defined WirePayloadType value

        assertFailsWith<WireEnvelopeDecodeException> { WireEnvelope.decode(mutated) }
    }

    @Test
    fun `decoding an envelope whose declared payload length exceeds available bytes throws`() {
        val full = WireEnvelope.encode(WirePayloadType.MESSAGE_CIPHERTEXT, randomBytes(100))
        val truncated = full.copyOf(full.size - 10)

        assertFailsWith<WireEnvelopeDecodeException> { WireEnvelope.decode(truncated) }
    }

    // --- Explicit byte-value contract (documented, since it's now part of the wire) ---

    @Test
    fun `WirePayloadType wire values match the documented contract`() {
        assertEquals(0, WirePayloadType.POST_FRAME.wireValue)
        assertEquals(1, WirePayloadType.PREKEY_BUNDLE.wireValue)
        assertEquals(2, WirePayloadType.MESSAGE_CIPHERTEXT.wireValue)
    }
}
