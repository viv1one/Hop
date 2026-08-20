package com.hop.app.inbox

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.hop.app.AppContainer
import com.hop.app.theme.HopSpacing
import com.hop.repository.ConversationSummary
import com.hop.repository.GroupSummary
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/**
 * The Inbox tab's conversation list -- replaces [com.hop.app.HopNavHost]'s
 * "Inbox — coming soon" placeholder. Sources
 * [ConversationListViewModel.conversations] (already filtered against the
 * block list, 1:1 and group rows combined into one chronological list -- see
 * [InboxRow]'s own doc) and renders one row per conversation. No display-name
 * concept exists anywhere in this app for a *peer* (no accounts, by design)
 * -- a 1:1 row is labeled with a short fragment of [ConversationSummary.peerId],
 * a named, deliberate rough edge (see the Phase 1 messaging plan), not a
 * nickname/profile system. A group row, by contrast, has a real local
 * display name (the name its creator gave it at creation time).
 */
@Composable
fun ConversationListScreen(
    container: AppContainer,
    onConversationClick: (peerId: String) -> Unit,
    onGroupClick: (groupId: String, groupName: String) -> Unit,
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
        items(
            conversations,
            key = { row ->
                when (row) {
                    is InboxRow.Direct -> "direct:${row.summary.peerId}"
                    is InboxRow.Group -> "group:${row.summary.groupId}"
                }
            },
        ) { row ->
            when (row) {
                is InboxRow.Direct -> ConversationRow(
                    summary = row.summary,
                    onClick = { onConversationClick(row.summary.peerId) },
                )

                is InboxRow.Group -> GroupConversationRow(
                    summary = row.summary,
                    onClick = { onGroupClick(row.summary.groupId, row.summary.name) },
                )
            }
        }
    }
}

@Composable
private fun EmptyInbox() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(HopSpacing.lg),
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.MailOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(32.dp),
                )
            }
            Text(
                text = "No conversations yet",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = HopSpacing.md),
            )
            Text(
                text = "Message someone straight from one of their posts to start a conversation.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = HopSpacing.sm),
            )
        }
    }
}

@Composable
private fun ConversationRow(summary: ConversationSummary, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = HopSpacing.md, vertical = HopSpacing.sm + HopSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PeerAvatar(peerId = summary.peerId)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = HopSpacing.md),
        ) {
            Text(
                text = summary.peerId.shortPeerLabel(),
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                text = buildString {
                    if (summary.lastMessageWasOutgoing) append("You: ")
                    append(summary.lastMessagePreview)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Text(
            text = summary.lastMessageAtMs.toRelativeTimeLabel(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(start = HopSpacing.sm)
                .width(64.dp),
            textAlign = TextAlign.End,
        )
    }
}

/**
 * The group analog of [ConversationRow] -- same row shape, but keyed and
 * labeled off a real local [GroupSummary.name] instead of a peer id fragment.
 */
@Composable
private fun GroupConversationRow(summary: GroupSummary, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = HopSpacing.md, vertical = HopSpacing.sm + HopSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GroupAvatar(name = summary.name)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = HopSpacing.md),
        ) {
            Text(
                text = summary.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    if (summary.lastMessageWasOutgoing) append("You: ")
                    append(summary.lastMessagePreview)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Text(
            text = summary.lastMessageAtMs.toRelativeTimeLabel(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(start = HopSpacing.sm)
                .width(64.dp),
            textAlign = TextAlign.End,
        )
    }
}

/**
 * Deterministic colored circle standing in for an avatar -- no accounts, no
 * profile photos, so [peerId] itself (the same value shown as text) is the
 * only identity this app can render. Same 2 hex chars as [shortPeerLabel]'s
 * leading fragment, just visually elevated.
 */
@Composable
internal fun PeerAvatar(peerId: String, size: androidx.compose.ui.unit.Dp = 44.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = peerId.take(2).uppercase(),
            style = MaterialTheme.typography.labelLarge,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

/** [PeerAvatar]'s group analog -- keyed off a group's real display [name] instead of a peer id fragment. */
@Composable
internal fun GroupAvatar(name: String, size: androidx.compose.ui.unit.Dp = 44.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.take(2).uppercase().ifBlank { "?" },
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
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

/** Time-only for today (matches this list's own scanability goal), short date otherwise. */
private fun Long.toRelativeTimeLabel(): String {
    val target = Calendar.getInstance().apply { timeInMillis = this@toRelativeTimeLabel }
    val today = Calendar.getInstance()
    val isToday = target.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
        target.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
    val date = Date(this)
    return if (isToday) {
        DateFormat.getTimeInstance(DateFormat.SHORT).format(date)
    } else {
        DateFormat.getDateInstance(DateFormat.SHORT).format(date)
    }
}
