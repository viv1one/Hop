package com.hop.repository

import com.hop.data.DontRelayFlagDao
import com.hop.data.DontRelayFlagEntity
import com.hop.data.RelayQueueDao
import com.hop.data.RelayQueueEntity
import com.hop.protocol.ContentType
import com.hop.protocol.DontRelayFlagEnvelope
import com.hop.protocol.Frame
import com.hop.protocol.ReachTier
import com.hop.protocol.RelayPolicy
import com.hop.protocol.WireEnvelope
import com.hop.protocol.WirePayloadType
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Plain-logic JVM tests for [DontRelayRepository] against hand-rolled fake
 * DAOs -- no Room/instrumentation, matching [RelayRepositoryTest]'s own
 * testability seam. Covers Phase 2 Slice 2's test plan cases 1-5.
 */
class DontRelayRepositoryTest {

    private val random = SecureRandom()
    private val originatedAtMs = 1_700_000_000_000L
    private val ttlSeconds = 3600L
    private val threshold = 3

    private fun randomHex(size: Int): String =
        ByteArray(size).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }

    private fun clockAt(epochMs: Long): Clock = Clock.fixed(Instant.ofEpochMilli(epochMs), ZoneOffset.UTC)

    private fun sampleFlag(
        clipHash: String,
        attestedDeviceKey: String = randomHex(32),
        originatedAtMs: Long = this.originatedAtMs,
        ttlSeconds: Long = this.ttlSeconds,
    ) = DontRelayFlagEntity(
        clipHash = clipHash,
        attestedDeviceKey = attestedDeviceKey,
        flaggedAtMs = originatedAtMs + 1_000,
        originatedAtMs = originatedAtMs,
        ttlSeconds = ttlSeconds,
    )

    private fun sampleFrame(clipHashHex: String, dontRelay: Boolean = false): Frame = Frame(
        clipHash = clipHashHex.hexToByteArray(),
        senderDeviceId = ByteArray(Frame.SENDER_DEVICE_ID_SIZE).also { random.nextBytes(it) },
        contentType = ContentType.PHOTO,
        hopCount = 0,
        originatedAtMs = originatedAtMs,
        ttlSeconds = ttlSeconds,
        reachTier = ReachTier.LOCALITY,
        dontRelay = dontRelay,
        keyIncluded = true,
        contentEncryptionKey = ByteArray(Frame.CONTENT_ENCRYPTION_KEY_SIZE).also { random.nextBytes(it) },
        payload = ByteArray(64).also { random.nextBytes(it) },
    )

    private fun String.hexToByteArray(): ByteArray =
        ByteArray(length / 2) { i -> ((Character.digit(this[i * 2], 16) shl 4) + Character.digit(this[i * 2 + 1], 16)).toByte() }

    // --- Case 1: threshold crossing ---

    @Test
    fun `distinct flags below threshold leave dontRelay false, the Nth flips it`() {
        val flagDao = FakeDontRelayFlagDao()
        val relayQueueDao = FakeRelayQueueDao()
        val clipHash = randomHex(32)
        relayQueueDao.rows[clipHash] = sampleRelayRow(clipHash)
        val repository = DontRelayRepository(flagDao, relayQueueDao, RelayPolicy(clock = clockAt(originatedAtMs)), threshold = threshold)

        runBlocking {
            repository.recordFlag(sampleFlag(clipHash))
            assertFalse(relayQueueDao.rows.getValue(clipHash).dontRelay, "N-1 distinct flags must not flip dontRelay yet")
            repository.recordFlag(sampleFlag(clipHash))
            assertFalse(relayQueueDao.rows.getValue(clipHash).dontRelay, "N-1 distinct flags must not flip dontRelay yet")
            repository.recordFlag(sampleFlag(clipHash)) // 3rd distinct flag -- crosses threshold
        }

        assertTrue(relayQueueDao.rows.getValue(clipHash).dontRelay, "the Nth distinct flag must flip dontRelay to true")
    }

    // --- Case 2: duplicate flag, same key ---

    @Test
    fun `the same attestedDeviceKey flagging twice does not increase the distinct count and is not new`() {
        val flagDao = FakeDontRelayFlagDao()
        val relayQueueDao = FakeRelayQueueDao()
        val clipHash = randomHex(32)
        val sameKey = randomHex(32)
        val repository = DontRelayRepository(flagDao, relayQueueDao, RelayPolicy(clock = clockAt(originatedAtMs)), threshold = threshold)

        val firstResult = runBlocking { repository.recordFlag(sampleFlag(clipHash, attestedDeviceKey = sameKey)) }
        val secondResult = runBlocking {
            repository.recordFlag(sampleFlag(clipHash, attestedDeviceKey = sameKey, ttlSeconds = ttlSeconds + 99))
        }

        assertTrue(firstResult, "first flag from a key must be recorded as new")
        assertFalse(secondResult, "a second flag from the same key for the same clipHash must not be reported as new")
        assertEquals(1, runBlocking { flagDao.distinctFlaggerCount(clipHash) })
    }

    // --- Case 3: distinct-key counting (Sybil-shape test) ---

    @Test
    fun `N flags from N distinct keys count as N -- validates counting logic only, not Sybil-hardness under the stub`() {
        // This test validates DontRelayRepository's *counting* correctness
        // given N distinct keys -- it is explicitly NOT a claim that minting
        // N distinct attestedDeviceKey values is hard. Under
        // com.hop.crypto.StubAttestationProvider, it costs nothing, even for
        // a stock client -- see DontRelayRepository's own "Limits" doc.
        val flagDao = FakeDontRelayFlagDao()
        val relayQueueDao = FakeRelayQueueDao()
        val clipHash = randomHex(32)
        val repository = DontRelayRepository(flagDao, relayQueueDao, RelayPolicy(clock = clockAt(originatedAtMs)), threshold = 100)

        runBlocking {
            repeat(7) { repository.recordFlag(sampleFlag(clipHash)) }
        }

        assertEquals(7, runBlocking { flagDao.distinctFlaggerCount(clipHash) })
    }

    // --- Case 4: order-independence -- flag for a clipHash never held locally ---

    @Test
    fun `recordFlag succeeds with no matching RelayQueueEntity row, and a post arriving later is suppressed from the start`() {
        val flagDao = FakeDontRelayFlagDao()
        val relayQueueDao = FakeRelayQueueDao()
        val clock = clockAt(originatedAtMs)
        val relayPolicy = RelayPolicy(clock = clock)
        val dontRelayRepository = DontRelayRepository(flagDao, relayQueueDao, relayPolicy, threshold = threshold)
        val clipHash = randomHex(32)

        // Flags arrive first -- no RelayQueueEntity row exists yet for this clipHash.
        runBlocking {
            repeat(threshold) { dontRelayRepository.recordFlag(sampleFlag(clipHash)) }
        }
        assertTrue(relayQueueDao.rows.isEmpty(), "recordFlag must not create/require a RelayQueueEntity row")
        assertEquals(threshold, runBlocking { flagDao.distinctFlaggerCount(clipHash) })
        assertTrue(runBlocking { dontRelayRepository.isSuppressed(clipHash) })

        // The post now arrives, wired through RelayRepository with
        // DontRelayRepository.isSuppressed injected exactly as production
        // AppContainer wiring does.
        val relayRepository = RelayRepository(
            relayQueueDao,
            relayPolicy,
            isFlaggedForSuppression = dontRelayRepository::isSuppressed,
        )
        val frame = sampleFrame(clipHash, dontRelay = false)
        runBlocking { relayRepository.considerForRelay(frame) }

        assertEquals(1, relayQueueDao.rows.size)
        assertTrue(
            relayQueueDao.rows.getValue(clipHash).dontRelay,
            "a post arriving after its flags already crossed threshold must have dontRelay = true from the start",
        )
    }

    // --- Case 5: flag TTL/expiry ---

    @Test
    fun `an already-expired flag is rejected at recordFlag time`() {
        val flagDao = FakeDontRelayFlagDao()
        val relayQueueDao = FakeRelayQueueDao()
        val expiresAtMs = originatedAtMs + ttlSeconds * 1000
        val repository = DontRelayRepository(flagDao, relayQueueDao, RelayPolicy(clock = clockAt(expiresAtMs)), threshold = threshold)
        val clipHash = randomHex(32)

        val result = runBlocking { repository.recordFlag(sampleFlag(clipHash)) }

        assertFalse(result, "an already-expired flag must be rejected")
        assertTrue(flagDao.rows.isEmpty(), "an already-expired flag must not be stored")
    }

    @Test
    fun `an already-stored flag past its TTL is excluded from buildOutgoingFlagBacklog`() {
        val flagDao = FakeDontRelayFlagDao()
        val relayQueueDao = FakeRelayQueueDao()
        val custodyClock = clockAt(originatedAtMs)
        val repository = DontRelayRepository(flagDao, relayQueueDao, RelayPolicy(clock = custodyClock), threshold = threshold)
        val clipHash = randomHex(32)
        runBlocking { repository.recordFlag(sampleFlag(clipHash)) }
        assertEquals(1, flagDao.rows.size)

        val expiresAtMs = originatedAtMs + ttlSeconds * 1000
        val laterRepository = DontRelayRepository(flagDao, relayQueueDao, RelayPolicy(clock = clockAt(expiresAtMs)), threshold = threshold)
        val backlog = runBlocking { laterRepository.buildOutgoingFlagBacklog() }

        assertTrue(backlog.isEmpty(), "an expired flag must be excluded from the outgoing backlog")
        assertTrue(flagDao.rows.isEmpty(), "an expired flag encountered while building the backlog should be pruned")
    }

    @Test
    fun `buildOutgoingFlagBacklog wraps non-expired flags as DONT_RELAY_FLAG envelopes`() {
        val flagDao = FakeDontRelayFlagDao()
        val relayQueueDao = FakeRelayQueueDao()
        val repository = DontRelayRepository(flagDao, relayQueueDao, RelayPolicy(clock = clockAt(originatedAtMs)), threshold = threshold)
        val clipHash = randomHex(32)
        runBlocking { repository.recordFlag(sampleFlag(clipHash)) }

        val backlog = runBlocking { repository.buildOutgoingFlagBacklog() }

        assertEquals(1, backlog.size)
        val envelope = WireEnvelope.decode(backlog.single())
        assertEquals(WirePayloadType.DONT_RELAY_FLAG, envelope.type)
        val decoded = DontRelayFlagEnvelope.decode(envelope.payload)
        assertEquals(clipHash, decoded.clipHash.toHexString())
    }

    private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

    private fun sampleRelayRow(clipHash: String) = RelayQueueEntity(
        clipHash = clipHash,
        encodedFrame = sampleFrame(clipHash).encode(),
        hopCount = 0,
        originatedAtMs = originatedAtMs,
        ttlSeconds = ttlSeconds,
        dontRelay = false,
        receivedAtMs = originatedAtMs,
    )

    /** Minimal fake [DontRelayFlagDao], matching this repo's hand-rolled-fakes pattern. */
    private class FakeDontRelayFlagDao : DontRelayFlagDao {
        val rows = mutableMapOf<Pair<String, String>, DontRelayFlagEntity>()

        override suspend fun insert(row: DontRelayFlagEntity): Long {
            val key = row.clipHash to row.attestedDeviceKey
            if (rows.containsKey(key)) return -1L
            rows[key] = row
            return 1L
        }

        override suspend fun distinctFlaggerCount(clipHash: String): Int =
            rows.keys.count { it.first == clipHash }

        override suspend fun getAll(): List<DontRelayFlagEntity> = rows.values.toList()

        override suspend fun deleteAllForClip(clipHash: String) {
            rows.keys.filter { it.first == clipHash }.forEach { rows.remove(it) }
        }
    }

    /** Minimal fake [RelayQueueDao], matching [RelayRepositoryTest]'s own fake. */
    private class FakeRelayQueueDao : RelayQueueDao {
        val rows = mutableMapOf<String, RelayQueueEntity>()

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
}
