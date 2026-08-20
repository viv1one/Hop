package com.hop.repository

import com.hop.data.PendingMessageDao
import com.hop.data.PendingMessageEntity
import com.hop.protocol.MessageCiphertextEnvelope
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
 * Plain-logic JVM tests for [PendingMessageRepository] against a hand-rolled
 * fake [PendingMessageDao] -- no Room/instrumentation, mirroring
 * [RelayRepositoryTest]'s own shape exactly (this class deliberately mirrors
 * [RelayRepository]'s design for the same reasons).
 */
class PendingMessageRepositoryTest {

    private val random = SecureRandom()
    private val originatedAtMs = 1_700_000_000_000L
    private val ttlSeconds = MessageCiphertextEnvelope.DEFAULT_TTL_SECONDS

    private fun randomBytes(size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }

    private fun clockAt(epochMs: Long): Clock = Clock.fixed(Instant.ofEpochMilli(epochMs), ZoneOffset.UTC)

    private fun sampleEnvelope(hopCount: Int = 0, ciphertextSeed: Int = 0): MessageCiphertextEnvelope =
        MessageCiphertextEnvelope(
            senderPeerId = "sender-peer",
            recipientPeerId = "recipient-peer",
            hopCount = hopCount,
            originatedAtMs = originatedAtMs,
            ciphertext = randomBytes(64).also { it[0] = ciphertextSeed.toByte() },
        )

    @Test
    fun `considerForRelay stores an eligible envelope and returns the row`() {
        val dao = FakePendingMessageDao()
        val repository = PendingMessageRepository(dao, RelayPolicy(clock = clockAt(originatedAtMs)))
        val envelope = sampleEnvelope(hopCount = 0)

        val row = runBlocking { repository.considerForRelay(envelope) }

        assertNotNull(row, "genuine new custody must return the inserted row")
        assertEquals(1, dao.rows.size)
        assertEquals(0, dao.rows.values.single().hopCount)
        assertEquals("recipient-peer", dao.rows.values.single().recipientPeerId)
    }

