package com.hop.app.feed

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.hop.app.AppContainer

/**
 * Full-screen swipeable feed (PRD §5, modeled closely on Reels) -- replaces
 * [com.hop.app.HopNavHost]'s "Feed — coming soon" placeholder. One
 * [VerticalPager] page per post, each rendered by [PostPagerItem].
 *
 * [onComposeClick] is a no-op placeholder wired from the empty-feed CTA, same
 * pattern as [com.hop.app.HopNavHost]'s existing `onNavigateToComposer`
 * placeholder -- the post composer doesn't exist until a later slice.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FeedScreen(
    container: AppContainer,
    onComposeClick: () -> Unit = {},
) {
    val viewModel: FeedViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                FeedViewModel(
                    postRepository = container.postRepository,
                    blockRepository = container.blockRepository,
                    reportRepository = container.reportRepository,
                )
            }
        },
    )
    val posts by viewModel.posts.collectAsStateWithLifecycle()

    if (posts.isEmpty()) {
        EmptyFeed(onComposeClick = onComposeClick)
        return
    }

    val pagerState = rememberPagerState(pageCount = { posts.size })

    VerticalPager(
        state = pagerState,
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
        )
    }
}

@Composable
private fun EmptyFeed(onComposeClick: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp),
        ) {
            Text(
                text = "Nothing nearby yet",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "Posts from people around you will show up here. Be the first to share something.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
            )
            Button(onClick = onComposeClick) {
                Text("Post something")
            }
        }
    }
}
