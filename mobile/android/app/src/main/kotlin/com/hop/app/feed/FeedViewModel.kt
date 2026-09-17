package com.hop.app.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hop.data.DontRelayFlagEntity
import com.hop.data.PostEntity
import com.hop.dht.Contact
import com.hop.protocol.ReachTier
import com.hop.protocol.TierKeyRequestEnvelope
import com.hop.protocol.TierMembershipClaim
import com.hop.repository.BlockRepository
import com.hop.repository.DontRelayRepository
import com.hop.repository.PostRepository
import com.hop.repository.ReportRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Backs [FeedScreen]. Combines [PostRepository.observeAllPosts] with the
 * block/report repositories' flows so a blocked sender's posts and a
 * self-reported post never render in this feed -- filtering happens once,
 * here, rather than being re-derived per-screen.
 *
 * [decrypt] fronts [PostRepository.decrypt] with a small bounded LRU cache
 * (current page +/- 1, capped at [MAX_CACHE_SIZE]). This is a real
 * security-relevant boundary, not just a perf nicety: holding decrypted
 * plaintext for the *whole* feed in memory at once would undercut ADR 0003's
 * decay model in the same spirit as writing it to disk unbounded would --
 * an in-memory copy that outlives the moment its key was legitimately live
 * is exactly the kind of casual-access surface decay-by-key-expiry is meant
 * to close off. Capping it to a handful of nearby pages keeps the exposure
 * bounded to what the user is actually looking at right now.
 */
