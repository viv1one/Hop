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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class GroupDaoTest {

    private lateinit var db: HopDatabase
    private lateinit var dao: GroupDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            HopDatabase::class.java,
        ).build()
        dao = db.groupDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun insertGroupWithMembersIsAtomicAndQueryable() = runBlocking {
        dao.insertGroupWithMembers(
            GroupEntity(groupId = "group-1", name = "Trip", creatorPeerId = "creator", createdAtMs = 1000L),
            listOf(GroupMemberEntity("group-1", "member-b"), GroupMemberEntity("group-1", "member-c")),
        )

        assertEquals("creator", dao.getCreatorPeerId("group-1"))
        assertEquals(GroupEntity("group-1", "Trip", "creator", 1000L), dao.getGroup("group-1"))
    }

    @Test
    fun getCreatorPeerIdReturnsNullForAnUnknownGroup() = runBlocking {
        assertNull(dao.getCreatorPeerId("no-such-group"))
    }

    @Test
    fun isKnownMemberIsTrueForTheCreatorEvenThoughItHasNoMemberRowOfItsOwn() = runBlocking {
        dao.insertGroupWithMembers(
            GroupEntity(groupId = "group-1", name = "Trip", creatorPeerId = "creator", createdAtMs = 1000L),
            listOf(GroupMemberEntity("group-1", "member-b")),
        )

        assertTrue(dao.isKnownMember("group-1", "creator"))
        assertTrue(dao.isKnownMember("group-1", "member-b"))
        assertFalse(dao.isKnownMember("group-1", "a-stranger"))
        assertFalse(dao.isKnownMember("no-such-group", "creator"))
    }

    @Test
    fun getFanoutTargetPeerIdsUnionsTheCreatorAndTheMemberRows() = runBlocking {
        dao.insertGroupWithMembers(
            GroupEntity(groupId = "group-1", name = "Trip", creatorPeerId = "creator", createdAtMs = 1000L),
            listOf(GroupMemberEntity("group-1", "member-b"), GroupMemberEntity("group-1", "member-c")),
        )

        val targets = dao.getFanoutTargetPeerIds("group-1")
        assertEquals(setOf("creator", "member-b", "member-c"), targets.toSet())
    }

    @Test
    fun observeGroupsReflectsInsertedRows() = runBlocking {
        dao.insertGroupWithMembers(
            GroupEntity(groupId = "group-1", name = "Trip", creatorPeerId = "creator", createdAtMs = 1000L),
            emptyList(),
        )

        val groups = dao.observeGroups().first()
        assertEquals(listOf("group-1"), groups.map(GroupEntity::groupId))
    }
}
