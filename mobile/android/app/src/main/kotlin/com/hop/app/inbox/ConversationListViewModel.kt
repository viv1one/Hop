package com.hop.app.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hop.repository.BlockRepository
import com.hop.repository.ConversationSummary
import com.hop.repository.MessageRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Backs [ConversationListScreen]. Combines
 * [MessageRepository.observeConversationSummaries] with
 * [BlockRepository.observeBlockedSenderIds] so a blocked peer's conversation
 * never renders in the Inbox list -- same filtering shape as
 * `com.hop.app.feed.FeedViewModel`'s combine-with-blocklist pattern for posts
 * (deliberately mirrored, not reinvented), and the plan's own call to lean
 * toward excluding a blocked peer's conversation entirely rather than merely
 * greying it out.
 */
class ConversationListViewModel(
    messageRepository: MessageRepository,
    blockRepository: BlockRepository,
) : ViewModel() {

    val conversations: StateFlow<List<ConversationSummary>> = combine(
        messageRepository.observeConversationSummaries(),
        blockRepository.observeBlockedSenderIds(),
    ) { summaries, blockedPeerIds ->
        summaries.filter { summary -> summary.peerId !in blockedPeerIds }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
}
