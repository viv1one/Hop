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
class ReportedPostDaoTest {

    private lateinit var db: HopDatabase
    private lateinit var dao: ReportedPostDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            HopDatabase::class.java,
        ).build()
        dao = db.reportedPostDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun insertThenObserveAllRoundTrip() = runBlocking {
        dao.insert(ReportedPostEntity(clipHash = "clip-1", reportedAtMs = 1000L))

        assertEquals(listOf("clip-1"), dao.observeAll().first())
    }

    @Test
    fun observeAllReturnsEmptyWhenNothingReported() = runBlocking {
        assertTrue(dao.observeAll().first().isEmpty())
    }

    @Test
    fun reInsertOfSameClipHashDoesNotThrowOrDuplicate() = runBlocking {
        dao.insert(ReportedPostEntity(clipHash = "clip-1", reportedAtMs = 1000L))
        // Re-report must not throw on the primary-key conflict.
        dao.insert(ReportedPostEntity(clipHash = "clip-1", reportedAtMs = 2000L))

        assertEquals(listOf("clip-1"), dao.observeAll().first())
    }

    @Test
    fun multipleReportedPostsAllReturned() = runBlocking {
        dao.insert(ReportedPostEntity(clipHash = "clip-1", reportedAtMs = 1000L))
        dao.insert(ReportedPostEntity(clipHash = "clip-2", reportedAtMs = 2000L))

        val all = dao.observeAll().first()
        assertEquals(setOf("clip-1", "clip-2"), all.toSet())
    }
}
