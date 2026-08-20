package com.hop.app.composer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hop.crypto.DecayKeyStore
import com.hop.data.PostEntity
import com.hop.data.SettingsRepository
import com.hop.protocol.ContentType
import com.hop.protocol.EncryptedFrameCodec
import com.hop.protocol.Frame
import com.hop.protocol.ReachTier
import com.hop.repository.PostRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.security.MessageDigest
import java.time.Duration

/**
 * Backs [PostComposerScreen]. Deliberately takes already-read plaintext
 * [ByteArray]s from the picked media rather than a [android.net.Uri] plus
 * `ContentResolver` -- reading the picked file is a `Context`-dependent,
 * Android-framework operation that belongs in the screen (same as the
 * mime-type/duration validation ported from `com.hop.spike.MainActivity`);
 * everything from "hash these bytes" onward is plain JVM logic and stays
 * fully unit-testable with a faked [PostRepository]/[DecayKeyStore], matching
 * this repo's hand-rolled-fakes testing pattern (no mocking library, see
 * `FeedViewModelTest`).
 *
 * Likewise takes [postsDir] (a plain [File], e.g.
 * `File(context.filesDir, "posts")`) rather than a `Context` -- so the
 * ciphertext-write step is also a plain [File] operation a JVM test can
 * exercise against a real temp directory, no Android framework/Robolectric
 * needed.
 *
 * [defaultReachTier] and [getOrCreateSenderDeviceId] are threaded in as the
 * two specific [SettingsRepository] capabilities this screen needs, rather
 * than the whole [SettingsRepository] instance -- `SettingsRepository` is a
 * concrete DataStore/`Context`-backed class with no fake-friendly seam (and
 * is explicitly out of scope to modify for this task), and this repo has no
 * Robolectric setup to construct a real one in a plain JVM unit test. Passing
 * its two members as a `Flow` and a suspend function keeps this view model
 * fully unit-testable with trivial fakes (`flowOf(...)`, `{ someBytes }`)
 * while [PostComposerScreen] still wires them straight from the real
 * `container.settingsRepository`.
 *
 * [ioDispatcher] defaults to [Dispatchers.IO] in production but is an
 * injectable constructor parameter (not hard-coded inside [post]) so a JVM
 * unit test can supply a `TestDispatcher` instead -- a real dispatcher switch
 * here is necessary (see [post]'s doc: Room throws if its blocking queries
 * run on the main thread) but hard-coding `Dispatchers.IO` directly would run
 * that work on a real thread pool outside a `StandardTestDispatcher`'s
 * virtual-time control, breaking `advanceUntilIdle()`-based tests.
 *
 * [broadcastPost] is the one specific [com.hop.transport.TransportManager]
 * capability this screen needs (delegates to
 * `TransportManager.broadcastPost`), threaded in as a narrow function
 * parameter rather than the whole `TransportManager` -- same pattern as
 * [defaultReachTier]/[getOrCreateSenderDeviceId] above, and for the same
 * reason: `TransportManager` is a concrete `Context`/`WifiP2pManager`-backed
 * class with no fake-friendly seam, so a plain no-op lambda is all a JVM unit
 * test needs to stand in for it.
 */
