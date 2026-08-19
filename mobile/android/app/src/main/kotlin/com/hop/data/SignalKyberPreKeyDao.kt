package com.hop.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Backs [KyberPreKeyEntity] -- see its doc. Deliberately blocking
 * (non-`suspend`); see [SignalIdentityDao]'s doc for why, and for the
 * main-thread caveat that applies identically here.
 */
@Dao
interface SignalKyberPreKeyDao {
    @Query("SELECT * FROM signal_kyber_prekeys WHERE kyberPreKeyId = :kyberPreKeyId")
    fun getById(kyberPreKeyId: Int): KyberPreKeyEntity?

    @Query("SELECT * FROM signal_kyber_prekeys")
    fun getAll(): List<KyberPreKeyEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertOrReplace(entity: KyberPreKeyEntity)

    @Query("SELECT COUNT(*) FROM signal_kyber_prekeys WHERE kyberPreKeyId = :kyberPreKeyId")
    fun countById(kyberPreKeyId: Int): Int

    /** See [KyberPreKeyEntity.used]'s doc -- marks, never deletes. */
    @Query("UPDATE signal_kyber_prekeys SET used = 1 WHERE kyberPreKeyId = :kyberPreKeyId")
    fun markUsed(kyberPreKeyId: Int)
}
