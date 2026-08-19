package com.hop.app.feed

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import android.graphics.BitmapFactory
import com.hop.data.PostEntity
import com.hop.repository.PostRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Renders a single [FeedScreen] page: on-demand decrypt of [post] (via
 * [decrypt], [FeedViewModel]'s cached wrapper around
 * `PostRepository.decrypt`), dispatched by [PostEntity.contentType] to a
 * photo/video/decayed renderer. The block/report/message affordance renders
 * regardless of decrypt outcome -- sender metadata ([PostEntity.senderDeviceId])
 * is available even for a post that has decayed, and [onMessage] uses that
 * same `senderDeviceId` as the Inbox conversation's `peerId` (see
 * `com.hop.app.inbox`'s "one identity, reused everywhere" doc).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PostPagerItem(
    post: PostEntity,
    pageIndex: Int,
    pagerState: PagerState,
    decrypt: suspend (PostEntity) -> PostRepository.DecryptResult,
    onBlock: () -> Unit,
    onReport: () -> Unit,
    onMessage: () -> Unit = {},
) {
    var result by remember(post.clipHash) { mutableStateOf<PostRepository.DecryptResult?>(null) }

    LaunchedEffect(post.clipHash) {
        result = decrypt(post)
    }

    Box(Modifier.fillMaxSize()) {
        when (val current = result) {
            // Brief decrypt-in-flight state: local file read + AES-GCM decrypt,
            // effectively instant on real hardware -- no spinner, matching this
            // app's existing posture on fast local-only operations (see
            // FirstRunScreen's DataStore-read loading-state comment).
            null -> Unit
            is PostRepository.DecryptResult.Decayed -> DecayedPostPlaceholder()
            is PostRepository.DecryptResult.Decrypted -> when (post.contentType) {
                "PHOTO" -> PhotoPage(bytes = current.bytes, pageIndex = pageIndex, pagerState = pagerState)
                "VIDEO" -> VideoPage(
                    bytes = current.bytes,
                    clipHash = post.clipHash,
                    pageIndex = pageIndex,
                    pagerState = pagerState,
                )
                // Defensive only -- PostEntity.contentType is always written from
                // com.hop.protocol.ContentType.name, so this should never be hit.
                else -> Unit
            }
        }

        BlockReportAffordance(
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
            onBlock = onBlock,
            onReport = onReport,
            onMessage = onMessage,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhotoPage(bytes: ByteArray, pageIndex: Int, pagerState: PagerState) {
    val bitmap = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }

    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = null,
        modifier = Modifier.fillMaxSize(),
    )

    LaunchedEffect(pageIndex) {
        delay(5_000)
        if (pagerState.currentPage == pageIndex && pageIndex + 1 < pagerState.pageCount) {
            pagerState.animateScrollToPage(pageIndex + 1)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VideoPage(bytes: ByteArray, clipHash: String, pageIndex: Int, pagerState: PagerState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Deliberate, bounded exception to PostEntity's "ciphertext only on disk"
    // contract: ContentEncryption is whole-blob AES-GCM, not a streaming
    // cipher, so Media3 needs an actual seekable plaintext file to play from.
    // Deleted in DisposableEffect's onDispose below the moment this page
    // leaves composition; HopApplication's startup sweep is the
    // defense-in-depth backstop if a crash skips that.
    val tempFile = remember(clipHash) {
        val dir = File(context.cacheDir, "decrypted-playback").apply { mkdirs() }
        File(dir, "$clipHash.mp4").apply { writeBytes(bytes) }
    }

    val exoPlayer = remember(clipHash) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(tempFile)))
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(clipHash) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED &&
                    pagerState.currentPage == pageIndex &&
                    pageIndex + 1 < pagerState.pageCount
                ) {
                    scope.launch { pagerState.animateScrollToPage(pageIndex + 1) }
                }
            }
        }
        exoPlayer.addListener(listener)

        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
            tempFile.delete()
        }
    }

    AndroidView(
        factory = { PlayerView(it).apply { player = exoPlayer } },
        modifier = Modifier.fillMaxSize(),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BlockReportAffordance(
    modifier: Modifier = Modifier,
    onBlock: () -> Unit,
    onReport: () -> Unit,
    onMessage: () -> Unit,
) {
    var sheetOpen by remember { mutableStateOf(false) }

    IconButton(onClick = { sheetOpen = true }, modifier = modifier) {
        Icon(Icons.Filled.MoreVert, contentDescription = "More options")
    }

    if (sheetOpen) {
        ModalBottomSheet(
            onDismissRequest = { sheetOpen = false },
            sheetState = rememberModalBottomSheetState(),
        ) {
            TextButton(
                onClick = {
                    onMessage()
                    sheetOpen = false
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Message")
            }
            TextButton(
                onClick = {
                    onBlock()
                    sheetOpen = false
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Block this sender")
            }
            TextButton(
                onClick = {
                    onReport()
                    sheetOpen = false
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Report this post")
            }
        }
    }
}
