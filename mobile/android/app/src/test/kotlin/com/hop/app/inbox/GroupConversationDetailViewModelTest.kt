package com.hop.app.inbox

import com.hop.data.GroupMessageEntity
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
 * Hand-rolled fakes (no mocking library in this repo) -- see
 * `InboxTestFakes.kt`'s [FakeMessageRepository].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupConversationDetailViewModelTest {

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
    fun messagesObserveTheUnderlyingGroupConversationFlow() = runTest(testDispatcher) {
        val messages = MutableStateFlow(
            listOf(
                GroupMessageEntity(id = 1, groupId = "group-1", authorPeerId = "peer-b", plaintext = "hi", sentAtMs = 1000L, isOutgoing = false),
            ),
        )
        val viewModel = GroupConversationDetailViewModel(
            groupId = "group-1",
            messageRepository = FakeMessageRepository(groupMessages = messages),
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, viewModel.messages.value.size)
        assertEquals("hi", viewModel.messages.value.first().plaintext)
    }

    @Test
    fun sendClearsDraftAndCallsSendToGroupWithTheGroupId() = runTest(testDispatcher) {
        val fakeRepository = FakeMessageRepository()
        val viewModel = GroupConversationDetailViewModel(groupId = "group-1", messageRepository = fakeRepository)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onDraftTextChanged("hello group")
        viewModel.send()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("group-1" to "hello group", fakeRepository.lastSendToGroupCall)
        assertEquals("", viewModel.uiState.value.draftText)
    }

    @Test
    fun sendIgnoresBlankDraft() = runTest(testDispatcher) {
        val fakeRepository = FakeMessageRepository()
        val viewModel = GroupConversationDetailViewModel(groupId = "group-1", messageRepository = fakeRepository)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onDraftTextChanged("   ")
        viewModel.send()
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(fakeRepository.lastSendToGroupCall)
    }
}
