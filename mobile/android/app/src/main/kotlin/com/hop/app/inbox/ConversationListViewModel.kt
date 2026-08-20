package com.hop.app.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hop.repository.BlockRepository
import com.hop.repository.ConversationSummary
import com.hop.repository.GroupSummary
import com.hop.repository.MessageRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * One row in the combined Inbox list -- either a 1:1 conversation or a group
 * conversation, sorted together by [lastMessageAtMs] (see
 * [ConversationListViewModel.conversations]'s doc). A sealed type rather than
 * two separate lists so [ConversationListScreen] can render one chronologically
 * ordered `LazyColumn` without interleaving two source lists itself.
 */
sealed interface InboxRow {
    val lastMessageAtMs: Long

    data class Direct(val summary: ConversationSummary) : InboxRow {
        override val lastMessageAtMs: Long get() = summary.lastMessageAtMs
    }

    data class Group(val summary: GroupSummary) : InboxRow {
        override val lastMessageAtMs: Long get() = summary.lastMessageAtMs
    }
}

/**
 * Backs [ConversationListScreen]. Combines
 * [MessageRepository.observeConversationSummaries] and
 * [MessageRepository.observeGroupSummaries] with
 * [BlockRepository.observeBlockedSenderIds] into one sorted [InboxRow] list --
 * same filtering shape as `com.hop.app.feed.FeedViewModel`'s
 * combine-with-blocklist pattern for posts (deliberately mirrored, not
 * reinvented), and the plan's own call to lean toward excluding a blocked
 * peer's conversation entirely rather than merely greying it out.
 *
 * Block-filtering is deliberately **not** applied to group rows: a group
 * conversation isn't hidden just because one of its members happens to be
 * blocked (a group can have several members; blocking one doesn't make the
 * whole conversation irrelevant) -- blocking still prevents *sending to* that
 * specific member via `MessageRepository.sendToGroup`'s own filter. This
 * mirrors Design §3's explicit call-out of the same asymmetry.
 */
class ConversationListViewModel(
    messageRepository: MessageRepository,
    blockRepository: BlockRepository,
) : ViewModel() {

    val conversations: StateFlow<List<InboxRow>> = combine(
        messageRepository.observeConversationSummaries(),
        messageRepository.observeGroupSummaries(),
        blockRepository.observeBlockedSenderIds(),
    ) { summaries, groupSummaries, blockedPeerIds ->
        val directRows: List<InboxRow> = summaries
            .filter { summary -> summary.peerId !in blockedPeerIds }
            .map(InboxRow::Direct)
        val groupRows: List<InboxRow> = groupSummaries.map(InboxRow::Group)
        (directRows + groupRows).sortedByDescending { it.lastMessageAtMs }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
}
