package com.hop.app.composer

import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
            pickErrorMessage = "That file type isn't supported -- pick a photo or video."
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
                pickErrorMessage = "Couldn't read that video's length -- try a different one."
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
                    TextButton(onClick = onCancel) { Text("Cancel") }
                },
            )
        },
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data -> Snackbar { Text(data.visuals.message) } }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val uri = pendingUri
            val contentType = pendingContentType

            if (uri == null || contentType == null) {
                Button(
                    onClick = {
                        pickMediaLauncher.launch(
                            PickVisualMediaRequest(
                                mediaType = ActivityResultContracts.PickVisualMedia.ImageAndVideo,
                            ),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Choose photo or video")
                }
            } else {
                Text(
                    text = when (contentType) {
                        ContentType.PHOTO -> "Photo selected"
                        ContentType.VIDEO -> "Video selected"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                OutlinedButton(
                    onClick = {
                        pickMediaLauncher.launch(
                            PickVisualMediaRequest(
                                mediaType = ActivityResultContracts.PickVisualMedia.ImageAndVideo,
                            ),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Choose a different one")
                }
            }

            Text(
                text = "Who should see this?",
                style = MaterialTheme.typography.titleMedium,
            )

            Column(Modifier.selectableGroup()) {
                REACH_OPTIONS.forEach { (tier, label, description) ->
                    val selected = uiState.selectedReachTier == tier
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = selected,
                                onClick = { viewModel.onReachTierSelected(tier) },
                                role = Role.RadioButton,
                            )
                            .padding(vertical = 8.dp),
                    ) {
                        RadioButton(selected = selected, onClick = null)
                        Column(modifier = Modifier.padding(start = 8.dp)) {
                            Text(text = label, style = MaterialTheme.typography.bodyLarge)
                            Text(text = description, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

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
                            pickErrorMessage = "Couldn't read that file -- try again."
                            return@launch
                        }
                        viewModel.post(bytes = bytes, contentType = currentContentType)
                    }
                },
                enabled = uri != null && contentType != null && !uiState.isPosting && !isReadingMedia,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (uiState.isPosting || isReadingMedia) "Posting..." else "Post")
            }
        }
    }
}

private data class ReachOption(val tier: ReachTier, val label: String, val description: String)

// Matches FirstRunScreen's exact labels/copy for the same four tiers -- no
// separate wording invented here, and no exposed technical language
// (geohash/tier/DHT/mesh) per PRD §5.
private val REACH_OPTIONS = listOf(
    ReachOption(ReachTier.LOCALITY, "Just around me", "The people physically near you right now"),
    ReachOption(ReachTier.TOWN, "My town", "Everyone in your town"),
    ReachOption(ReachTier.CITY, "My city", "Everyone in your city"),
    ReachOption(ReachTier.COUNTRY, "My country", "Everyone in your country"),
)

// Settled by real-device measurement -- see BUILD_PLAN.md open decision #3
// and the matching constant in com.hop.spike.MainActivity.
private const val MAX_VIDEO_DURATION_MS = 15_000L
