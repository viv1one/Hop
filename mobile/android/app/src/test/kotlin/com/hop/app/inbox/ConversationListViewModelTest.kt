package com.hop.app.inbox

import com.hop.data.BlockedSenderDeviceDao
import com.hop.data.BlockedSenderDeviceEntity
import com.hop.repository.BlockRepository
import com.hop.repository.ConversationSummary
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
 * Hand-rolled fakes throughout (no mocking library in this repo, matching
 * `FeedViewModelTest`'s pattern) -- see [InboxTestFakes.kt][FakeMessageRepository]
 * for the shared [com.hop.repository.MessageRepository] fake.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationListViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun conversationsSurfaceCorrectlyAndExcludeBlockedPeer() = runTest(testDispatcher) {
        val summaries = MutableStateFlow(
            listOf(
                ConversationSummary(
                    peerId = "visiblepeer",
                    lastMessagePreview = "hi",
                    lastMessageAtMs = 1000L,
                    lastMessageWasOutgoing = false,
                ),
                ConversationSummary(
                    peerId = "blockedpeer",
                    lastMessagePreview = "spam",
                    lastMessageAtMs = 2000L,
                    lastMessageWasOutgoing = false,
                ),
            ),
        )
        val blockedDao = FakeBlockedSenderDeviceDao(listOf("blockedpeer"))

        val viewModel = ConversationListViewModel(
            messageRepository = FakeMessageRepository(conversationSummaries = summaries),
            blockRepository = BlockRepository(blockedDao),
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("visiblepeer"), viewModel.conversations.value.map(ConversationSummary::peerId))
    }

    @Test
    fun emptyWhenAllConversationsAreWithBlockedPeers() = runTest(testDispatcher) {
        val summaries = MutableStateFlow(
            listOf(
                ConversationSummary(
                    peerId = "blockedpeer",
                    lastMessagePreview = "spam",
                    lastMessageAtMs = 2000L,
                    lastMessageWasOutgoing = false,
                ),
            ),
        )
        val blockedDao = FakeBlockedSenderDeviceDao(listOf("blockedpeer"))

        val viewModel = ConversationListViewModel(
            messageRepository = FakeMessageRepository(conversationSummaries = summaries),
            blockRepository = BlockRepository(blockedDao),
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(emptyList(), viewModel.conversations.value)
    }

    private class FakeBlockedSenderDeviceDao(initial: List<String>) : BlockedSenderDeviceDao {
        private val state = MutableStateFlow(initial)

        override suspend fun insert(entity: BlockedSenderDeviceEntity) {
            state.value = (state.value + entity.senderDeviceId).distinct()
        }

        override fun observeAll(): Flow<List<String>> = state
    }
}
