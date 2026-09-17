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
import java.time.Duration
import kotlin.test.assertContentEquals
import kotlin.test.assertIs

/**
 * Covers [PostRepository.decrypt]'s Phase 4 Slice 9 tier-aware lookup: the
 * [DecayKeyStore] storage key depends on [PostEntity.reachTier] (plain
 * hex-encoded clipHash for LOCALITY, [ReachTierKeyDistribution.decayKeyStorageKey]
 * for Town/City/Country) -- this must agree with however the key was stored
 * in the first place, or a legitimate re-decrypt silently misses.
 *
 * Also covers Phase 4 Slice 10's [PostRepository.DecryptResult.AwaitingKey]
 * vs. [PostRepository.DecryptResult.Decayed] split for a `null`-key lookup:
 * both start from "no stored key," and only the post's own
 * `originatedAtMs`/`ttlSeconds` expiry decides which case it resolves to.
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

    /**
     * Builds (but never stores any key for) a Town/City/Country post,
     * standing in for "a received post whose TIER_KEY_REQUEST was never
     * sent/answered yet" -- the exact shape [PostRepository.DecryptResult.AwaitingKey]
     * exists to distinguish from genuine decay (Phase 4 Slice 10). Not
     * inserted into [postDao] here (callers do that themselves after tweaking
     * `originatedAtMs`/`ttlSeconds` as needed) since the two tests below need
     * two different expiry shapes for the exact same otherwise-identical post.
     */
    private fun buildKeylessCityPost(originatedAtMs: Long, ttlSeconds: Long): PostEntity {
        val plaintext = "never got a key ($originatedAtMs,$ttlSeconds)".toByteArray()
        val clipHash = MessageDigest.getInstance("SHA-256").digest(plaintext)
        val clipHashHex = clipHash.joinToString("") { "%02x".format(it) }
        val result = EncryptedFrameCodec.encode(
            plaintext = plaintext,
            clipHash = clipHash,
            senderDeviceId = ByteArray(Frame.SENDER_DEVICE_ID_SIZE) { it.toByte() },
            contentType = ContentType.PHOTO,
            hopCount = 0,
            originatedAtMs = originatedAtMs,
            ttlSeconds = ttlSeconds,
            reachTier = ReachTier.CITY,
            dontRelay = false,
            originGeohashPrefix = "9q8y",
        )
        val frame = Frame.decode(result.encoded)
        val payloadFile = File(tempFolder.newFolder("posts-nokey-${System.nanoTime()}"), "$clipHashHex.enc")
        payloadFile.writeBytes(frame.payload)
        return PostEntity(
            clipHash = clipHashHex,
            senderDeviceId = "sender",
            contentType = ContentType.PHOTO.name,
            originatedAtMs = originatedAtMs,
            ttlSeconds = ttlSeconds,
            reachTier = ReachTier.CITY.name,
            originGeohashPrefix = "9q8y",
            dontRelay = false,
            receivedAtMs = System.currentTimeMillis(),
            encryptedPayloadFilePath = payloadFile.absolutePath,
        )
    }

    @Test
    fun decryptReturnsAwaitingKeyWhenTheTieredKeyWasNeverStoredAndThePostHasNotExpired() = runBlocking {
        val postDao = FakePostDao()
        val decayKeyStore = DecayKeyStore()
        val repository = PostRepository(postDao, decayKeyStore)

        // Fresh post (originated "now", a full hour of TTL remaining) with no
        // key ever stored under any composition -- this is the ordinary
        // "no one has asked for (or been granted) this tier's key yet" case,
        // NOT decay: the post itself is nowhere near its own TTL boundary.
        val entity = buildKeylessCityPost(originatedAtMs = System.currentTimeMillis(), ttlSeconds = 3600L)
        postDao.upsert(entity)

        val result = repository.decrypt(entity)

        assertIs<PostRepository.DecryptResult.AwaitingKey>(
            result,
            "a non-expired Town/City/Country post with no stored key must be AwaitingKey, not Decayed",
        )
        Unit
    }

    @Test
    fun decryptReturnsDecayedWhenTheTieredKeyWasNeverStoredAndThePostHasExpired() = runBlocking {
        val postDao = FakePostDao()
        val decayKeyStore = DecayKeyStore()
        val repository = PostRepository(postDao, decayKeyStore)

        // Same shape as the AwaitingKey case above -- no key ever stored --
        // but this post's own TTL has genuinely elapsed (originated 2 hours
        // ago with a 1-hour TTL). Distinguishes real decay from "just hasn't
        // been requested yet": both start from "no stored key," only the
        // post's own origination/TTL differs.
        val twoHoursAgo = System.currentTimeMillis() - Duration.ofHours(2).toMillis()
        val entity = buildKeylessCityPost(originatedAtMs = twoHoursAgo, ttlSeconds = 3600L)
        postDao.upsert(entity)

        val result = repository.decrypt(entity)

        assertIs<PostRepository.DecryptResult.Decayed>(
            result,
            "an already-expired Town/City/Country post with no stored key must be Decayed, not AwaitingKey",
        )
        Unit
    }

    @Test
    fun decryptNeverReturnsAwaitingKeyForALocalityPost() = runBlocking {
        val postDao = FakePostDao()
        val decayKeyStore = DecayKeyStore()
        val repository = PostRepository(postDao, decayKeyStore)

        // A Locality post with no key ever stored, but NOT past its own TTL
        // -- unlike the CITY case above, this must still resolve to Decayed:
        // Locality never has a separate "key not yet requested" state (ADR
        // 0003, that tier's key is always either inlined at receive time or
        // genuinely gone).
        val plaintext = "locality, never got a key".toByteArray()
        val clipHash = MessageDigest.getInstance("SHA-256").digest(plaintext)
        val clipHashHex = clipHash.joinToString("") { "%02x".format(it) }
        val entity = PostEntity(
            clipHash = clipHashHex,
            senderDeviceId = "sender",
            contentType = ContentType.PHOTO.name,
            originatedAtMs = System.currentTimeMillis(),
            ttlSeconds = 3600L,
            reachTier = ReachTier.LOCALITY.name,
            dontRelay = false,
            receivedAtMs = System.currentTimeMillis(),
            encryptedPayloadFilePath = tempFolder.newFile("locality-nokey.enc").absolutePath,
        )
        postDao.upsert(entity)

        val result = repository.decrypt(entity)

        assertIs<PostRepository.DecryptResult.Decayed>(result, "Locality never yields AwaitingKey")
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
