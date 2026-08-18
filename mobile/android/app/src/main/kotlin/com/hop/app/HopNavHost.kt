package com.hop.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.hop.app.firstrun.FirstRunScreen

private const val ROUTE_FIRST_RUN = "first_run"
private const val ROUTE_MAIN = "main"

/**
 * Outer navigation graph: `first_run` -> `main`. Start destination is
 * resolved by reading [AppContainer.settingsRepository]'s
 * `hasCompletedFirstRun` flag once, with a brief loading state while that
 * first read resolves (DataStore's first emission isn't synchronous).
 *
 * `main`'s content accepts [onNavigateToComposer] so a later slice (post
 * composer) can wire in a route + callback without restructuring this file --
 * unused in this slice (no `post_composer` route exists yet), deliberately
 * left as a no-op default.
 */
@Composable
fun HopNavHost(
    container: AppContainer,
    onNavigateToComposer: () -> Unit = {},
) {
    val hasCompletedFirstRun by container.settingsRepository.hasCompletedFirstRun
        .collectAsStateWithLifecycle(initialValue = null)

    // Brief loading state while the first DataStore read resolves -- avoids
    // flashing first-run setup for a returning user before we actually know.
    // Deliberately no visible content (not even a spinner): this resolves in
    // a single DataStore read, effectively instant on real hardware.
    val startResolved = hasCompletedFirstRun != null
    if (!startResolved) {
        Box(Modifier.fillMaxSize())
        return
    }

    val navController = rememberNavController()
    val startDestination = if (hasCompletedFirstRun == true) ROUTE_MAIN else ROUTE_FIRST_RUN

    NavHost(navController = navController, startDestination = startDestination) {
        composable(ROUTE_FIRST_RUN) {
            FirstRunScreen(
                container = container,
                onFirstRunComplete = {
                    navController.navigateToMainClearingBackStack()
                },
            )
        }
        composable(ROUTE_MAIN) {
            MainScreen(onNavigateToComposer = onNavigateToComposer)
        }
    }
}

private fun NavHostController.navigateToMainClearingBackStack() {
    navigate(ROUTE_MAIN) {
        popUpTo(ROUTE_FIRST_RUN) { inclusive = true }
    }
}

/**
 * Placeholder for the real two-tab shell (PRD §5: full-screen feed + inbox).
 * Feed's real content lands in a later slice; Inbox is an inert placeholder
 * for the whole of Phase 1 (BUILD_PLAN.md -- Inbox/messaging needs a
 * persistent SignalProtocolStore that doesn't exist yet). This slice just
 * needs the shell to build and navigate correctly.
 */
@Composable
private fun MainScreen(onNavigateToComposer: () -> Unit) {
    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                    label = { Text("Feed") },
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Filled.MailOutline, contentDescription = null) },
                    label = { Text("Inbox") },
                )
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.Center,
        ) {
            when (selectedTab) {
                0 -> Text("Feed — coming soon", style = MaterialTheme.typography.bodyLarge)
                else -> Text("Inbox — coming soon", style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
