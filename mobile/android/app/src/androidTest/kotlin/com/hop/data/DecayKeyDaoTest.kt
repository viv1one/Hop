package com.hop.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

@RunWith(AndroidJUnit4::class)
class DecayKeyDaoTest {

    private lateinit var db: HopDatabase
    private lateinit var dao: DecayKeyDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            HopDatabase::class.java,
        ).build()
        dao = db.decayKeyDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun insertThenGetByIdRoundTrip() {
        val entity = DecayKeyEntity(contentId = "clip-1", wrappedCek = byteArrayOf(1, 2, 3, 4), expiresAtMs = 5000L)
        dao.insertOrReplace(entity)

        val fetched = dao.getById("clip-1")
        assertContentEquals(entity.wrappedCek, fetched?.wrappedCek)
    }

    @Test
    fun getByIdReturnsNullWhenAbsent() {
        assertNull(dao.getById("never-stored"))
    }

    @Test
    fun insertOrReplaceOverwritesExistingRow() {
        dao.insertOrReplace(DecayKeyEntity(contentId = "clip-1", wrappedCek = byteArrayOf(1), expiresAtMs = 1000L))
        dao.insertOrReplace(DecayKeyEntity(contentId = "clip-1", wrappedCek = byteArrayOf(2), expiresAtMs = 2000L))

        val fetched = dao.getById("clip-1")
        assertContentEquals(byteArrayOf(2), fetched?.wrappedCek)
    }

    @Test
    fun deleteByIdRemovesTheRow() {
        dao.insertOrReplace(DecayKeyEntity(contentId = "clip-1", wrappedCek = byteArrayOf(1), expiresAtMs = 1000L))
        dao.deleteById("clip-1")

        assertNull(dao.getById("clip-1"))
    }
}
