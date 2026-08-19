package com.hop.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Raw DAO/entity round-trip coverage for the six tables backing
 * [RoomSignalProtocolStore] -- matches this repo's established per-DAO test
 * granularity (see [DecayKeyDaoTest]). [RoomSignalProtocolStoreTest] covers
 * the actual Double Ratchet behavior on top of these DAOs (round trip,
 * forward secrecy, out-of-order delivery, persistence across a simulated
 * restart); this file is narrower -- just "does Room store and return
 * exactly what was put in," isolated from any ratchet logic.
 */
@RunWith(AndroidJUnit4::class)
class SignalStoreDaoTest {

    private lateinit var db: HopDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), HopDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun ownIdentityRoundTripsAndIsAbsentInitially() {
        val dao = db.signalIdentityDao()
        assertNull(dao.getOwnIdentity())

        val entity = IdentityKeyPairEntity(
            identityKeyPairBytes = byteArrayOf(1, 2, 3),
            registrationId = 42,
            createdAtMs = 1000L,
        )
        dao.insertOwnIdentity(entity)

        val fetched = dao.getOwnIdentity()
        assertContentEquals(entity.identityKeyPairBytes, fetched?.identityKeyPairBytes)
        assertEquals(entity.registrationId, fetched?.registrationId)
    }

    @Test
    fun insertOwnIdentityReplacesTheSingletonRow() {
        val dao = db.signalIdentityDao()
        dao.insertOwnIdentity(IdentityKeyPairEntity(identityKeyPairBytes = byteArrayOf(1), registrationId = 1, createdAtMs = 0L))
        dao.insertOwnIdentity(IdentityKeyPairEntity(identityKeyPairBytes = byteArrayOf(2), registrationId = 2, createdAtMs = 0L))

        val fetched = dao.getOwnIdentity()
        assertContentEquals(byteArrayOf(2), fetched?.identityKeyPairBytes)
        assertEquals(2, fetched?.registrationId)
    }

    @Test
    fun remoteIdentityRoundTripsPerPeer() {
        val dao = db.signalIdentityDao()
        assertNull(dao.getRemoteIdentity("peer-1"))

        dao.upsertRemoteIdentity(RemoteIdentityEntity(peerId = "peer-1", identityKeyBytes = byteArrayOf(9, 9), firstSeenAtMs = 500L))
        val fetched = dao.getRemoteIdentity("peer-1")
        assertContentEquals(byteArrayOf(9, 9), fetched?.identityKeyBytes)

        // A different peer id is a distinct row.
        assertNull(dao.getRemoteIdentity("peer-2"))
    }

    @Test
    fun preKeyRoundTripAndDelete() {
        val dao = db.signalPreKeyDao()
        assertEquals(0, dao.countById(1))

        dao.insertOrReplace(PreKeyEntity(preKeyId = 1, recordBytes = byteArrayOf(1, 2, 3)))
        assertEquals(1, dao.countById(1))
        assertContentEquals(byteArrayOf(1, 2, 3), dao.getById(1)?.recordBytes)

        dao.deleteById(1)
        assertNull(dao.getById(1))
    }

    @Test
    fun signedPreKeyRoundTripAndGetAll() {
        val dao = db.signalSignedPreKeyDao()
        dao.insertOrReplace(SignedPreKeyEntity(signedPreKeyId = 1, recordBytes = byteArrayOf(1), createdAtMs = 10L))
        dao.insertOrReplace(SignedPreKeyEntity(signedPreKeyId = 2, recordBytes = byteArrayOf(2), createdAtMs = 20L))

        assertEquals(2, dao.getAll().size)
        assertTrue(dao.containsIdViaCount(2))
    }

    private fun SignalSignedPreKeyDao.containsIdViaCount(id: Int): Boolean = countById(id) > 0

    @Test
    fun kyberPreKeyDefaultsUnusedThenMarkedUsed() {
        val dao = db.signalKyberPreKeyDao()
        dao.insertOrReplace(KyberPreKeyEntity(kyberPreKeyId = 1, recordBytes = byteArrayOf(7)))

        assertEquals(false, dao.getById(1)?.used)

        dao.markUsed(1)
        assertEquals(true, dao.getById(1)?.used)
    }

    @Test
    fun sessionRoundTripByAddressKeyAndByName() {
        val dao = db.signalSessionDao()
        val key1 = SessionEntity.addressKey("alice", 1)
        val key2 = SessionEntity.addressKey("alice", 2)

        dao.insertOrReplace(SessionEntity(addressKey = key1, name = "alice", deviceId = 1, sessionRecordBytes = byteArrayOf(1)))
        dao.insertOrReplace(SessionEntity(addressKey = key2, name = "alice", deviceId = 2, sessionRecordBytes = byteArrayOf(2)))

        assertEquals(2, dao.getDeviceIdsForName("alice").size)
        assertContentEquals(byteArrayOf(1), dao.getByKey(key1)?.sessionRecordBytes)

        dao.deleteAllForName("alice")
        assertNull(dao.getByKey(key1))
        assertNull(dao.getByKey(key2))
    }
}
