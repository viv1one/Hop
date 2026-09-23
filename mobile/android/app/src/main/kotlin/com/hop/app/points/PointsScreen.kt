package com.hop.app.points

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.hop.app.AppContainer
import com.hop.app.R
import com.hop.app.theme.HopSpacing

/**
 * Phase 2 Slice 2's minimal points tab (PRD-adjacent "visible credit from
 * day one" for relay-operating devices) -- a running total plus plain-language
 * explanation. Deliberately still thin: it exists to give the local points
 * counter a visible surface, not to become a profile/settings screen
 * prematurely. No mesh/relay jargon in the copy below (hop-dev invariant #5
 * -- "relay" itself is a banned term in user-facing strings): "helping share
 * posts nearby" describes the same mechanism to a user without naming
 * BLE/WiFi Direct/hop count/relay.
 *
 * These points are non-tradeable and never leave this device -- there is no
 * server to report them to and no marketplace to spend them in, consistent
 * with the no-accounts/no-HOP-server invariants everywhere else in this app.
 * The screen now says so outright: a bare unexplained number invited exactly
 * the "when can I cash out" reading this project cannot honor.
 */
@Composable
fun PointsScreen(container: AppContainer) {
    val viewModel: PointsViewModel = viewModel(
        factory = viewModelFactory {
            initializer { PointsViewModel(pointsRepository = container.pointsRepository) }
        },
    )
    val totalPoints by viewModel.totalPoints.collectAsStateWithLifecycle()

    Box(
        Modifier
            .fillMaxSize()
            .padding(HopSpacing.lg),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(HopSpacing.lg),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_hop_logo),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(36.dp),
                )
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(HopSpacing.xs),
            ) {
                Text(
                    text = totalPoints.toString(),
                    style = MaterialTheme.typography.displayLarge,
                    // Monospace for the headline figure so the number does
                    // not reflow horizontally as it ticks up.
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = if (totalPoints == 1L) "point" else "points",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                text = "Earned for helping share posts nearby",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )

            // The honest footnote. Stating the limit plainly is the house
            // rule for anything user-facing (CLAUDE.md), and it belongs
            // here more than anywhere: a counter with no stated meaning
            // reads as a balance.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(HopSpacing.md),
                horizontalArrangement = Arrangement.spacedBy(HopSpacing.sm),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = "These stay on your device. There's no account to sync them to and nothing to spend them on.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
