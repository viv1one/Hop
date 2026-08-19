package com.hop.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Backs [SignalPreKeyCounterEntity] -- see its doc. Deliberately blocking
 * (non-`suspend`), matching every other DAO backing
 * [RoomSignalProtocolStore]/[PreKeyRotationManager]; callers must invoke off
 * the main thread.
 */
@Dao
interface SignalPreKeyCounterDao {
    // Room @Query requires a literal string (no arbitrary Kotlin expression/
    // constant interpolation), so this hardcodes 0 rather than referencing
    // [SignalPreKeyCounterEntity.ID] directly -- see that companion constant's
    // doc; keep this literal in sync with it if it ever changes.
    @Query("SELECT * FROM signal_prekey_counters WHERE id = 0")
    fun get(): SignalPreKeyCounterEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entity: SignalPreKeyCounterEntity)
}
