package com.hop.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface RelayQueueDao {
    /**
     * `IGNORE`, not `REPLACE` (unlike [PostDao.upsert]'s deliberate `REPLACE`
     * for re-share decay extension) -- first custody wins. A duplicate
     * receipt of a clipHash this device already holds (a second peer
     * offering the same post, or the same peer reconnecting) must never
     * reset [RelayQueueEntity.hopCount] or [RelayQueueEntity.receivedAtMs];
     * the receiver's existing dedup ([ReceivedFrameStore.handle] no-ops on
     * an already-known clipHash before this is ever reached) means this
     * conflict path is mostly a defensive backstop, not a case expected to
     * fire often in practice.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: RelayQueueEntity)

    @Query("SELECT * FROM relay_queue")
    suspend fun getAll(): List<RelayQueueEntity>

    @Query("DELETE FROM relay_queue WHERE clipHash = :clipHash")
    suspend fun delete(clipHash: String)

    /**
     * Phase 2 Slice 2: flips a row's [RelayQueueEntity.dontRelay] to `true`
     * once this device has independently observed enough distinct-attested-
     * device "don't relay" flags for [clipHash] (see
     * [com.hop.repository.DontRelayRepository.recordFlag]). No-ops silently
     * if no row exists yet for [clipHash] -- expected, not a bug: flags can
     * arrive before the post itself does (mesh delivery order isn't
     * guaranteed), in which case [com.hop.repository.RelayRepository.considerForRelay]
     * is what actually applies the already-crossed threshold once the post
     * arrives, by consulting `DontRelayRepository.isSuppressed` at insert
     * time -- see that method's own doc for the order-independence fix this
     * is one half of.
     */
    @Query("UPDATE relay_queue SET dontRelay = 1 WHERE clipHash = :clipHash")
    suspend fun markDontRelay(clipHash: String)
}
