package com.hop.app.points

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.hop.app.AppContainer
import com.hop.app.theme.HopSpacing

/**
 * Phase 2 Slice 2's minimal points tab (PRD-adjacent "visible credit from
 * day one" for relay-operating devices) -- a running total plus one line of
 * plain-language explanation, nothing else. Deliberately this thin: it
 * exists to give the local points counter *some* visible surface, not to
 * become a profile/settings screen prematurely. No mesh/relay jargon in the
 * copy below (hop-dev invariant #5 -- "relay" itself is a banned term in
 * user-facing strings): "helping share posts nearby" describes the same
 * mechanism to a user without naming BLE/WiFi Direct/hop count/relay.
 *
 * These points are non-tradeable and never leave this device -- there is no
 * server to report them to and no marketplace to spend them in, consistent
 * with the no-accounts/no-HOP-server invariants everywhere else in this app.
 */
@Composable
fun PointsScreen(container: AppContainer) {
    val viewModel: PointsViewModel = viewModel(
        factory = viewModelFactory {
            initializer { PointsViewModel(pointsRepository = container.pointsRepository) }
        },
    )
    val totalPoints by viewModel.totalPoints.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(HopSpacing.lg),
        ) {
            Text(
                text = totalPoints.toString(),
                style = MaterialTheme.typography.displayMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "Points earned for helping share posts nearby",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = HopSpacing.sm),
            )
        }
    }
}
