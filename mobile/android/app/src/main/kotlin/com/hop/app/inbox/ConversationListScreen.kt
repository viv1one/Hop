package com.hop.app.inbox

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.hop.app.AppContainer
import com.hop.repository.ConversationSummary
import java.text.DateFormat
import java.util.Date

/**
 * The Inbox tab's conversation list -- replaces [com.hop.app.HopNavHost]'s
 * "Inbox — coming soon" placeholder. Sources
 * [ConversationListViewModel.conversations] (already filtered against the
 * block list) and renders one row per peer. No display-name concept exists
 * anywhere in this app (no accounts, by design) -- each row is labeled with a
 * short fragment of [ConversationSummary.peerId], a named, deliberate rough
 * edge (see the Phase 1 messaging plan), not a nickname/profile system.
 */
@Composable
fun ConversationListScreen(
    container: AppContainer,
    onConversationClick: (peerId: String) -> Unit,
) {
    val viewModel: ConversationListViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                ConversationListViewModel(
                    messageRepository = container.messageRepository,
                    blockRepository = container.blockRepository,
                )
            }
        },
    )
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()

    if (conversations.isEmpty()) {
        EmptyInbox()
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(conversations, key = { it.peerId }) { summary ->
            ConversationRow(
                summary = summary,
                onClick = { onConversationClick(summary.peerId) },
            )
            Divider()
        }
    }
}

@Composable
private fun EmptyInbox() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp),
        ) {
            Text(
                text = "No conversations yet",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "Message someone straight from one of their posts to start a conversation.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun ConversationRow(summary: ConversationSummary, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = summary.peerId.shortPeerLabel(),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = buildString {
                if (summary.lastMessageWasOutgoing) append("You: ")
                append(summary.lastMessagePreview)
            },
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
        )
        Text(
            text = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(Date(summary.lastMessageAtMs)),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * The short-id-fragment label named throughout the Phase 1 messaging plan --
 * first 6 hex chars of [peerId], shared by [ConversationListScreen] and
 * [ConversationDetailScreen] so a given peer reads identically in both
 * places.
 */
internal fun String.shortPeerLabel(): String = take(6)
