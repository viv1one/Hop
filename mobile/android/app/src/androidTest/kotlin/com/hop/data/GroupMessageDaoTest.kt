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
class GroupMessageDaoTest {

    private lateinit var db: HopDatabase
    private lateinit var groupMessageDao: GroupMessageDao
    private lateinit var messageDao: MessageDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            HopDatabase::class.java,
        ).build()
        groupMessageDao = db.groupMessageDao()
        messageDao = db.messageDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun insertThenQueryRoundTrip() = runBlocking {
        val id = groupMessageDao.insert(
            GroupMessageEntity(groupId = "group-1", authorPeerId = "peer-b", plaintext = "hi", sentAtMs = 1000L, isOutgoing = false),
        )

        val messages = groupMessageDao.getMessagesForGroup("group-1").first()
        assertEquals(1, messages.size)
        assertEquals(id, messages.single().id)
        assertEquals("hi", messages.single().plaintext)
    }

    @Test
    fun getMessagesForGroupOrdersBySentAtMsAscending() = runBlocking {
        groupMessageDao.insert(GroupMessageEntity(groupId = "group-1", authorPeerId = "peer-b", plaintext = "third", sentAtMs = 3000L, isOutgoing = false))
        groupMessageDao.insert(GroupMessageEntity(groupId = "group-1", authorPeerId = "peer-b", plaintext = "first", sentAtMs = 1000L, isOutgoing = true))
        groupMessageDao.insert(GroupMessageEntity(groupId = "group-1", authorPeerId = "peer-b", plaintext = "second", sentAtMs = 2000L, isOutgoing = false))

        val ordered = groupMessageDao.getMessagesForGroup("group-1").first()
        assertEquals(listOf("first", "second", "third"), ordered.map { it.plaintext })
    }

    @Test
    fun getLatestMessagePerGroupReturnsOneRowPerGroup() = runBlocking {
        groupMessageDao.insert(GroupMessageEntity(groupId = "group-1", authorPeerId = "peer-b", plaintext = "old", sentAtMs = 1000L, isOutgoing = false))
        groupMessageDao.insert(GroupMessageEntity(groupId = "group-1", authorPeerId = "peer-b", plaintext = "new", sentAtMs = 2000L, isOutgoing = false))
        groupMessageDao.insert(GroupMessageEntity(groupId = "group-2", authorPeerId = "peer-c", plaintext = "only", sentAtMs = 1500L, isOutgoing = false))

        val latest = groupMessageDao.getLatestMessagePerGroup().first()
        assertEquals(2, latest.size)
        assertEquals("new", latest.first { it.groupId == "group-1" }.plaintext)
        assertEquals("only", latest.first { it.groupId == "group-2" }.plaintext)
    }

    /**
     * Guards the separate-table boundary [GroupEntity]'s class doc depends on:
     * a group message must never be reachable through [MessageDao]'s own
     * queries, even if the group's `authorPeerId` happens to also be a real
     * 1:1 `peerId` this device has a conversation with (exactly the collision
     * scenario the separate-tables design exists to prevent).
     */
    @Test
    fun aGroupMessageNeverAppearsInMessageDaosQueries() = runBlocking {
        val sharedPeerId = "peer-b" // also a member of the group below
        messageDao.insert(MessageEntity(peerId = sharedPeerId, plaintext = "a real 1:1 message", sentAtMs = 1000L, isOutgoing = false))
        groupMessageDao.insert(
            GroupMessageEntity(groupId = "group-1", authorPeerId = sharedPeerId, plaintext = "a group message from the same peer id", sentAtMs = 2000L, isOutgoing = false),
        )

        val directMessages = messageDao.getMessagesForPeer(sharedPeerId).first()
        assertEquals(listOf("a real 1:1 message"), directMessages.map { it.plaintext })

        val latestPerPeer = messageDao.getLatestMessagePerPeer().first()
        assertTrue(latestPerPeer.all { it.plaintext != "a group message from the same peer id" })
    }
}
