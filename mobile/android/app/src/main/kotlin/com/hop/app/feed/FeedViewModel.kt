package com.hop.app.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hop.data.PostEntity
import com.hop.repository.BlockRepository
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

    private companion object {
        const val MAX_CACHE_SIZE = 3
    }
}
