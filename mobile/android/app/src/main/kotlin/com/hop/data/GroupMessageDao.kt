package com.hop.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface GroupMessageDao {

    @Insert
    suspend fun insert(message: GroupMessageEntity): Long

    @Query("SELECT * FROM group_messages WHERE groupId = :groupId ORDER BY sentAtMs ASC")
    fun getMessagesForGroup(groupId: String): Flow<List<GroupMessageEntity>>

    /**
     * The most recent [GroupMessageEntity] per distinct `groupId` -- one row
     * per group conversation, ordered newest-first. Same correlated-subquery
     * shape as [MessageDao.getLatestMessagePerPeer] (see its own doc for why
     * that shape, over a bare-column aggregate, is used), backing
     * `com.hop.repository.MessageRepository.observeGroupSummaries`'s
     * contribution to the combined Inbox list.
     */
    @Query(
        """
        SELECT * FROM group_messages AS m
        WHERE m.sentAtMs = (
            SELECT MAX(m2.sentAtMs) FROM group_messages AS m2 WHERE m2.groupId = m.groupId
        )
        ORDER BY m.sentAtMs DESC
        """,
    )
    fun getLatestMessagePerGroup(): Flow<List<GroupMessageEntity>>
}
