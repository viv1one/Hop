package com.hop.repository

import com.hop.data.BundleQueueDao
import com.hop.data.BundleQueueEntity
import com.hop.protocol.PreKeyBundleEnvelope
import com.hop.protocol.RelayPolicy
import com.hop.protocol.WireEnvelope
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Plain-logic JVM tests for [BundleRepository] against a hand-rolled fake
 * [BundleQueueDao] -- no Room/instrumentation, mirroring
 * [PendingMessageRepositoryTest]'s own shape (this class deliberately mirrors
 * [PendingMessageRepository]'s design, except for the peer-id-keyed
 * conditional-replace logic named in [BundleQueueEntity]'s own doc -- test
 * cases 2/3 below are the regression guard for that specific deviation).
 */
class BundleRepositoryTest {

    private val random = SecureRandom()
    private val originatedAtMs = 1_700_000_000_000L
    private val ttlSeconds = PreKeyBundleEnvelope.DEFAULT_TTL_SECONDS

    private fun randomBytes(size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }

    private fun clockAt(epochMs: Long): Clock = Clock.fixed(Instant.ofEpochMilli(epochMs), ZoneOffset.UTC)

    private fun sampleEnvelope(hopCount: Int = 1, originatedAt: Long = originatedAtMs, bundleSeed: Int = 0): PreKeyBundleEnvelope =
        PreKeyBundleEnvelope(
            peerId = "bundle-owner-peer",
            hopCount = hopCount,
            originatedAtMs = originatedAt,
            bundleBytes = randomBytes(64).also { it[0] = bundleSeed.toByte() },
        )

    // --- Case 1: hop-bound and TTL-expiry rejection ---

