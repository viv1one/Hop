package com.hop.app.inbox

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.hop.app.AppContainer
import com.hop.app.theme.HopSpacing
import com.hop.repository.ConversationSummary

/**
 * Group creation: a name field, a multi-select list of existing 1:1 contacts
 * (the only candidate pool -- see [GroupCreateViewModel]'s own doc), and a
 * plain-language notice about this slice's member-introduction limitation
 * (PRD §5's mesh-invisibility mandate: no "pairwise fan-out"/"Double
 * Ratchet"/session jargon anywhere here, but no stronger delivery guarantee
 * implied either -- see `com.hop.repository.MessageRepository`'s own class
 * doc for the underlying limitation this copy describes in plain terms).
 * Navigates into the new group's conversation on success via [onGroupCreated].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupCreateScreen(
    container: AppContainer,
    onGroupCreated: (groupId: String, groupName: String) -> Unit,
    onCancel: () -> Unit,
) {
    val viewModel: GroupCreateViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                GroupCreateViewModel(messageRepository = container.messageRepository)
            }
        },
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val availableContacts by viewModel.availableContacts.collectAsStateWithLifecycle()
    val createdGroup by viewModel.createdGroup.collectAsStateWithLifecycle()

    LaunchedEffect(createdGroup) {
        createdGroup?.let { (groupId, groupName) -> onGroupCreated(groupId, groupName) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New group") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(
                        onClick = viewModel::createGroup,
                        enabled = uiState.name.isNotBlank() && uiState.selectedPeerIds.isNotEmpty() && !uiState.isCreating,
                    ) {
                        Text("Create")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            OutlinedTextField(
                value = uiState.name,
                onValueChange = viewModel::onNameChanged,
                modifier = Modifier.fillMaxWidth().padding(HopSpacing.md),
                label = { Text("Group name") },
                singleLine = true,
            )

            Text(
                text = "Two members who haven't met each other in person yet may not get each other's " +
                    "group messages right away -- this usually sorts itself out once your phones have " +
                    "both been near other group members a few times.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = HopSpacing.md, vertical = HopSpacing.xs),
            )

            if (availableContacts.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(HopSpacing.lg), contentAlignment = Alignment.Center) {
                    Text(
                        text = "You don't have anyone to add yet -- message someone from a post first.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(availableContacts, key = ConversationSummary::peerId) { contact ->
                        ContactSelectRow(
                            contact = contact,
                            isSelected = contact.peerId in uiState.selectedPeerIds,
                            onToggle = { viewModel.toggleMember(contact.peerId) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ContactSelectRow(contact: ConversationSummary, isSelected: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = HopSpacing.md, vertical = HopSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PeerAvatar(peerId = contact.peerId, size = 36.dp)
            Text(
                text = contact.peerId.shortPeerLabel(),
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(start = HopSpacing.md),
            )
        }
        Checkbox(checked = isSelected, onCheckedChange = { onToggle() })
    }
}
