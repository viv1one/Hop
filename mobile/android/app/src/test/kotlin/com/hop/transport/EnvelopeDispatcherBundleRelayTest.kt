package com.hop.transport

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
import com.hop.protocol.PreKeyBundleEnvelope
import com.hop.protocol.RelayPolicy
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
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Regression tests for the prekey-bundle relay/discovery follow-up's
 * hop-gating fix in [EnvelopeDispatcher.dispatch]'s
 * [WirePayloadType.PREKEY_BUNDLE] branch.
 *
 * Run as a fast JVM test, not an instrumented one -- no Android
 * device/emulator is available in this environment (same standing gap as
 * every prior Phase 2 slice), and this specific fix is exactly the kind of
 * thing that "compiles but is subtly wrong" without a real runnable
 * assertion against it: a first version of this feature only took
 * [BundleRepository] relay custody on the `hopCount >= 1` branch, meaning a
 * genuine direct announce (`hopCount == 0`) was never re-offerable to
 * anyone else -- the mesh had no first hop to relay from at all, and the
 * "closes group member-introduction for free" claim silently didn't hold in
 * the common case where the creator learns both members' bundles directly.
 * These tests exercise the real [EnvelopeDispatcher.dispatch] entry point
 * (not a hand-seeded [BundleRepository] row) specifically so they'd have
 * caught that bug.
 */
class EnvelopeDispatcherBundleRelayTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun newDispatcher(
        ownPeerId: String = "me",
        bundleQueueDao: BundleQueueDao = FakeBundleQueueDao(),
    ): Pair<EnvelopeDispatcher, BundleQueueDao> {
        val postDao = FakePostDao()
        val decayKeyStore = DecayKeyStore()
        val receivedFrameStore = ReceivedFrameStore(
            postRepository = PostRepository(postDao, decayKeyStore),
            decayKeyStore = decayKeyStore,
            postsDir = tempFolder.newFolder("posts-${System.nanoTime()}"),
        )
        val dontRelayRepository = DontRelayRepository(
            flagDao = FakeDontRelayFlagDao(),
            relayQueueDao = FakeRelayQueueDao(),
            relayPolicy = RelayPolicy(),
        )
        val pendingMessageRepository = PendingMessageRepository(
            dao = FakePendingMessageDao(),
            relayPolicy = RelayPolicy(),
        )
        val bundleRepository = BundleRepository(dao = bundleQueueDao, relayPolicy = RelayPolicy())
        val dispatcher = EnvelopeDispatcher(
            receivedFrameStore = receivedFrameStore,
            dontRelayRepository = dontRelayRepository,
            pendingMessageRepository = pendingMessageRepository,
            bundleRepository = bundleRepository,
            getOwnPeerId = { ownPeerId },
            onPreKeyBundleReceived = { _, _ -> },
            onMessageCiphertextReceived = { _, _ -> },
        )
        return dispatcher to bundleQueueDao
    }

    @Test
    fun directAnnounceAtHopZeroIdentifiesTheConnectionAndTakesRelayCustody() = runBlocking {
        val (dispatcher, bundleQueueDao) = newDispatcher()
        val envelope = PreKeyBundleEnvelope(
            peerId = "alice",
            hopCount = 0,
            originatedAtMs = System.currentTimeMillis(),
            bundleBytes = byteArrayOf(1, 2, 3),
        )
        val wireEnvelope = WireEnvelope(WirePayloadType.PREKEY_BUNDLE, envelope.encode())

        val result = dispatcher.dispatch(wireEnvelope)

        val direct = assertIs<DispatchResult.DirectBundleAnnounce>(
            result,
            "hopCount == 0 must identify the connection as the bundle owner",
        )
        assertEquals("alice", direct.peerId)
        assertEquals(
            1,
            bundleQueueDao.getAll().size,
            "a genuine direct announce must ALSO take relay custody -- the bug this test guards against: an " +
                "earlier version of this feature only took custody on the hopCount >= 1 branch, so a device " +
                "that only ever learned a bundle directly could never re-offer it onward, and the mesh had no " +
                "first hop to relay from at all",
        )
    }

    @Test
    fun relayedBundleAtHopOneNeverIdentifiesTheConnectionButStillTakesCustody() = runBlocking {
        val (dispatcher, bundleQueueDao) = newDispatcher()
        val envelope = PreKeyBundleEnvelope(
            peerId = "alice",
            hopCount = 1,
            originatedAtMs = System.currentTimeMillis(),
            bundleBytes = byteArrayOf(1, 2, 3),
        )
        val wireEnvelope = WireEnvelope(WirePayloadType.PREKEY_BUNDLE, envelope.encode())

        val result = dispatcher.dispatch(wireEnvelope)

        assertIs<DispatchResult.NewRelayableBundle>(
            result,
            "hopCount >= 1 must never be reported as identifying the connection -- its owner is a mere " +
                "carrier, not alice, and misidentifying it would let sendMessage()'s unicast fast path " +
                "believe it reached alice directly, silently skipping durable relay-custody as a fallback",
        )
        assertEquals(1, bundleQueueDao.getAll().size)
    }

    // -- Minimal hand-rolled fakes, matching this repo's established pattern (see WifiDirectTransportTest's own doc). --

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
