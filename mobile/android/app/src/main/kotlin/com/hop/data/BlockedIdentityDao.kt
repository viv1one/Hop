package com.hop.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface BlockedIdentityDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(blocked: BlockedIdentityEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM blocked_identities WHERE attestedDeviceKey = :attestedDeviceKey)")
    suspend fun isBlocked(attestedDeviceKey: String): Boolean
}
