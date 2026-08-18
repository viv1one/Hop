package com.hop.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class BlockedSenderDeviceDaoTest {

    private lateinit var db: HopDatabase
    private lateinit var dao: BlockedSenderDeviceDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            HopDatabase::class.java,
        ).build()
        dao = db.blockedSenderDeviceDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun insertThenObserveAllRoundTrip() = runBlocking {
        dao.insert(BlockedSenderDeviceEntity(senderDeviceId = "device-1", blockedAtMs = 1000L))

        assertEquals(listOf("device-1"), dao.observeAll().first())
    }

    @Test
    fun observeAllReturnsEmptyWhenNothingBlocked() = runBlocking {
        assertTrue(dao.observeAll().first().isEmpty())
    }

    @Test
    fun reInsertOfSameSenderDeviceIdDoesNotThrowOrDuplicate() = runBlocking {
        dao.insert(BlockedSenderDeviceEntity(senderDeviceId = "device-1", blockedAtMs = 1000L))
        // Re-block (e.g. the app inserting the same row again) must not throw
        // on the primary-key conflict.
        dao.insert(BlockedSenderDeviceEntity(senderDeviceId = "device-1", blockedAtMs = 2000L))

        assertEquals(listOf("device-1"), dao.observeAll().first())
    }

    @Test
    fun multipleBlockedDevicesAllReturned() = runBlocking {
        dao.insert(BlockedSenderDeviceEntity(senderDeviceId = "device-1", blockedAtMs = 1000L))
        dao.insert(BlockedSenderDeviceEntity(senderDeviceId = "device-2", blockedAtMs = 2000L))

        val all = dao.observeAll().first()
        assertEquals(setOf("device-1", "device-2"), all.toSet())
    }
}
