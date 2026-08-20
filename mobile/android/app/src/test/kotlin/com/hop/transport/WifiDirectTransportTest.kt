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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

        assertNotNull(stored, "a newly received frame must be returned so callers can act on it (e.g. relay custody)")
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

        assertNotNull(firstResult, "first receipt of a new clipHash must be stored")
        assertNull(secondResult, "re-receiving the same clipHash (reconnect / second peer) must be skipped, not re-inserted")
        assertEquals(
            1,
            postDao.inserted.size,
            "dedupe must not create a second row for the same clipHash",
        )
    }

    @Test
    fun handleReturnsNullForAnUndecodableFrameWithoutThrowing() {
        val postDao = FakePostDao()
        val store = ReceivedFrameStore(
            postRepository = PostRepository(postDao, DecayKeyStore()),
            decayKeyStore = DecayKeyStore(),
            postsDir = tempFolder.newFolder("posts"),
        )

        val stored = store.handle(byteArrayOf(1, 2, 3))

        assertNull(stored)
        assertTrue(postDao.inserted.isEmpty())
    }

    /**
     * Finding A's regression test at the JVM/[ReceivedFrameStore] level
     * (the fuller multi-hop version lives in the instrumented suite): the
     * decay key's expiry must be anchored to `Frame.originatedAtMs +
     * Frame.ttlSeconds`, not to whenever [ReceivedFrameStore.handle] happens
     * to be called. Simulates "receipt happens well after origination" (the
     * multi-hop relay queuing-delay case) by using an [originatedAtMs] far
     * in the past relative to the fixed clock both [DecayKeyStore] and
     * [ReceivedFrameStore]'s internal [com.hop.protocol.RelayPolicy] share.
     */
    @Test
    fun handleAnchorsDecayKeyExpiryToOriginationTimeNotReceiptTime() {
        val clock = java.time.Clock.fixed(
            java.time.Instant.ofEpochMilli(1_700_000_000_000L + 3_000_000L), // 3,000s after origination
            java.time.ZoneOffset.UTC,
        )
        val postDao = FakePostDao()
        val decayKeyStore = DecayKeyStore(clock)
        val store = ReceivedFrameStore(
            postRepository = PostRepository(postDao, decayKeyStore),
            decayKeyStore = decayKeyStore,
            postsDir = tempFolder.newFolder("posts"),
            relayPolicy = com.hop.protocol.RelayPolicy(clock = clock),
        )
        // originatedAtMs = 1_700_000_000_000L, ttlSeconds = 3600 (1 hour) --
        // "receipt" (this clock) happens 3,000s (50min) after origination,
        // well within the 3600s window but close to its edge. If expiry
        // were (buggily) anchored to receipt time instead, the key would be
        // readable for another full hour from now; anchored correctly to
        // origination, only ~600s (10min) of window remain.
        val frame = encodedFrame("decay-anchor")
        val clipHashHex = Frame.decode(frame).clipHash.toHexString()

        val stored = store.handle(frame)
        assertNotNull(stored)

        // Still within the *origin-anchored* window (600s remain) -- key must be live.
        assertNotNull(decayKeyStore.retrieve(clipHashHex), "key must still be live within the origin-anchored window")
    }

    @Test
    fun handleSkipsStoringAnAlreadyDecayedKeyButStillInsertsThePost() {
        // originatedAtMs = 1_700_000_000_000L, ttlSeconds = 3600 -- clock is
        // set 2 hours (7200s) past origination, well past the 1-hour window.
        val clock = java.time.Clock.fixed(
            java.time.Instant.ofEpochMilli(1_700_000_000_000L + 7_200_000L),
            java.time.ZoneOffset.UTC,
        )
        val postDao = FakePostDao()
        val decayKeyStore = DecayKeyStore(clock)
        val store = ReceivedFrameStore(
            postRepository = PostRepository(postDao, decayKeyStore),
            decayKeyStore = decayKeyStore,
            postsDir = tempFolder.newFolder("posts"),
            relayPolicy = com.hop.protocol.RelayPolicy(clock = clock),
        )
        val frame = encodedFrame("already-decayed-on-arrival")
        val clipHashHex = Frame.decode(frame).clipHash.toHexString()

        val stored = store.handle(frame)

        assertNotNull(stored, "the frame is still stored as a PostEntity even though its key already decayed")
        assertEquals(1, postDao.inserted.size)
        assertNull(decayKeyStore.retrieve(clipHashHex), "an already-decayed key must never become retrievable")
    }

    private fun ByteArray.toHexString(): String = joinToString(separator = "") { "%02x".format(it) }

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
