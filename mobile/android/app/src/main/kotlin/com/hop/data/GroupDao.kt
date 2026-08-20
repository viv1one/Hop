package com.hop.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface GroupDao {

    @Insert
    suspend fun insertGroup(group: GroupEntity)

    @Insert
    suspend fun insertMembers(members: List<GroupMemberEntity>)

    /**
     * Atomically inserts [group] plus every row in [members] -- both the local
     * "I just created this group" path
     * (`com.hop.repository.MessageRepository.createGroup`) and the "first
     * `GroupInvite` ever seen for this id" trust-on-first-use path
     * (`com.hop.repository.MessageRepository.onEnvelopeReceived`) need a
     * group's row and its membership rows to appear together, never one
     * without the other.
     */
    @Transaction
    suspend fun insertGroupWithMembers(group: GroupEntity, members: List<GroupMemberEntity>) {
        insertGroup(group)
        insertMembers(members)
    }

    @Query("SELECT creatorPeerId FROM groups WHERE groupId = :groupId")
    suspend fun getCreatorPeerId(groupId: String): String?

    @Query("SELECT * FROM groups WHERE groupId = :groupId")
    suspend fun getGroup(groupId: String): GroupEntity?

    @Query("SELECT * FROM groups ORDER BY createdAtMs DESC")
    fun observeGroups(): Flow<List<GroupEntity>>

    /**
     * True if [peerId] is a member of [groupId] this device recognizes --
     * either the group's pinned creator ([GroupEntity.creatorPeerId]) or a row
     * in [GroupMemberEntity]. Used by
     * `com.hop.repository.MessageRepository.onEnvelopeReceived` to reject
     * (log-and-drop, never persist) a group text message from a sender this
     * device doesn't recognize as belonging to the group it claims to be for
     * -- see that method's own doc for the exact validation this backs.
     */
    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM groups WHERE groupId = :groupId AND creatorPeerId = :peerId
        ) OR EXISTS(
            SELECT 1 FROM group_members WHERE groupId = :groupId AND peerId = :peerId
        )
        """,
    )
    suspend fun isKnownMember(groupId: String, peerId: String): Boolean

    /**
     * Every other member of [groupId] this device should fan a message out to
     * -- the union of [GroupEntity.creatorPeerId] and every [GroupMemberEntity]
     * row, i.e. exactly the same combined membership set [isKnownMember]
     * checks against, just returned as a list instead of a single lookup. A
     * device's own peer id is never a row in either source (see
     * [GroupMemberEntity]'s class doc), so this list never needs a separate
     * "exclude myself" filter -- `com.hop.repository.MessageRepository.sendToGroup`
     * uses this directly as its fan-out target list.
     */
    @Query(
        """
        SELECT creatorPeerId AS peerId FROM groups WHERE groupId = :groupId
        UNION
        SELECT peerId FROM group_members WHERE groupId = :groupId
        """,
    )
    suspend fun getFanoutTargetPeerIds(groupId: String): List<String>
}
