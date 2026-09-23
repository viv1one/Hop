package com.hop.app.composer

import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import com.hop.app.theme.ReachOptionGroup
import com.hop.app.theme.HopSpacing
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.runtime.produceState
import androidx.compose.material3.Surface
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.hop.app.AppContainer
import com.hop.protocol.ContentType
import com.hop.protocol.ReachTier
import com.hop.protocol.Geohash
import com.hop.protocol.ReachTierGeohash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Post composer: pick a photo/video from the device's media library (v1 has
 * no in-app camera -- PRD §4.1, §8), choose a reach tier for this post
 * (pre-filled from the persisted default, overridable here without changing
 * that default -- PRD §4.2), and post. No transport/send to other devices
 * yet -- posting here lands the post in this device's own feed only (see
 * [PostComposerViewModel]'s doc).
 *
 * The media picker (mime-type-based [ContentType] resolution, 15s video
 * duration cap) is ported near-verbatim from `com.hop.spike.MainActivity`'s
 * `pickMediaLauncher`/`onMediaPicked` -- UI-adjacent logic with no hardware/
 * radio dependency, so a near-literal port is appropriate here rather than a
 * redesign.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostComposerScreen(
    container: AppContainer,
    onPosted: () -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val viewModel: PostComposerViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                PostComposerViewModel(
                    defaultReachTier = container.settingsRepository.defaultReachTier,
                    getOrCreateSenderDeviceId = { container.settingsRepository.getOrCreateStableSenderDeviceId() },
                    postRepository = container.postRepository,
                    decayKeyStore = container.decayKeyStore,
                    postsDir = File(context.filesDir, "posts"),
                    broadcastPost = { encoded -> container.transportManager.broadcastPost(encoded) },
                    // Phase 4 Slice 7: composes LocationProvider + DhtNodeManager
                    // into the single narrow suspend capability
                    // PostComposerViewModel.post() calls for every reach tier
                    // above Locality. Never called for Locality -- see that
                    // view model's own doc for why that guard lives there, not
                    // here. Either half being unavailable (no location fix,
                    // DHT node not ready within its bounded wait) just skips
                    // this attempt -- logged, never surfaced to the user (mesh
                    // mechanics stay invisible, PRD §5).
                    publishToDht = { tier ->
                        val location = container.locationProvider.currentLocation()
                        if (location == null) {
                            android.util.Log.d("PostComposerScreen", "Skipping DHT publish for $tier -- no location available")
                        } else {
                            val subscription = container.dhtNodeManager.awaitTopicSubscription()
                            if (subscription == null) {
                                android.util.Log.d("PostComposerScreen", "Skipping DHT publish for $tier -- DHT node not ready")
                            } else {
                                subscription.publish(location.latitude, location.longitude, tier)
                            }
                        }
                    },
                    // Phase 4 Slice 9: the other narrow capability composed
                    // from container.locationProvider, alongside publishToDht
                    // above -- resolves this device's current location into
                    // the geohash-prefix string PostComposerViewModel.post()
                    // stamps onto Frame.originGeohashPrefix for a Town/City/
                    // Country post, so a later peer can answer a
                    // TIER_KEY_REQUEST for it without ever learning this
                    // device's raw coordinates. Returns null (never throws)
                    // exactly when publishToDht's own location read would
                    // have skipped -- that view model already treats null
                    // as "post with an empty originGeohashPrefix," logged,
                    // never surfaced to the user (mesh mechanics stay
                    // invisible, PRD §5).
                    getOriginGeohashPrefix = { tier ->
                        val location = container.locationProvider.currentLocation()
                        location?.let {
                            Geohash.encode(it.latitude, it.longitude, ReachTierGeohash.precisionFor(tier))
                        }
                    },
                )
            }
        },
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    var pendingContentType by remember { mutableStateOf<ContentType?>(null) }
    var pickErrorMessage by remember { mutableStateOf<String?>(null) }
    var isReadingMedia by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }

    val pickMediaLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult

        val mimeType = context.contentResolver.getType(uri)
        val contentType = when {
            mimeType?.startsWith("image/") == true -> ContentType.PHOTO
            mimeType?.startsWith("video/") == true -> ContentType.VIDEO
            else -> null
        }
        if (contentType == null) {
            pickErrorMessage = "That file type isn't supported — pick a photo or video."
            return@rememberLauncherForActivityResult
        }

        // BUILD_PLAN.md open decision #3 (settled via real-device spike): v1
        // has no in-app camera, so HOP never controls the source encode --
        // real phone camera output runs ~20-22 Mbps native, and a ~15s clip is
        // what keeps a WiFi Direct transfer inside the §7 NFR's "low
        // single-digit seconds" bar. Enforced here at pick time, same cap and
        // same MediaMetadataRetriever approach as the Phase 0 spike.
        if (contentType == ContentType.VIDEO) {
            val retriever = MediaMetadataRetriever()
            val durationMs = try {
                retriever.setDataSource(context, uri)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            } finally {
                retriever.release()
            }
            if (durationMs == null) {
                pickErrorMessage = "Couldn't read that video's length — try a different one."
                return@rememberLauncherForActivityResult
            }
            if (durationMs > MAX_VIDEO_DURATION_MS) {
                pickErrorMessage =
                    "That video is longer than ${MAX_VIDEO_DURATION_MS / 1000} seconds -- pick a shorter clip."
                return@rememberLauncherForActivityResult
            }
        }

        pickErrorMessage = null
        pendingUri = uri
        pendingContentType = contentType
    }

    LaunchedEffect(uiState.postComplete) {
        if (uiState.postComplete) onPosted()
    }

    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let { message -> snackbarHostState.showSnackbar(message) }
    }

    LaunchedEffect(pickErrorMessage) {
        pickErrorMessage?.let { message -> snackbarHostState.showSnackbar(message) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New post") },
                navigationIcon = {
                    // An icon, not a "Cancel" text button: the navigation
                    // slot is icon-sized, and the label was cramped against
                    // the title. aria equivalent supplied for the icon.
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Filled.Close, contentDescription = "Cancel")
                    }
                },
            )
        },
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data -> Snackbar { Text(data.visuals.message) } }
        },
        bottomBar = {
            // Pinned rather than scrolling with the content: the primary
            // action should not be somewhere below the fold on a short
            // screen, and it sits in the thumb zone here.
            Surface(
                tonalElevation = 3.dp,
                shadowElevation = 8.dp,
            ) {
                Button(
                    onClick = {
                        val currentUri = pendingUri
                        val currentContentType = pendingContentType
                        if (currentUri == null || currentContentType == null) return@Button
                        isReadingMedia = true
                        scope.launch {
                            val bytes = withContext(Dispatchers.IO) {
                                context.contentResolver.openInputStream(currentUri)?.use { it.readBytes() }
                            }
                            isReadingMedia = false
                            if (bytes == null || bytes.isEmpty()) {
                                pickErrorMessage = "Couldn't read that file — try again."
                                return@launch
                            }
                            viewModel.post(bytes = bytes, contentType = currentContentType)
                        }
                    },
                    enabled = pendingUri != null && pendingContentType != null &&
                        !uiState.isPosting && !isReadingMedia,
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(vertical = 16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = HopSpacing.lg, vertical = HopSpacing.sm),
                ) {
                    val busy = uiState.isPosting || isReadingMedia
                    if (busy) {
                        // A label change alone left the button looking
                        // simply disabled while a large video was read and
                        // encrypted.
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier
                                .padding(end = HopSpacing.sm)
                                .size(18.dp),
                        )
                    }
                    Text(
                        text = if (busy) "Posting..." else "Post",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = HopSpacing.lg, vertical = HopSpacing.md),
            verticalArrangement = Arrangement.spacedBy(HopSpacing.lg),
        ) {
            val uri = pendingUri
            val contentType = pendingContentType

            val launchPicker = {
                pickMediaLauncher.launch(
                    PickVisualMediaRequest(
                        mediaType = ActivityResultContracts.PickVisualMedia.ImageAndVideo,
                    ),
                )
            }

            // The composer previously showed only the words "Photo
            // selected" -- you could not see what you were about to post.
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                MediaPreview(
                    uri = uri,
                    contentType = contentType,
                    onPick = launchPicker,
                )
            }

            Text(
                text = "Who should see this?",
                style = MaterialTheme.typography.titleMedium,
            )

            ReachOptionGroup(
                selectedTier = uiState.selectedReachTier,
                onTierSelected = viewModel::onReachTierSelected,
            )
        }
    }
}

