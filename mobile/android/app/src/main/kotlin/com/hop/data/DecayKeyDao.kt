package com.hop.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Backs [RoomDecayKeyStorage] -- see [DecayKeyEntity]'s doc. This DAO itself
 * has no decay/expiry opinion; it is exactly the "dumb key-value+expiry store"
 * `crypto/`'s `DecayKeyStorage` interface calls for.
 *
 * Deliberately blocking (non-`suspend`), unlike the other DAOs in this
 * package: `crypto/`'s `DecayKeyStorage` interface is synchronous by design
 * (it's called from `DecayKeyStore`'s own `@Synchronized` `store`/`retrieve`,
 * which are not suspend functions either, since `crypto/` has no dependency
 * on Android/coroutines-for-persistence concerns). [RoomDecayKeyStorage]
 * bridges that synchronous contract directly onto Room's blocking-query
 * support. Callers must invoke it off the main thread, same as any other
 * blocking Room usage.
 */
@Dao
interface DecayKeyDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertOrReplace(entity: DecayKeyEntity)

    @Query("SELECT * FROM decay_keys WHERE contentId = :contentId")
    fun getById(contentId: String): DecayKeyEntity?

    @Query("DELETE FROM decay_keys WHERE contentId = :contentId")
    fun deleteById(contentId: String)
}
