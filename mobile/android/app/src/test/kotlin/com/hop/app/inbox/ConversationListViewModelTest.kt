package com.hop.app.inbox

import com.hop.data.BlockedSenderDeviceDao
import com.hop.data.BlockedSenderDeviceEntity
import com.hop.repository.BlockRepository
import com.hop.repository.ConversationSummary
import com.hop.repository.GroupSummary
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

        val peerIds = viewModel.conversations.value.filterIsInstance<InboxRow.Direct>().map { it.summary.peerId }
        assertEquals(listOf("visiblepeer"), peerIds)
    }

    @Test
    fun emptyWhenAllConversationsAreWithBlockedPeersAndNoGroups() = runTest(testDispatcher) {
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

    @Test
    fun groupRowsAreCombinedWithDirectRowsAndSortedByRecency() = runTest(testDispatcher) {
        val summaries = MutableStateFlow(
            listOf(
                ConversationSummary(
                    peerId = "peer-a",
                    lastMessagePreview = "older direct message",
                    lastMessageAtMs = 1000L,
                    lastMessageWasOutgoing = false,
                ),
            ),
        )
        val groupSummaries = MutableStateFlow(
            listOf(
                GroupSummary(
                    groupId = "group-1",
                    name = "Weekend trip",
                    lastMessagePreview = "newer group message",
                    lastMessageAtMs = 5000L,
                    lastMessageWasOutgoing = false,
                ),
            ),
        )

        val viewModel = ConversationListViewModel(
            messageRepository = FakeMessageRepository(conversationSummaries = summaries, groupSummaries = groupSummaries),
            blockRepository = BlockRepository(FakeBlockedSenderDeviceDao(emptyList())),
        )
        testDispatcher.scheduler.advanceUntilIdle()

        val rows = viewModel.conversations.value
        assertEquals(2, rows.size)
        // Sorted newest-first across both kinds -- the group row (5000L) comes
        // before the direct row (1000L).
        assertEquals(InboxRow.Group(groupSummaries.value.single()), rows[0])
        assertEquals(InboxRow.Direct(summaries.value.single()), rows[1])
    }

    @Test
    fun aGroupRowIsNotHiddenByABlockedMemberUnlikeADirectRow() = runTest(testDispatcher) {
        // Design §3: block-filtering stays per-member for 1:1 rows, but a
        // group row isn't hidden by any single member being blocked.
        val summaries = MutableStateFlow(
            listOf(
                ConversationSummary(
                    peerId = "blocked-member",
                    lastMessagePreview = "spam",
                    lastMessageAtMs = 2000L,
                    lastMessageWasOutgoing = false,
                ),
            ),
        )
        val groupSummaries = MutableStateFlow(
            listOf(
                GroupSummary(
                    groupId = "group-1",
                    name = "Has a blocked member",
                    lastMessagePreview = "still visible",
                    lastMessageAtMs = 3000L,
                    lastMessageWasOutgoing = false,
                ),
            ),
        )
        val blockedDao = FakeBlockedSenderDeviceDao(listOf("blocked-member"))

        val viewModel = ConversationListViewModel(
            messageRepository = FakeMessageRepository(conversationSummaries = summaries, groupSummaries = groupSummaries),
            blockRepository = BlockRepository(blockedDao),
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            listOf(InboxRow.Group(groupSummaries.value.single())),
            viewModel.conversations.value,
        )
    }

    private class FakeBlockedSenderDeviceDao(initial: List<String>) : BlockedSenderDeviceDao {
        private val state = MutableStateFlow(initial)

        override suspend fun insert(entity: BlockedSenderDeviceEntity) {
            state.value = (state.value + entity.senderDeviceId).distinct()
        }

        override fun observeAll(): Flow<List<String>> = state
    }
}
