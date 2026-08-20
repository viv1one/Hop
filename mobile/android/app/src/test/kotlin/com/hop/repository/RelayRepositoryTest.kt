package com.hop.repository

import com.hop.data.RelayQueueDao
import com.hop.data.RelayQueueEntity
import com.hop.protocol.ContentType
import com.hop.protocol.Frame
import com.hop.protocol.ReachTier
import com.hop.protocol.RelayPolicy
import com.hop.protocol.WireEnvelope
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Plain-logic JVM tests for [RelayRepository] against a hand-rolled fake
 * [RelayQueueDao] -- no Room/instrumentation, matching [PostRepository]'s
 * own testability seam and this repo's hand-rolled-fakes pattern
 * ([WifiDirectTransportTest]'s `FakePostDao`).
 */
class RelayRepositoryTest {

    private val random = SecureRandom()
    private val originatedAtMs = 1_700_000_000_000L
    private val ttlSeconds = 3600L

    private fun randomBytes(size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }

    private fun clockAt(epochMs: Long): Clock = Clock.fixed(Instant.ofEpochMilli(epochMs), ZoneOffset.UTC)

    private fun sampleFrame(hopCount: Int = 0, dontRelay: Boolean = false): Frame = Frame(
        clipHash = randomBytes(Frame.CLIP_HASH_SIZE),
        senderDeviceId = randomBytes(Frame.SENDER_DEVICE_ID_SIZE),
        contentType = ContentType.PHOTO,
        hopCount = hopCount,
        originatedAtMs = originatedAtMs,
        ttlSeconds = ttlSeconds,
        reachTier = ReachTier.LOCALITY,
        dontRelay = dontRelay,
        keyIncluded = true,
        contentEncryptionKey = randomBytes(Frame.CONTENT_ENCRYPTION_KEY_SIZE),
        payload = randomBytes(128),
    )

    @Test
    fun `considerForRelay stores an eligible frame`() {
        val dao = FakeRelayQueueDao()
        val repository = RelayRepository(dao, RelayPolicy(clock = clockAt(originatedAtMs)))
        val frame = sampleFrame(hopCount = 0)

        runBlocking { repository.considerForRelay(frame) }

        assertEquals(1, dao.rows.size)
        assertEquals(0, dao.rows.values.single().hopCount)
    }

    @Test
    fun `considerForRelay does not store a dontRelay frame`() {
        val dao = FakeRelayQueueDao()
        val repository = RelayRepository(dao, RelayPolicy(clock = clockAt(originatedAtMs)))

        runBlocking { repository.considerForRelay(sampleFrame(dontRelay = true)) }

        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `considerForRelay does not store a frame already at maxHops`() {
        val dao = FakeRelayQueueDao()
        val repository = RelayRepository(dao, RelayPolicy(maxHops = 6, clock = clockAt(originatedAtMs)))

        runBlocking { repository.considerForRelay(sampleFrame(hopCount = 6)) }

        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `considerForRelay does not store an already-expired frame`() {
        val dao = FakeRelayQueueDao()
        val expiresAtMs = originatedAtMs + ttlSeconds * 1000
        val repository = RelayRepository(dao, RelayPolicy(clock = clockAt(expiresAtMs)))

        runBlocking { repository.considerForRelay(sampleFrame()) }

        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `considerForRelay is idempotent for a repeated clipHash -- first custody wins`() {
        val dao = FakeRelayQueueDao()
        val repository = RelayRepository(dao, RelayPolicy(clock = clockAt(originatedAtMs)))
        val frame = sampleFrame(hopCount = 2)

        runBlocking {
            repository.considerForRelay(frame)
            // A "duplicate" custody attempt for the same clipHash but a
            // different (bogus) hopCount must not overwrite the first.
            repository.considerForRelay(frame.copy(hopCount = 5))
        }

        assertEquals(1, dao.rows.size)
        assertEquals(2, dao.rows.values.single().hopCount, "first custody's hopCount must win, never be reset")
    }

    @Test
    fun `buildOutgoingBacklog returns eligible rows re-encoded with hopCount plus one`() {
        val dao = FakeRelayQueueDao()
        val repository = RelayRepository(dao, RelayPolicy(clock = clockAt(originatedAtMs)))
        val frame = sampleFrame(hopCount = 1)
        runBlocking { repository.considerForRelay(frame) }

        val backlog = runBlocking { repository.buildOutgoingBacklog() }

        assertEquals(1, backlog.size)
        val envelope = WireEnvelope.decode(backlog.single())
        val outgoingFrame = Frame.decode(envelope.payload)
        assertEquals(2, outgoingFrame.hopCount)
        assertTrue(frame.clipHash.contentEquals(outgoingFrame.clipHash))
    }

    @Test
    fun `buildOutgoingBacklog deletes an expired row and does not include it`() {
        val dao = FakeRelayQueueDao()
        val custodyClock = clockAt(originatedAtMs)
        val repository = RelayRepository(dao, RelayPolicy(clock = custodyClock))
        runBlocking { repository.considerForRelay(sampleFrame()) }
        assertEquals(1, dao.rows.size)

        val expiresAtMs = originatedAtMs + ttlSeconds * 1000
        val laterRepository = RelayRepository(dao, RelayPolicy(clock = clockAt(expiresAtMs)))
        val backlog = runBlocking { laterRepository.buildOutgoingBacklog() }

        assertTrue(backlog.isEmpty())
        assertTrue(dao.rows.isEmpty(), "an expired row encountered while building the backlog must be deleted")
    }

    @Test
    fun `buildOutgoingBacklog omits a row past maxHops but does not delete it`() {
        val dao = FakeRelayQueueDao()
        // Insert directly (bypassing eligibility) to simulate a row that was
        // eligible at custody time but the policy's maxHops has since been
        // reached by repeated relay -- RelayQueueEntity.hopCount is never
        // mutated after insert, so this models "queried again later".
        dao.rows["clip-past-max"] = RelayQueueEntity(
            clipHash = "clip-past-max",
            encodedFrame = sampleFrame(hopCount = 6).encode(),
            hopCount = 6,
            originatedAtMs = originatedAtMs,
            ttlSeconds = ttlSeconds,
            dontRelay = false,
            receivedAtMs = originatedAtMs,
        )
        val repository = RelayRepository(dao, RelayPolicy(maxHops = 6, clock = clockAt(originatedAtMs)))

        val backlog = runBlocking { repository.buildOutgoingBacklog() }

        assertTrue(backlog.isEmpty())
        assertEquals(1, dao.rows.size, "a row past maxHops is left in place, not deleted -- only expiry deletes")
    }

    @Test
    fun `considerForRelay suppresses a post arriving after its flags already crossed threshold`() {
        // Order-independence regression test (Phase 2 Slice 2): the post
        // arrives *after* DontRelayRepository has already recorded enough
        // distinct flags for its clipHash -- the freshly-inserted row must
        // have dontRelay = true from the start, not just from some future
        // flag onward.
        val dao = FakeRelayQueueDao()
        val repository = RelayRepository(
            dao,
            RelayPolicy(clock = clockAt(originatedAtMs)),
            isFlaggedForSuppression = { true },
        )
        val frame = sampleFrame(hopCount = 0, dontRelay = false)

        runBlocking { repository.considerForRelay(frame) }

        assertEquals(1, dao.rows.size)
        assertTrue(dao.rows.values.single().dontRelay, "a post arriving after its flags already crossed threshold must be suppressed from the start")
    }

    @Test
    fun `considerForRelay does not suppress a frame when isFlaggedForSuppression returns false`() {
        val dao = FakeRelayQueueDao()
        val repository = RelayRepository(
            dao,
            RelayPolicy(clock = clockAt(originatedAtMs)),
            isFlaggedForSuppression = { false },
        )
        val frame = sampleFrame(hopCount = 0, dontRelay = false)

        runBlocking { repository.considerForRelay(frame) }

        assertEquals(1, dao.rows.size)
        assertTrue(!dao.rows.values.single().dontRelay)
    }

    /** Minimal fake [RelayQueueDao], matching this repo's hand-rolled-fakes pattern. */
    private class FakeRelayQueueDao : RelayQueueDao {
        val rows = mutableMapOf<String, RelayQueueEntity>()

        override suspend fun insert(row: RelayQueueEntity) {
            // Mirrors OnConflictStrategy.IGNORE -- first custody wins.
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
}
