package com.hop.repository

import com.hop.crypto.DecayKeyStore
import com.hop.data.PostDao
import com.hop.data.PostEntity
import com.hop.protocol.EncryptedFrameCodec
import com.hop.protocol.ReachTier
import com.hop.protocol.RelayPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Wraps [PostDao] plus the on-demand decrypt path
 * (`protocol/`'s `EncryptedFrameCodec.decryptFromStore`, backed by `crypto/`'s
 * [DecayKeyStore]) that turns a stored [PostEntity] (metadata + a pointer to
 * still-encrypted ciphertext on disk, see [PostEntity]'s own doc) into
 * viewable plaintext, or a clean "this has decayed" result.
 *
 * `open` (not `final`) so a JVM unit test can subclass and override
 * [observeAllPosts]/[decrypt] with an in-memory fake -- this repository has
 * real file I/O and a real `crypto/` dependency, neither of which a
 * `FeedViewModel` unit test should need to exercise for real. No mocking
 * library exists in this repo (hand-rolled fakes throughout, matching
 * `DecayKeyStorage`'s in-memory-vs-Room split), so this is the equivalent
 * pattern applied here.
 */
open class PostRepository(
    private val postDao: PostDao,
    private val decayKeyStore: DecayKeyStore,
    /**
     * Shared expiry math (`isExpired`/`expiresAtMs` against
     * [PostEntity.originatedAtMs]/[PostEntity.ttlSeconds]) used only to tell
     * [DecryptResult.AwaitingKey] apart from [DecryptResult.Decayed] below --
     * see that case's own doc. Defaulted (not required) so every existing
     * two-arg construction of this class across the app/tests keeps compiling
     * unchanged; [com.hop.app.AppContainer] doesn't thread in anything special
     * here since there's no tuning input for this policy yet (same posture as
     * [RelayRepository]/[DontRelayRepository]'s own default-shaped [RelayPolicy]s).
     */
    private val relayPolicy: RelayPolicy = RelayPolicy(),
) {

    /**
     * Result of [decrypt]. Three cases, per ADR 0003's own instruction to
     * "state precisely what's happening, don't overclaim": a post can fail to
     * decrypt for two meaningfully different reasons, and collapsing them
     * into one case would hide the one actionable signal
     * ([com.hop.app.feed.FeedViewModel.decrypt]'s cache-miss-triggers-a-request
     * behavior) that depends on telling them apart.
     */
    sealed interface DecryptResult {
        data class Decrypted(val bytes: ByteArray) : DecryptResult {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is Decrypted) return false
                return bytes.contentEquals(other.bytes)
            }

            override fun hashCode(): Int = bytes.contentHashCode()
        }

        /**
         * The post's own TTL has genuinely elapsed (`relayPolicy.isExpired`
         * against [PostEntity.originatedAtMs]/[PostEntity.ttlSeconds]), or (the
         * pre-Phase-4-Slice-10 case, unchanged) this is a [ReachTier.LOCALITY]
         * post whose plain-keyed [DecayKeyStore] lookup came back empty past
         * expiry -- ADR 0003's decay-by-key-expiry primitive actually biting.
         * The ciphertext this post's [PostEntity.encryptedPayloadFilePath]
         * points at is still on disk, untouched; it's just permanently opaque
         * to this store from this point on. See [DecayKeyStore]'s own "Limit"
         * doc: this binds the stock client, it doesn't erase the ciphertext.
         */
        data object Decayed : DecryptResult

        /**
         * A Town/City/Country post that has NOT decayed
         * (`!relayPolicy.isExpired(...)`) but whose tiered key was never found
         * in [DecayKeyStore] -- i.e. this device has simply never asked for
         * (or been granted) the key yet, distinct from [Decayed]'s "the window
         * closed." [com.hop.app.feed.FeedViewModel.decrypt] treats this as the
         * signal to fire a best-effort [com.hop.protocol.TierKeyRequestEnvelope]
         * broadcast; a future decrypt of the same post (after the user's next
         * manual refresh) may resolve to [Decrypted] if a key arrived in the
         * meantime, or may still be [AwaitingKey]/settle into [Decayed] once
         * the post's own TTL elapses. Never returned for [ReachTier.LOCALITY]
         * -- that tier's key is always either present (inlined at receive
         * time) or genuinely decayed, never "not yet requested" (ADR 0003:
         * Locality never touches the DHT/separate key-distribution path).
         */
        data object AwaitingKey : DecryptResult
    }

    open fun observeAllPosts(): Flow<List<PostEntity>> = postDao.getAllOrderedByReceivedDesc()

    open suspend fun getByClipHash(clipHash: String): PostEntity? = postDao.getByClipHash(clipHash)

    /**
     * Inserts (or replaces, per [PostDao.upsert]'s own conflict policy) [post].
     * Used by the post composer (self-post, no transport yet) and, once
     * `com.hop.transport` exists, the receive path.
     */
    open suspend fun insert(post: PostEntity) = postDao.upsert(post)

    /**
     * Reads the ciphertext blob at [PostEntity.encryptedPayloadFilePath] and
     * decrypts it via [EncryptedFrameCodec.decryptFromStore], which looks up
     * the still-live content-encryption key from [decayKeyStore] under a key
     * that depends on [PostEntity.reachTier]: the plain hex-encoded
     * [PostEntity.clipHash] for LOCALITY, or
     * [com.hop.protocol.ReachTierKeyDistribution.decayKeyStorageKey] for
     * Town/City/Country -- must agree with however the key was stored for
     * this post in the first place (see [PostComposerViewModel.post]'s own
     * storage choice for a self-authored post, or
     * [ReceivedFrameStore.handle]'s for a received one).
     *
     * Runs on [Dispatchers.IO]: this does blocking file I/O plus
     * [DecayKeyStore]'s synchronous (non-suspend, potentially Room-blocking)
     * `retrieve` call -- callers must not assume this is safe on the main
     * thread just because the signature is `suspend`.
     *
     * On a `null` [EncryptedFrameCodec.decryptFromStore] result, distinguishes
     * [DecryptResult.AwaitingKey] from [DecryptResult.Decayed] using
     * [relayPolicy]'s own expiry math against [post]'s
     * [PostEntity.originatedAtMs]/[PostEntity.ttlSeconds] -- see
     * [DecryptResult.AwaitingKey]'s own doc for exactly which combination of
     * `reachTier`/expiry yields which case.
     */
    open suspend fun decrypt(post: PostEntity): DecryptResult = withContext(Dispatchers.IO) {
        val ciphertext = File(post.encryptedPayloadFilePath).readBytes()
        val reachTier = ReachTier.valueOf(post.reachTier)
        val plaintext = EncryptedFrameCodec.decryptFromStore(
            clipHash = post.clipHash.hexToByteArray(),
            encryptedPayload = ciphertext,
            decayKeyStore = decayKeyStore,
            reachTier = reachTier,
        )
        when {
            plaintext != null -> DecryptResult.Decrypted(plaintext)
            reachTier != ReachTier.LOCALITY &&
                !relayPolicy.isExpired(post.originatedAtMs, post.ttlSeconds) -> DecryptResult.AwaitingKey
            else -> DecryptResult.Decayed
        }
    }
}

/** Decodes a lowercase hex string (as produced by `EncryptedFrameCodec`'s own
 * `"%02x"`-per-byte encoding) back into raw bytes. */
private fun String.hexToByteArray(): ByteArray {
    require(length % 2 == 0) { "hex string must have an even length, was $length: $this" }
    return ByteArray(length / 2) { i ->
        val start = i * 2
        substring(start, start + 2).toInt(16).toByte()
    }
}
