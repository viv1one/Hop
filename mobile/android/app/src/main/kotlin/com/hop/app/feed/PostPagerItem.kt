package com.hop.app.feed

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.hop.app.inbox.PeerAvatar
import com.hop.app.inbox.shortPeerLabel
import com.hop.app.theme.HopSpacing
import com.hop.data.PostEntity
import com.hop.repository.PostRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 *
 * The page is a layered stack, back to front: black backdrop, media, two
 * gradient scrims, then chrome. The scrims exist because every piece of
 * chrome on this screen sits on arbitrary user photo/video, not on app
 * surface -- without them, white-on-bright-content text is unreadable and
 * fails contrast outright. Black backdrop (not the theme's `background`) for
 * the same reason: a portrait clip letterboxed against a white light-mode
 * surface looked broken on device.
 *
 * [pagerState] and [pageIndex] gate video playback to the page actually on
 * screen. They were previously accepted and never read, which left every
 * composed-but-offscreen page's [ExoPlayer] running -- audible during a
 * swipe, since the pager composes the incoming page before the outgoing one
 * is disposed.
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
    contentPadding: PaddingValues = PaddingValues(),
) {
    var result by remember(post.clipHash) { mutableStateOf<PostRepository.DecryptResult?>(null) }

    LaunchedEffect(post.clipHash) {
        result = decrypt(post)
    }

    val isActivePage = pagerState.currentPage == pageIndex

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        when (val current = result) {
            // Decrypt is a local file read plus an AES-GCM pass, but for a
            // multi-megabyte video that is not free, and a bare black frame
            // reads as a broken page rather than a loading one.
            null -> PostLoadingState()
            is PostRepository.DecryptResult.Decayed -> DecayedPostPlaceholder()
            // Not (yet) decayed -- this device just hasn't obtained a key for
            // this Town/City/Country post yet. FeedViewModel.decrypt already
            // fired a best-effort tier-key request on this outcome; the
            // user's own next pull-to-refresh is what picks up a key that
            // arrives in the meantime (see that function's own doc). Distinct
            // copy from the Decayed case above -- "expired" would overclaim.
            is PostRepository.DecryptResult.AwaitingKey ->
                DecayedPostPlaceholder(
                    message = "This post isn't available yet",
                    detail = "It hasn't reached your device. Pull down to check again.",
                )
            is PostRepository.DecryptResult.Decrypted -> when (post.contentType) {
                "PHOTO" -> PhotoPage(bytes = current.bytes)
                "VIDEO" -> VideoPage(
                    bytes = current.bytes,
                    clipHash = post.clipHash,
                    isActivePage = isActivePage,
                )
                // Defensive only -- PostEntity.contentType is always written from
                // com.hop.protocol.ContentType.name, so this should never be hit.
                else -> Unit
            }
        }

        // Scrims sit above the media and below every control, so the chrome
        // stays legible over arbitrary content without dimming the middle of
        // the frame where the subject usually is.
        TopScrim()
        BottomScrim()

        BlockReportAffordance(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(HopSpacing.sm),
            onBlock = onBlock,
            onReport = onReport,
            onMessage = onMessage,
            onDontRelay = onDontRelay,
            dontRelayEnabled = dontRelayActionEnabled(result),
        )

        PostMetadata(
            post = post,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(contentPadding)
                .padding(horizontal = HopSpacing.md, vertical = HopSpacing.md),
        )
    }
}

/** Top-edge darkening so the overflow control reads against bright content. */
@Composable
private fun TopScrim() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(160.dp)
            .background(
                Brush.verticalGradient(
                    listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent),
                ),
            ),
    )
}

/** Bottom-edge darkening behind the post metadata. Taller and stronger than the top: more text sits here. */
@Composable
private fun BottomScrim() {
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0.55f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.70f),
                ),
            ),
    )
}

/**
 * Who posted, how long ago, roughly where it reaches, and when it fades.
 *
 * A feed with no attribution at all was the single biggest gap here: the
 * screen showed media and nothing else, so a post carried no context. There
 * are no accounts and no display names by design, so the sender is rendered
 * the same way the Inbox renders it -- [PeerAvatar] plus [shortPeerLabel] --
 * reusing those directly rather than inventing a second visual identity for
 * the same peer.
 */
@Composable
private fun PostMetadata(post: PostEntity, modifier: Modifier = Modifier) {
    // Recomputed per composition rather than ticking on a timer: the labels
    // are coarse (minutes at the finest), and a per-second clock behind a
    // full-screen video would be a lot of recomposition for a label that
    // rarely changes. Swiping the pager or refreshing re-reads it.
    val now = remember(post.clipHash) { System.currentTimeMillis() }
    val place = reachLabel(post.reachTier)
    val fades = fadeLabel(post.originatedAtMs, post.ttlSeconds, now)

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(HopSpacing.sm),
    ) {
        PeerAvatar(peerId = post.senderDeviceId, size = 38.dp)

        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(
                text = post.senderDeviceId.shortPeerLabel(),
                style = MaterialTheme.typography.titleSmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(HopSpacing.xs)) {
                MetaChip(text = postAgeLabel(post.originatedAtMs, now))
                place?.let { MetaChip(text = it) }
                fades?.let { MetaChip(text = it) }
            }
        }
    }
}

