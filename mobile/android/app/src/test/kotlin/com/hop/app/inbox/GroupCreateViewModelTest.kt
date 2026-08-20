package com.hop.app.inbox

import com.hop.repository.ConversationSummary
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
import kotlin.test.assertTrue

/**
 * Hand-rolled fakes (no mocking library in this repo) -- see
 * `InboxTestFakes.kt`'s [FakeMessageRepository].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupCreateViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val contacts = MutableStateFlow(
        listOf(
            ConversationSummary(peerId = "peer-b", lastMessagePreview = "hi", lastMessageAtMs = 1000L, lastMessageWasOutgoing = false),
            ConversationSummary(peerId = "peer-c", lastMessagePreview = "hey", lastMessageAtMs = 2000L, lastMessageWasOutgoing = false),
        ),
    )

    @Test
    fun availableContactsSourcedFromConversationSummaries() = runTest(testDispatcher) {
        val viewModel = GroupCreateViewModel(FakeMessageRepository(conversationSummaries = contacts))
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("peer-b", "peer-c"), viewModel.availableContacts.value.map(ConversationSummary::peerId))
    }

    @Test
    fun toggleMemberAddsAndRemovesFromSelection() = runTest(testDispatcher) {
        val viewModel = GroupCreateViewModel(FakeMessageRepository(conversationSummaries = contacts))
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.toggleMember("peer-b")
        assertEquals(setOf("peer-b"), viewModel.uiState.value.selectedPeerIds)

        viewModel.toggleMember("peer-c")
        assertEquals(setOf("peer-b", "peer-c"), viewModel.uiState.value.selectedPeerIds)

        viewModel.toggleMember("peer-b")
        assertEquals(setOf("peer-c"), viewModel.uiState.value.selectedPeerIds)
    }

    @Test
    fun createGroupCallsRepositoryWithNameAndSelectedMembersAndSurfacesTheNewGroupId() = runTest(testDispatcher) {
        val fakeRepository = FakeMessageRepository(conversationSummaries = contacts)
        val viewModel = GroupCreateViewModel(fakeRepository)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onNameChanged("Weekend trip")
        viewModel.toggleMember("peer-b")
        viewModel.toggleMember("peer-c")
        viewModel.createGroup()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("Weekend trip", fakeRepository.lastCreateGroupCall?.first)
        assertEquals(setOf("peer-b", "peer-c"), fakeRepository.lastCreateGroupCall?.second?.toSet())
        assertEquals("unused-group-id" to "Weekend trip", viewModel.createdGroup.value)
    }

    @Test
    fun createGroupIsANoOpWithoutANameOrWithoutAnyMembersSelected() = runTest(testDispatcher) {
        val fakeRepository = FakeMessageRepository(conversationSummaries = contacts)
        val viewModel = GroupCreateViewModel(fakeRepository)
        testDispatcher.scheduler.advanceUntilIdle()

        // No name, one member selected.
        viewModel.toggleMember("peer-b")
        viewModel.createGroup()
        testDispatcher.scheduler.advanceUntilIdle()
        assertNull(fakeRepository.lastCreateGroupCall)
        assertTrue(viewModel.uiState.value.isCreating.not())

        // A name, but no member selected.
        viewModel.toggleMember("peer-b")
        viewModel.onNameChanged("Solo")
        viewModel.createGroup()
        testDispatcher.scheduler.advanceUntilIdle()
        assertNull(fakeRepository.lastCreateGroupCall)
    }
}
