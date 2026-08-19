package com.hop.protocol

import java.security.SecureRandom
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Covers [MessageCiphertextEnvelope]'s own field encoding: `senderPeerId`/
 * `recipientPeerId` string length-prefixing plus the opaque `ciphertext`
 * payload. This module never touches Double Ratchet internals -- these
 * tests only exercise this envelope's own wire shape, matching [FrameTest]'s
 * round-trip/malformed-input rigor.
 */
class MessageCiphertextEnvelopeTest {

    private val random = SecureRandom()

    private fun randomBytes(size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }

    @Test
    fun `round trip preserves senderPeerId, recipientPeerId, and ciphertext`() {
        val original = MessageCiphertextEnvelope(
            senderPeerId = "0011223344556677",
            recipientPeerId = "8899aabbccddeeff",
            ciphertext = randomBytes(256),
        )

        val decoded = MessageCiphertextEnvelope.decode(original.encode())

        assertEquals(original.senderPeerId, decoded.senderPeerId)
        assertEquals(original.recipientPeerId, decoded.recipientPeerId)
        assertTrue(original.ciphertext.contentEquals(decoded.ciphertext))
        assertEquals(original, decoded)
    }

    @Test
    fun `round trip works with empty ciphertext`() {
        val original = MessageCiphertextEnvelope(
            senderPeerId = "sender",
            recipientPeerId = "recipient",
            ciphertext = ByteArray(0),
        )

        val decoded = MessageCiphertextEnvelope.decode(original.encode())

        assertEquals(0, decoded.ciphertext.size)
    }

    @Test
    fun `round trip works with empty peer id strings`() {
        val original = MessageCiphertextEnvelope(senderPeerId = "", recipientPeerId = "", ciphertext = randomBytes(32))

        val decoded = MessageCiphertextEnvelope.decode(original.encode())

        assertEquals("", decoded.senderPeerId)
        assertEquals("", decoded.recipientPeerId)
        assertTrue(original.ciphertext.contentEquals(decoded.ciphertext))
    }

    @Test
    fun `round trip works with a large ciphertext payload`() {
        val original = MessageCiphertextEnvelope(
            senderPeerId = "s",
            recipientPeerId = "r",
            ciphertext = randomBytes(65_536),
        )

        val decoded = MessageCiphertextEnvelope.decode(original.encode())

        assertTrue(original.ciphertext.contentEquals(decoded.ciphertext))
    }

    @Test
    fun `encoded size accounts for both length-prefixed peer ids plus ciphertext`() {
        val envelope = MessageCiphertextEnvelope(
            senderPeerId = "abcdef",
            recipientPeerId = "0123456789",
            ciphertext = randomBytes(40),
        )
        val expectedSize = 4 + "abcdef".toByteArray(Charsets.UTF_8).size +
            4 + "0123456789".toByteArray(Charsets.UTF_8).size + 40
        assertEquals(expectedSize, envelope.encode().size)
    }

    @Test
    fun `decoding empty bytes throws`() {
        assertFailsWith<MessageCiphertextEnvelopeDecodeException> { MessageCiphertextEnvelope.decode(ByteArray(0)) }
    }

    @Test
    fun `decoding bytes shorter than the senderPeerId length prefix throws`() {
        assertFailsWith<MessageCiphertextEnvelopeDecodeException> { MessageCiphertextEnvelope.decode(ByteArray(3)) }
    }

    @Test
    fun `decoding an envelope whose declared senderPeerId length exceeds available bytes throws`() {
        val full = MessageCiphertextEnvelope(senderPeerId = "abc", recipientPeerId = "d", ciphertext = randomBytes(10)).encode()
        val truncated = full.copyOf(4 + 1)

        assertFailsWith<MessageCiphertextEnvelopeDecodeException> { MessageCiphertextEnvelope.decode(truncated) }
    }

    @Test
    fun `decoding an envelope missing the recipientPeerId length prefix throws`() {
        val full = MessageCiphertextEnvelope(senderPeerId = "abc", recipientPeerId = "def", ciphertext = randomBytes(10)).encode()
        // Keep exactly through senderPeerId, drop everything else (no room for the
        // recipientPeerId length prefix at all).
        val senderIdBytes = "abc".toByteArray(Charsets.UTF_8)
        val truncated = full.copyOf(4 + senderIdBytes.size)

        assertFailsWith<MessageCiphertextEnvelopeDecodeException> { MessageCiphertextEnvelope.decode(truncated) }
    }

    @Test
    fun `decoding an envelope whose declared recipientPeerId length exceeds available bytes throws`() {
        val full = MessageCiphertextEnvelope(senderPeerId = "abc", recipientPeerId = "defgh", ciphertext = randomBytes(10)).encode()
        val senderIdBytes = "abc".toByteArray(Charsets.UTF_8)
        // Keep through the recipientPeerId length prefix, but cut off before its bytes.
        val truncated = full.copyOf(4 + senderIdBytes.size + 4 + 1)

        assertFailsWith<MessageCiphertextEnvelopeDecodeException> { MessageCiphertextEnvelope.decode(truncated) }
    }
}
