package com.hop.app.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hop.data.MessageEntity
import com.hop.repository.MessageRepository
import com.hop.repository.SendResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Backs [ConversationDetailScreen] for one conversation ([peerId]). Sources
 * [MessageRepository.observeConversation] for the message list and wraps
 * [MessageRepository.send] for the text input's send action, translating each
 * [SendResult] case into distinct, plain-language [SendFeedback] the screen
 * can render without ever mentioning mesh/handshake/prekey mechanics (PRD
 * §5's mesh-invisibility mandate).
 *
 * [messages] is a live [StateFlow] over the observed conversation -- on
 * [SendResult.Sent], nothing is written locally by this view model itself;
 * `MessageRepository.send` already inserts the optimistic outgoing
 * [MessageEntity], so it simply appears via this same observed flow, matching
 * the plan's "message appears via the observed flow" verification bullet.
 */
class ConversationDetailViewModel(
    private val peerId: String,
    private val messageRepository: MessageRepository,
) : ViewModel() {

    /** Distinguishable outcome of the most recent [send] attempt, surfaced to [ConversationDetailScreen]. */
    sealed interface SendFeedback {
        /**
         * No live session or cached bundle exists for this peer yet -- the
         * plan's named plain-language copy belongs to the screen, this is
         * just the signal it reacts to.
         */
        data object NoSessionAvailable : SendFeedback

        /**
         * This peer is blocked. Not normally reachable if the conversation
         * list already excludes blocked peers, but handled distinctly rather
         * than crashing or silently dropping the attempt.
         */
        data object Blocked : SendFeedback
    }

    data class UiState(
        val draftText: String = "",
        val sendFeedback: SendFeedback? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    val messages: StateFlow<List<MessageEntity>> = messageRepository.observeConversation(peerId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * True when this peer's identity key changed since it was first trusted
     * (see [MessageRepository.observeIdentityChangeWarning]'s doc). Surfaced
     * by [ConversationDetailScreen] as a plain-language banner with a
     * [trustChangedIdentity] action, never as "identity key"/"safety
     * number" language (PRD §5's mesh-invisibility mandate).
     */
    val hasIdentityChangeWarning: StateFlow<Boolean> = messageRepository.observeIdentityChangeWarning(peerId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun onDraftTextChanged(text: String) {
        _uiState.update { it.copy(draftText = text, sendFeedback = null) }
    }

    fun send() {
        val text = _uiState.value.draftText.trim()
        if (text.isEmpty()) return

        viewModelScope.launch {
            when (messageRepository.send(peerId, text)) {
                SendResult.Sent -> _uiState.update { it.copy(draftText = "", sendFeedback = null) }
                SendResult.Blocked -> _uiState.update { it.copy(sendFeedback = SendFeedback.Blocked) }
                SendResult.NoSessionAvailable ->
                    _uiState.update { it.copy(sendFeedback = SendFeedback.NoSessionAvailable) }
            }
        }
    }

    /** Acknowledges [hasIdentityChangeWarning] and re-enables messaging this peer -- see [MessageRepository.trustChangedIdentity]'s doc for exactly what this does. */
    fun trustChangedIdentity() {
        viewModelScope.launch {
            messageRepository.trustChangedIdentity(peerId)
        }
    }
}
