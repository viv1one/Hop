package com.hop.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Insert
    suspend fun insert(message: MessageEntity): Long

    @Query("SELECT * FROM messages WHERE peerId = :peerId ORDER BY sentAtMs ASC")
    fun getMessagesForPeer(peerId: String): Flow<List<MessageEntity>>

    /**
     * The most recent [MessageEntity] per distinct `peerId` -- i.e. one row
     * per conversation, ordered newest-first. Backs
     * `com.hop.repository.MessageRepository.observeConversationSummaries`'s
     * conversation-list view.
     *
     * Uses a correlated subquery (rather than SQLite's bare-column MIN/MAX
     * aggregate idiom) so the result maps directly onto [MessageEntity] with
     * no extra/POJO-mapping ambiguity -- every column here already exists on
     * [MessageEntity], so no new Room-mapped shape is needed just for this
     * query. Two messages to/from the same peer landing on the exact same
     * `sentAtMs` (unlikely, not impossible) could both match; acceptable for
     * a local conversation-list preview, not something this query guards
     * against.
     */
    @Query(
        """
        SELECT * FROM messages AS m
        WHERE m.sentAtMs = (
            SELECT MAX(m2.sentAtMs) FROM messages AS m2 WHERE m2.peerId = m.peerId
        )
        ORDER BY m.sentAtMs DESC
        """,
    )
    fun getLatestMessagePerPeer(): Flow<List<MessageEntity>>
}
