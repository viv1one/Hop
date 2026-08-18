package com.hop.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Backs [BlockedSenderDeviceEntity] -- see its doc for this table's scope/limits. */
@Dao
interface BlockedSenderDeviceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: BlockedSenderDeviceEntity)

    @Query("SELECT senderDeviceId FROM blocked_sender_devices")
    fun observeAll(): Flow<List<String>>
}
