package com.hop.repository

import com.hop.crypto.DecayKeyStore
import com.hop.data.PostDao
import com.hop.data.PostEntity
import com.hop.protocol.EncryptedFrameCodec
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
) {

    /** Result of [decrypt] -- deliberately only these two cases, both backed by
     * real [EncryptedFrameCodec.decryptFromStore] outcomes. No `Error`/
     * exception-wrapping case: that function already returns null cleanly for
     * the one real failure mode it has (an expired/missing decay key), so
     * there's no other failure mode to invent a case for here.
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
         * The decay key for this post has expired (or was never present) in
         * [DecayKeyStore] -- ADR 0003's decay-by-key-expiry primitive actually
         * biting. The ciphertext this post's [PostEntity.encryptedPayloadFilePath]
         * points at is still on disk, untouched; it's just permanently opaque
         * to this store from this point on. See [DecayKeyStore]'s own "Limit"
         * doc: this binds the stock client, it doesn't erase the ciphertext.
         */
        data object Decayed : DecryptResult
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
     * the still-live content-encryption key from [decayKeyStore] keyed by
     * [PostEntity.clipHash].
     *
     * Runs on [Dispatchers.IO]: this does blocking file I/O plus
     * [DecayKeyStore]'s synchronous (non-suspend, potentially Room-blocking)
     * `retrieve` call -- callers must not assume this is safe on the main
     * thread just because the signature is `suspend`.
     */
    open suspend fun decrypt(post: PostEntity): DecryptResult = withContext(Dispatchers.IO) {
        val ciphertext = File(post.encryptedPayloadFilePath).readBytes()
        val plaintext = EncryptedFrameCodec.decryptFromStore(
            clipHash = post.clipHash.hexToByteArray(),
            encryptedPayload = ciphertext,
            decayKeyStore = decayKeyStore,
        )
        if (plaintext == null) DecryptResult.Decayed else DecryptResult.Decrypted(plaintext)
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
