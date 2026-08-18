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

@RunWith(AndroidJUnit4::class)
class MessageDaoTest {

    private lateinit var db: HopDatabase
    private lateinit var dao: MessageDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            HopDatabase::class.java,
        ).build()
        dao = db.messageDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun insertThenQueryRoundTrip() = runBlocking {
        val id = dao.insert(
            MessageEntity(peerId = "peer-1", plaintext = "hi", sentAtMs = 1000L, isOutgoing = true)
        )

        val messages = dao.getMessagesForPeer("peer-1").first()
        assertEquals(1, messages.size)
        assertEquals(id, messages.single().id)
        assertEquals("hi", messages.single().plaintext)
    }

    @Test
    fun getMessagesForPeerOrdersBySentAtMsAscending() = runBlocking {
        dao.insert(MessageEntity(peerId = "peer-1", plaintext = "third", sentAtMs = 3000L, isOutgoing = false))
        dao.insert(MessageEntity(peerId = "peer-1", plaintext = "first", sentAtMs = 1000L, isOutgoing = true))
        dao.insert(MessageEntity(peerId = "peer-1", plaintext = "second", sentAtMs = 2000L, isOutgoing = false))

        val ordered = dao.getMessagesForPeer("peer-1").first()
        assertEquals(listOf("first", "second", "third"), ordered.map { it.plaintext })
    }

    @Test
    fun getMessagesForPeerOnlyReturnsThatPeersMessages() = runBlocking {
        dao.insert(MessageEntity(peerId = "peer-1", plaintext = "for peer 1", sentAtMs = 1000L, isOutgoing = true))
        dao.insert(MessageEntity(peerId = "peer-2", plaintext = "for peer 2", sentAtMs = 1000L, isOutgoing = true))

        val peer1Messages = dao.getMessagesForPeer("peer-1").first()
        assertEquals(listOf("for peer 1"), peer1Messages.map { it.plaintext })
    }
}
