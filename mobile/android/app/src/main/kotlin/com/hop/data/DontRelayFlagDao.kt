package com.hop.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface DontRelayFlagDao {
    /**
     * `IGNORE` on a duplicate `(clipHash, attestedDeviceKey)` pair -- Room
     * returns `-1` in that case. That `-1` IS the "isNewFlag" signal
     * [com.hop.repository.DontRelayRepository.recordFlag] needs (no separate
     * exists-check), same pattern [RelayQueueDao.insert]'s conflict strategy
     * already establishes for post custody.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: DontRelayFlagEntity): Long

    @Query("SELECT COUNT(DISTINCT attestedDeviceKey) FROM dont_relay_flags WHERE clipHash = :clipHash")
    suspend fun distinctFlaggerCount(clipHash: String): Int

    @Query("SELECT * FROM dont_relay_flags")
    suspend fun getAll(): List<DontRelayFlagEntity>

    @Query("DELETE FROM dont_relay_flags WHERE clipHash = :clipHash")
    suspend fun deleteAllForClip(clipHash: String)
}
