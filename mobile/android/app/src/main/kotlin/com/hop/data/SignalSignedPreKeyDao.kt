package com.hop.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Backs [SignedPreKeyEntity] -- see its doc. Deliberately blocking
 * (non-`suspend`); see [SignalIdentityDao]'s doc for why, and for the
 * main-thread caveat that applies identically here.
 */
@Dao
interface SignalSignedPreKeyDao {
    @Query("SELECT * FROM signal_signed_prekeys WHERE signedPreKeyId = :signedPreKeyId")
    fun getById(signedPreKeyId: Int): SignedPreKeyEntity?

    @Query("SELECT * FROM signal_signed_prekeys")
    fun getAll(): List<SignedPreKeyEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertOrReplace(entity: SignedPreKeyEntity)

    @Query("SELECT COUNT(*) FROM signal_signed_prekeys WHERE signedPreKeyId = :signedPreKeyId")
    fun countById(signedPreKeyId: Int): Int

    @Query("DELETE FROM signal_signed_prekeys WHERE signedPreKeyId = :signedPreKeyId")
    fun deleteById(signedPreKeyId: Int)
}
