package com.hop.app.feed

import com.hop.crypto.DecayKeyStore
import com.hop.data.BlockedSenderDeviceDao
import com.hop.data.BlockedSenderDeviceEntity
import com.hop.data.DontRelayFlagDao
import com.hop.data.DontRelayFlagEntity
import com.hop.data.PostDao
import com.hop.data.PostEntity
import com.hop.data.RelayQueueDao
import com.hop.data.RelayQueueEntity
import com.hop.data.ReportedPostDao
import com.hop.data.ReportedPostEntity
import com.hop.dht.Contact
import com.hop.dht.NodeId
import com.hop.protocol.ContentType
import com.hop.protocol.EncryptedFrameCodec
import com.hop.protocol.Frame
import com.hop.protocol.ReachTier
import com.hop.protocol.RelayPolicy
import com.hop.protocol.TierKeyRequestEnvelope
import com.hop.protocol.TierMembershipClaim
import com.hop.repository.BlockRepository
import com.hop.repository.DontRelayRepository
import com.hop.repository.PostRepository
import com.hop.repository.ReportRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals

/**
 * Hand-rolled fakes throughout (no mocking library exists in this repo, see
 * [PostRepository]/[BlockRepository]/[ReportRepository]'s `open` doc
 * comments) -- these implement the real DAO interfaces / subclass the real
 * repositories, matching this repo's existing `DecayKeyStorage`-style
 * fake-vs-real-backing split rather than reaching for a new dependency.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FeedViewModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun samplePost(
        clipHash: String,
        senderDeviceId: String = "sender-1",
        reachTier: String = "LOCALITY",
    ) = PostEntity(
        clipHash = clipHash,
        senderDeviceId = senderDeviceId,
        contentType = "PHOTO",
        originatedAtMs = 1_700_000_000_000L,
        ttlSeconds = 3600,
        reachTier = reachTier,
        dontRelay = false,
        receivedAtMs = 1000L,
        encryptedPayloadFilePath = "/unused/for/this/test/$clipHash.enc",
    )

    /** SHA-256 hex digest of [seed] -- a valid 32-byte hex clipHash, as [TierKeyRequestEnvelope.contentId] requires (unlike this file's other, non-hex sample clipHashes such as "clip-1"). */
    private fun sha256Hex(seed: String): String =
        MessageDigest.getInstance("SHA-256").digest(seed.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun ByteArray.toHexString(): String = joinToString(separator = "") { "%02x".format(it) }

    /**
     * Builds a real Town/City/Country [PostEntity] (real [EncryptedFrameCodec]-encoded
     * ciphertext on disk, matching [com.hop.repository.PostRepositoryTest]'s own
     * pattern) with no key ever stored under any [DecayKeyStore] composition,
     * and inserts it into [postDao] -- used by the "never triggers a broadcast"
     * test below, which needs [PostRepository.decrypt]'s *real* AwaitingKey-vs-Decayed
     * decision (not a canned [StubPostRepository] result) to prove FeedViewModel
     * only ever broadcasts for the genuine AwaitingKey case.
     */
    private suspend fun insertKeylessPost(
        postDao: FakePostDao,
        reachTier: ReachTier,
        originatedAtMs: Long,
        ttlSeconds: Long,
        originGeohashPrefix: String = "",
    ): PostEntity {
        val plaintext = "keyless post for $reachTier at $originatedAtMs".toByteArray()
        val clipHash = MessageDigest.getInstance("SHA-256").digest(plaintext)
        val clipHashHex = clipHash.toHexString()
        val encodeResult = EncryptedFrameCodec.encode(
            plaintext = plaintext,
            clipHash = clipHash,
            senderDeviceId = ByteArray(Frame.SENDER_DEVICE_ID_SIZE) { it.toByte() },
            contentType = ContentType.PHOTO,
            hopCount = 0,
            originatedAtMs = originatedAtMs,
            ttlSeconds = ttlSeconds,
            reachTier = reachTier,
            dontRelay = false,
            originGeohashPrefix = originGeohashPrefix,
        )
        val frame = Frame.decode(encodeResult.encoded)
        val payloadFile = File(tempFolder.newFolder("posts-${System.nanoTime()}"), "$clipHashHex.enc")
        payloadFile.writeBytes(frame.payload)
        val entity = PostEntity(
            clipHash = clipHashHex,
            senderDeviceId = "sender",
            contentType = ContentType.PHOTO.name,
            originatedAtMs = originatedAtMs,
            ttlSeconds = ttlSeconds,
            reachTier = reachTier.name,
            originGeohashPrefix = originGeohashPrefix,
            dontRelay = false,
            receivedAtMs = System.currentTimeMillis(),
            encryptedPayloadFilePath = payloadFile.absolutePath,
        )
        postDao.upsert(entity)
        return entity
    }

    @Test
    fun postsFlowFiltersBlockedAndReportedEntries() = runTest(testDispatcher) {
        val visible = samplePost("clip-visible", senderDeviceId = "sender-visible")
        val fromBlockedSender = samplePost("clip-from-blocked", senderDeviceId = "sender-blocked")
        val reportedPost = samplePost("clip-reported", senderDeviceId = "sender-visible")

        val postDao = FakePostDao(listOf(visible, fromBlockedSender, reportedPost))
        val blockedDao = FakeBlockedSenderDeviceDao(listOf("sender-blocked"))
        val reportedDao = FakeReportedPostDao(listOf("clip-reported"))

        val viewModel = FeedViewModel(
            postRepository = PostRepository(postDao, DecayKeyStore()),
            blockRepository = BlockRepository(blockedDao),
            reportRepository = ReportRepository(reportedDao),
            dontRelayRepository = DontRelayRepository(FakeDontRelayFlagDao(), FakeRelayQueueDao(), RelayPolicy()),
            getAttestedDeviceKey = { "attested-key" },
            broadcastDontRelayFlag = {},
        )

        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("clip-visible"), viewModel.posts.value.map { it.clipHash })
    }

    @Test
    fun decryptCacheDoesNotGrowUnbounded() = runTest(testDispatcher) {
        val countingPostRepository = CountingFakePostRepository()

        val viewModel = FeedViewModel(
            postRepository = countingPostRepository,
            blockRepository = BlockRepository(FakeBlockedSenderDeviceDao(emptyList())),
            reportRepository = ReportRepository(FakeReportedPostDao(emptyList())),
            dontRelayRepository = DontRelayRepository(FakeDontRelayFlagDao(), FakeRelayQueueDao(), RelayPolicy()),
            getAttestedDeviceKey = { "attested-key" },
            broadcastDontRelayFlag = {},
        )
        testDispatcher.scheduler.advanceUntilIdle()

        val posts = (1..4).map { samplePost("clip-$it") }

        // Decrypt 4 distinct posts -- one more than the cache's cap of 3.
        posts.forEach { post -> viewModel.decrypt(post) }
        assertEquals(4, countingPostRepository.decryptCallCount)

        // Re-decrypting the most recently-used post is a cache hit -- no new
        // underlying decrypt call.
        viewModel.decrypt(posts.last())
        assertEquals(4, countingPostRepository.decryptCallCount)

        // Re-decrypting the *first* post decrypted must have been evicted by
        // now (cache cap is 3, and 4 distinct posts were decrypted since) --
        // this is the cache actually staying bounded, not silently growing to
        // hold the whole feed's plaintext.
        viewModel.decrypt(posts.first())
        assertEquals(5, countingPostRepository.decryptCallCount)
    }

    @Test
    fun `decrypt on a cache-miss AwaitingKey post triggers exactly one tier-key request broadcast with a correctly-shaped claim`() = runTest(testDispatcher) {
        val clipHash = sha256Hex("clip-awaiting-key")
        val post = samplePost(clipHash, reachTier = "TOWN")
        val stubRepository = StubPostRepository(mapOf(clipHash to PostRepository.DecryptResult.AwaitingKey))
        val broadcastRequests = mutableListOf<TierKeyRequestEnvelope>()

        val viewModel = FeedViewModel(
            postRepository = stubRepository,
            blockRepository = BlockRepository(FakeBlockedSenderDeviceDao(emptyList())),
            reportRepository = ReportRepository(FakeReportedPostDao(emptyList())),
            dontRelayRepository = DontRelayRepository(FakeDontRelayFlagDao(), FakeRelayQueueDao(), RelayPolicy()),
            getAttestedDeviceKey = { "attested-key" },
            broadcastDontRelayFlag = {},
            broadcastTierKeyRequest = { request -> broadcastRequests.add(request) },
            buildTierMembershipClaim = { tier ->
                TierMembershipClaim(reachTier = tier, geohashPrefix = "9q8yy", claimedAtMs = 1_000L)
            },
        )
        testDispatcher.scheduler.advanceUntilIdle()

        val result = viewModel.decrypt(post)

        assertEquals(PostRepository.DecryptResult.AwaitingKey, result)
        assertEquals(1, broadcastRequests.size)
        val sentRequest = broadcastRequests.single()
        assertEquals(clipHash, sentRequest.contentId.toHexString())
        assertEquals(ReachTier.TOWN, sentRequest.claim.reachTier)
        assertEquals("9q8yy", sentRequest.claim.geohashPrefix)
    }

    @Test
    fun `a second decrypt call for the same AwaitingKey post within the cooldown window does not re-trigger a broadcast`() = runTest(testDispatcher) {
        val clipHash = sha256Hex("clip-awaiting-cooldown")
        val post = samplePost(clipHash, reachTier = "CITY")
        val stubRepository = StubPostRepository(mapOf(clipHash to PostRepository.DecryptResult.AwaitingKey))
        var broadcastCount = 0

        val viewModel = FeedViewModel(
            postRepository = stubRepository,
            blockRepository = BlockRepository(FakeBlockedSenderDeviceDao(emptyList())),
            reportRepository = ReportRepository(FakeReportedPostDao(emptyList())),
            dontRelayRepository = DontRelayRepository(FakeDontRelayFlagDao(), FakeRelayQueueDao(), RelayPolicy()),
            getAttestedDeviceKey = { "attested-key" },
            broadcastDontRelayFlag = {},
            broadcastTierKeyRequest = { broadcastCount++ },
            buildTierMembershipClaim = { tier ->
                TierMembershipClaim(reachTier = tier, geohashPrefix = "9q8y", claimedAtMs = 1_000L)
            },
        )
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.decrypt(post)
        viewModel.decrypt(post)

        assertEquals(1, broadcastCount, "the second decrypt() call inside the cooldown window must not re-trigger a broadcast")
    }

    @Test
    fun `a Locality post or a genuinely-expired post never triggers a tier-key request broadcast`() = runTest(testDispatcher) {
        val postDao = FakePostDao(emptyList())
        val decayKeyStore = DecayKeyStore()
        val realPostRepository = PostRepository(postDao, decayKeyStore)
        var broadcastCount = 0

        val viewModel = FeedViewModel(
            postRepository = realPostRepository,
            blockRepository = BlockRepository(FakeBlockedSenderDeviceDao(emptyList())),
            reportRepository = ReportRepository(FakeReportedPostDao(emptyList())),
            dontRelayRepository = DontRelayRepository(FakeDontRelayFlagDao(), FakeRelayQueueDao(), RelayPolicy()),
            getAttestedDeviceKey = { "attested-key" },
            broadcastDontRelayFlag = {},
            broadcastTierKeyRequest = { broadcastCount++ },
            buildTierMembershipClaim = { tier ->
                TierMembershipClaim(reachTier = tier, geohashPrefix = "9q8y", claimedAtMs = 1_000L)
            },
        )
        testDispatcher.scheduler.advanceUntilIdle()

        // Locality, no key ever stored -- must resolve to Decayed (Locality
        // never yields AwaitingKey, ADR 0003), so no broadcast fires.
        val localityPost = insertKeylessPost(
            postDao,
            reachTier = ReachTier.LOCALITY,
            originatedAtMs = System.currentTimeMillis(),
            ttlSeconds = 3600L,
        )
        assertEquals(PostRepository.DecryptResult.Decayed, viewModel.decrypt(localityPost))

        // Town, no key ever stored, but genuinely past its own TTL (originated
        // 2 hours ago with a 1-hour TTL) -- must resolve to Decayed, not
        // AwaitingKey, so no broadcast fires for it either.
        val expiredTownPost = insertKeylessPost(
            postDao,
            reachTier = ReachTier.TOWN,
            originatedAtMs = System.currentTimeMillis() - Duration.ofHours(2).toMillis(),
            ttlSeconds = 3600L,
            originGeohashPrefix = "9q8yy",
        )
        assertEquals(PostRepository.DecryptResult.Decayed, viewModel.decrypt(expiredTownPost))

        assertEquals(0, broadcastCount, "neither a Locality post nor a genuinely-expired post must ever trigger a tier-key request broadcast")
    }

    @Test
    fun `flagDontRelay records the flag locally and broadcasts it`() = runTest(testDispatcher) {
        val flagDao = FakeDontRelayFlagDao()
        // samplePost's fixed originatedAtMs/ttlSeconds (1_700_000_000_000L, 1
        // hour) are long past relative to the real system clock -- pin
        // RelayPolicy's clock to just after origination so recordFlag's own
        // isExpired check doesn't reject this flag as already-decayed.
        val fixedClock = Clock.fixed(Instant.ofEpochMilli(1_700_000_000_000L + 1_000L), ZoneOffset.UTC)
        val dontRelayRepository = DontRelayRepository(flagDao, FakeRelayQueueDao(), RelayPolicy(clock = fixedClock))
        var broadcastRow: DontRelayFlagEntity? = null

        val viewModel = FeedViewModel(
            postRepository = PostRepository(FakePostDao(emptyList()), DecayKeyStore()),
            blockRepository = BlockRepository(FakeBlockedSenderDeviceDao(emptyList())),
            reportRepository = ReportRepository(FakeReportedPostDao(emptyList())),
            dontRelayRepository = dontRelayRepository,
            getAttestedDeviceKey = { "own-attested-key" },
            broadcastDontRelayFlag = { row -> broadcastRow = row },
        )

        val post = samplePost("clip-to-flag")
        viewModel.flagDontRelay(post)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, runBlocking { flagDao.distinctFlaggerCount("clip-to-flag") })
        assertEquals("clip-to-flag", broadcastRow?.clipHash)
        assertEquals("own-attested-key", broadcastRow?.attestedDeviceKey)
        assertEquals(post.originatedAtMs, broadcastRow?.originatedAtMs)
        assertEquals(post.ttlSeconds, broadcastRow?.ttlSeconds)
    }

    @Test
    fun `discoveredRemoteHolders reflects what browseNearbyDht returns`() = runTest(testDispatcher) {
        val fakeContact = Contact(
            id = NodeId(ByteArray(NodeId.SIZE_BYTES) { it.toByte() }),
            address = ByteArray(7),
            lastSeenAtMs = 0L,
        )

        val viewModel = FeedViewModel(
            postRepository = PostRepository(FakePostDao(emptyList()), DecayKeyStore()),
            blockRepository = BlockRepository(FakeBlockedSenderDeviceDao(emptyList())),
            reportRepository = ReportRepository(FakeReportedPostDao(emptyList())),
            dontRelayRepository = DontRelayRepository(FakeDontRelayFlagDao(), FakeRelayQueueDao(), RelayPolicy()),
            getAttestedDeviceKey = { "attested-key" },
            broadcastDontRelayFlag = {},
            browseNearbyDht = { listOf(fakeContact) },
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(fakeContact), viewModel.discoveredRemoteHolders.value)
    }

    @Test
    fun `discoveredRemoteHolders defaults to empty when browseNearbyDht is not provided`() = runTest(testDispatcher) {
        val viewModel = FeedViewModel(
            postRepository = PostRepository(FakePostDao(emptyList()), DecayKeyStore()),
            blockRepository = BlockRepository(FakeBlockedSenderDeviceDao(emptyList())),
            reportRepository = ReportRepository(FakeReportedPostDao(emptyList())),
            dontRelayRepository = DontRelayRepository(FakeDontRelayFlagDao(), FakeRelayQueueDao(), RelayPolicy()),
            getAttestedDeviceKey = { "attested-key" },
            broadcastDontRelayFlag = {},
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(emptyList(), viewModel.discoveredRemoteHolders.value)
    }

    @Test
    fun `refresh re-runs browseNearbyDht and updates discoveredRemoteHolders`() = runTest(testDispatcher) {
        val firstContact = Contact(
            id = NodeId(ByteArray(NodeId.SIZE_BYTES) { it.toByte() }),
            address = ByteArray(7),
            lastSeenAtMs = 0L,
        )
        val secondContact = Contact(
            id = NodeId(ByteArray(NodeId.SIZE_BYTES) { (it + 1).toByte() }),
            address = ByteArray(7),
            lastSeenAtMs = 0L,
        )
        var callCount = 0
        val viewModel = FeedViewModel(
            postRepository = PostRepository(FakePostDao(emptyList()), DecayKeyStore()),
            blockRepository = BlockRepository(FakeBlockedSenderDeviceDao(emptyList())),
            reportRepository = ReportRepository(FakeReportedPostDao(emptyList())),
            dontRelayRepository = DontRelayRepository(FakeDontRelayFlagDao(), FakeRelayQueueDao(), RelayPolicy()),
            getAttestedDeviceKey = { "attested-key" },
            broadcastDontRelayFlag = {},
            browseNearbyDht = {
                callCount++
                if (callCount == 1) listOf(firstContact) else listOf(firstContact, secondContact)
            },
        )
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, callCount, "construction must already have run browseNearbyDht once")
        assertEquals(listOf(firstContact), viewModel.discoveredRemoteHolders.value)

        viewModel.refresh()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, callCount, "refresh() must re-run browseNearbyDht rather than reuse the construction-time result")
        assertEquals(listOf(firstContact, secondContact), viewModel.discoveredRemoteHolders.value)
        assertEquals(false, viewModel.isRefreshing.value, "isRefreshing must settle back to false once refresh completes")
    }

    @Test
    fun `a second refresh call while one is already in flight is dropped, not queued`() = runTest(testDispatcher) {
        var callCount = 0
        val viewModel = FeedViewModel(
            postRepository = PostRepository(FakePostDao(emptyList()), DecayKeyStore()),
            blockRepository = BlockRepository(FakeBlockedSenderDeviceDao(emptyList())),
            reportRepository = ReportRepository(FakeReportedPostDao(emptyList())),
            dontRelayRepository = DontRelayRepository(FakeDontRelayFlagDao(), FakeRelayQueueDao(), RelayPolicy()),
            getAttestedDeviceKey = { "attested-key" },
            broadcastDontRelayFlag = {},
            browseNearbyDht = { callCount++; emptyList() },
        )
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, callCount)

        // Both calls happen before either's coroutine has had a chance to run
        // on the (paused-until-advanced) test dispatcher -- isRefreshing is
        // still true from the first call's still-pending launch when the
        // second fires, so the second must see the guard and no-op.
        viewModel.refresh()
        viewModel.refresh()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, callCount, "the overlapping second refresh() call must be dropped, not run a third time")
    }

    private class FakePostDao(initial: List<PostEntity>) : PostDao {
        private val state = MutableStateFlow(initial)

        override suspend fun upsert(post: PostEntity) {
            state.value = state.value.filterNot { it.clipHash == post.clipHash } + post
        }

        override fun getAllOrderedByReceivedDesc(): Flow<List<PostEntity>> = state

        override suspend fun getByClipHash(clipHash: String): PostEntity? =
            state.value.find { it.clipHash == clipHash }
    }

    private class FakeBlockedSenderDeviceDao(initial: List<String>) : BlockedSenderDeviceDao {
        private val state = MutableStateFlow(initial)

        override suspend fun insert(entity: BlockedSenderDeviceEntity) {
            state.value = (state.value + entity.senderDeviceId).distinct()
        }

        override fun observeAll(): Flow<List<String>> = state
    }

    private class FakeReportedPostDao(initial: List<String>) : ReportedPostDao {
        private val state = MutableStateFlow(initial)

        override suspend fun insert(entity: ReportedPostEntity) {
            state.value = (state.value + entity.clipHash).distinct()
        }

        override fun observeAll(): Flow<List<String>> = state
    }

    /** Minimal fake [DontRelayFlagDao] -- FeedViewModel only needs a constructible [DontRelayRepository] here, its flag-counting behavior isn't under test in this file (see [DontRelayRepositoryTest]). */
    private class FakeDontRelayFlagDao : DontRelayFlagDao {
        private val rows = mutableMapOf<Pair<String, String>, DontRelayFlagEntity>()

        override suspend fun insert(row: DontRelayFlagEntity): Long {
            val key = row.clipHash to row.attestedDeviceKey
            if (rows.containsKey(key)) return -1L
            rows[key] = row
            return 1L
        }

        override suspend fun distinctFlaggerCount(clipHash: String): Int = rows.keys.count { it.first == clipHash }

        override suspend fun getAll(): List<DontRelayFlagEntity> = rows.values.toList()

        override suspend fun deleteAllForClip(clipHash: String) {
            rows.keys.filter { it.first == clipHash }.forEach { rows.remove(it) }
        }
    }

    /** Minimal fake [RelayQueueDao] -- see [FakeDontRelayFlagDao]'s own doc for why this file only needs a constructible instance. */
    private class FakeRelayQueueDao : RelayQueueDao {
        private val rows = mutableMapOf<String, RelayQueueEntity>()

        override suspend fun insert(row: RelayQueueEntity) {
            rows.putIfAbsent(row.clipHash, row)
        }

        override suspend fun getAll(): List<RelayQueueEntity> = rows.values.toList()

        override suspend fun delete(clipHash: String) {
            rows.remove(clipHash)
        }

        override suspend fun markDontRelay(clipHash: String) {
            rows[clipHash]?.let { rows[clipHash] = it.copy(dontRelay = true) }
        }
    }

    /**
     * Subclasses the real [PostRepository] (rather than implementing an
     * interface) per its own `open`-for-testability doc comment. Overrides
     * only what [FeedViewModel] actually calls; the constructor args are
     * never exercised since both overridden methods bypass them entirely.
     */
    private class CountingFakePostRepository :
        PostRepository(FakePostDao(emptyList()), DecayKeyStore()) {

        var decryptCallCount = 0
            private set

        override fun observeAllPosts(): Flow<List<PostEntity>> = MutableStateFlow(emptyList())

        override suspend fun decrypt(post: PostEntity): DecryptResult {
            decryptCallCount++
            return DecryptResult.Decrypted(post.clipHash.toByteArray())
        }
    }

    /**
     * Returns a canned [DecryptResult] per `clipHash` (defaulting to
     * [DecryptResult.Decayed] for anything not in [results]) -- used by the
     * tier-key-request-broadcast tests above, which need to force a specific
     * outcome (AwaitingKey) without depending on real [EncryptedFrameCodec]/
     * [DecayKeyStore] expiry arithmetic. Same "subclass the real
     * [PostRepository]" pattern as [CountingFakePostRepository].
     */
    private class StubPostRepository(private val results: Map<String, DecryptResult>) :
        PostRepository(FakePostDao(emptyList()), DecayKeyStore()) {

        override fun observeAllPosts(): Flow<List<PostEntity>> = MutableStateFlow(emptyList())

        override suspend fun decrypt(post: PostEntity): DecryptResult =
            results[post.clipHash] ?: DecryptResult.Decayed
    }
}
