package com.hop.app.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hop.repository.ConversationSummary
import com.hop.repository.MessageRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Backs [GroupCreateScreen]. Candidate members are sourced entirely from
 * [MessageRepository.observeConversationSummaries] -- existing 1:1 contacts,
 * the only pool [com.hop.repository.MessageRepository.createGroup] itself
 * assumes (see its own doc) -- there is no way to add a stranger from this
 * screen, by design (PRD §4.3-4.4: chats only ever start from a post or an
 * in-person hop, never a lookup).
 */
class GroupCreateViewModel(
    private val messageRepository: MessageRepository,
) : ViewModel() {

    data class UiState(
        val name: String = "",
        val selectedPeerIds: Set<String> = emptySet(),
        val isCreating: Boolean = false,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** Candidate members -- see class doc for why this is exactly [MessageRepository.observeConversationSummaries], unfiltered further. */
    val availableContacts: StateFlow<List<ConversationSummary>> = messageRepository.observeConversationSummaries()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Non-null once [createGroup] has successfully returned a new group id -- [GroupCreateScreen] navigates on this. */
    private val _createdGroupId = MutableStateFlow<Pair<String, String>?>(null)
    val createdGroup: StateFlow<Pair<String, String>?> = _createdGroupId.asStateFlow()

    fun onNameChanged(name: String) {
        _uiState.update { it.copy(name = name) }
    }

    fun toggleMember(peerId: String) {
        _uiState.update { state ->
            val selected = state.selectedPeerIds
            state.copy(selectedPeerIds = if (peerId in selected) selected - peerId else selected + peerId)
        }
    }

    fun createGroup() {
        val state = _uiState.value
        val name = state.name.trim()
        if (name.isEmpty() || state.selectedPeerIds.isEmpty() || state.isCreating) {
            return
        }

        _uiState.update { it.copy(isCreating = true) }
        viewModelScope.launch {
            val groupId = messageRepository.createGroup(name, state.selectedPeerIds.toList())
            _createdGroupId.value = groupId to name
        }
    }
}
