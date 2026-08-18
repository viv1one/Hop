package com.hop.protocol

import com.hop.crypto.ContentEncryption
import com.hop.crypto.DecayKeyStore
import java.time.Duration

/**
 * Encrypt/decrypt orchestration between plaintext post content and the
 * version-2 [Frame] wire envelope. This is the one place in `protocol/` that
 * depends on `crypto/` — [Frame] itself stays a pure wire envelope with no
 * `crypto/` dependency, per ADR 0001's one-way rule (`protocol/` may depend
 * on `crypto/`, never the reverse).
 *
 * Phase 1 scope: Locality tier is the only reach tier this phase has (no
 * DHT until Phase 4), and per ADR 0003, at Locality tier "the ciphertext and
 * key both stay on local mesh only" — so [encode] always sets
 * `keyIncluded = true` and inlines a fresh content-encryption key (CEK) in
 * the frame. Town/City/Country's DHT-gated key-distribution
 * (`keyIncluded = false`, key delivered separately once a tier-membership
 * proof exists) is Phase 4 scope and deliberately not built here — that's
 * the entire reason [Frame] carries `keyIncluded` as a flag now rather than
 * only gaining it in Phase 4's wire-format bump.
 *
 * Limit (state plainly, per ADR 0003): none of this stops a determined
 * custom client from ignoring `DecayKeyStore` expiry and hanging onto a CEK
 * it captured while the key was live. This binds the stock/reference client
 * — it raises the cost of casual post-decay access, it does not make decay
 * cryptographically unbreakable against a modified client.
 */
object EncryptedFrameCodec {

    /**
     * Thrown when a frame cannot be decrypted: no [Frame.keyIncluded] key
     * present at receive-time (Phase 1 has no other key source), or the key
     * looked up from a [DecayKeyStore] has decayed / was never stored.
     */
    class EncryptedFrameDecodeException(message: String) : Exception(message)

    /**
     * Encrypts [plaintext] under a freshly generated content-encryption key
     * (CEK) and builds a version-2 [Frame] with that CEK inlined
     * (`keyIncluded = true`) plus the given decay/relay metadata. Returns the
     * encoded frame bytes ready to hand to the WiFi Direct transfer layer.
     *
     * [clipHash] must be the hash of [plaintext] (content-addressed identity
     * of the post), not of the resulting ciphertext — see
     * /protocol/WIRE_FORMAT.md. This function does not compute [clipHash]
     * itself since hashing policy (algorithm, what exactly gets hashed for a
     * multi-part post) lives with the caller, not the encryption codec.
     */
    fun encode(
        plaintext: ByteArray,
        clipHash: ByteArray,
        senderDeviceId: ByteArray,
        contentType: ContentType,
        hopCount: Int,
        originatedAtMs: Long,
        ttlSeconds: Long,
        reachTier: ReachTier,
        dontRelay: Boolean,
    ): ByteArray {
        val cek = ContentEncryption.generateKey()
        val ciphertext = ContentEncryption.encrypt(cek, plaintext)

        val frame = Frame(
            clipHash = clipHash,
            senderDeviceId = senderDeviceId,
            contentType = contentType,
            hopCount = hopCount,
            originatedAtMs = originatedAtMs,
            ttlSeconds = ttlSeconds,
            reachTier = reachTier,
            dontRelay = dontRelay,
            keyIncluded = true,
            contentEncryptionKey = cek.encoded,
            payload = ciphertext,
        )
        return frame.encode()
    }

    /**
     * Decodes [bytes] into a [Frame], and if it carries an inline key
     * (`keyIncluded`), stores that key into [decayKeyStore] keyed by the
     * hex-encoded `clipHash` with a decay window of `frame.ttlSeconds` —
     * this is the receive-time "now I have a live key" moment described in
     * ADR 0003. Then decrypts and returns the plaintext payload.
     *
     * This always succeeds at getting a key when `keyIncluded` is true — the
     * key just arrived, it cannot already be decayed. Decay only bites on a
     * *later* re-decrypt attempt against the store; see [decryptFromStore].
     *
     * Throws [EncryptedFrameDecodeException] if `keyIncluded` is false:
     * Phase 1 has no other key source (that's Phase 4's DHT-gated
     * key-distribution path), so there is nothing to decrypt with.
     */
    fun decode(bytes: ByteArray, decayKeyStore: DecayKeyStore): ByteArray {
        val frame = Frame.decode(bytes)

        if (!frame.keyIncluded) {
            throw EncryptedFrameDecodeException(
                "Frame has no inline content-encryption key (keyIncluded=false); " +
                    "Phase 1 has no other key source to decrypt this payload with"
            )
        }

        val contentId = frame.clipHash.toHexString()
        decayKeyStore.store(
            contentId = contentId,
            wrappedCek = frame.contentEncryptionKey,
            decayWindow = Duration.ofSeconds(frame.ttlSeconds),
        )

        val cek = ContentEncryption.keyFromBytes(frame.contentEncryptionKey)
        return ContentEncryption.decrypt(cek, frame.payload)
    }

    /**
     * Re-decrypts [encryptedPayload] (a `Frame.payload` ciphertext blob
     * previously extracted and cached, e.g. a post reopened from local
     * storage after time has passed) by looking up its CEK in
     * [decayKeyStore] under the hex-encoded [clipHash].
     *
     * Returns null if the key has decayed (expired and removed from the
     * store per [DecayKeyStore.retrieve]) or was never stored under this
     * content id — this is where ADR 0003's decay actually bites, distinct
     * from [decode]'s receive-time path which always has a fresh key.
     */
    fun decryptFromStore(
        clipHash: ByteArray,
        encryptedPayload: ByteArray,
        decayKeyStore: DecayKeyStore,
    ): ByteArray? {
        val wrappedCek = decayKeyStore.retrieve(clipHash.toHexString()) ?: return null
        val cek = ContentEncryption.keyFromBytes(wrappedCek)
        return ContentEncryption.decrypt(cek, encryptedPayload)
    }

    private fun ByteArray.toHexString(): String =
        joinToString(separator = "") { byte -> "%02x".format(byte) }
}
