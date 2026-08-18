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
}
