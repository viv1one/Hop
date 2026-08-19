package com.hop.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Backs [PreKeyEntity] -- see its doc. Deliberately blocking (non-`suspend`);
 * see [SignalIdentityDao]'s doc for why, and for the main-thread caveat that
 * applies identically here.
 */
@Dao
interface SignalPreKeyDao {
    @Query("SELECT * FROM signal_prekeys WHERE preKeyId = :preKeyId")
    fun getById(preKeyId: Int): PreKeyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertOrReplace(entity: PreKeyEntity)

    @Query("SELECT COUNT(*) FROM signal_prekeys WHERE preKeyId = :preKeyId")
    fun countById(preKeyId: Int): Int

    @Query("DELETE FROM signal_prekeys WHERE preKeyId = :preKeyId")
    fun deleteById(preKeyId: Int)
}
