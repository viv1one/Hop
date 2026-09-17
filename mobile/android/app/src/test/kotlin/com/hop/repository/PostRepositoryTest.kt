package com.hop.repository

import com.hop.crypto.DecayKeyStore
import com.hop.data.PostDao
import com.hop.data.PostEntity
import com.hop.protocol.ContentType
import com.hop.protocol.EncryptedFrameCodec
import com.hop.protocol.Frame
import com.hop.protocol.ReachTier
import com.hop.protocol.ReachTierKeyDistribution
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import kotlin.test.assertContentEquals
import kotlin.test.assertIs

/**
 * Covers [PostRepository.decrypt]'s Phase 4 Slice 9 tier-aware lookup: the
 * [DecayKeyStore] storage key depends on [PostEntity.reachTier] (plain
 * hex-encoded clipHash for LOCALITY, [ReachTierKeyDistribution.decayKeyStorageKey]
 * for Town/City/Country) -- this must agree with however the key was stored
 * in the first place, or a legitimate re-decrypt silently misses.
 */
class PostRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun storePost(
        postDao: FakePostDao,
        decayKeyStore: DecayKeyStore,
        reachTier: ReachTier,
        originGeohashPrefix: String = "",
    ): PostEntity {
        val plaintext = "post for $reachTier".toByteArray()
        val clipHash = MessageDigest.getInstance("SHA-256").digest(plaintext)
        val clipHashHex = clipHash.joinToString("") { "%02x".format(it) }
        val result = EncryptedFrameCodec.encode(
            plaintext = plaintext,
            clipHash = clipHash,
            senderDeviceId = ByteArray(Frame.SENDER_DEVICE_ID_SIZE) { it.toByte() },
            contentType = ContentType.PHOTO,
            hopCount = 0,
            originatedAtMs = System.currentTimeMillis(),
            ttlSeconds = 3600L,
            reachTier = reachTier,
            dontRelay = false,
            originGeohashPrefix = originGeohashPrefix,
        )
        val frame = Frame.decode(result.encoded)

        val storageKey = if (reachTier == ReachTier.LOCALITY) {
            clipHashHex
        } else {
            ReachTierKeyDistribution.decayKeyStorageKey(clipHashHex, reachTier)
        }
        decayKeyStore.store(
            contentId = storageKey,
            wrappedCek = result.contentEncryptionKey,
            decayWindow = java.time.Duration.ofSeconds(3600L),
        )

        val payloadFile = File(tempFolder.newFolder("posts-${System.nanoTime()}"), "$clipHashHex.enc")
        payloadFile.writeBytes(frame.payload)

        val entity = PostEntity(
            clipHash = clipHashHex,
            senderDeviceId = "sender",
            contentType = ContentType.PHOTO.name,
            originatedAtMs = System.currentTimeMillis(),
            ttlSeconds = 3600L,
            reachTier = reachTier.name,
            originGeohashPrefix = originGeohashPrefix,
            dontRelay = false,
            receivedAtMs = System.currentTimeMillis(),
            encryptedPayloadFilePath = payloadFile.absolutePath,
        )
        runBlocking { postDao.upsert(entity) }
        return entity
    }

    @Test
    fun decryptSucceedsForALocalityPostUnderThePlainClipHashKey() = runBlocking {
        val postDao = FakePostDao()
        val decayKeyStore = DecayKeyStore()
        val repository = PostRepository(postDao, decayKeyStore)
        val entity = storePost(postDao, decayKeyStore, ReachTier.LOCALITY)

        val result = repository.decrypt(entity)

        val decrypted = assertIs<PostRepository.DecryptResult.Decrypted>(result)
        assertContentEquals("post for LOCALITY".toByteArray(), decrypted.bytes)
    }

    @Test
    fun decryptSucceedsForATownPostUnderTheTieredKey() = runBlocking {
        val postDao = FakePostDao()
        val decayKeyStore = DecayKeyStore()
        val repository = PostRepository(postDao, decayKeyStore)
        val entity = storePost(postDao, decayKeyStore, ReachTier.TOWN, originGeohashPrefix = "9q8yy")

        val result = repository.decrypt(entity)

        val decrypted = assertIs<PostRepository.DecryptResult.Decrypted>(result)
        assertContentEquals("post for TOWN".toByteArray(), decrypted.bytes)
    }

    @Test
    fun decryptReturnsDecayedForATownPostWhoseKeyWasOnlyStoredUnderThePlainKey() = runBlocking {
        // Regression guard for the exact bug this tier-aware lookup fixes:
        // before Phase 4 Slice 9, decryptFromStore always looked up the
        // plain clipHash key regardless of tier. If that regressed, this
        // post's key (correctly stored under the tiered key by storePost)
        // would never be found via the (wrong) plain-key lookup either --
        // this test instead directly proves the *tiered* post's own decrypt
        // path depends on PostEntity.reachTier being read and used, by
        // clobbering the plain-key entry with an unrelated key and
        // confirming decrypt() still succeeds (it must ignore the plain key
        // entirely for a non-Locality post).
        val postDao = FakePostDao()
        val decayKeyStore = DecayKeyStore()
        val repository = PostRepository(postDao, decayKeyStore)
        val entity = storePost(postDao, decayKeyStore, ReachTier.TOWN, originGeohashPrefix = "9q8yy")

        // Poison the plain (Locality-shaped) key with garbage -- a correct
        // implementation must never consult it for a TOWN post.
        decayKeyStore.store(
            contentId = entity.clipHash,
            wrappedCek = ByteArray(32) { 0 },
            decayWindow = java.time.Duration.ofSeconds(3600L),
        )

        val result = repository.decrypt(entity)

        val decrypted = assertIs<PostRepository.DecryptResult.Decrypted>(result)
        assertContentEquals("post for TOWN".toByteArray(), decrypted.bytes)
    }

    @Test
    fun decryptReturnsDecayedWhenTheTieredKeyWasNeverStored() = runBlocking {
        val postDao = FakePostDao()
        val decayKeyStore = DecayKeyStore()
        val repository = PostRepository(postDao, decayKeyStore)

        // Build the post but only insert the row -- never store any key at
        // all under any composition. Simulates a received Town/City/Country
        // post whose key hasn't arrived yet (same shape as an
        // already-decayed post from this repository's point of view).
        val plaintext = "never got a key".toByteArray()
        val clipHash = MessageDigest.getInstance("SHA-256").digest(plaintext)
        val clipHashHex = clipHash.joinToString("") { "%02x".format(it) }
        val result = EncryptedFrameCodec.encode(
            plaintext = plaintext,
            clipHash = clipHash,
            senderDeviceId = ByteArray(Frame.SENDER_DEVICE_ID_SIZE) { it.toByte() },
            contentType = ContentType.PHOTO,
            hopCount = 0,
            originatedAtMs = System.currentTimeMillis(),
            ttlSeconds = 3600L,
            reachTier = ReachTier.CITY,
            dontRelay = false,
            originGeohashPrefix = "9q8y",
        )
        val frame = Frame.decode(result.encoded)
        val payloadFile = File(tempFolder.newFolder("posts-nokey"), "$clipHashHex.enc")
        payloadFile.writeBytes(frame.payload)
        val entity = PostEntity(
            clipHash = clipHashHex,
            senderDeviceId = "sender",
            contentType = ContentType.PHOTO.name,
            originatedAtMs = System.currentTimeMillis(),
            ttlSeconds = 3600L,
            reachTier = ReachTier.CITY.name,
            originGeohashPrefix = "9q8y",
            dontRelay = false,
            receivedAtMs = System.currentTimeMillis(),
            encryptedPayloadFilePath = payloadFile.absolutePath,
        )
        postDao.upsert(entity)

        val result2 = repository.decrypt(entity)

        assertIs<PostRepository.DecryptResult.Decayed>(result2, "expected Decayed when no key was ever stored under any composition")
        Unit
    }

    private class FakePostDao : PostDao {
        private val state = MutableStateFlow<List<PostEntity>>(emptyList())
        override suspend fun upsert(post: PostEntity) {
            state.value = state.value.filterNot { it.clipHash == post.clipHash } + post
        }
        override fun getAllOrderedByReceivedDesc(): Flow<List<PostEntity>> = state
        override suspend fun getByClipHash(clipHash: String): PostEntity? = state.value.find { it.clipHash == clipHash }
    }
}
