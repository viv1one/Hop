package com.hop.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PendingMessageDao {
    /**
     * `IGNORE`, not `REPLACE` -- first custody wins, mirroring
     * [RelayQueueDao.insert]'s own doc exactly: a duplicate receipt of a
     * [PendingMessageEntity.ciphertextHash] this device already holds (a
     * second peer offering the same message, or the same peer reconnecting)
     * must never reset [PendingMessageEntity.hopCount] or
     * [PendingMessageEntity.receivedAtMs].
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: PendingMessageEntity)

    @Query("SELECT * FROM pending_messages")
    suspend fun getAll(): List<PendingMessageEntity>

    /**
     * Used by [com.hop.repository.PendingMessageRepository.considerForRelay]
     * to distinguish genuine new custody from a duplicate offer *before*
     * inserting -- see that method's own doc for why the caller needs to know
     * which case it was (only genuine new custody returns a non-null row).
     */
    @Query("SELECT * FROM pending_messages WHERE ciphertextHash = :hash")
    suspend fun getByHash(hash: String): PendingMessageEntity?

    @Query("DELETE FROM pending_messages WHERE ciphertextHash = :hash")
    suspend fun delete(hash: String)
}
