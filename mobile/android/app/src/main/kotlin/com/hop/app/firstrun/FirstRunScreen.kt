package com.hop.app.firstrun

import androidx.compose.foundation.layout.Arrangement
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
            Text(
                text = "How far should your posts reach?",
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = "You can change this anytime, and adjust it for individual posts.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
            )

            Column(Modifier.selectableGroup()) {
                REACH_OPTIONS.forEach { (tier, label, description) ->
                    val selected = uiState.selectedTier == tier
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = selected,
                                onClick = { viewModel.onTierSelected(tier) },
                                role = Role.RadioButton,
                            )
                            .padding(vertical = 12.dp),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            RadioButton(selected = selected, onClick = null)
                            Text(text = label, style = MaterialTheme.typography.titleMedium)
                            Text(text = description, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            Button(
                onClick = { viewModel.onContinueClicked() },
                enabled = !uiState.isSubmitting,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp),
            ) {
                Text(if (uiState.isSubmitting) "Setting up..." else "Continue")
            }
        }
    }
}

private data class ReachOption(val tier: ReachTier, val label: String, val description: String)

// Plain product-language labels, deliberately not "final marketing copy" --
// no exposed technical language (geohash/tier/DHT/mesh) per PRD §5.
private val REACH_OPTIONS = listOf(
    ReachOption(ReachTier.LOCALITY, "Just around me", "The people physically near you right now"),
    ReachOption(ReachTier.TOWN, "My town", "Everyone in your town"),
    ReachOption(ReachTier.CITY, "My city", "Everyone in your city"),
    ReachOption(ReachTier.COUNTRY, "My country", "Everyone in your country"),
)