/**
 * The picked photo/video, or a tappable empty slot when nothing is picked.
 *
 * 4:5 is the tallest portrait ratio the feed renders without heavy cropping,
 * so it is the honest frame to review a pick in. Decoding happens off the
 * main thread and downsampled -- a full-resolution camera photo decoded in
 * composition is tens of megabytes and janks the screen open.
 */
@Composable
private fun MediaPreview(
    uri: Uri?,
    contentType: ContentType?,
    onPick: () -> Unit,
) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(18.dp)

    val preview by produceState<ImageBitmap?>(initialValue = null, key1 = uri, key2 = contentType) {
        val currentUri = uri
        value = if (currentUri == null || contentType == null) {
            null
        } else {
            withContext(Dispatchers.IO) {
                runCatching { loadPreviewBitmap(context, currentUri, contentType) }.getOrNull()
            }
        }
    }

    Box(
        modifier = Modifier
            // Height-capped rather than full-width 4:5. At full width the
            // preview was taller than the viewport on a normal phone, which
            // pushed "Who should see this?" -- the actual decision on this
            // screen -- entirely below the fold. matchHeightConstraintsFirst
            // keeps the 4:5 frame honest by deriving width from this height.
            .height(PREVIEW_HEIGHT)
            .aspectRatio(4f / 5f, matchHeightConstraintsFirst = true)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable(onClick = onPick),
        contentAlignment = Alignment.Center,
    ) {
        when {
            uri == null || contentType == null -> EmptyPickSlot()

            preview != null -> {
                Image(
                    bitmap = preview!!,
                    contentDescription = when (contentType) {
                        ContentType.PHOTO -> "Selected photo"
                        ContentType.VIDEO -> "Selected video"
                    },
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                // Change affordance, over a scrim so it reads on any frame.
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(HopSpacing.sm)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = HopSpacing.md, vertical = HopSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Change",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White,
                    )
                }
                if (contentType == ContentType.VIDEO) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(HopSpacing.sm)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black.copy(alpha = 0.55f))
                            .padding(horizontal = HopSpacing.sm, vertical = 4.dp),
                    ) {
                        Text(
                            text = "Video",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                        )
                    }
                }
            }

            // Picked, but the frame has not decoded yet.
            else -> CircularProgressIndicator(strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun EmptyPickSlot() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(HopSpacing.sm),
    ) {
        Icon(
            Icons.Filled.Add,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(34.dp),
        )
        Text(
            text = "Choose photo or video",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "From your device",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Decodes a downsampled preview frame. Photos decode bounds-first so a
 * full-resolution image is never held in memory; videos take their first
 * frame via [MediaMetadataRetriever], the same class the duration cap above
 * already uses.
 */
private fun loadPreviewBitmap(
    context: android.content.Context,
    uri: Uri,
    contentType: ContentType,
): ImageBitmap? = when (contentType) {
    ContentType.VIDEO -> {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            retriever.frameAtTime?.asImageBitmap()
        } finally {
            retriever.release()
        }
    }

    ContentType.PHOTO -> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        var sample = 1
        while (bounds.outWidth / sample > PREVIEW_TARGET_WIDTH_PX) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)?.asImageBitmap()
        }
    }
}

/** Tall enough to judge a pick, short enough to leave the reach choice on screen. */
private val PREVIEW_HEIGHT = 300.dp

/** Preview slot is at most a phone width; decoding beyond that is wasted memory. */
private const val PREVIEW_TARGET_WIDTH_PX = 1080

// Settled by real-device measurement -- see BUILD_PLAN.md open decision #3
// and the matching constant in com.hop.spike.MainActivity.
private const val MAX_VIDEO_DURATION_MS = 15_000L
