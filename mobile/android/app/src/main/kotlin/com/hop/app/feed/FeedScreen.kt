package com.hop.app.feed

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.hop.app.AppContainer
import com.hop.app.R
import com.hop.app.theme.HopSpacing
import com.hop.protocol.Geohash
import com.hop.protocol.ReachTier
import com.hop.protocol.ReachTierGeohash
import com.hop.protocol.TierMembershipClaim
import kotlinx.coroutines.flow.first

/**
 * Full-screen swipeable feed (PRD §5, modeled closely on Reels) -- replaces
 * [com.hop.app.HopNavHost]'s "Feed — coming soon" placeholder. One
 * [VerticalPager] page per post, each rendered by [PostPagerItem].
 *
 * [onComposeClick] is a no-op placeholder wired from the empty-feed CTA, same
 * pattern as [com.hop.app.HopNavHost]'s existing `onNavigateToComposer`
 * placeholder -- the post composer doesn't exist until a later slice.
 *
 * [onMessageClick] backs each post's "Message" action (see
 * [PostPagerItem]'s `BlockReportAffordance`) -- fired with the exact same
 * `senderDeviceId` hex string already passed to [onBlock]/`onReport`, no
 * separate identity concept for messaging.
 *
 * **Pull-to-refresh, not a button:** [rememberPullToRefreshState]'s
 * [androidx.compose.material3.pulltorefresh.PullToRefreshState.nestedScrollConnection]
 * is attached to the outer [Box] so it sees every vertical drag before
 * [VerticalPager] (or, in the empty-feed case, the
 * [androidx.compose.foundation.verticalScroll] container below) consumes it.
 * This works with [VerticalPager] specifically because of *where* in the
 * gesture it activates: [VerticalPager] only ever consumes as much of a drag
 * as it can actually turn into a page change, so at the very first page --
 * the only place a downward pull is physically possible from -- a further
 * downward drag has nothing left for the pager to consume and bubbles up
 * to this nested-scroll connection as leftover delta, which is exactly what
 * drives the pull animation. Elsewhere in the pager (mid-list), the pager
 * consumes the whole drag itself and no pull-to-refresh gesture can start,
 * which is the same "only at the top" constraint Instagram's own plain
 * scroll feed has. [EmptyFeed] has no scrollable content of its own, so it's
 * wrapped in a plain [androidx.compose.foundation.verticalScroll] purely so a
 * drag gesture has something to originate a nested-scroll chain from --
 * without it, dragging over static empty-state text would never reach
 * [nestedScroll] at all.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(
    container: AppContainer,
    onComposeClick: () -> Unit = {},
    onMessageClick: (peerId: String) -> Unit = {},
    contentPadding: PaddingValues = PaddingValues(),
) {
    val viewModel: FeedViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                FeedViewModel(
                    postRepository = container.postRepository,
                    blockRepository = container.blockRepository,
                    reportRepository = container.reportRepository,
                    dontRelayRepository = container.dontRelayRepository,
                    getAttestedDeviceKey = { container.settingsRepository.attestedDeviceKey.first().orEmpty() },
                    broadcastDontRelayFlag = { row -> container.transportManager.broadcastDontRelayFlag(row) },
                    // Phase 4 Slice 7: composes SettingsRepository +
                    // LocationProvider + DhtNodeManager into the single narrow
                    // suspend capability FeedViewModel calls once at
                    // construction. LOCALITY is skipped here, before ever
                    // reaching a location read or the DHT -- that tier never
                    // touches the DHT (ADR 0003).
                    browseNearbyDht = {
                        val tier = container.settingsRepository.defaultReachTier.first()
                        if (tier == null || tier == ReachTier.LOCALITY) {
                            emptyList()
                        } else {
                            val location = container.locationProvider.currentLocation()
                            if (location == null) {
                                android.util.Log.d("FeedScreen", "Skipping DHT browse for $tier -- no location available")
                                emptyList()
                            } else {
                                val subscription = container.dhtNodeManager.awaitTopicSubscription()
                                val holders = subscription?.browse(location.latitude, location.longitude, tier) ?: emptyList()
                                android.util.Log.d("FeedScreen", "DHT browse ($tier) found ${holders.size} remote holder(s)")
                                holders
                            }
                        }
                    },
                    // Phase 4 Slice 10: delegates to TransportManager.broadcastTierKeyRequest
                    // (see FeedViewModel.decrypt/maybeRequestTierKey's own doc
                    // for when this fires).
                    broadcastTierKeyRequest = { request ->
                        container.transportManager.broadcastTierKeyRequest(request)
                    },
                    // The other Phase 4 Slice 10 capability: composes
                    // container.locationProvider into a fresh TierMembershipClaim
                    // for whatever tier decrypt() is currently missing a key
                    // for -- the exact same location-read-to-geohash-prefix
                    // composition PostComposerScreen's own getOriginGeohashPrefix
                    // lambda already uses, just wrapped in a TierMembershipClaim
                    // instead of a bare String. Returns null (never throws)
                    // exactly when no location fix is available right now --
                    // FeedViewModel treats that as "skip this attempt," logged,
                    // never surfaced to the user (mesh mechanics stay invisible,
                    // PRD §5).
                    buildTierMembershipClaim = { tier ->
                        val location = container.locationProvider.currentLocation()
                        location?.let {
                            TierMembershipClaim(
                                reachTier = tier,
                                geohashPrefix = Geohash.encode(
                                    it.latitude,
                                    it.longitude,
                                    ReachTierGeohash.precisionFor(tier),
                                ),
                                claimedAtMs = System.currentTimeMillis(),
                            )
                        }
                    },
                    // Phase 4 Slice 11: delegates to container's singleton
                    // InternetPeerConnectionManager (see its own doc for why
                    // this is a container-owned singleton, not a per-screen
                    // instance) -- dials a real internet connection for every
                    // newly browseNearbyDht-discovered holder.
                    connectToDiscoveredHolders = { holders ->
                        container.internetPeerConnectionManager.connectToDiscoveredHolders(holders)
                    },
                )
            }
        },
    )
    val posts by viewModel.posts.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val pullToRefreshState = rememberPullToRefreshState()

    // Bridges the gesture's own internal "pulled past threshold and
    // released" trigger (PullToRefreshState.isRefreshing flips to true
    // entirely inside the library, no explicit call from this code) to the
    // real work in FeedViewModel.refresh -- and, the other direction,
    // FeedViewModel.isRefreshing finishing back to ending the gesture's
    // spinner. Two separate LaunchedEffects (not one) since they key off,
    // and react to, two independently-changing booleans.
    if (pullToRefreshState.isRefreshing) {
        LaunchedEffect(Unit) { viewModel.refresh() }
    }
    LaunchedEffect(isRefreshing) {
        if (!isRefreshing) pullToRefreshState.endRefresh()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // The feed is full-bleed media, so its backdrop is black rather
            // than the theme surface: in light mode, any clip that does not
            // exactly match the screen aspect ratio was letterboxed against
            // white.
            .background(Color.Black)
            .nestedScroll(pullToRefreshState.nestedScrollConnection),
    ) {
        if (posts.isEmpty()) {
            EmptyFeed(onComposeClick = onComposeClick, contentPadding = contentPadding)
        } else {
            val pagerState = rememberPagerState(pageCount = { posts.size })

            VerticalPager(
                state = pagerState,
                // Stable per-post key. Without it, a post filtering itself
                // out mid-view (reported/blocked) shifts every later page's
                // identity by one, so the already-composed decrypt state and
                // ExoPlayer instance get reused for the wrong post.
                key = { index -> posts.getOrNull(index)?.clipHash ?: index },
                modifier = Modifier.fillMaxSize(),
            ) { pageIndex ->
                // posts can shrink out from under an already-composed pager (e.g. the
                // page currently on screen gets reported/blocked mid-view and filters
                // itself out) -- guard rather than index out of bounds.
                val post = posts.getOrNull(pageIndex) ?: return@VerticalPager
                PostPagerItem(
                    post = post,
                    pageIndex = pageIndex,
                    pagerState = pagerState,
                    decrypt = viewModel::decrypt,
                    onBlock = { viewModel.blockSender(post.senderDeviceId) },
                    onReport = { viewModel.reportPost(post.clipHash) },
                    onMessage = { onMessageClick(post.senderDeviceId) },
                    onDontRelay = { viewModel.flagDontRelay(post) },
                    contentPadding = contentPadding,
                )
            }
        }

        PullToRefreshContainer(
            state = pullToRefreshState,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

@Composable
private fun EmptyFeed(onComposeClick: () -> Unit, contentPadding: PaddingValues) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            // No scrollable content of its own -- this exists purely so a
            // downward drag over the empty state has something to originate
            // a nested-scroll chain from (see FeedScreen's own doc). The
            // content's height never exceeds the viewport, so this never
            // actually scrolls anything; it only participates in the
            // gesture-consumption chain pull-to-refresh depends on.
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(HopSpacing.md),
            modifier = Modifier
                .padding(contentPadding)
                .padding(horizontal = HopSpacing.xl, vertical = HopSpacing.lg),
        ) {
            // The brand mark carries the empty state rather than a generic
            // glyph. This is the first screen a new user lands on after
            // setup, and it was previously two lines of text on a blank
            // field.
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.07f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_hop_logo),
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.55f),
                    modifier = Modifier.size(48.dp),
                )
            }

            Text(
                text = "Nothing nearby yet",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = HopSpacing.sm),
            )
            Text(
                text = "Posts from people around you show up here, then fade. Be the first to share something.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.66f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = HopSpacing.sm),
            )
            Button(
                onClick = onComposeClick,
                // The feed backdrop is always black, so this button cannot
                // inherit the theme's light-mode scheme and stay readable.
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = Color(0xFF17171F),
                ),
                contentPadding = PaddingValues(horizontal = HopSpacing.lg, vertical = 14.dp),
            ) {
                Text("Post something", style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}
