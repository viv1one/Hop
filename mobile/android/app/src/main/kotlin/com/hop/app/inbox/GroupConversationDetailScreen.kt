package com.hop.app.inbox

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.hop.app.AppContainer
import com.hop.app.theme.HopSpacing
import com.hop.data.GroupMessageEntity
import java.text.DateFormat
import java.util.Date

/**
 * One group conversation: message history plus a text input and send action
 * -- the group analog of [ConversationDetailScreen]. [groupName] is passed in
 * (rather than looked up here) by whichever caller navigated in --
 * `HopNavHost` threads it straight from `GroupCreateScreen`'s just-entered
 * name or [InboxRow.Group]'s already-loaded [com.hop.repository.GroupSummary.name],
 * avoiding a separate lookup for what's already in hand at every call site.
 *
 * Unlike [ConversationDetailScreen], there's no `sendFeedback`/identity-change
 * banner here -- see [GroupConversationDetailViewModel]'s own doc for why a
 * group send has no single per-attempt outcome to surface distinctly.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupConversationDetailScreen(
    container: AppContainer,
    groupId: String,
    groupName: String,
    onBack: () -> Unit,
) {
    val viewModel: GroupConversationDetailViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                GroupConversationDetailViewModel(
                    groupId = groupId,
                    messageRepository = container.messageRepository,
                )
            }
        },
    )
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GroupAvatar(name = groupName, size = 32.dp)
                        Text(text = groupName, modifier = Modifier.padding(start = HopSpacing.sm))
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (messages.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth().padding(HopSpacing.lg), contentAlignment = Alignment.Center) {
                    Text(
                        text = "No messages yet -- say hello.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(HopSpacing.md),
                    verticalArrangement = Arrangement.spacedBy(HopSpacing.sm),
                ) {
                    items(messages, key = GroupMessageEntity::id) { message ->
                        GroupMessageBubble(message)
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = HopSpacing.md, vertical = HopSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = uiState.draftText,
                    onValueChange = viewModel::onDraftTextChanged,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message") },
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                        focusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                    ),
                )
                FilledIconButton(
                    onClick = viewModel::send,
                    enabled = uiState.draftText.isNotBlank(),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    modifier = Modifier.padding(start = HopSpacing.sm).size(44.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }
}

@Composable
private fun GroupMessageBubble(message: GroupMessageEntity) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isOutgoing) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            horizontalAlignment = if (message.isOutgoing) Alignment.End else Alignment.Start,
            modifier = Modifier.widthIn(max = 280.dp),
        ) {
            // Deliberately no per-message sender label beyond alignment (mirrors
            // ConversationDetailScreen's own "alignment/color only" posture) --
            // a short peer-id-fragment label per incoming bubble is a real gap
            // for a 3+-member group (readers can't tell members apart at a
            // glance), left for a later UI pass rather than solved here.
            Card(
                shape = RoundedCornerShape(
                    topStart = 16.dp,
                    topEnd = 16.dp,
                    bottomStart = if (message.isOutgoing) 16.dp else 4.dp,
                    bottomEnd = if (message.isOutgoing) 4.dp else 16.dp,
                ),
                colors = CardDefaults.cardColors(
                    containerColor = if (message.isOutgoing) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                ),
            ) {
                Text(
                    text = message.plaintext,
                    color = if (message.isOutgoing) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(horizontal = HopSpacing.md, vertical = HopSpacing.sm),
                )
            }
            Text(
                text = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(message.sentAtMs)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, start = HopSpacing.xs, end = HopSpacing.xs),
            )
        }
    }
}
