package com.hop.crypto

import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-GCM content encryption for post payloads (ADR 0003's decay/reach-tier
 * primitive — the "clip payloads are encrypted at rest" half). This is standard
 * JVM crypto (`javax.crypto`), deliberately independent of libsignal — decay/
 * reach-tier key-wrapping is a different problem from the 1:1/group messaging
 * ratchet in [DoubleRatchetSession] and doesn't need Double Ratchet's forward
 * secrecy machinery, just a per-post symmetric key that can be made
 * unrecoverable on schedule (see [DecayKeyStore]).
 *
 * Reminder of the actual guarantee (ADR 0003 Limits): this makes stale/out-of-
 * tier content *opaque* to a client that doesn't have a live unwrap key. It does
 * not remove the ciphertext from the DHT, and it does not stop a client that
 * captured the key while it was live from continuing to decrypt after the decay
 * window closes. Decay here means "the key becomes unrecoverable from an honest
 * client's [DecayKeyStore]," not "the content is destroyed everywhere."
 */
object ContentEncryption {

    private const val ALGORITHM = "AES"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val KEY_SIZE_BITS = 256
    private const val GCM_IV_SIZE_BYTES = 12
    private const val GCM_TAG_SIZE_BITS = 128

    private val secureRandom = SecureRandom()

    /** Generates a fresh random per-post content-encryption key (CEK). */
    fun generateKey(): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(ALGORITHM)
        keyGenerator.init(KEY_SIZE_BITS, secureRandom)
        return keyGenerator.generateKey()
    }

    /**
     * Encrypts [plaintext] under [key]. Returns `iv || ciphertext-with-appended-tag`
     * as a single blob — the IV is not secret and must travel with the ciphertext
     * for decryption, so bundling it avoids a separate wire field for this
     * standalone content-encryption primitive.
     */
    fun encrypt(key: SecretKey, plaintext: ByteArray): ByteArray {
        val iv = ByteArray(GCM_IV_SIZE_BYTES).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_SIZE_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext)
        return iv + ciphertext
    }

    /**
     * Decrypts a blob produced by [encrypt]. Throws [AEADBadTagException] (via the
     * underlying `javax.crypto` implementation) if [key] is wrong or [blob] was
     * tampered with — GCM's tag check makes this fail closed, never silently
     * returning garbage plaintext.
     */
    fun decrypt(key: SecretKey, blob: ByteArray): ByteArray {
        require(blob.size > GCM_IV_SIZE_BYTES) { "Ciphertext blob too short to contain an IV" }
        val iv = blob.copyOfRange(0, GCM_IV_SIZE_BYTES)
        val ciphertext = blob.copyOfRange(GCM_IV_SIZE_BYTES, blob.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_SIZE_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    /** Reconstructs a [SecretKey] from raw bytes, e.g. after retrieval from [DecayKeyStore]. */
    fun keyFromBytes(bytes: ByteArray): SecretKey = SecretKeySpec(bytes, ALGORITHM)
}
