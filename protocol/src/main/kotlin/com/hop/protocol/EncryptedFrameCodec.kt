package com.hop.protocol

import com.hop.crypto.ContentEncryption
import com.hop.crypto.DecayKeyStore
import java.time.Duration

/**
 * Encrypt/decrypt orchestration between plaintext post content and the
 * version-3 [Frame] wire envelope. This is the one place in `protocol/` that
 * depends on `crypto/` — [Frame] itself stays a pure wire envelope with no
 * `crypto/` dependency, per ADR 0001's one-way rule (`protocol/` may depend
 * on `crypto/`, never the reverse).
 *
 * Per ADR 0003, at Locality tier "the ciphertext and key both stay on local
 * mesh only" — so [encode] sets `keyIncluded = true` and inlines a fresh
 * content-encryption key (CEK) in the frame for [ReachTier.LOCALITY] only.
 * For Town/City/Country, [encode] sets `keyIncluded = false`: the CEK is
 * *not* inlined on the wire (`Frame.encode()` zero-fills that field), and is
 * instead distributed separately, gated by a tier-membership proof, via
 * [ReachTierKeyDistribution]/[TierKeyRequestEnvelope]/[TierKeyResponseEnvelope]
 * once a peer holding the post is asked for it. This is the key-distribution
 * mechanism [TierClaimVerifier]'s and this file's own prior docs pointed at
 * as "a later slice" -- that slice is this one.
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
     * present at receive-time (this codec's [decode] has no other key
     * source -- Town/City/Country's separate key-distribution path is never
     * exercised by [decode], only by [decryptFromStore] once a key has
     * separately arrived and been stored), or the key looked up from a
     * [DecayKeyStore] has decayed / was never stored.
     */
    class EncryptedFrameDecodeException(message: String) : Exception(message)

    /**
     * The result of [encode]: the encoded frame bytes ready to hand to the
     * WiFi Direct transfer layer, plus the real raw content-encryption key
     * (CEK) bytes -- returned directly rather than making callers decode
     * [encoded] back to recover it. That decode-it-back trick (this codec's
     * pre-Slice-9 shape) only worked because [encode] used to *always* inline
     * the CEK on the wire; now that non-Locality tiers set `keyIncluded =
     * false` (`Frame.encode()` zero-fills the wire copy in that case), the
     * poster's own device still needs the real key for its own later
     * re-decrypt -- this is the only place that real key is available.
     */
    data class EncodeResult(val encoded: ByteArray, val contentEncryptionKey: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is EncodeResult) return false
            return encoded.contentEquals(other.encoded) && contentEncryptionKey.contentEquals(other.contentEncryptionKey)
        }

        override fun hashCode(): Int = 31 * encoded.contentHashCode() + contentEncryptionKey.contentHashCode()
    }

    /**
     * Encrypts [plaintext] under a freshly generated content-encryption key
     * (CEK) and builds a version-3 [Frame] with the given decay/relay
     * metadata. [keyIncluded] is set from [reachTier] itself, not passed in
     * by the caller: `true` (CEK inlined on the wire) only for
     * [ReachTier.LOCALITY], `false` for Town/City/Country -- per ADR 0003,
     * "reach-tier limits above Locality are enforced by per-tier
     * key-wrapping," which requires the key to travel separately, not inline.
     *
     * [clipHash] must be the hash of [plaintext] (content-addressed identity
     * of the post), not of the resulting ciphertext — see
     * /protocol/WIRE_FORMAT.md. This function does not compute [clipHash]
     * itself since hashing policy (algorithm, what exactly gets hashed for a
     * multi-part post) lives with the caller, not the encryption codec.
     *
     * [originGeohashPrefix] is ignored (and never encoded -- see
     * [Frame.originGeohashPrefix]'s own doc) for [ReachTier.LOCALITY];
     * required in practice for every other tier, since it's what lets a
     * later holder of this post answer a [TierKeyRequestEnvelope] against
     * the right cell. This function does not validate that non-Locality
     * callers actually supplied a non-empty prefix -- that policy lives with
     * the caller (`PostComposerViewModel.post`), which has the location
     * source this codec deliberately does not.
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
        originGeohashPrefix: String = "",
    ): EncodeResult {
        val cek = ContentEncryption.generateKey()
        val ciphertext = ContentEncryption.encrypt(cek, plaintext)
        val keyIncluded = reachTier == ReachTier.LOCALITY

        val frame = Frame(
            clipHash = clipHash,
            senderDeviceId = senderDeviceId,
            contentType = contentType,
            hopCount = hopCount,
            originatedAtMs = originatedAtMs,
            ttlSeconds = ttlSeconds,
            reachTier = reachTier,
            dontRelay = dontRelay,
            keyIncluded = keyIncluded,
            contentEncryptionKey = cek.encoded,
            originGeohashPrefix = if (reachTier == ReachTier.LOCALITY) "" else originGeohashPrefix,
            payload = ciphertext,
        )
        return EncodeResult(encoded = frame.encode(), contentEncryptionKey = cek.encoded)
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
     * [decayKeyStore].
     *
     * [reachTier] decides the lookup key, mirroring how the key was stored
     * in the first place (see [PostComposerViewModel.post]'s own storage
     * choice and [ReceivedFrameStore.handle]'s receive-path storage choice,
     * which must both agree with this lookup or a legitimate re-decrypt
     * would silently miss): for [ReachTier.LOCALITY], the plain hex-encoded
     * [clipHash] (unchanged pre-Slice-9 behavior); for Town/City/Country,
     * [ReachTierKeyDistribution.decayKeyStorageKey] -- the same per-tier
     * composition [ReachTierKeyDistribution.releaseKeyFor] uses to look up
     * what it hands out to a peer presenting a valid tier claim.
     *
     * Returns null if the key has decayed (expired and removed from the
     * store per [DecayKeyStore.retrieve]) or was never stored under this
     * content id -- for a non-Locality post, this is also the ordinary
     * "no one has asked for (or been granted) this tier's key yet" case,
     * not just decay; see this codec's own class doc and ADR 0003's
     * "requesting side" follow-up scope note. This is where ADR 0003's
     * decay actually bites, distinct from [decode]'s receive-time path
     * which always has a fresh key.
     */
    fun decryptFromStore(
        clipHash: ByteArray,
        encryptedPayload: ByteArray,
        decayKeyStore: DecayKeyStore,
        reachTier: ReachTier = ReachTier.LOCALITY,
    ): ByteArray? {
        val contentId = clipHash.toHexString()
        val storageKey = if (reachTier == ReachTier.LOCALITY) {
            contentId
        } else {
            ReachTierKeyDistribution.decayKeyStorageKey(contentId, reachTier)
        }
        val wrappedCek = decayKeyStore.retrieve(storageKey) ?: return null
        val cek = ContentEncryption.keyFromBytes(wrappedCek)
        return ContentEncryption.decrypt(cek, encryptedPayload)
    }

    private fun ByteArray.toHexString(): String =
        joinToString(separator = "") { byte -> "%02x".format(byte) }
}
