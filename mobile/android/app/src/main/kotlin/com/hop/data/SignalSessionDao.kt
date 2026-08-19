package com.hop.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Backs [SessionEntity] -- see its doc. Deliberately blocking (non-`suspend`);
 * see [SignalIdentityDao]'s doc for why, and for the main-thread caveat that
 * applies identically here.
 *
 * `getDeviceIdsForName`/`deleteAllForName` back libsignal-client's
 * `SessionStore.getSubDeviceSessions`/`deleteAllSessions`, which operate on
 * just the address `name` (across every `deviceId` for that name) -- see
 * [SessionEntity]'s doc for why `name` is its own indexed column rather than
 * parsed back out of [SessionEntity.addressKey].
 */
@Dao
interface SignalSessionDao {
    @Query("SELECT * FROM signal_sessions WHERE addressKey = :addressKey")
    fun getByKey(addressKey: String): SessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertOrReplace(entity: SessionEntity)

    @Query("SELECT COUNT(*) FROM signal_sessions WHERE addressKey = :addressKey")
    fun countByKey(addressKey: String): Int

    @Query("DELETE FROM signal_sessions WHERE addressKey = :addressKey")
    fun deleteByKey(addressKey: String)

    @Query("SELECT deviceId FROM signal_sessions WHERE name = :name")
    fun getDeviceIdsForName(name: String): List<Int>

    @Query("DELETE FROM signal_sessions WHERE name = :name")
    fun deleteAllForName(name: String)
}
