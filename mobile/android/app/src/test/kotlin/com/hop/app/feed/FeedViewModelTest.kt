package com.hop.app.feed

import com.hop.crypto.DecayKeyStore
import com.hop.data.BlockedSenderDeviceDao
import com.hop.data.BlockedSenderDeviceEntity
import com.hop.data.PostDao
import com.hop.data.PostEntity
import com.hop.data.ReportedPostDao
import com.hop.data.ReportedPostEntity
import com.hop.repository.BlockRepository
import com.hop.repository.PostRepository
import com.hop.repository.ReportRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Hand-rolled fakes throughout (no mocking library exists in this repo, see
 * [PostRepository]/[BlockRepository]/[ReportRepository]'s `open` doc
 * comments) -- these implement the real DAO interfaces / subclass the real
 * repositories, matching this repo's existing `DecayKeyStorage`-style
 * fake-vs-real-backing split rather than reaching for a new dependency.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FeedViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun samplePost(clipHash: String, senderDeviceId: String = "sender-1") = PostEntity(
        clipHash = clipHash,
        senderDeviceId = senderDeviceId,
        contentType = "PHOTO",
        originatedAtMs = 1_700_000_000_000L,
        ttlSeconds = 3600,
        reachTier = "LOCALITY",
        dontRelay = false,
        receivedAtMs = 1000L,
        encryptedPayloadFilePath = "/unused/for/this/test/$clipHash.enc",
    )

    @Test
    fun postsFlowFiltersBlockedAndReportedEntries() = runTest(testDispatcher) {
        val visible = samplePost("clip-visible", senderDeviceId = "sender-visible")
        val fromBlockedSender = samplePost("clip-from-blocked", senderDeviceId = "sender-blocked")
        val reportedPost = samplePost("clip-reported", senderDeviceId = "sender-visible")

        val postDao = FakePostDao(listOf(visible, fromBlockedSender, reportedPost))
        val blockedDao = FakeBlockedSenderDeviceDao(listOf("sender-blocked"))
        val reportedDao = FakeReportedPostDao(listOf("clip-reported"))

        val viewModel = FeedViewModel(
            postRepository = PostRepository(postDao, DecayKeyStore()),
            blockRepository = BlockRepository(blockedDao),
            reportRepository = ReportRepository(reportedDao),
        )

        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("clip-visible"), viewModel.posts.value.map { it.clipHash })
    }

    @Test
    fun decryptCacheDoesNotGrowUnbounded() = runTest(testDispatcher) {
        val countingPostRepository = CountingFakePostRepository()

        val viewModel = FeedViewModel(
            postRepository = countingPostRepository,
            blockRepository = BlockRepository(FakeBlockedSenderDeviceDao(emptyList())),
            reportRepository = ReportRepository(FakeReportedPostDao(emptyList())),
        )
        testDispatcher.scheduler.advanceUntilIdle()

        val posts = (1..4).map { samplePost("clip-$it") }

        // Decrypt 4 distinct posts -- one more than the cache's cap of 3.
        posts.forEach { post -> viewModel.decrypt(post) }
        assertEquals(4, countingPostRepository.decryptCallCount)

        // Re-decrypting the most recently-used post is a cache hit -- no new
        // underlying decrypt call.
        viewModel.decrypt(posts.last())
        assertEquals(4, countingPostRepository.decryptCallCount)

        // Re-decrypting the *first* post decrypted must have been evicted by
        // now (cache cap is 3, and 4 distinct posts were decrypted since) --
        // this is the cache actually staying bounded, not silently growing to
        // hold the whole feed's plaintext.
        viewModel.decrypt(posts.first())
        assertEquals(5, countingPostRepository.decryptCallCount)
    }

    private class FakePostDao(initial: List<PostEntity>) : PostDao {
        private val state = MutableStateFlow(initial)

        override suspend fun upsert(post: PostEntity) {
            state.value = state.value.filterNot { it.clipHash == post.clipHash } + post
        }

        override fun getAllOrderedByReceivedDesc(): Flow<List<PostEntity>> = state

        override suspend fun getByClipHash(clipHash: String): PostEntity? =
            state.value.find { it.clipHash == clipHash }
    }

    private class FakeBlockedSenderDeviceDao(initial: List<String>) : BlockedSenderDeviceDao {
        private val state = MutableStateFlow(initial)

        override suspend fun insert(entity: BlockedSenderDeviceEntity) {
            state.value = (state.value + entity.senderDeviceId).distinct()
        }

        override fun observeAll(): Flow<List<String>> = state
    }

    private class FakeReportedPostDao(initial: List<String>) : ReportedPostDao {
        private val state = MutableStateFlow(initial)

        override suspend fun insert(entity: ReportedPostEntity) {
            state.value = (state.value + entity.clipHash).distinct()
        }

        override fun observeAll(): Flow<List<String>> = state
    }

    /**
     * Subclasses the real [PostRepository] (rather than implementing an
     * interface) per its own `open`-for-testability doc comment. Overrides
     * only what [FeedViewModel] actually calls; the constructor args are
     * never exercised since both overridden methods bypass them entirely.
     */
    private class CountingFakePostRepository :
        PostRepository(FakePostDao(emptyList()), DecayKeyStore()) {

        var decryptCallCount = 0
            private set

        override fun observeAllPosts(): Flow<List<PostEntity>> = MutableStateFlow(emptyList())

        override suspend fun decrypt(post: PostEntity): DecryptResult {
            decryptCallCount++
            return DecryptResult.Decrypted(post.clipHash.toByteArray())
        }
    }
}