class PostComposerViewModel(
    private val defaultReachTier: Flow<ReachTier?>,
    private val getOrCreateSenderDeviceId: suspend () -> ByteArray,
    private val postRepository: PostRepository,
    private val decayKeyStore: DecayKeyStore,
    private val postsDir: File,
    private val broadcastPost: (ByteArray) -> Unit,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    data class UiState(
        val selectedReachTier: ReachTier = ReachTier.LOCALITY,
        val isPosting: Boolean = false,
        val postComplete: Boolean = false,
        val errorMessage: String? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        // Pre-fill from the persisted default, read once (not observed live) --
        // this screen's reach-tier control is a per-post override only. Picking
        // a different tier here (see [onReachTierSelected]) must never write
        // back to the default; only FirstRunViewModel/settings ever call
        // `settingsRepository.setDefaultReachTier`.
        viewModelScope.launch {
            val default = defaultReachTier.first() ?: ReachTier.LOCALITY
            _uiState.update { it.copy(selectedReachTier = default) }
        }
    }

    fun onReachTierSelected(tier: ReachTier) {
        _uiState.update { it.copy(selectedReachTier = tier, errorMessage = null) }
    }

    /**
     * Encrypts [bytes] under a freshly generated content-encryption key,
     * stores that key in [decayKeyStore] on this device's own decay/reach-tier
     * schedule (ADR 0003) so the sender can re-view their own post later,
     * writes the ciphertext under [postsDir], inserts a [PostEntity] row so
     * the post is immediately visible in this device's own feed, and hands
     * the encoded frame to [broadcastPost], which both live-pushes it to any
     * currently-connected WiFi Direct peer and (as of Phase 2 Slice 1) takes
     * persisted relay custody of it via `RelayRepository`, so it's still
     * offered to a peer this device only meets *after* the post was made --
     * see `WifiDirectTransport.broadcastPost`'s own doc for exactly what
     * that does and doesn't guarantee (still no ack/retry, and still bounded
     * by `RelayPolicy`'s hop-count/decay limits).
     */
    fun post(bytes: ByteArray, contentType: ContentType) {
        if (_uiState.value.isPosting) return
        _uiState.update { it.copy(isPosting = true, errorMessage = null) }

        viewModelScope.launch {
            try {
                // viewModelScope.launch runs on Dispatchers.Main.immediate by
                // default. decayKeyStore.store() (-> RoomDecayKeyStorage ->
                // DecayKeyDao.insertOrReplace, deliberately non-suspend/blocking
                // per its own doc) and the plain File I/O below both hit real
                // disk/SQLite work, and Room throws IllegalStateException if a
                // blocking query runs on the main thread (no
                // allowMainThreadQueries() is set, deliberately, in
                // AppContainer). Confirmed by real on-device testing -- the
                // previous version of this function had no dispatcher switch
                // here at all and failed every single post with exactly that
                // exception, silently swallowed by the catch block below before
                // this fix also added logging (see catch).
                withContext(ioDispatcher) {
                    val reachTier = _uiState.value.selectedReachTier
                    val clipHash = MessageDigest.getInstance("SHA-256").digest(bytes)
                    val senderDeviceId = getOrCreateSenderDeviceId()
                    val ttlSeconds = ttlSecondsFor(reachTier)
                    val originatedAtMs = System.currentTimeMillis()

                    val encoded = EncryptedFrameCodec.encode(
                        plaintext = bytes,
                        clipHash = clipHash,
                        senderDeviceId = senderDeviceId,
                        contentType = contentType,
                        hopCount = 0,
                        originatedAtMs = originatedAtMs,
                        ttlSeconds = ttlSeconds,
                        reachTier = reachTier,
                        dontRelay = false,
                    )

                    // Extract the CEK actually embedded in the frame we just built --
                    // never call ContentEncryption.generateKey() again here, that
                    // would produce a key that doesn't match what's in `encoded`,
                    // silently making this post undecryptable even by its own
                    // sender.
                    val frame = Frame.decode(encoded)
                    val clipHashHex = frame.clipHash.toHexString()

                    decayKeyStore.store(
                        contentId = clipHashHex,
                        wrappedCek = frame.contentEncryptionKey,
                        decayWindow = Duration.ofSeconds(ttlSeconds),
                    )

                    postsDir.mkdirs()
                    val payloadFile = File(postsDir, "$clipHashHex.enc")
                    payloadFile.writeBytes(frame.payload)

                    postRepository.insert(
                        PostEntity(
                            clipHash = clipHashHex,
                            senderDeviceId = senderDeviceId.toHexString(),
                            contentType = contentType.name,
                            originatedAtMs = originatedAtMs,
                            ttlSeconds = ttlSeconds,
                            reachTier = reachTier.name,
                            dontRelay = false,
                            receivedAtMs = System.currentTimeMillis(),
                            encryptedPayloadFilePath = payloadFile.absolutePath,
                        ),
                    )

                    broadcastPost(encoded)
                }

                _uiState.update { it.copy(isPosting = false, postComplete = true) }
            } catch (e: Exception) {
                // Logged, not just surfaced as a generic UI message -- the prior
                // version of this catch block swallowed the real exception
                // entirely (no Log call), which is exactly why the main-thread
                // Room bug above was invisible in logcat during real-device
                // testing until this fix added it.
                android.util.Log.e("PostComposerViewModel", "Failed to post", e)
                _uiState.update {
                    it.copy(isPosting = false, errorMessage = "Couldn't post that -- try again.")
                }
            }
        }
    }

    /**
     * Phase 1's decay window is a single flat placeholder for every reach
     * tier, not a tuned schedule -- ADR 0003 explicitly calls Phase 1's decay
     * rule "crude," and Phase 1 only really has Locality traffic (no DHT/
     * per-tier key-wrapping until Phase 4, see [EncryptedFrameCodec]'s own
     * doc). What matters now is that the key-wrapping *shape* (a decay window
     * attached to every post) exists from day one so it isn't a breaking
     * wire-format change later.
     */
    private fun ttlSecondsFor(@Suppress("UNUSED_PARAMETER") reachTier: ReachTier): Long =
        PLACEHOLDER_DECAY_WINDOW_SECONDS

    private fun ByteArray.toHexString(): String =
        joinToString(separator = "") { byte -> "%02x".format(byte) }

    private companion object {
        /** Placeholder Phase 1 decay window: 24 hours, flat across all reach tiers. */
        const val PLACEHOLDER_DECAY_WINDOW_SECONDS = 24L * 60 * 60
    }
}