class FeedViewModel(
    private val postRepository: PostRepository,
    private val blockRepository: BlockRepository,
    private val reportRepository: ReportRepository,
    /** Phase 2 Slice 2's "don't relay" distinct-attested-device flag counter (see its own doc). */
    private val dontRelayRepository: DontRelayRepository,
    /**
     * This device's own attested public key (ADR 0004), hex-encoded -- a
     * narrow suspend capability (matching [getOwnPeerId]-style injection
     * used elsewhere in this app) rather than a hard dependency on
     * `SettingsRepository`'s full surface.
     */
    private val getAttestedDeviceKey: suspend () -> String,
    /** Delegates to `TransportManager.broadcastDontRelayFlag` -- propagates this device's own flag onto the mesh. */
    private val broadcastDontRelayFlag: suspend (DontRelayFlagEntity) -> Unit,
    /**
     * Phase 4 Slice 7: best-effort DHT topic-subscription browse (see
     * [com.hop.topics.TopicSubscription.browse]) for this device's current
     * browse-tier reach setting -- a narrow suspend capability, same pattern
     * as [broadcastDontRelayFlag]/[getAttestedDeviceKey] above, so this class
     * stays unit-testable with a trivial fake lambda instead of needing to
     * fake `SettingsRepository`/`com.hop.app.location.LocationProvider`/
     * `com.hop.app.dht.DhtNodeManager` here. [FeedScreen] composes the real
     * one: reads `SettingsRepository.defaultReachTier` (the SAME single
     * reach-tier value posts use, per that repository's own doc -- not a
     * second "browse radius" setting), skips entirely for
     * [com.hop.protocol.ReachTier.LOCALITY] (that tier never touches the DHT
     * -- ADR 0003, resolved entirely over BLE/WiFi Direct instead), reads a
     * location, and calls `TopicSubscription.browse`. Defaults to
     * `{ emptyList() }` so every test that doesn't care about DHT browsing
     * doesn't need to fake this at all.
     */
    private val browseNearbyDht: suspend () -> List<Contact> = { emptyList() },
    /**
     * Phase 4 Slice 10: delegates to `TransportManager.broadcastTierKeyRequest`
     * -- a narrow suspend capability, same pattern as [broadcastDontRelayFlag]/
     * [getAttestedDeviceKey] above, so this class stays unit-testable with a
     * trivial fake lambda instead of a real `TransportManager`. Fired
     * best-effort from [decrypt] on an [PostRepository.DecryptResult.AwaitingKey]
     * outcome -- see that function's own doc for the cache-miss trigger and
     * [maybeRequestTierKey]'s cooldown.
     */
    private val broadcastTierKeyRequest: suspend (TierKeyRequestEnvelope) -> Unit = {},
    /**
     * Phase 4 Slice 10: builds a fresh [TierMembershipClaim] for [tier] from
     * this device's *current* location -- the same narrow suspend-lambda
     * capability shape as [browseNearbyDht]/`PostComposerScreen`'s own
     * `getOriginGeohashPrefix` (see that lambda's doc for why this stays a
     * capability rather than a direct `LocationProvider` dependency here).
     * Returns `null` exactly when no location fix is available right now
     * (mirrors `LocationProvider.currentLocation()`'s own contract) --
     * [maybeRequestTierKey] treats that as "skip this attempt," logged, never
     * surfaced to the user (mesh mechanics stay invisible, PRD §5). Never
     * called with [ReachTier.LOCALITY] -- [decrypt] only reaches this for an
     * [PostRepository.DecryptResult.AwaitingKey] outcome, which [PostRepository.decrypt]
     * never returns for Locality (ADR 0003: that tier never touches the
     * separate key-distribution path this claim is for).
     */
    private val buildTierMembershipClaim: suspend (ReachTier) -> TierMembershipClaim? = { null },
    /**
     * Phase 4 Slice 11: delegates to
     * `com.hop.transport.InternetPeerConnectionManager.connectToDiscoveredHolders`
     * -- the same narrow suspend-capability pattern as [browseNearbyDht]/
     * [broadcastTierKeyRequest]/[buildTierMembershipClaim] above, so this
     * class stays unit-testable with a trivial fake lambda instead of a real
     * `InternetPeerConnectionManager`. Fired best-effort from [launchDiscovery]
     * right after [browseNearbyDht] returns its holder list -- this is the
     * fix for the exact gap [discoveredRemoteHolders]'s own doc used to
     * describe verbatim ("no transport here that fetches an actual clip FROM
     * one of these contacts"): a real internet connection is now actually
     * attempted for each newly-discovered holder, not just a [Contact] list
     * sitting unused. Defaults to `{}` so every existing test that doesn't
     * care about this doesn't need to fake it.
     */
    private val connectToDiscoveredHolders: suspend (List<Contact>) -> Unit = {},
) : ViewModel() {

    val posts: StateFlow<List<PostEntity>> = combine(
        postRepository.observeAllPosts(),
        blockRepository.observeBlockedSenderIds(),
        reportRepository.observeReportedClipHashes(),
    ) { allPosts, blockedSenderIds, reportedClipHashes ->
        allPosts.filter { post ->
            post.senderDeviceId !in blockedSenderIds && post.clipHash !in reportedClipHashes
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _discoveredRemoteHolders = MutableStateFlow<List<Contact>>(emptyList())

    /**
     * Devices [browseNearbyDht] found claiming to hold content in this
     * device's current browse-tier cell(s) (Town/City/Country only -- see
     * [browseNearbyDht]'s own doc). Populated once at construction, and
     * again on every user-triggered [refresh] -- see that function's own
     * doc for why a manual refresh only re-runs this, not [posts] itself.
     *
     * **What this is (as of Phase 4 Slice 11):** [com.hop.topics.TopicSubscription.browse]
     * itself still only returns [Contact]s -- who claims to hold something --
     * never content (see that class's own doc: it is topic-routing plumbing,
     * not a content-fetch mechanism). But every holder returned here is now
     * also handed to [connectToDiscoveredHolders]
     * (`com.hop.transport.InternetPeerConnectionManager`), which actually
     * dials each newly-discovered one over a real internet socket
     * (`com.hop.transport.InternetPeerConnection`, the internet-mode
     * content-transfer equivalent to `com.hop.transport.WifiDirectTransport`
     * this doc used to say didn't exist yet -- it does now). This [StateFlow]
     * still exists so a real DHT discovery result is never silently thrown
     * away regardless, and remains deliberately not rendered by [FeedScreen]:
     * surfacing "N people are posting somewhere out of BLE/WiFi-Direct range",
     * or "N internet peers connected," is a real product/UX decision (what
     * copy, what affordance, whether it's wanted at all, given mesh mechanics
     * are supposed to stay invisible to the user per PRD §5) that hasn't been
     * made -- not an oversight.
     */
    val discoveredRemoteHolders: StateFlow<List<Contact>> = _discoveredRemoteHolders.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)

    /** Backs [FeedScreen]'s refresh-button spinner. `true` only for the duration of a [refresh] call. */
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    init {
        launchDiscovery()
    }

    /**
     * The Instagram-style manual refresh button's action ([FeedScreen]'s
     * `IconButton`). A no-op re-entrancy guard, not a queue -- a second tap
     * while one is already in flight is dropped rather than stacked. The
     * guard flag is set synchronously, in this function, before [launchDiscovery]
     * ever suspends -- not inside the launched coroutine -- so two [refresh]
     * calls made back-to-back on the same thread (e.g. a double-tap, or two
     * calls in the same test body before the dispatcher advances) can never
     * both slip past the check.
     *
     * **Deliberately does NOT reload [posts].** [PostRepository.observeAllPosts]
     * is a Room-backed [kotlinx.coroutines.flow.Flow] that already emits every
     * local insert/update the instant it happens -- there is nothing to
     * manually re-fetch there, unlike a server-backed feed's REST refresh.
     * The only part of this feed that is a one-shot snapshot rather than a
     * live subscription is [discoveredRemoteHolders] (Town/City/Country DHT
     * discovery, Phase 4 Slice 7) -- re-running [browseNearbyDht] is the one
     * actionable thing a "refresh the whole feed" gesture can honestly mean
     * here today. Skips itself entirely (no DHT round trip at all) when
     * [browseNearbyDht] would anyway -- see that parameter's own doc for the
     * Locality/no-location/DHT-not-ready cases it already no-ops on.
     */
    fun refresh() {
        if (_isRefreshing.value) return
        launchDiscovery()
    }

    /**
     * Runs [browseNearbyDht], publishes its result to [discoveredRemoteHolders]
     * immediately (never delayed by anything below), then best-effort awaits
     * [connectToDiscoveredHolders] for that exact same holder list before
     * clearing [_isRefreshing] -- awaited inline rather than fired on a
     * separate coroutine, since [connectToDiscoveredHolders]'s own
     * implementation (`InternetPeerConnectionManager.connectToDiscoveredHolders`)
     * already bounds its own worst-case latency: a small fixed cap on new
     * dial attempts per call, each individually bounded by
     * `com.hop.p2p.PeerDialer`'s own connect timeouts. That keeps this
     * spinner's worst case *bounded*, not indefinite, which is the actual
     * property worth protecting here -- a genuinely unbounded/hanging call
     * would need the fire-and-forget treatment instead, but this one doesn't
     * qualify.
     */
    private fun launchDiscovery() {
        _isRefreshing.value = true
        viewModelScope.launch {
            try {
                val holders = browseNearbyDht()
                _discoveredRemoteHolders.value = holders
                connectToDiscoveredHolders(holders)
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    private val decryptCacheMutex = Mutex()

    // LinkedHashMap in access-order mode (`true`) + removeEldestEntry gives a
    // plain LRU without pulling in a caching library for a 3-entry cache.
    private val decryptCache = object : LinkedHashMap<String, PostRepository.DecryptResult>(
        MAX_CACHE_SIZE, 0.75f, true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, PostRepository.DecryptResult>?,
        ): Boolean = size > MAX_CACHE_SIZE
    }

    /**
     * Every [tierKeyRequestAttemptedAtMs]-throttled best-effort key request
     * fired so far this process, keyed by [PostEntity.clipHash] -- see
     * [maybeRequestTierKey]'s own doc. Deliberately a plain, unsynchronized
     * map, not a [java.util.concurrent.ConcurrentHashMap] like
     * `TransportManager.lastConnectAttemptMs` -- unlike that class's
     * multi-thread `WifiP2pManager` callbacks, every call into this
     * [ViewModel] (Compose's `LaunchedEffect`s, [refresh]) runs on the main
     * thread, so there is no concurrent-write hazard to guard against here.
     * In-memory only, same as [decryptCache] -- lost on process death, never
     * persisted; a fresh process re-attempts on the next cache-miss decrypt.
     */
    private val tierKeyRequestAttemptedAtMs = mutableMapOf<String, Long>()

    suspend fun decrypt(post: PostEntity): PostRepository.DecryptResult {
        val result = decryptCacheMutex.withLock {
            decryptCache[post.clipHash]?.let { cached -> return@withLock cached }
            val fresh = postRepository.decrypt(post)
            decryptCache[post.clipHash] = fresh
            fresh
        }
        if (result is PostRepository.DecryptResult.AwaitingKey) {
            maybeRequestTierKey(post)
        }
        return result
    }

    /**
     * Fires a best-effort [TierKeyRequestEnvelope] broadcast for [post] --
     * called only when [decrypt] just observed
     * [PostRepository.DecryptResult.AwaitingKey] for it (a Town/City/Country
     * post this device holds ciphertext for but has no live key for yet).
     *
     * Throttled independently of [decryptCache]'s own LRU eviction via
     * [tierKeyRequestAttemptedAtMs]: a cache eviction and later re-miss for
     * the same post must NOT bypass this cooldown, so this map is checked
     * regardless of whether [decrypt]'s own result just came from the cache
     * or a fresh [PostRepository.decrypt] call. [TIER_KEY_REQUEST_COOLDOWN_MS]
     * is an unmeasured placeholder, matching `TransportManager.CONNECT_COOLDOWN_MS`'s
     * own "not tuned against real data" posture.
     *
     * No correlation, timeout, retry-with-backoff, or polling here by design
     * (see [com.hop.protocol.ReachTierKeyDistribution]'s "Explicitly out of
     * scope" note) -- the user's own next pull-to-refresh (see [refresh]'s
     * doc) is what re-drives [decrypt] and, if a key arrived in the meantime,
     * picks it up. A `null` [buildTierMembershipClaim] result (no location
     * fix right now) skips the broadcast for this attempt but still records
     * the cooldown timestamp, matching `TransportManager.maybeConnect`'s own
     * "record the attempt, not just the success" shape.
     */
    private suspend fun maybeRequestTierKey(post: PostEntity) {
        val now = System.currentTimeMillis()
        val lastAttempt = tierKeyRequestAttemptedAtMs[post.clipHash]
        if (lastAttempt != null && now - lastAttempt < TIER_KEY_REQUEST_COOLDOWN_MS) return
        tierKeyRequestAttemptedAtMs[post.clipHash] = now

        val reachTier = ReachTier.valueOf(post.reachTier)
        val claim = buildTierMembershipClaim(reachTier) ?: return
        val request = TierKeyRequestEnvelope(contentId = post.clipHash.hexToByteArray(), claim = claim)
        broadcastTierKeyRequest(request)
    }

    fun blockSender(senderDeviceId: String) {
        viewModelScope.launch { blockRepository.block(senderDeviceId) }
    }

    fun reportPost(clipHash: String) {
        viewModelScope.launch { reportRepository.report(clipHash) }
    }

    /**
     * Flags [post] "don't relay" with this device's own attested identity
     * (Phase 2 Slice 2, PRD §4.6/ADR 0004): records it locally, then
     * propagates it onto the mesh. [DontRelayFlagEntity.originatedAtMs]/
     * [DontRelayFlagEntity.ttlSeconds] are copied off [post] itself -- see
     * [DontRelayFlagEntity]'s own doc for why the flag carries these rather
     * than relying on a lookup once it's already propagating (the
     * order-independence design: this flag may reach another device before
     * that device has [post] at all). Only callable once [post] has actually
     * been decrypted -- see [PostPagerItem]'s `dontRelayActionEnabled` gate;
     * this method itself does not re-check that, trusting the UI affordance
     * that invokes it.
     */
    fun flagDontRelay(post: PostEntity) {
        viewModelScope.launch {
            val row = DontRelayFlagEntity(
                clipHash = post.clipHash,
                attestedDeviceKey = getAttestedDeviceKey(),
                flaggedAtMs = System.currentTimeMillis(),
                originatedAtMs = post.originatedAtMs,
                ttlSeconds = post.ttlSeconds,
            )
            dontRelayRepository.recordFlag(row)
            broadcastDontRelayFlag(row)
        }
    }

    private companion object {
        const val MAX_CACHE_SIZE = 3

        /**
         * Unmeasured placeholder, matching `TransportManager.CONNECT_COOLDOWN_MS`'s
         * own "not tuned against real data" posture -- revisit once real
         * mesh-density/request-latency numbers exist. Deliberately shorter
         * than [PostRepository.decrypt]'s underlying [DecayKeyStore] lookups'
         * own TTLs; this bounds how often this device pesters the mesh for
         * the same post's key, not how long a granted key stays valid.
         */
        const val TIER_KEY_REQUEST_COOLDOWN_MS = 30_000L
    }
}

/** Decodes a lowercase hex string (as produced by `EncryptedFrameCodec`'s own
 * `"%02x"`-per-byte encoding) back into raw bytes -- mirrors [PostRepository]'s
 * own private helper of the same shape, needed here to build the
 * [TierKeyRequestEnvelope.contentId] this class sends. */
private fun String.hexToByteArray(): ByteArray {
    require(length % 2 == 0) { "hex string must have an even length, was $length: $this" }
    return ByteArray(length / 2) { i ->
        val start = i * 2
        substring(start, start + 2).toInt(16).toByte()
    }
}
