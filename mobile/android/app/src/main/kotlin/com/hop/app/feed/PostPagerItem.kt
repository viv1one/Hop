package com.hop.app.feed

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import com.hop.app.theme.HopSpacing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import android.graphics.BitmapFactory
import com.hop.data.PostEntity
import com.hop.repository.PostRepository
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
/**
 * The "proof of local receipt" gate for the "Stop sharing this post" action
 * (Phase 2 Slice 2, PRD §4.6/ADR 0004): a stock client only lets a user flag
 * something they've actually decrypted, never before -- `null` (still
 * decrypting) and [PostRepository.DecryptResult.Decayed] (never successfully
 * decrypted, or no longer decryptable) both leave it disabled. A plain,
 * unit-testable predicate (not inlined into the Composable below) so this
 * gating logic has JVM test coverage without needing a Compose UI test
 * harness, which this repo doesn't have set up yet.
 */
internal fun dontRelayActionEnabled(result: PostRepository.DecryptResult?): Boolean =
    result is PostRepository.DecryptResult.Decrypted

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
    onDontRelay: () -> Unit = {},
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
                "PHOTO" -> PhotoPage(bytes = current.bytes)
                "VIDEO" -> VideoPage(bytes = current.bytes, clipHash = post.clipHash)
                // Defensive only -- PostEntity.contentType is always written from
                // com.hop.protocol.ContentType.name, so this should never be hit.
                else -> Unit
            }
        }

        BlockReportAffordance(
            modifier = Modifier.align(Alignment.TopEnd).padding(HopSpacing.md),
            onBlock = onBlock,
            onReport = onReport,
            onMessage = onMessage,
            onDontRelay = onDontRelay,
            dontRelayEnabled = dontRelayActionEnabled(result),
        )
    }
}

@Composable
private fun PhotoPage(bytes: ByteArray) {
    val bitmap = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }

    // No auto-advance -- a photo stays on screen until the user swipes away,
    // same as a video plays through and then simply holds on its last frame
    // (see VideoPage below). Both used to force-advance to the next post on a
    // timer/on playback end; removed as an unwanted-feeling interruption
    // found via real device use, not a deliberate Reels-style design choice
    // this app ever committed to.
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = null,
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun VideoPage(bytes: ByteArray, clipHash: String) {
    val context = LocalContext.current

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
        onDispose {
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
    onDontRelay: () -> Unit,
    dontRelayEnabled: Boolean,
) {
    var sheetOpen by remember { mutableStateOf(false) }

    // A dark scrim behind the icon, not the theme's own surface color -- this
    // button sits directly on top of arbitrary user photo/video content, not
    // app chrome, so it needs contrast against whatever's underneath rather
    // than whatever's in light/dark mode.
    IconButton(
        onClick = { sheetOpen = true },
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.35f)),
    ) {
        Icon(Icons.Filled.MoreVert, contentDescription = "More options", tint = Color.White)
    }

    if (sheetOpen) {
        ModalBottomSheet(
            onDismissRequest = { sheetOpen = false },
            sheetState = rememberModalBottomSheetState(),
        ) {
            SheetAction(
                icon = Icons.AutoMirrored.Filled.Send,
                label = "Message",
                onClick = {
                    onMessage()
                    sheetOpen = false
                },
            )
            SheetAction(
                icon = Icons.Filled.Close,
                label = "Block this sender",
                tint = MaterialTheme.colorScheme.error,
                onClick = {
                    onBlock()
                    sheetOpen = false
                },
            )
            SheetAction(
                icon = Icons.Filled.Warning,
                label = "Report this post",
                tint = MaterialTheme.colorScheme.error,
                onClick = {
                    onReport()
                    sheetOpen = false
                },
            )
            // Distinct, genuinely new primitive from "Report this post" above
            // (which is already documented as local-only hiding, explicitly
            // not the real distributed mechanism -- both actions stay in this
            // sheet, they mean different things). No mesh/relay jargon in the
            // label (hop-dev invariant #5 -- "relay" itself is a banned term
            // in user-facing strings). Enabled only once this post has
            // actually been decrypted -- see [dontRelayActionEnabled]'s own
            // doc for why that's the "proof of local receipt" gate.
            SheetAction(
                icon = Icons.Filled.Clear,
                label = "Stop sharing this post",
                tint = MaterialTheme.colorScheme.error,
                enabled = dontRelayEnabled,
                onClick = {
                    onDontRelay()
                    sheetOpen = false
                },
            )
        }
    }
}

@Composable
private fun SheetAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary,
    enabled: Boolean = true,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (enabled) tint else tint.copy(alpha = 0.38f),
            modifier = Modifier.padding(end = HopSpacing.sm),
        )
        Text(
            label,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        )
    }
}
