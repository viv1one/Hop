package com.hop.crypto

import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class ContentEncryptionTest {

    private val random = SecureRandom()

    private fun randomBytes(size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }

    @Test
    fun `round trip recovers the original plaintext`() {
        val key = ContentEncryption.generateKey()
        val plaintext = randomBytes(4096) // roughly a small-photo-sized chunk

        val ciphertext = ContentEncryption.encrypt(key, plaintext)
        val decrypted = ContentEncryption.decrypt(key, ciphertext)

        assertContentEquals(plaintext, decrypted)
    }

    @Test
    fun `round trip works with empty plaintext`() {
        val key = ContentEncryption.generateKey()
        val ciphertext = ContentEncryption.encrypt(key, ByteArray(0))
        assertContentEquals(ByteArray(0), ContentEncryption.decrypt(key, ciphertext))
    }

    @Test
    fun `encrypting the same plaintext twice produces different ciphertext`() {
        // Fresh random IV per call -- content-addressed identical plaintexts
        // (e.g. two people posting the same viral clip) must not produce
        // identical ciphertext, or clip identity would leak key material reuse.
        val key = ContentEncryption.generateKey()
        val plaintext = randomBytes(256)

        val first = ContentEncryption.encrypt(key, plaintext)
        val second = ContentEncryption.encrypt(key, plaintext)

        assertNotEquals(first.toList(), second.toList())
    }

    @Test
    fun `decrypting with the wrong key fails closed instead of returning garbage`() {
        val key = ContentEncryption.generateKey()
        val wrongKey = ContentEncryption.generateKey()
        val ciphertext = ContentEncryption.encrypt(key, "hop content".toByteArray())

        assertFailsWith<AEADBadTagException> { ContentEncryption.decrypt(wrongKey, ciphertext) }
    }

    @Test
    fun `decrypting tampered ciphertext fails closed`() {
        val key = ContentEncryption.generateKey()
        val ciphertext = ContentEncryption.encrypt(key, "hop content".toByteArray())
        ciphertext[ciphertext.size - 1] = (ciphertext[ciphertext.size - 1] + 1).toByte()

        assertFailsWith<AEADBadTagException> { ContentEncryption.decrypt(key, ciphertext) }
    }

    @Test
    fun `keyFromBytes reconstructs a usable key`() {
        val key = ContentEncryption.generateKey()
        val plaintext = "round trip via raw bytes".toByteArray()
        val ciphertext = ContentEncryption.encrypt(key, plaintext)

        val reconstructed = ContentEncryption.keyFromBytes(key.encoded)
        assertContentEquals(plaintext, ContentEncryption.decrypt(reconstructed, ciphertext))
    }
}
