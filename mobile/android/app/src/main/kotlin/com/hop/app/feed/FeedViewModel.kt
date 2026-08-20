package com.hop.app.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hop.data.DontRelayFlagEntity
import com.hop.data.PostEntity
import com.hop.repository.BlockRepository
import com.hop.repository.DontRelayRepository
import com.hop.repository.PostRepository
import com.hop.repository.ReportRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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

    suspend fun decrypt(post: PostEntity): PostRepository.DecryptResult = decryptCacheMutex.withLock {
        decryptCache[post.clipHash]?.let { cached -> return@withLock cached }
        val result = postRepository.decrypt(post)
        decryptCache[post.clipHash] = result
        result
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
    }
}