    @Test
    fun `considerForRelay does not store an envelope already at maxHops`() {
        val dao = FakePendingMessageDao()
        val repository = PendingMessageRepository(dao, RelayPolicy(maxHops = 6, clock = clockAt(originatedAtMs)))

        val row = runBlocking { repository.considerForRelay(sampleEnvelope(hopCount = 6)) }

        assertNull(row)
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `considerForRelay does not store an already-expired envelope`() {
        val dao = FakePendingMessageDao()
        val expiresAtMs = originatedAtMs + ttlSeconds * 1000
        val repository = PendingMessageRepository(dao, RelayPolicy(clock = clockAt(expiresAtMs)))

        val row = runBlocking { repository.considerForRelay(sampleEnvelope()) }

        assertNull(row)
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `considerForRelay is idempotent for the same ciphertext hash -- first custody wins, and returns null on the dup`() {
        val dao = FakePendingMessageDao()
        val repository = PendingMessageRepository(dao, RelayPolicy(clock = clockAt(originatedAtMs)))
        val envelope = sampleEnvelope(hopCount = 2, ciphertextSeed = 7)

        val first = runBlocking { repository.considerForRelay(envelope) }
        // A "duplicate" custody attempt for the same ciphertext (a second
        // peer offering it, or a reconnect) but a different (bogus) hopCount
        // must not overwrite the first, and must report itself as not-new.
        val second = runBlocking { repository.considerForRelay(envelope.copy(hopCount = 5)) }

        assertNotNull(first)
        assertNull(second, "a duplicate custody attempt must return null, not a row")
        assertEquals(1, dao.rows.size)
        assertEquals(2, dao.rows.values.single().hopCount, "first custody's hopCount must win, never be reset")
    }

    @Test
    fun `buildOutgoingBacklog returns eligible rows re-encoded with hopCount plus one`() {
        val dao = FakePendingMessageDao()
        val repository = PendingMessageRepository(dao, RelayPolicy(clock = clockAt(originatedAtMs)))
        val envelope = sampleEnvelope(hopCount = 1)
        runBlocking { repository.considerForRelay(envelope) }

        val backlog = runBlocking { repository.buildOutgoingBacklog() }

        assertEquals(1, backlog.size)
        val wireEnvelope = WireEnvelope.decode(backlog.single())
        val outgoing = MessageCiphertextEnvelope.decode(wireEnvelope.payload)
        assertEquals(2, outgoing.hopCount)
        assertEquals("recipient-peer", outgoing.recipientPeerId)
    }

    @Test
    fun `buildOutgoingBacklog deletes an expired row and does not include it -- TTL expiry pruning`() {
        val dao = FakePendingMessageDao()
        val custodyClock = clockAt(originatedAtMs)
        val repository = PendingMessageRepository(dao, RelayPolicy(clock = custodyClock))
        runBlocking { repository.considerForRelay(sampleEnvelope()) }
        assertEquals(1, dao.rows.size)

        val expiresAtMs = originatedAtMs + ttlSeconds * 1000
        val laterRepository = PendingMessageRepository(dao, RelayPolicy(clock = clockAt(expiresAtMs)))
        val backlog = runBlocking { laterRepository.buildOutgoingBacklog() }

        assertTrue(backlog.isEmpty())
        assertTrue(dao.rows.isEmpty(), "an expired row encountered while building the backlog must be pruned")
    }

    @Test
    fun `buildOutgoingBacklog omits a row past maxHops but does not delete it`() {
        val dao = FakePendingMessageDao()
        val pastMaxHopsEnvelope = sampleEnvelope(hopCount = 6)
        dao.rows["past-max-hash"] = PendingMessageEntity(
            ciphertextHash = "past-max-hash",
            recipientPeerId = "recipient-peer",
            encodedEnvelope = pastMaxHopsEnvelope.encode(),
            hopCount = 6,
            originatedAtMs = originatedAtMs,
            receivedAtMs = originatedAtMs,
        )
        val repository = PendingMessageRepository(dao, RelayPolicy(maxHops = 6, clock = clockAt(originatedAtMs)))

        val backlog = runBlocking { repository.buildOutgoingBacklog() }

        assertTrue(backlog.isEmpty())
        assertEquals(1, dao.rows.size, "a row past maxHops is left in place, not deleted -- only expiry deletes")
    }

    @Test
    fun `findByRecipient returns only rows addressed to that recipient`() {
        val dao = FakePendingMessageDao()
        val repository = PendingMessageRepository(dao, RelayPolicy(clock = clockAt(originatedAtMs)))
        val forAlice = sampleEnvelope(ciphertextSeed = 1).copy(recipientPeerId = "alice")
        val forBob = sampleEnvelope(ciphertextSeed = 2).copy(recipientPeerId = "bob")
        runBlocking {
            repository.considerForRelay(forAlice)
            repository.considerForRelay(forBob)
        }

        val aliceRows = runBlocking { repository.findByRecipient("alice") }

        assertEquals(1, aliceRows.size)
        assertEquals("alice", aliceRows.single().recipientPeerId)
    }

    @Test
    fun `delete removes a row by ciphertextHash -- the local delivery-confirmation stop signal`() {
        val dao = FakePendingMessageDao()
        val repository = PendingMessageRepository(dao, RelayPolicy(clock = clockAt(originatedAtMs)))
        val envelope = sampleEnvelope()
        val row = runBlocking { repository.considerForRelay(envelope) }
        assertNotNull(row)

        runBlocking { repository.delete(row.ciphertextHash) }

        assertTrue(dao.rows.isEmpty())
    }

    /** Minimal fake [PendingMessageDao], matching this repo's hand-rolled-fakes pattern. */
    private class FakePendingMessageDao : PendingMessageDao {
        val rows = mutableMapOf<String, PendingMessageEntity>()

        override suspend fun insert(row: PendingMessageEntity) {
            rows.putIfAbsent(row.ciphertextHash, row)
        }

        override suspend fun getAll(): List<PendingMessageEntity> = rows.values.toList()

        override suspend fun getByHash(hash: String): PendingMessageEntity? = rows[hash]

        override suspend fun delete(hash: String) {
            rows.remove(hash)
        }
    }
}
