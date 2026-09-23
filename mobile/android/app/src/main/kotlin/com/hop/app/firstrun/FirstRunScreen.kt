package com.hop.app.firstrun

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.hop.app.AppContainer
import com.hop.app.theme.HopSpacing
import com.hop.app.theme.ReachOptionGroup
import com.hop.app.theme.HopWordmark
import com.hop.protocol.ReachTier

/**
 * First-run setup: one screen, four reach-tier options in plain product
 * language (no "geohash"/"tier"/"locality" jargon exposed -- PRD §5, hop-dev
 * skill invariant #5), a Continue button. Device attestation happens
 * silently in the view model; this screen only ever surfaces a fallback
 * message, never a blocking prompt, if attestation failed.
 */
@Composable
fun FirstRunScreen(
    container: AppContainer,
    onFirstRunComplete: () -> Unit,
) {
    val viewModel: FirstRunViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                FirstRunViewModel(container.settingsRepository, container.attestationProvider)
            }
        },
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.navigateToMain) {
        if (uiState.navigateToMain) {
            onFirstRunComplete()
        }
    }

    LaunchedEffect(uiState.attestationFailedMessage) {
        uiState.attestationFailedMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    Scaffold(
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar { Text(data.visuals.message) }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            // First-run is the one screen that should introduce the app by
            // name -- the feed is full-bleed media with no chrome to hang a
            // wordmark on, so this is HOP's only in-app branding moment.
            HopWordmark(modifier = Modifier.padding(bottom = HopSpacing.xl))

            Text(
                text = "How far should your posts reach?",
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = "You can change this anytime, and adjust it for individual posts.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
            )

            ReachOptionGroup(
                selectedTier = uiState.selectedTier,
                onTierSelected = viewModel::onTierSelected,
            )

            Button(
                onClick = { viewModel.onContinueClicked() },
                enabled = !uiState.isSubmitting,
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(vertical = 16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = HopSpacing.lg),
            ) {
                Text(
                    text = if (uiState.isSubmitting) "Setting up..." else "Continue",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}