    @Test
    fun `considerForRelay does not store an envelope already at maxHops`() {
        val dao = FakeBundleQueueDao()
        val repository = BundleRepository(dao, RelayPolicy(maxHops = 6, clock = clockAt(originatedAtMs)))

        val row = runBlocking { repository.considerForRelay(sampleEnvelope(hopCount = 6)) }

        assertNull(row)
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `considerForRelay does not store an already-expired envelope`() {
        val dao = FakeBundleQueueDao()
        val expiresAtMs = originatedAtMs + ttlSeconds * 1000
        val repository = BundleRepository(dao, RelayPolicy(clock = clockAt(expiresAtMs)))

        val row = runBlocking { repository.considerForRelay(sampleEnvelope()) }

        assertNull(row)
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `considerForRelay stores an eligible envelope and returns the row`() {
        val dao = FakeBundleQueueDao()
        val repository = BundleRepository(dao, RelayPolicy(clock = clockAt(originatedAtMs)))

        val row = runBlocking { repository.considerForRelay(sampleEnvelope(hopCount = 1)) }

        assertNotNull(row, "genuine new custody must return the inserted row")
        assertEquals(1, dao.rows.size)
        assertEquals(1, dao.rows.values.single().hopCount)
        assertEquals("bundle-owner-peer", dao.rows.values.single().peerId)
    }

    // --- Case 2: fresher envelope supersedes an existing stale row for the same peerId ---

    @Test
    fun `considerForRelay replaces an existing row when the incoming envelope is strictly fresher`() {
        val dao = FakeBundleQueueDao()
        val repository = BundleRepository(dao, RelayPolicy(clock = clockAt(originatedAtMs + 10_000)))

        val stale = sampleEnvelope(hopCount = 1, originatedAt = originatedAtMs, bundleSeed = 1)
        val firstRow = runBlocking { repository.considerForRelay(stale) }
        assertNotNull(firstRow)

        val fresher = sampleEnvelope(hopCount = 2, originatedAt = originatedAtMs + 5_000, bundleSeed = 2)
        val secondRow = runBlocking { repository.considerForRelay(fresher) }

        assertNotNull(secondRow, "a strictly fresher envelope for the same peerId must supersede the stale row")
        assertEquals(1, dao.rows.size, "conditional replace must still be exactly one row per peerId")
        assertEquals(originatedAtMs + 5_000, dao.rows.getValue("bundle-owner-peer").originatedAtMs)
        assertEquals(2, dao.rows.getValue("bundle-owner-peer").hopCount)
    }

    // --- Case 3: out-of-order stale envelope does NOT clobber an already-fresher row (the inverse of case 2) ---

    @Test
    fun `considerForRelay does not let an out-of-order stale envelope clobber an already-fresher row`() {
        val dao = FakeBundleQueueDao()
        val repository = BundleRepository(dao, RelayPolicy(clock = clockAt(originatedAtMs + 10_000)))

        val fresher = sampleEnvelope(hopCount = 1, originatedAt = originatedAtMs + 5_000, bundleSeed = 9)
        val firstRow = runBlocking { repository.considerForRelay(fresher) }
        assertNotNull(firstRow)

        // A stale copy arrives late over a slower/longer relay path.
        val stale = sampleEnvelope(hopCount = 3, originatedAt = originatedAtMs, bundleSeed = 1)
        val secondRow = runBlocking { repository.considerForRelay(stale) }

        assertNull(secondRow, "an out-of-order stale envelope must never be reported as genuine new custody")
        assertEquals(1, dao.rows.size)
        assertEquals(
            originatedAtMs + 5_000,
            dao.rows.getValue("bundle-owner-peer").originatedAtMs,
            "the already-fresher row must survive untouched -- the stale arrival must not overwrite it",
        )
        assertEquals(1, dao.rows.getValue("bundle-owner-peer").hopCount, "hopCount of the surviving fresher row must also be untouched")
    }

    @Test
    fun `considerForRelay rejects an envelope with the same originatedAtMs as the already-held row -- fresher-or-equal, not strictly fresher`() {
        val dao = FakeBundleQueueDao()
        val repository = BundleRepository(dao, RelayPolicy(clock = clockAt(originatedAtMs)))

        val first = sampleEnvelope(hopCount = 1, originatedAt = originatedAtMs, bundleSeed = 1)
        assertNotNull(runBlocking { repository.considerForRelay(first) })

        val sameTimestampDifferentHop = sampleEnvelope(hopCount = 4, originatedAt = originatedAtMs, bundleSeed = 2)
        val second = runBlocking { repository.considerForRelay(sameTimestampDifferentHop) }

        assertNull(second, "equal originatedAtMs must not be treated as strictly fresher")
        assertEquals(1, dao.rows.getValue("bundle-owner-peer").hopCount, "the first-held row must be untouched")
    }

    // --- Case 4: TTL-expired row excluded from buildOutgoingBacklog() and pruned as a side effect ---

    @Test
    fun `buildOutgoingBacklog returns eligible rows re-encoded with hopCount plus one`() {
        val dao = FakeBundleQueueDao()
        val repository = BundleRepository(dao, RelayPolicy(clock = clockAt(originatedAtMs)))
        runBlocking { repository.considerForRelay(sampleEnvelope(hopCount = 1)) }

        val backlog = runBlocking { repository.buildOutgoingBacklog() }

        assertEquals(1, backlog.size)
        val wireEnvelope = WireEnvelope.decode(backlog.single())
        val outgoing = PreKeyBundleEnvelope.decode(wireEnvelope.payload)
        assertEquals(2, outgoing.hopCount)
        assertEquals("bundle-owner-peer", outgoing.peerId)
    }

    @Test
    fun `buildOutgoingBacklog deletes an expired row and does not include it -- TTL expiry pruning`() {
        val dao = FakeBundleQueueDao()
        val custodyClock = clockAt(originatedAtMs)
        val repository = BundleRepository(dao, RelayPolicy(clock = custodyClock))
        runBlocking { repository.considerForRelay(sampleEnvelope(hopCount = 1)) }
        assertEquals(1, dao.rows.size)

        val expiresAtMs = originatedAtMs + ttlSeconds * 1000
        val laterRepository = BundleRepository(dao, RelayPolicy(clock = clockAt(expiresAtMs)))
        val backlog = runBlocking { laterRepository.buildOutgoingBacklog() }

        assertTrue(backlog.isEmpty())
        assertTrue(dao.rows.isEmpty(), "an expired row encountered while building the backlog must be pruned")
    }

    @Test
    fun `buildOutgoingBacklog omits a row past maxHops but does not delete it`() {
        val dao = FakeBundleQueueDao()
        val pastMaxHopsEnvelope = sampleEnvelope(hopCount = 6)
        dao.rows["bundle-owner-peer"] = BundleQueueEntity(
            peerId = "bundle-owner-peer",
            encodedEnvelope = pastMaxHopsEnvelope.encode(),
            hopCount = 6,
            originatedAtMs = originatedAtMs,
            receivedAtMs = originatedAtMs,
        )
        val repository = BundleRepository(dao, RelayPolicy(maxHops = 6, clock = clockAt(originatedAtMs)))

        val backlog = runBlocking { repository.buildOutgoingBacklog() }

        assertTrue(backlog.isEmpty())
        assertEquals(1, dao.rows.size, "a row past maxHops is left in place, not deleted -- only expiry deletes")
    }

    /** Minimal fake [BundleQueueDao], matching this repo's hand-rolled-fakes pattern. */
    private class FakeBundleQueueDao : BundleQueueDao {
        val rows = mutableMapOf<String, BundleQueueEntity>()

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
