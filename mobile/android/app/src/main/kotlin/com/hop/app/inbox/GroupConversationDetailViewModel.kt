package com.hop.app.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hop.data.GroupMessageEntity
import com.hop.repository.MessageRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Backs [GroupConversationDetailScreen] for one group ([groupId]) -- the group
 * analog of [ConversationDetailViewModel]. Sources
 * [MessageRepository.observeGroupConversation] for the message list and wraps
 * [MessageRepository.sendToGroup] for the text input's send action.
 *
 * Unlike [ConversationDetailViewModel], there is no [com.hop.repository.SendResult]
 * case to react to distinctly here -- [MessageRepository.sendToGroup] always
 * returns [com.hop.repository.SendResult.Sent] (see its own doc for why a
 * per-member outcome has no single caller-facing signal to surface), so this
 * view model has no `sendFeedback`-equivalent state.
 */
class GroupConversationDetailViewModel(
    private val groupId: String,
    private val messageRepository: MessageRepository,
) : ViewModel() {

    data class UiState(val draftText: String = "")

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    val messages: StateFlow<List<GroupMessageEntity>> = messageRepository.observeGroupConversation(groupId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun onDraftTextChanged(text: String) {
        _uiState.update { it.copy(draftText = text) }
    }

    fun send() {
        val text = _uiState.value.draftText.trim()
        if (text.isEmpty()) return

        viewModelScope.launch {
            messageRepository.sendToGroup(groupId, text)
            _uiState.update { it.copy(draftText = "") }
        }
    }
}
