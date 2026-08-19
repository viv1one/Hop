package com.hop.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.hop.app.composer.PostComposerScreen
import com.hop.app.feed.FeedScreen
import com.hop.app.firstrun.FirstRunScreen
import com.hop.app.inbox.ConversationDetailScreen
import com.hop.app.inbox.ConversationListScreen

private const val ROUTE_FIRST_RUN = "first_run"
private const val ROUTE_MAIN = "main"
private const val ROUTE_POST_COMPOSER = "post_composer"
private const val ARG_PEER_ID = "peerId"
private const val ROUTE_CONVERSATION_DETAIL = "conversation/{$ARG_PEER_ID}"

/**
 * Outer navigation graph: `first_run` -> `main` -> `post_composer` (modal-style,
 * reachable from `main`'s Feed tab FAB/empty-state CTA, pops back to `main` on
 * successful post or cancel) / `conversation/{peerId}` (reachable from the
 * Inbox tab's conversation list or a post's "Message" action, pops back to
 * `main` on back press -- the same second-level-destination-off-`main`
 * pattern as `post_composer`, not a separate nav mechanism). Start
 * destination is resolved by reading [AppContainer.settingsRepository]'s
 * `hasCompletedFirstRun` flag once, with a brief loading state while that
 * first read resolves (DataStore's first emission isn't synchronous).
 */
@Composable
fun HopNavHost(
    container: AppContainer,
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
            MainScreen(
                container = container,
                onNavigateToComposer = { navController.navigate(ROUTE_POST_COMPOSER) },
                onNavigateToConversation = { peerId -> navController.navigate("conversation/$peerId") },
            )
        }
        composable(ROUTE_POST_COMPOSER) {
            PostComposerScreen(
                container = container,
                onPosted = { navController.popBackStack() },
                onCancel = { navController.popBackStack() },
            )
        }
        composable(
            route = ROUTE_CONVERSATION_DETAIL,
            arguments = listOf(navArgument(ARG_PEER_ID) { type = NavType.StringType }),
        ) { backStackEntry ->
            val peerId = backStackEntry.arguments?.getString(ARG_PEER_ID).orEmpty()
            ConversationDetailScreen(
                container = container,
                peerId = peerId,
                onBack = { navController.popBackStack() },
            )
        }
    }
}

private fun NavHostController.navigateToMainClearingBackStack() {
    navigate(ROUTE_MAIN) {
        popUpTo(ROUTE_FIRST_RUN) { inclusive = true }
    }
}

/**
 * The real two-tab shell (PRD §5: full-screen feed + inbox). Feed shows real
 * (decrypted-on-demand) content via [FeedScreen]; Inbox now shows the real
 * conversation list via [ConversationListScreen]. Conversation *detail* isn't
 * a third tab-switch case here -- it's a top-level `NavHost` destination (see
 * [HopNavHost]'s own doc), the same "second-level destination off `main`"
 * shape [onNavigateToComposer] already uses, reused rather than nesting a
 * second `NavHost` inside this tab switch.
 */
@Composable
private fun MainScreen(
    container: AppContainer,
    onNavigateToComposer: () -> Unit,
    onNavigateToConversation: (peerId: String) -> Unit,
) {
    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        floatingActionButton = {
            // Only on the Feed tab -- the empty-feed CTA in FeedScreen reaches
            // the same composer via the same onNavigateToComposer callback, so
            // there's exactly one entry point into posting, not two competing
            // ones.
            if (selectedTab == 0) {
                FloatingActionButton(onClick = onNavigateToComposer) {
                    Icon(Icons.Filled.Add, contentDescription = "New post")
                }
            }
        },
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
                0 -> FeedScreen(
                    container = container,
                    onComposeClick = onNavigateToComposer,
                    onMessageClick = onNavigateToConversation,
                )
                else -> ConversationListScreen(
                    container = container,
                    onConversationClick = onNavigateToConversation,
                )
            }
        }
    }
}
