package com.hop.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android-Keystore-backed AES-256-GCM wrap/unwrap for this device's own
 * long-term Double Ratchet identity key pair -- the one field
 * ([IdentityKeyPairEntity.identityKeyPairBytes]) named as needing this
 * treatment (see that entity's doc for why: identity-key compromise lets an
 * attacker forge this device's identity in every *future* session, a
 * materially worse blast radius than any other Room-persisted Signal
 * material). Deliberately scoped to exactly that one field -- remote
 * identity keys ([RemoteIdentityEntity], public by nature), prekeys, signed
 * prekeys, Kyber prekeys, and session records are all untouched.
 *
 * This is the first use of `android.security.keystore`/`AndroidKeyStore` in
 * this codebase. It lives in `com.hop.data` (`mobile/android/`), not
 * `crypto/`, because `crypto/` is a plain-JVM module (its tests run as plain
 * JUnit, not Android-instrumented) -- `java.security.KeyStore` type
 * `"AndroidKeyStore"` and `KeyGenParameterSpec` are Android-framework-only
 * APIs unavailable off-device, so putting this there would break `crypto/`'s
 * build/tests (ADR 0001: `protocol/`/`mobile/` may depend on `crypto/`,
 * never the reverse -- this class has no such dependency either way, it is
 * pure platform plumbing consumed only by [RoomSignalProtocolStore]).
 *
 * The wrapping key itself never leaves the Keystore (hardware-backed where
 * the device supports it) -- only ciphertext produced/consumed via
 * [Cipher] crosses into this process's heap, never the raw AES key bytes.
 * That's the actual security property this class buys: a forensic
 * extraction of the on-disk `hop.db` file alone (SQLite bytes, e.g. via a
 * backup or a rooted-device file pull) yields ciphertext the attacker cannot
 * unwrap without also compromising the Keystore itself. It does **not**
 * protect against a fully compromised, currently-running app process --
 * that process can simply ask the Keystore to decrypt via this same class,
 * same as the legitimate app would (see [RoomSignalProtocolStoreTest]'s
 * simulated-compromise test for how that distinction plays out in practice).
 * State that limit plainly wherever this class is referenced rather than
 * implying "Keystore-wrapped" means "immune to a compromised device."
 *
 * Key generated lazily on first use (`getOrCreateWrappingKey`), matching
 * this codebase's established convention (see [IdentityKeyPairEntity]'s own
 * "lazy creation, not eager at DB-creation time" doc) -- not eagerly at
 * [com.hop.app.AppContainer] construction time. `setUserAuthenticationRequired(false)`:
 * this is a no-accounts, no-lock-screen-gate app (see CLAUDE.md's "no
 * accounts" constraint) -- there is no login/biometric-prompt concept to
 * require here, and requiring one would make every background message
 * decrypt (which needs [RoomSignalProtocolStore.getIdentityKeyPair]) fail
 * whenever the device is locked.
 *
 * AES/GCM/NoPadding, 256-bit key, 128-bit auth tag, one randomly-generated
 * 12-byte IV per [encrypt] call (Android's Keystore-backed `Cipher`
 * generates this itself when initialized for `ENCRYPT_MODE` without an
 * explicit `GCMParameterSpec` -- never reused, never derived) -- the
 * standard, widely-reviewed AES-GCM parameterization, and the only mode
 * `KeyProperties.BLOCK_MODE_GCM` supports on Android Keystore-backed keys
 * regardless. [encrypt]'s output is `iv (12 bytes) || ciphertext+tag`, a
 * single opaque blob so callers ([RoomSignalProtocolStore]) can keep storing
 * it in the exact same `identityKeyPairBytes` `ByteArray` column with no
 * schema change ([HopDatabase] already uses
 * `fallbackToDestructiveMigration()` and has no real installed users yet, so
 * this also needed no migration either way).
 */
class IdentityKeyPairKeystoreCipher {

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE_PROVIDER).apply { load(null) }

    /**
     * Returns the existing Keystore-resident wrapping key if one was already
     * generated (by this or a prior app process -- Keystore entries survive
     * process death, that's the entire point), otherwise generates a fresh
     * one under [KEY_ALIAS]. `@Synchronized` guards the same
     * check-then-generate race [RoomSignalProtocolStore.ensureOwnIdentity]
     * already guards against for the identity key pair itself: two
     * concurrent first-callers must not each generate a competing Keystore
     * entry under the same alias.
     */
    @Synchronized
    private fun getOrCreateWrappingKey(): SecretKey {
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE_PROVIDER)
        keyGenerator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .setUserAuthenticationRequired(false)
                .build()
        )
        return keyGenerator.generateKey()
    }

    /** Encrypts [plaintext] (the identity key pair's `.serialize()` bytes) into `iv || ciphertext+tag`. */
    fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateWrappingKey())
        // GCM-mode Keystore-backed Cipher.init(ENCRYPT_MODE, key) generates a
        // fresh random IV itself when no GCMParameterSpec is supplied --
        // read back afterward via cipher.iv rather than generated by hand.
        val iv = cipher.iv
        require(iv.size == GCM_IV_LENGTH_BYTES) { "unexpected GCM IV length: ${iv.size}" }
        return iv + cipher.doFinal(plaintext)
    }

    /**
     * Decrypts [wrapped] (this class's own `iv || ciphertext+tag` [encrypt]
     * output) back to the identity key pair's plain `.serialize()` bytes.
     * Throws (via the underlying `Cipher.doFinal`, e.g.
     * `AEADBadTagException`) if [wrapped] was tampered with, truncated, or
     * was never produced by [encrypt] against this same Keystore key --
     * deliberately not caught/swallowed here, matching this codebase's
     * "fail loud rather than silently do the wrong thing" convention (see
     * [RoomSignalProtocolStore]'s `SenderKeyStore` methods for the same
     * philosophy elsewhere in this file's neighborhood).
     */
    fun decrypt(wrapped: ByteArray): ByteArray {
        require(wrapped.size > GCM_IV_LENGTH_BYTES) { "wrapped identity key pair bytes too short to contain an IV" }
        val iv = wrapped.copyOfRange(0, GCM_IV_LENGTH_BYTES)
        val ciphertext = wrapped.copyOfRange(GCM_IV_LENGTH_BYTES, wrapped.size)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateWrappingKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    companion object {
        private const val ANDROID_KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "hop_own_identity_key_pair_wrap"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_SIZE_BITS = 256
        private const val GCM_IV_LENGTH_BYTES = 12
        private const val GCM_TAG_LENGTH_BITS = 128
    }
}
