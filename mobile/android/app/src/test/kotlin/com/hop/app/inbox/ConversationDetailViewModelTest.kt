package com.hop.app.inbox

import com.hop.data.MessageEntity
import com.hop.repository.SendResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Hand-rolled fakes (no mocking library in this repo, matching
 * `FeedViewModelTest`'s pattern) -- see `InboxTestFakes.kt`'s
 * [FakeMessageRepository] for the shared [com.hop.repository.MessageRepository]
 * fake used here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationDetailViewModelTest {

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
    fun messagesObserveTheUnderlyingConversationFlow() = runTest(testDispatcher) {
        val messages = MutableStateFlow(
            listOf(
                MessageEntity(id = 1, peerId = "peer1", plaintext = "hi", sentAtMs = 1000L, isOutgoing = false),
            ),
        )
        val viewModel = ConversationDetailViewModel(
            peerId = "peer1",
            messageRepository = FakeMessageRepository(conversationMessages = messages),
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, viewModel.messages.value.size)
        assertEquals("hi", viewModel.messages.value.first().plaintext)

        // A new incoming message appearing on the observed flow (e.g. from
        // MessageRepository.onEnvelopeReceived's insert) shows up here too --
        // this view model does no separate polling/refresh of its own.
        messages.value = messages.value + MessageEntity(
            id = 2,
            peerId = "peer1",
            plaintext = "how's it going",
            sentAtMs = 2000L,
            isOutgoing = false,
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, viewModel.messages.value.size)
    }

    @Test
    fun sendClearsDraftOnSentResult() = runTest(testDispatcher) {
        val fakeRepository = FakeMessageRepository()
        val viewModel = ConversationDetailViewModel(peerId = "peer1", messageRepository = fakeRepository)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onDraftTextChanged("hello there")
        viewModel.send()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("peer1" to "hello there", fakeRepository.lastSendCall)
        assertEquals("", viewModel.uiState.value.draftText)
        assertNull(viewModel.uiState.value.sendFeedback)
    }

    @Test
    fun sendSurfacesNoSessionAvailableDistinctlyAndKeepsDraft() = runTest(testDispatcher) {
        val fakeRepository = FakeMessageRepository(sendResults = mutableListOf(SendResult.NoSessionAvailable))
        val viewModel = ConversationDetailViewModel(peerId = "peer1", messageRepository = fakeRepository)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onDraftTextChanged("are you there")
        viewModel.send()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            ConversationDetailViewModel.SendFeedback.NoSessionAvailable,
            viewModel.uiState.value.sendFeedback,
        )
        // Draft text is preserved on failure -- nothing was actually sent, the
        // user shouldn't have to retype it.
        assertEquals("are you there", viewModel.uiState.value.draftText)
    }

    @Test
    fun sendSurfacesBlockedDistinctlyFromNoSessionAvailable() = runTest(testDispatcher) {
        val fakeRepository = FakeMessageRepository(sendResults = mutableListOf(SendResult.Blocked))
        val viewModel = ConversationDetailViewModel(peerId = "peer1", messageRepository = fakeRepository)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onDraftTextChanged("hey")
        viewModel.send()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(ConversationDetailViewModel.SendFeedback.Blocked, viewModel.uiState.value.sendFeedback)
    }

    @Test
    fun sendIgnoresBlankDraft() = runTest(testDispatcher) {
        val fakeRepository = FakeMessageRepository()
        val viewModel = ConversationDetailViewModel(peerId = "peer1", messageRepository = fakeRepository)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onDraftTextChanged("   ")
        viewModel.send()
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(fakeRepository.lastSendCall)
    }
}
