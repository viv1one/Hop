package com.hop.transport

import com.hop.crypto.DecayKeyStore
import com.hop.data.PostDao
import com.hop.data.PostEntity
import com.hop.protocol.ContentType
import com.hop.protocol.EncryptedFrameCodec
import com.hop.protocol.Frame
import com.hop.protocol.ReachTier
import com.hop.repository.PostRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Plain-logic JVM tests for [ReceivedFrameStore] (the receive-path logic
 * [WifiDirectTransport] delegates to) -- no real sockets, no
 * `WifiP2pManager`/Android framework dependency, matching this repo's
 * hand-rolled-fakes testing pattern (see [PostComposerViewModelTest] and
 * [FeedViewModelTest]).
 */
class WifiDirectTransportTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun encodedFrame(payloadTag: String): ByteArray {
        val plaintext = "post bytes for $payloadTag".toByteArray()
        val clipHash = java.security.MessageDigest.getInstance("SHA-256").digest(plaintext)
        return EncryptedFrameCodec.encode(
            plaintext = plaintext,
            clipHash = clipHash,
            senderDeviceId = ByteArray(Frame.SENDER_DEVICE_ID_SIZE) { it.toByte() },
            contentType = ContentType.PHOTO,
            hopCount = 0,
            originatedAtMs = 1_700_000_000_000L,
            ttlSeconds = 3600L,
            reachTier = ReachTier.LOCALITY,
            dontRelay = false,
        )
    }

    @Test
    fun handleStoresANewlyReceivedPost() {
        val postDao = FakePostDao()
        val store = ReceivedFrameStore(
            postRepository = PostRepository(postDao, DecayKeyStore()),
            decayKeyStore = DecayKeyStore(),
            postsDir = tempFolder.newFolder("posts"),
        )

        val stored = store.handle(encodedFrame("first"))

        assertTrue(stored)
        assertEquals(1, postDao.inserted.size)
    }

    @Test
    fun handleSkipsAndDoesNotReinsertAnAlreadySeenClipHash() {
        val postDao = FakePostDao()
        val decayKeyStore = DecayKeyStore()
        val store = ReceivedFrameStore(
            postRepository = PostRepository(postDao, decayKeyStore),
            decayKeyStore = decayKeyStore,
            postsDir = tempFolder.newFolder("posts"),
        )
        val frameBytes = encodedFrame("dupe-check")

        val firstResult = store.handle(frameBytes)
        val secondResult = store.handle(frameBytes)

        assertTrue(firstResult, "first receipt of a new clipHash must be stored")
        assertFalse(secondResult, "re-receiving the same clipHash (reconnect / second peer) must be skipped, not re-inserted")
        assertEquals(
            1,
            postDao.inserted.size,
            "dedupe must not create a second row for the same clipHash",
        )
    }

    @Test
    fun handleReturnsFalseForAnUndecodableFrameWithoutThrowing() {
        val postDao = FakePostDao()
        val store = ReceivedFrameStore(
            postRepository = PostRepository(postDao, DecayKeyStore()),
            decayKeyStore = DecayKeyStore(),
            postsDir = tempFolder.newFolder("posts"),
        )

        val stored = store.handle(byteArrayOf(1, 2, 3))

        assertFalse(stored)
        assertTrue(postDao.inserted.isEmpty())
    }

    /** Minimal fake [PostDao], matching [PostComposerViewModelTest]/[FeedViewModelTest]'s pattern. */
    private class FakePostDao : PostDao {
        private val state = MutableStateFlow<List<PostEntity>>(emptyList())
        val inserted: List<PostEntity> get() = state.value

        override suspend fun upsert(post: PostEntity) {
            state.value = state.value.filterNot { it.clipHash == post.clipHash } + post
        }

        override fun getAllOrderedByReceivedDesc(): Flow<List<PostEntity>> = state

        override suspend fun getByClipHash(clipHash: String): PostEntity? =
            state.value.find { it.clipHash == clipHash }
    }
}
