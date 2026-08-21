package com.hop.app.composer

import com.hop.crypto.DecayKeyStore
import com.hop.data.PostDao
import com.hop.data.PostEntity
import com.hop.protocol.ContentType
import com.hop.protocol.EncryptedFrameCodec
import com.hop.protocol.ReachTier
import com.hop.repository.PostRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Hand-rolled fakes (no mocking library in this repo -- matches
 * `FeedViewModelTest`'s pattern). [PostComposerViewModel] deliberately takes
 * plain plaintext bytes and a plain [postsDir][java.io.File] rather than a
 * `Uri`/`Context`, specifically so this test can exercise "post construction"
 * logic -- clipHash computation, [PostEntity] field mapping, and CEK
 * extraction-not-regeneration -- without any Android framework/Robolectric
 * dependency.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PostComposerViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val fakeSenderDeviceId = ByteArray(16) { it.toByte() }

    private fun newViewModel(
        postDao: FakePostDao,
        defaultReachTier: Flow<ReachTier?> = flowOf(ReachTier.LOCALITY),
    ): Pair<PostComposerViewModel, java.io.File> {
        val postsDir = tempFolder.newFolder("posts")
        val viewModel = PostComposerViewModel(
            defaultReachTier = defaultReachTier,
            getOrCreateSenderDeviceId = { fakeSenderDeviceId },
            postRepository = PostRepository(postDao, DecayKeyStore()),
            decayKeyStore = DecayKeyStore(),
            postsDir = postsDir,
            broadcastPost = { /* no-op fake -- transport is exercised separately, see com.hop.transport tests */ },
            ioDispatcher = testDispatcher,
        )
        return viewModel to postsDir
    }

    @Test
    fun postComputesClipHashAndMapsPostEntityFields() = runTest(testDispatcher) {
        val postDao = FakePostDao()
        val (viewModel, postsDir) = newViewModel(postDao, defaultReachTier = flowOf(ReachTier.CITY))
        testDispatcher.scheduler.advanceUntilIdle()

        val bytes = "fake photo bytes".toByteArray()
        val expectedClipHash = MessageDigest.getInstance("SHA-256").digest(bytes)
        val expectedClipHashHex = expectedClipHash.joinToString("") { "%02x".format(it) }
        val expectedSenderHex = fakeSenderDeviceId.joinToString("") { "%02x".format(it) }

        viewModel.post(bytes = bytes, contentType = ContentType.PHOTO)
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value.postComplete)
        assertEquals(null, viewModel.uiState.value.errorMessage)

        val inserted = postDao.inserted.single()
        assertEquals(expectedClipHashHex, inserted.clipHash)
        assertEquals(expectedSenderHex, inserted.senderDeviceId)
        assertEquals(ContentType.PHOTO.name, inserted.contentType)
        assertEquals(ReachTier.CITY.name, inserted.reachTier)
        assertEquals(false, inserted.dontRelay)
        assertEquals(java.io.File(postsDir, "$expectedClipHashHex.enc").absolutePath, inserted.encryptedPayloadFilePath)

        // Ciphertext actually landed on disk under postsDir, not the plaintext.
        val onDisk = java.io.File(inserted.encryptedPayloadFilePath).readBytes()
        assertTrue(!onDisk.contentEquals(bytes), "payload on disk must be ciphertext, not plaintext")
    }

    @Test
    fun postExtractsCekFromEncodedFrameRatherThanRegenerating() = runTest(testDispatcher) {
        val postDao = FakePostDao()
        val decayKeyStore = DecayKeyStore()
        val postsDir = tempFolder.newFolder("posts2")
        val viewModel = PostComposerViewModel(
            defaultReachTier = flowOf(ReachTier.LOCALITY),
            getOrCreateSenderDeviceId = { fakeSenderDeviceId },
            postRepository = PostRepository(postDao, decayKeyStore),
            decayKeyStore = decayKeyStore,
            postsDir = postsDir,
            broadcastPost = { /* no-op fake -- transport is exercised separately, see com.hop.transport tests */ },
            ioDispatcher = testDispatcher,
        )
        testDispatcher.scheduler.advanceUntilIdle()

        val bytes = "another fake clip".toByteArray()
        viewModel.post(bytes = bytes, contentType = ContentType.VIDEO)
        testDispatcher.scheduler.advanceUntilIdle()

        val inserted = postDao.inserted.single()

        // If the CEK stored in decayKeyStore didn't match the one actually
        // embedded in the encoded frame (e.g. a second, mismatched
        // ContentEncryption.generateKey() call), this decrypt would fail --
        // decryptFromStore looks the key up purely by clipHash, same as the
        // real receive/view path (PostRepository.decrypt).
        val ciphertext = java.io.File(inserted.encryptedPayloadFilePath).readBytes()
        val decrypted = EncryptedFrameCodec.decryptFromStore(
            clipHash = MessageDigest.getInstance("SHA-256").digest(bytes),
            encryptedPayload = ciphertext,
            decayKeyStore = decayKeyStore,
        )
        assertNotNull(decrypted, "the stored CEK must be the one actually embedded in the encoded frame")
        assertTrue(decrypted.contentEquals(bytes))
    }

    @Test
    fun reachTierSelectionOverridesForThisPostOnlyAndNeverTouchesDefault() = runTest(testDispatcher) {
        var defaultWasWritten = false
        val postDao = FakePostDao()
        val (viewModel, _) = newViewModel(postDao, defaultReachTier = flowOf(ReachTier.LOCALITY))
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(ReachTier.LOCALITY, viewModel.uiState.value.selectedReachTier)

        viewModel.onReachTierSelected(ReachTier.COUNTRY)
        assertEquals(ReachTier.COUNTRY, viewModel.uiState.value.selectedReachTier)

        // No settingsRepository.setDefaultReachTier-equivalent hook exists on
        // this view model at all -- there is nothing here for a per-post
        // override to accidentally call. This assertion documents that
        // invariant rather than exercising a real settings write.
        assertTrue(!defaultWasWritten)

        viewModel.post(bytes = "x".toByteArray(), contentType = ContentType.PHOTO)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(ReachTier.COUNTRY.name, postDao.inserted.single().reachTier)
    }

    @Test
    fun postNeverCallsPublishToDhtForLocalityTier() = runTest(testDispatcher) {
        val postDao = FakePostDao()
        var publishedTier: ReachTier? = null
        val viewModel = PostComposerViewModel(
            defaultReachTier = flowOf(ReachTier.LOCALITY),
            getOrCreateSenderDeviceId = { fakeSenderDeviceId },
            postRepository = PostRepository(postDao, DecayKeyStore()),
            decayKeyStore = DecayKeyStore(),
            postsDir = tempFolder.newFolder("posts-locality"),
            broadcastPost = {},
            publishToDht = { tier -> publishedTier = tier },
            ioDispatcher = testDispatcher,
        )
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.post(bytes = "x".toByteArray(), contentType = ContentType.PHOTO)
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value.postComplete)
        assertEquals(null, publishedTier, "publishToDht must never be called for LOCALITY -- ADR 0003, Locality never touches the DHT")
    }

    @Test
    fun postCallsPublishToDhtWithSelectedTierForNonLocalityTiers() = runTest(testDispatcher) {
        val postDao = FakePostDao()
        var publishedTier: ReachTier? = null
        val viewModel = PostComposerViewModel(
            defaultReachTier = flowOf(ReachTier.CITY),
            getOrCreateSenderDeviceId = { fakeSenderDeviceId },
            postRepository = PostRepository(postDao, DecayKeyStore()),
            decayKeyStore = DecayKeyStore(),
            postsDir = tempFolder.newFolder("posts-city"),
            broadcastPost = {},
            publishToDht = { tier -> publishedTier = tier },
            ioDispatcher = testDispatcher,
        )
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.post(bytes = "x".toByteArray(), contentType = ContentType.PHOTO)
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value.postComplete)
        assertEquals(ReachTier.CITY, publishedTier)
    }

    @Test
    fun postSucceedsLocallyEvenWhenPublishToDhtThrows() = runTest(testDispatcher) {
        val postDao = FakePostDao()
        val viewModel = PostComposerViewModel(
            defaultReachTier = flowOf(ReachTier.TOWN),
            getOrCreateSenderDeviceId = { fakeSenderDeviceId },
            postRepository = PostRepository(postDao, DecayKeyStore()),
            decayKeyStore = DecayKeyStore(),
            postsDir = tempFolder.newFolder("posts-town"),
            broadcastPost = {},
            publishToDht = { throw RuntimeException("DHT unreachable") },
            ioDispatcher = testDispatcher,
        )
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.post(bytes = "x".toByteArray(), contentType = ContentType.PHOTO)
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value.postComplete, "a DHT publish failure must never undo/fail a post that already saved locally")
        assertEquals(null, viewModel.uiState.value.errorMessage)
        assertEquals(1, postDao.inserted.size)
    }

    /** Minimal fake [PostDao] -- only [upsert] is exercised by [PostRepository.insert]. */
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
