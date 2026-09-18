package com.hop.transport

import com.hop.crypto.ContentEncryption
import com.hop.crypto.DecayKeyStore
import com.hop.data.BundleQueueDao
import com.hop.data.BundleQueueEntity
import com.hop.data.DontRelayFlagDao
import com.hop.data.DontRelayFlagEntity
import com.hop.data.PendingMessageDao
import com.hop.data.PendingMessageEntity
import com.hop.data.PostDao
import com.hop.data.PostEntity
import com.hop.data.RelayQueueDao
import com.hop.data.RelayQueueEntity
import com.hop.protocol.ContentType
import com.hop.protocol.EncryptedFrameCodec
import com.hop.protocol.Frame
import com.hop.protocol.ReachTier
import com.hop.protocol.ReachTierKeyDistribution
import com.hop.protocol.RelayPolicy
import com.hop.protocol.TierKeyRequestEnvelope
import com.hop.protocol.TierKeyResponseEnvelope
import com.hop.protocol.TierMembershipClaim
import com.hop.protocol.WireEnvelope
import com.hop.protocol.WirePayloadType
import com.hop.repository.BundleRepository
import com.hop.repository.DontRelayRepository
import com.hop.repository.PendingMessageRepository
import com.hop.repository.PostRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Covers [EnvelopeDispatcher.dispatch]'s `TIER_KEY_REQUEST`/`TIER_KEY_RESPONSE`
 * branches (Phase 4 Slice 9 -- the responding side of ADR 0003's
 * key-distribution mechanism, see [ReachTierKeyDistribution]). Follows
 * [EnvelopeDispatcherBundleRelayTest]'s own pattern: a real [EnvelopeDispatcher]
 * driven directly against real [WireEnvelope] bytes, hand-rolled DAO fakes
 * (no mocking library in this repo).
 *
 * Per the hop-dev "extra scrutiny" posture for crypto/protocol code, these
 * tests specifically exercise the negative cases (wrong cell, stale claim,
 * unheld content) alongside the happy path -- a bug in any of those is a
 * silent reach-tier access-control bypass or a silent denial-of-service
 * against a legitimate requester, not a cosmetic issue.
 *
 * Also covers the `TIER_KEY_RESPONSE` branch's [PendingTierKeyRequests]
 * correlation fix: a `granted=true` response is only ever honored when this
 * device can prove (via a live [PendingTierKeyRequests] entry) that it
 * actually broadcast a matching request -- see
 * `tierKeyResponseWithNoMatchingPendingRequestIsIgnoredEvenForAnAlreadyHeldPost`
 * for the regression test that would have failed before this fix (any
 * connected peer could otherwise overwrite an already-held tiered key with
 * attacker-supplied bytes), and
 * `secondResponseForAnAlreadyConsumedPendingRequestIsIgnored` for the
 * replay-after-accept case.
 */
class EnvelopeDispatcherTierKeyTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val postDao = FakePostDao()
    private val decayKeyStore = DecayKeyStore()

    private fun newDispatcher(
        ownPeerId: String = "me",
        pendingTierKeyRequests: PendingTierKeyRequests = PendingTierKeyRequests(),
    ): EnvelopeDispatcher {
        val receivedFrameStore = ReceivedFrameStore(
            postRepository = PostRepository(postDao, decayKeyStore),
            decayKeyStore = decayKeyStore,
            postsDir = tempFolder.newFolder("posts-${System.nanoTime()}"),
        )
        return EnvelopeDispatcher(
            receivedFrameStore = receivedFrameStore,
            postRepository = PostRepository(postDao, decayKeyStore),
            decayKeyStore = decayKeyStore,
            dontRelayRepository = DontRelayRepository(
                flagDao = FakeDontRelayFlagDao(),
                relayQueueDao = FakeRelayQueueDao(),
                relayPolicy = RelayPolicy(),
            ),
            pendingMessageRepository = PendingMessageRepository(
                dao = FakePendingMessageDao(),
                relayPolicy = RelayPolicy(),
            ),
            bundleRepository = BundleRepository(dao = FakeBundleQueueDao(), relayPolicy = RelayPolicy()),
            getOwnPeerId = { ownPeerId },
            pendingTierKeyRequests = pendingTierKeyRequests,
            onPreKeyBundleReceived = { _, _ -> },
            onMessageCiphertextReceived = { _, _ -> },
        )
    }

    /** Stores a held Town-tier post (ciphertext on disk + PostEntity row + tiered decay key) and returns its plaintext/clipHash/CEK. */
    private fun storeHeldTownPost(originGeohashPrefix: String): Triple<ByteArray, ByteArray, ByteArray> {
        val plaintext = "a town-tier post".toByteArray()
        val clipHash = MessageDigest.getInstance("SHA-256").digest(plaintext)
        val result = EncryptedFrameCodec.encode(
            plaintext = plaintext,
            clipHash = clipHash,
            senderDeviceId = ByteArray(Frame.SENDER_DEVICE_ID_SIZE) { it.toByte() },
            contentType = ContentType.PHOTO,
            hopCount = 0,
            originatedAtMs = System.currentTimeMillis(),
            ttlSeconds = 3600L,
            reachTier = ReachTier.TOWN,
            dontRelay = false,
            originGeohashPrefix = originGeohashPrefix,
        )
        val frame = Frame.decode(result.encoded)
        val clipHashHex = clipHash.joinToString("") { "%02x".format(it) }

        decayKeyStore.store(
            contentId = ReachTierKeyDistribution.decayKeyStorageKey(clipHashHex, ReachTier.TOWN),
            wrappedCek = result.contentEncryptionKey,
            decayWindow = java.time.Duration.ofSeconds(3600L),
        )

        val payloadFile = java.io.File(tempFolder.newFolder("payloads-${System.nanoTime()}"), "$clipHashHex.enc")
        payloadFile.writeBytes(frame.payload)

        runBlocking {
            postDao.upsert(
                PostEntity(
                    clipHash = clipHashHex,
                    senderDeviceId = "sender",
                    contentType = ContentType.PHOTO.name,
                    originatedAtMs = System.currentTimeMillis(),
                    ttlSeconds = 3600L,
                    reachTier = ReachTier.TOWN.name,
                    originGeohashPrefix = originGeohashPrefix,
                    dontRelay = false,
                    receivedAtMs = System.currentTimeMillis(),
                    encryptedPayloadFilePath = payloadFile.absolutePath,
                ),
            )
        }

        return Triple(plaintext, clipHash, frame.payload)
    }

    @Test
    fun validInCellFreshClaimGetsBackAGrantedKeyThatActuallyDecryptsTheStoredCiphertext() = runBlocking {
        val originGeohashPrefix = "9q8yy"
        val (plaintext, clipHash, ciphertext) = storeHeldTownPost(originGeohashPrefix)
        val dispatcher = newDispatcher()

        val claim = TierMembershipClaim(
            reachTier = ReachTier.TOWN,
            geohashPrefix = originGeohashPrefix,
            claimedAtMs = System.currentTimeMillis(),
        )
        val requestEnvelope = TierKeyRequestEnvelope(contentId = clipHash, claim = claim)
        val wireEnvelope = WireEnvelope(WirePayloadType.TIER_KEY_REQUEST, requestEnvelope.encode())

        val result = dispatcher.dispatch(wireEnvelope)

        val answered = assertIs<DispatchResult.TierKeyRequestAnswered>(result)
        assertTrue(answered.response.granted, "a valid in-cell fresh claim for a held post must be granted")
        assertContentEquals(clipHash, answered.response.contentId)

        val cek = ContentEncryption.keyFromBytes(answered.response.wrappedCek)
        val decrypted = ContentEncryption.decrypt(cek, ciphertext)
        assertContentEquals(plaintext, decrypted, "the granted key must actually decrypt this post's stored ciphertext")
    }

    @Test
    fun wrongCellClaimGetsBackDenied() = runBlocking {
        val originGeohashPrefix = "9q8yy"
        val (_, clipHash, _) = storeHeldTownPost(originGeohashPrefix)
        val dispatcher = newDispatcher()

        // A geohash prefix nowhere near the post's own cell or its
        // neighbors -- opposite side of the globe.
        val claim = TierMembershipClaim(
            reachTier = ReachTier.TOWN,
            geohashPrefix = "e2ykk",
            claimedAtMs = System.currentTimeMillis(),
        )
        val requestEnvelope = TierKeyRequestEnvelope(contentId = clipHash, claim = claim)
        val wireEnvelope = WireEnvelope(WirePayloadType.TIER_KEY_REQUEST, requestEnvelope.encode())

        val result = dispatcher.dispatch(wireEnvelope)

        val answered = assertIs<DispatchResult.TierKeyRequestAnswered>(result)
        assertFalse(answered.response.granted, "a claim for the wrong cell must be denied")
        assertEquals(0, answered.response.wrappedCek.size, "a denial must never carry key bytes")
    }

    @Test
    fun staleClaimGetsBackDenied() = runBlocking {
        val originGeohashPrefix = "9q8yy"
        val (_, clipHash, _) = storeHeldTownPost(originGeohashPrefix)
        val dispatcher = newDispatcher()

        // Well past ReachTierKeyDistribution.DEFAULT_CLAIM_MAX_AGE_SECONDS (5 minutes).
        val claim = TierMembershipClaim(
            reachTier = ReachTier.TOWN,
            geohashPrefix = originGeohashPrefix,
            claimedAtMs = System.currentTimeMillis() - (10 * 60 * 1000L),
        )
        val requestEnvelope = TierKeyRequestEnvelope(contentId = clipHash, claim = claim)
        val wireEnvelope = WireEnvelope(WirePayloadType.TIER_KEY_REQUEST, requestEnvelope.encode())

        val result = dispatcher.dispatch(wireEnvelope)

        val answered = assertIs<DispatchResult.TierKeyRequestAnswered>(result)
        assertFalse(answered.response.granted, "a stale claim (older than the claim staleness bound) must be denied")
    }

    @Test
    fun requestForAContentIdThisDeviceDoesNotHoldGetsBackDenied() = runBlocking {
        val dispatcher = newDispatcher()
        val unheldClipHash = MessageDigest.getInstance("SHA-256").digest("never received this one".toByteArray())

        val claim = TierMembershipClaim(
            reachTier = ReachTier.TOWN,
            geohashPrefix = "9q8yy",
            claimedAtMs = System.currentTimeMillis(),
        )
        val requestEnvelope = TierKeyRequestEnvelope(contentId = unheldClipHash, claim = claim)
        val wireEnvelope = WireEnvelope(WirePayloadType.TIER_KEY_REQUEST, requestEnvelope.encode())

        val result = dispatcher.dispatch(wireEnvelope)

        val answered = assertIs<DispatchResult.TierKeyRequestAnswered>(result)
        assertFalse(answered.response.granted, "a contentId this device never received must be denied, not throw")
        assertContentEquals(unheldClipHash, answered.response.contentId)
    }

    @Test
    fun tierKeyResponseWithALivePendingRequestOpportunisticallyCachesAGrantedKeyForAnAlreadyHeldPost() = runBlocking {
        val originGeohashPrefix = "9q8yy"
        val (_, clipHash, _) = storeHeldTownPost(originGeohashPrefix)
        val clipHashHex = clipHash.joinToString("") { "%02x".format(it) }
        val pendingTierKeyRequests = PendingTierKeyRequests()
        val dispatcher = newDispatcher(pendingTierKeyRequests = pendingTierKeyRequests)

        // This device must have actually broadcast a request for this exact
        // (contentId, tier) before a response is honored -- see
        // PendingTierKeyRequests' own doc for why.
        pendingTierKeyRequests.markPending(clipHashHex, ReachTier.TOWN)

        // Simulate this device's own tiered key having already decayed/never
        // arrived (distinct from storeHeldTownPost's own separately-stored
        // copy) by using a fresh contentId that only differs by which
        // wrappedCek bytes get cached, so the assertion below unambiguously
        // proves the TIER_KEY_RESPONSE branch (not storeHeldTownPost) wrote it.
        val freshCek = ByteArray(32) { 7 }
        val responseEnvelope = TierKeyResponseEnvelope.granted(clipHash, freshCek)
        val wireEnvelope = WireEnvelope(WirePayloadType.TIER_KEY_RESPONSE, responseEnvelope.encode())

        val result = dispatcher.dispatch(wireEnvelope)

        assertEquals(DispatchResult.NoOp, result)
        val cachedKey = decayKeyStore.retrieve(ReachTierKeyDistribution.decayKeyStorageKey(clipHashHex, ReachTier.TOWN))
        assertContentEquals(freshCek, cachedKey, "a granted response with a live matching pending request must be accepted and cached under the tiered key")
    }

    @Test
    fun tierKeyResponseWithNoMatchingPendingRequestIsIgnoredEvenForAnAlreadyHeldPost() = runBlocking {
        // This is the actual security-fix regression test: without
        // correlation, any connected peer could send a granted=true response
        // for a contentId this device never requested (e.g. one it already
        // holds a real, previously-granted key for) and silently overwrite
        // that entry with attacker-supplied bytes. This test would have
        // FAILED before the fix (the response used to be accepted
        // unconditionally whenever this device already held the post).
        val originGeohashPrefix = "9q8yy"
        val (_, clipHash, _) = storeHeldTownPost(originGeohashPrefix)
        val clipHashHex = clipHash.joinToString("") { "%02x".format(it) }
        val dispatcher = newDispatcher() // Never marked pending for this (contentId, tier).

        // storeHeldTownPost already stored a real, legitimately-obtained key
        // under this exact (contentId, tier) -- capture it so the assertion
        // below proves the attacker's bytes never overwrote it (rather than
        // just asserting "something non-null exists," which storeHeldTownPost
        // alone would already satisfy).
        val legitimateKeyBeforeAttack = decayKeyStore.retrieve(ReachTierKeyDistribution.decayKeyStorageKey(clipHashHex, ReachTier.TOWN))
        assertNotNull(legitimateKeyBeforeAttack, "storeHeldTownPost must have already stored a real key for this (contentId, tier)")

        val attackerSuppliedCek = ByteArray(32) { 0xEE.toByte() }
        val responseEnvelope = TierKeyResponseEnvelope.granted(clipHash, attackerSuppliedCek)
        val wireEnvelope = WireEnvelope(WirePayloadType.TIER_KEY_RESPONSE, responseEnvelope.encode())

        val result = dispatcher.dispatch(wireEnvelope)

        assertEquals(DispatchResult.NoOp, result)
        val cachedKey = decayKeyStore.retrieve(ReachTierKeyDistribution.decayKeyStorageKey(clipHashHex, ReachTier.TOWN))
        assertContentEquals(
            legitimateKeyBeforeAttack,
            cachedKey,
            "an uncorrelated/unsolicited response must never overwrite an already-held real key with attacker-supplied bytes",
        )
    }

    @Test
    fun secondResponseForAnAlreadyConsumedPendingRequestIsIgnored() = runBlocking {
        // Replay-after-accept protection: consumeIfPending removes the entry
        // on its first live match, so a second response for the same
        // (contentId, tier) -- honest or not -- must not be able to
        // overwrite an already-accepted grant.
        val originGeohashPrefix = "9q8yy"
        val (_, clipHash, _) = storeHeldTownPost(originGeohashPrefix)
        val clipHashHex = clipHash.joinToString("") { "%02x".format(it) }
        val pendingTierKeyRequests = PendingTierKeyRequests()
        val dispatcher = newDispatcher(pendingTierKeyRequests = pendingTierKeyRequests)
        pendingTierKeyRequests.markPending(clipHashHex, ReachTier.TOWN)

        val firstCek = ByteArray(32) { 7 }
        val firstResponse = WireEnvelope(
            WirePayloadType.TIER_KEY_RESPONSE,
            TierKeyResponseEnvelope.granted(clipHash, firstCek).encode(),
        )
        dispatcher.dispatch(firstResponse)

        val secondCek = ByteArray(32) { 9 }
        val secondResponse = WireEnvelope(
            WirePayloadType.TIER_KEY_RESPONSE,
            TierKeyResponseEnvelope.granted(clipHash, secondCek).encode(),
        )
        val result = dispatcher.dispatch(secondResponse)

        assertEquals(DispatchResult.NoOp, result)
        val cachedKey = decayKeyStore.retrieve(ReachTierKeyDistribution.decayKeyStorageKey(clipHashHex, ReachTier.TOWN))
        assertContentEquals(firstCek, cachedKey, "a second response after the first was already consumed must be ignored, not overwrite the accepted grant")
    }

    @Test
    fun tierKeyResponseWithNoLocallyHeldPostIsANoOp() = runBlocking {
        val dispatcher = newDispatcher()
        val unheldClipHash = MessageDigest.getInstance("SHA-256").digest("not held here either".toByteArray())
        val responseEnvelope = TierKeyResponseEnvelope.granted(unheldClipHash, byteArrayOf(1, 2, 3))
        val wireEnvelope = WireEnvelope(WirePayloadType.TIER_KEY_RESPONSE, responseEnvelope.encode())

        val result = dispatcher.dispatch(wireEnvelope)

        assertEquals(DispatchResult.NoOp, result)
    }

    // -- Minimal hand-rolled fakes, matching this repo's established pattern (see WifiDirectTransportTest/EnvelopeDispatcherBundleRelayTest). --

    private class FakePostDao : PostDao {
        private val state = MutableStateFlow<List<PostEntity>>(emptyList())
        override suspend fun upsert(post: PostEntity) {
            state.value = state.value.filterNot { it.clipHash == post.clipHash } + post
        }
        override fun getAllOrderedByReceivedDesc(): Flow<List<PostEntity>> = state
        override suspend fun getByClipHash(clipHash: String): PostEntity? = state.value.find { it.clipHash == clipHash }
    }

    private class FakeDontRelayFlagDao : DontRelayFlagDao {
        private val rows = mutableListOf<DontRelayFlagEntity>()
        override suspend fun insert(row: DontRelayFlagEntity): Long {
            if (rows.any { it.clipHash == row.clipHash && it.attestedDeviceKey == row.attestedDeviceKey }) return -1L
            rows.add(row)
            return rows.size.toLong()
        }
        override suspend fun distinctFlaggerCount(clipHash: String): Int = rows.count { it.clipHash == clipHash }
        override suspend fun getAll(): List<DontRelayFlagEntity> = rows.toList()
        override suspend fun deleteAllForClip(clipHash: String) {
            rows.removeAll { it.clipHash == clipHash }
        }
    }

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

    private class FakePendingMessageDao : PendingMessageDao {
        private val rows = mutableMapOf<String, PendingMessageEntity>()
        override suspend fun insert(row: PendingMessageEntity) {
            rows.putIfAbsent(row.ciphertextHash, row)
        }
        override suspend fun getAll(): List<PendingMessageEntity> = rows.values.toList()
        override suspend fun getByHash(hash: String): PendingMessageEntity? = rows[hash]
        override suspend fun delete(hash: String) {
            rows.remove(hash)
        }
    }

    private class FakeBundleQueueDao : BundleQueueDao {
        private val rows = mutableMapOf<String, BundleQueueEntity>()
        override suspend fun getByPeerId(peerId: String): BundleQueueEntity? = rows[peerId]
        override suspend fun insertOrReplace(row: BundleQueueEntity) {
            rows[row.peerId] = row
        }
        override suspend fun getAll(): List<BundleQueueEntity> = rows.values.toList()
        override suspend fun delete(peerId: String) {
            rows.remove(peerId)
        }
    }
}
