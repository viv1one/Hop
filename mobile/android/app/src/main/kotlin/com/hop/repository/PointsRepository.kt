package com.hop.repository

import com.hop.data.PointsLedgerDao
import com.hop.data.PointsLedgerEntity
import kotlinx.coroutines.flow.Flow

/**
 * Wraps [PointsLedgerDao] -- Phase 2 Slice 2's non-tradeable local points
 * counter, giving relay-operating devices "visible credit from day one".
 * `open` for the same reason as [ReportRepository]/[BlockRepository]: lets a
 * JVM unit test subclass with an in-memory fake instead of needing a mocking
 * library.
 *
 * [award] relies entirely on [PointsLedgerDao.insert]'s `OnConflictStrategy.IGNORE`
 * for dedup -- see [PointsLedgerEntity]'s own doc for the backlog-resend
 * double-counting bug this specifically closes. Never gate a call site on
 * "have I awarded this before"; let the DB constraint do that.
 */
open class PointsRepository(private val dao: PointsLedgerDao) {

    open suspend fun award(clipHash: String) {
        dao.insert(
            PointsLedgerEntity(
                clipHash = clipHash,
                awardedAtMs = System.currentTimeMillis(),
                amount = 1,
            ),
        )
    }

    open fun observeTotalPoints(): Flow<Long> = dao.observeTotal()
}
