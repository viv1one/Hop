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
import com.hop.protocol.ReachTierKeyDistribution
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
 *
 * [publishToDht] is Phase 4 Slice 7's DHT topic-subscription publish (see
 * [com.hop.topics.TopicSubscription.publish]) for reach tiers above
 * Locality -- another narrow suspend-function capability, same pattern as
 * [broadcastPost], so this view model never needs to fake
 * `com.hop.app.location.LocationProvider`/`com.hop.app.dht.DhtNodeManager`
 * directly. [post] below calls it for every tier EXCEPT
 * [ReachTier.LOCALITY] -- that tier never touches the DHT (ADR 0003) and
 * resolves entirely over BLE/WiFi Direct instead; this view model enforces
 * that "never for Locality" invariant itself (not left to whatever composes
 * this lambda at [PostComposerScreen]), and a failure here is caught and
 * logged, never allowed to undo or fail the post itself -- by the time
 * [publishToDht] runs, the post already exists locally and has already been
 * handed to [broadcastPost].
 *
 * [getOriginGeohashPrefix] is Phase 4 Slice 9's other narrow location-reading
 * capability, alongside [publishToDht]: resolves this device's current
 * location into a geohash-prefix string at [reachTier]'s own precision
 * (`ReachTierGeohash.precisionFor`), for tiers above Locality only. Same
 * "narrow suspend function, not a whole `LocationProvider`" pattern as
 * every other capability on this constructor -- [PostComposerScreen] composes
 * it from `container.locationProvider` + `ReachTierGeohash`/`Geohash`,
 * exactly where [publishToDht]'s own composition already has a
 * `LocationProvider` in scope, rather than adding a second concrete Android
 * dependency to this view model. Returns `null` when no location fix is
 * available right now (mirrors `LocationProvider.currentLocation()`'s own
 * "unavailable for any reason" contract) -- [post] below logs that and
 * falls back to an empty `originGeohashPrefix` rather than failing the post
 * itself, matching [publishToDht]'s "never undo an already-locally-saved
 * post" posture. An empty `originGeohashPrefix` is a real limitation, not a
 * silent no-op: per ADR 0003, no peer will ever be able to answer a future
 * `TierKeyRequestEnvelope` for this post (a valid claim can never match an
 * empty target-cell set), so this device's own non-Locality post becomes
 * permanently key-less for everyone, itself included past whatever it
 * already has locally in [decayKeyStore].
 */
class PostComposerViewModel(
    private val defaultReachTier: Flow<ReachTier?>,
    private val getOrCreateSenderDeviceId: suspend () -> ByteArray,
    private val postRepository: PostRepository,
    private val decayKeyStore: DecayKeyStore,
    private val postsDir: File,
    private val broadcastPost: (ByteArray) -> Unit,
    private val publishToDht: suspend (ReachTier) -> Unit = {},
    private val getOriginGeohashPrefix: suspend (ReachTier) -> String? = { null },
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
                // Read once, up front -- reused after the withContext block
                // below decides whether/what to publish to the DHT.
                val reachTier = _uiState.value.selectedReachTier

                // Resolved once, up front, same as reachTier above -- never
                // for LOCALITY (it never touches the DHT, ADR 0003, and
                // Frame.originGeohashPrefix is unused there). Calling this
                // before the ioDispatcher switch below is fine even though
                // it's a real suspend call: LocationProvider's own
                // implementation is main-thread-safe (suspendCancellableCoroutine
                // over a Play Services callback), same as publishToDht's own
                // composition already assumes.
                val originGeohashPrefix = if (reachTier == ReachTier.LOCALITY) {
                    ""
                } else {
                    getOriginGeohashPrefix(reachTier) ?: run {
                        android.util.Log.d(
                            "PostComposerViewModel",
                            "No location available for a $reachTier post -- posting with an empty " +
                                "originGeohashPrefix (no peer will be able to answer a future tier-key " +
                                "request for it)",
                        )
                        ""
                    }
                }

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
                    val clipHash = MessageDigest.getInstance("SHA-256").digest(bytes)
                    val senderDeviceId = getOrCreateSenderDeviceId()
                    val ttlSeconds = ttlSecondsFor(reachTier)
                    val originatedAtMs = System.currentTimeMillis()

                    val encodeResult = EncryptedFrameCodec.encode(
                        plaintext = bytes,
                        clipHash = clipHash,
                        senderDeviceId = senderDeviceId,
                        contentType = contentType,
                        hopCount = 0,
                        originatedAtMs = originatedAtMs,
                        ttlSeconds = ttlSeconds,
                        reachTier = reachTier,
                        dontRelay = false,
                        originGeohashPrefix = originGeohashPrefix,
                    )
                    val encoded = encodeResult.encoded

                    // Decoded back only to recover the ciphertext `payload`
                    // for the disk write below -- the real content-encryption
                    // key comes directly from `encodeResult.contentEncryptionKey`
                    // now, never by decoding it back out of `encoded`. That
                    // decode-it-back trick (this function's pre-Phase-4-Slice-9
                    // shape) only worked because encode() used to always
                    // inline the real key on the wire; for Town/City/Country,
                    // `encoded`'s own copy is zero-filled (keyIncluded=false),
                    // so decoding it back here would silently make this
                    // device's own post undecryptable even by its own sender.
                    val frame = Frame.decode(encoded)
                    val clipHashHex = frame.clipHash.toHexString()

                    // Storage key mirrors ReachTierKeyDistribution's own
                    // per-tier composition -- LOCALITY keeps the plain
                    // clipHashHex key (unchanged pre-Slice-9 behavior);
                    // Town/City/Country store under the tiered key so this
                    // device's own later re-view of its own post (via
                    // PostRepository.decrypt) and any peer's later
                    // TIER_KEY_REQUEST lookup (via
                    // EnvelopeDispatcher.dispatch) both find the same entry.
                    // decayKeyStoreKeyFor encapsulates the LOCALITY special-case
                    // itself -- see that function's own doc.
                    val decayKeyStorageKey = ReachTierKeyDistribution.decayKeyStoreKeyFor(clipHashHex, reachTier)
                    decayKeyStore.store(
                        contentId = decayKeyStorageKey,
                        wrappedCek = encodeResult.contentEncryptionKey,
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
                            originGeohashPrefix = originGeohashPrefix,
                            dontRelay = false,
                            receivedAtMs = System.currentTimeMillis(),
                            encryptedPayloadFilePath = payloadFile.absolutePath,
                        ),
                    )

                    broadcastPost(encoded)
                }

                // ADR 0003 / hop-dev skill invariant: Locality never touches
                // the DHT, resolving entirely over BLE/WiFi Direct instead --
                // publishToDht is never even called for it. Best-effort for
                // every other tier: a DHT publish failure (no location, DHT
                // node not ready, network hiccup) is logged and swallowed
                // here, never allowed to undo the post that already succeeded
                // locally and was already handed to broadcastPost above.
                if (reachTier != ReachTier.LOCALITY) {
                    try {
                        publishToDht(reachTier)
                    } catch (e: Exception) {
                        android.util.Log.e("PostComposerViewModel", "DHT publish failed (post already saved locally)", e)
                    }
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
                    it.copy(isPosting = false, errorMessage = "Couldn't post that — try again.")
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
