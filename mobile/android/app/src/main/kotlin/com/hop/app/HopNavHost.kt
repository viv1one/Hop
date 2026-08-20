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
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
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
import com.hop.app.inbox.GroupConversationDetailScreen
import com.hop.app.inbox.GroupCreateScreen
import com.hop.app.points.PointsScreen
import java.net.URLDecoder
import java.net.URLEncoder

private const val ROUTE_FIRST_RUN = "first_run"
private const val ROUTE_MAIN = "main"
private const val ROUTE_POST_COMPOSER = "post_composer"
private const val ARG_PEER_ID = "peerId"
private const val ROUTE_CONVERSATION_DETAIL = "conversation/{$ARG_PEER_ID}"
private const val ROUTE_GROUP_CREATE = "group_create"
private const val ARG_GROUP_ID = "groupId"
private const val ARG_GROUP_NAME = "groupName"
private const val ROUTE_GROUP_CONVERSATION_DETAIL = "conversation/group/{$ARG_GROUP_ID}/{$ARG_GROUP_NAME}"
private const val URL_ENCODING = "UTF-8"

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
 *
 * As of Phase 2 Slice 4, also `group_create` (reachable from `main`'s Inbox
 * tab FAB, same modal-style shape as `post_composer`, but replaces itself with
 * `conversation/group/{groupId}/{groupName}` on success rather than popping
 * back to `main` -- a newly created group should land the user straight in
 * its conversation) / `conversation/group/{groupId}/{groupName}` (reachable
 * from the Inbox list's group rows or straight from `group_create`).
 * [ARG_GROUP_NAME] is carried as a nav argument (URL-encoded, since a group
 * name can contain arbitrary characters/spaces) rather than looked up by this
 * screen -- every caller that can navigate here already has the name in hand
 * (`GroupCreateScreen`'s just-entered name, or `InboxRow.Group`'s already-
 * loaded [com.hop.repository.GroupSummary.name]).
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
                onNavigateToGroupCreate = { navController.navigate(ROUTE_GROUP_CREATE) },
                onNavigateToGroupConversation = { groupId, groupName ->
                    navController.navigate(groupConversationRoute(groupId, groupName))
                },
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
        composable(ROUTE_GROUP_CREATE) {
            GroupCreateScreen(
                container = container,
                onGroupCreated = { groupId, groupName ->
                    navController.navigate(groupConversationRoute(groupId, groupName)) {
                        popUpTo(ROUTE_GROUP_CREATE) { inclusive = true }
                    }
                },
                onCancel = { navController.popBackStack() },
            )
        }
        composable(
            route = ROUTE_GROUP_CONVERSATION_DETAIL,
            arguments = listOf(
                navArgument(ARG_GROUP_ID) { type = NavType.StringType },
                navArgument(ARG_GROUP_NAME) { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val groupId = backStackEntry.arguments?.getString(ARG_GROUP_ID).orEmpty()
            val groupName = URLDecoder.decode(backStackEntry.arguments?.getString(ARG_GROUP_NAME).orEmpty(), URL_ENCODING)
            GroupConversationDetailScreen(
                container = container,
                groupId = groupId,
                groupName = groupName,
                onBack = { navController.popBackStack() },
            )
        }
    }
}

/** Builds the `conversation/group/{groupId}/{groupName}` route for [groupId]/[groupName], URL-encoding the name (see [HopNavHost]'s own doc for why it's carried as a nav argument at all). */
private fun groupConversationRoute(groupId: String, groupName: String): String =
    "conversation/group/$groupId/${URLEncoder.encode(groupName, URL_ENCODING)}"

private fun NavHostController.navigateToMainClearingBackStack() {
    navigate(ROUTE_MAIN) {
        popUpTo(ROUTE_FIRST_RUN) { inclusive = true }
    }
}

/**
 * The real tab shell (PRD §5: full-screen feed + inbox), plus a third,
 * deliberately minimal Points tab (Phase 2 Slice 2 -- see [PointsScreen]'s
 * own doc). Feed shows real (decrypted-on-demand) content via [FeedScreen];
 * Inbox shows the real conversation list via [ConversationListScreen].
 * Conversation *detail* isn't a fourth tab-switch case here -- it's a
 * top-level `NavHost` destination (see [HopNavHost]'s own doc), the same
 * "second-level destination off `main`" shape [onNavigateToComposer] already
 * uses, reused rather than nesting a second `NavHost` inside this tab
 * switch.
 */
@Composable
private fun MainScreen(
    container: AppContainer,
    onNavigateToComposer: () -> Unit,
    onNavigateToConversation: (peerId: String) -> Unit,
    onNavigateToGroupCreate: () -> Unit,
    onNavigateToGroupConversation: (groupId: String, groupName: String) -> Unit,
) {
    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        floatingActionButton = {
            // Feed tab: the empty-feed CTA in FeedScreen reaches the same
            // composer via the same onNavigateToComposer callback, so there's
            // exactly one entry point into posting, not two competing ones.
            // Inbox tab: the one entry point into group creation (Phase 2
            // Slice 4) -- no separate menu/CTA duplicates it.
            when (selectedTab) {
                0 -> FloatingActionButton(onClick = onNavigateToComposer) {
                    Icon(Icons.Filled.Add, contentDescription = "New post")
                }
                1 -> FloatingActionButton(onClick = onNavigateToGroupCreate) {
                    Icon(Icons.Filled.Person, contentDescription = "New group")
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
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Filled.Star, contentDescription = null) },
                    label = { Text("Points") },
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
                1 -> ConversationListScreen(
                    container = container,
                    onConversationClick = onNavigateToConversation,
                    onGroupClick = onNavigateToGroupConversation,
                )
                else -> PointsScreen(container = container)
            }
        }
    }
}