/**
 * One small translucent pill of post context.
 *
 * White-on-translucent-white would not hold 4.5:1 over bright media on its
 * own, so each chip carries its own dark fill rather than relying only on
 * [BottomScrim].
 */
@Composable
private fun MetaChip(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.42f))
            .padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

/** Quiet placeholder while the decrypt coroutine is in flight. */
@Composable
private fun PostLoadingState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = "Loading",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.55f),
        )
    }
}

@Composable
private fun PhotoPage(bytes: ByteArray) {
    // Decoded off the main thread. BitmapFactory.decodeByteArray on a
    // multi-megabyte JPEG was previously running synchronously inside
    // composition, which janked every single page swipe.
    val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = bytes) {
        value = withContext(Dispatchers.Default) {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }
    }

    // No auto-advance -- a photo stays on screen until the user swipes away,
    // same as a video plays through (see VideoPage below). Both used to
    // force-advance to the next post on a timer/on playback end; removed as
    // an unwanted-feeling interruption found via real device use, not a
    // deliberate Reels-style design choice this app ever committed to.
    bitmap?.let {
        Image(
            bitmap = it,
            // ContentScale.Crop, not the Image default of Fit: this is a
            // full-bleed feed page, and Fit letterboxed every non-matching
            // aspect ratio against the backdrop.
            contentScale = ContentScale.Crop,
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                // The image itself carries no describable text -- it is
                // arbitrary user media this device cannot caption. Naming it
                // as a photo at least tells a screen-reader user what kind of
                // page they are on, which a null description does not.
                .semantics { contentDescription = "Photo post" },
        )
    } ?: PostLoadingState()
}

@Composable
private fun VideoPage(bytes: ByteArray, clipHash: String, isActivePage: Boolean) {
    val context = LocalContext.current
    var userPaused by remember(clipHash) { mutableStateOf(false) }

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
            // Short clips in an endless feed: holding on a frozen last frame
            // read as a stalled player on device.
            repeatMode = Player.REPEAT_MODE_ONE
            prepare()
        }
    }

    // Only the page actually on screen plays. The pager composes the incoming
    // page before disposing the outgoing one, so without this two clips
    // overlap audibly mid-swipe.
    LaunchedEffect(isActivePage, userPaused) {
        exoPlayer.playWhenReady = isActivePage && !userPaused
    }

    DisposableEffect(clipHash) {
        onDispose {
            exoPlayer.release()
            tempFile.delete()
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = {
                PlayerView(it).apply {
                    player = exoPlayer
                    // The stock transport controls are desktop-video chrome:
                    // a scrub bar and buttons over a full-screen social clip.
                    // Tap-to-pause below is the whole control surface.
                    useController = false
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // Tap anywhere to pause/resume. No ripple: a ripple splash across a
        // full-screen video is noise, and there is no bounded control to
        // anchor it to.
        Box(
            Modifier
                .fillMaxSize()
                .semantics { contentDescription = "Video post. Double tap to play or pause." }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { userPaused = !userPaused },
        )

        AnimatedVisibility(
            visible = userPaused,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center),
        ) {
            Box(
                Modifier
                    .size(68.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(36.dp),
                )
            }
        }
    }
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
    // than whatever's in light/dark mode. 48dp, not the 40dp this used to be:
    // Android's minimum touch target is 48dp and this is the only control on
    // the page.
    IconButton(
        onClick = { sheetOpen = true },
        modifier = modifier.size(48.dp),
    ) {
        Box(
            Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.40f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.MoreVert, contentDescription = "More options", tint = Color.White)
        }
    }

    if (sheetOpen) {
        ModalBottomSheet(
            onDismissRequest = { sheetOpen = false },
            sheetState = rememberModalBottomSheetState(),
        ) {
            Column(
                // Without this the last action sits under the gesture bar on
                // a gesture-navigation device.
                Modifier
                    .navigationBarsPadding()
                    .padding(bottom = HopSpacing.sm),
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
}

@Composable
private fun SheetAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.primary,
    enabled: Boolean = true,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(0.dp),
        modifier = Modifier
            .fillMaxWidth()
            // A TextButton's default height is below Android's 48dp minimum
            // touch target, and these rows are the only way to reach block,
            // report and stop-sharing.
            .heightIn(min = 56.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (enabled) tint else tint.copy(alpha = 0.38f),
            modifier = Modifier.padding(end = HopSpacing.md),
        )
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            },
            modifier = Modifier.weight(1f),
        )
    }
}
