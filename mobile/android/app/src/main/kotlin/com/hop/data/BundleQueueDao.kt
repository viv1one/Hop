package com.hop.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Dumb CRUD only, matching every other DAO in this codebase -- the
 * conditional-replace freshness logic ("only overwrite an existing row if
 * the incoming envelope is strictly newer") lives in
 * [com.hop.repository.BundleRepository.considerForRelay], not here. See
 * [BundleQueueEntity]'s own doc for why this table is peer-id-keyed rather
 * than content-hash-keyed like every other relay DAO's `IGNORE`-on-conflict
 * pattern.
 */
@Dao
interface BundleQueueDao {
    /**
     * Used by [com.hop.repository.BundleRepository.considerForRelay] to read
     * the currently-held row (if any) for a peer *before* deciding whether an
     * incoming envelope is fresher than it -- this read-then-conditionally-
     * write shape is why that caller wraps this DAO's calls in a
     * `@androidx.room.Transaction` method rather than relying on a `REPLACE`
     * conflict strategy alone (see that method's own doc for the race this
     * guards against).
     */
    @Query("SELECT * FROM bundle_queue WHERE peerId = :peerId")
    suspend fun getByPeerId(peerId: String): BundleQueueEntity?

    /**
     * `REPLACE`, not `IGNORE` -- unlike every other relay DAO in this
     * codebase (see [BundleQueueEntity]'s own doc for why: a bundle is
     * mutable per-peer state, not an immutable payload where first custody
     * should win). The caller ([com.hop.repository.BundleRepository
     * .considerForRelay]) is responsible for only calling this once it has
     * already confirmed the incoming envelope is fresher than any existing
     * row -- this DAO method itself does no freshness comparison, it simply
     * does what it's told.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(row: BundleQueueEntity)

    @Query("SELECT * FROM bundle_queue")
    suspend fun getAll(): List<BundleQueueEntity>

    @Query("DELETE FROM bundle_queue WHERE peerId = :peerId")
    suspend fun delete(peerId: String)
}
