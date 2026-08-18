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
import kotlin.test.assertNull

@RunWith(AndroidJUnit4::class)
class PostDaoTest {

    private lateinit var db: HopDatabase
    private lateinit var dao: PostDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            HopDatabase::class.java,
        ).build()
        dao = db.postDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun samplePost(clipHash: String, receivedAtMs: Long) = PostEntity(
        clipHash = clipHash,
        senderDeviceId = "abcdef0123456789",
        contentType = "VIDEO",
        originatedAtMs = 1_700_000_000_000L,
        ttlSeconds = 3600,
        reachTier = "LOCALITY",
        dontRelay = false,
        receivedAtMs = receivedAtMs,
        encryptedPayloadFilePath = "/data/data/com.hop.spike/files/posts/$clipHash.enc",
    )

    @Test
    fun insertThenQueryRoundTrip() = runBlocking {
        val post = samplePost("clip-1", receivedAtMs = 1000L)
        dao.upsert(post)

        assertEquals(post, dao.getByClipHash("clip-1"))
    }

    @Test
    fun getByClipHashReturnsNullWhenAbsent() = runBlocking {
        assertNull(dao.getByClipHash("never-stored"))
    }

    @Test
    fun getAllOrderedByReceivedDescOrdersMostRecentFirst() = runBlocking {
        dao.upsert(samplePost("clip-old", receivedAtMs = 1000L))
        dao.upsert(samplePost("clip-new", receivedAtMs = 3000L))
        dao.upsert(samplePost("clip-mid", receivedAtMs = 2000L))

        val ordered = dao.getAllOrderedByReceivedDesc().first()
        assertEquals(listOf("clip-new", "clip-mid", "clip-old"), ordered.map { it.clipHash })
    }

    @Test
    fun upsertReplacesExistingRowForSameClipHash() = runBlocking {
        dao.upsert(samplePost("clip-1", receivedAtMs = 1000L))
        dao.upsert(samplePost("clip-1", receivedAtMs = 5000L))

        val all = dao.getAllOrderedByReceivedDesc().first()
        assertEquals(1, all.size)
        assertEquals(5000L, all.single().receivedAtMs)
    }
}
