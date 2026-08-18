package com.hop.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class BlockedIdentityDaoTest {

    private lateinit var db: HopDatabase
    private lateinit var dao: BlockedIdentityDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            HopDatabase::class.java,
        ).build()
        dao = db.blockedIdentityDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun insertThenQueryRoundTrip() = runBlocking {
        dao.insert(BlockedIdentityEntity(attestedDeviceKey = "deadbeef", blockedAtMs = 1000L))

        assertTrue(dao.isBlocked("deadbeef"))
    }

    @Test
    fun isBlockedReturnsFalseForUnblockedKey() = runBlocking {
        assertFalse(dao.isBlocked("never-blocked"))
    }

    @Test
    fun blockingSurvivesReinsertOfSameAttestedKey() = runBlocking {
        dao.insert(BlockedIdentityEntity(attestedDeviceKey = "deadbeef", blockedAtMs = 1000L))
        // Re-block (e.g. the app inserting the same row again) must not throw
        // on the primary-key conflict.
        dao.insert(BlockedIdentityEntity(attestedDeviceKey = "deadbeef", blockedAtMs = 2000L))

        assertTrue(dao.isBlocked("deadbeef"))
    }
}
