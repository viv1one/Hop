package com.hop.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PointsLedgerDao {
    /**
     * `IGNORE` is the entire dedup mechanism here -- awarding the same
     * `clipHash` twice is a silent no-op, by construction, not by a
     * separate exists-check. See [PointsLedgerEntity]'s own doc for the
     * double-counting bug (backlog resend on every WiFi Direct reconnect)
     * this specifically closes. Never add a second "have I already awarded
     * this" check at a call site -- let this constraint do that.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: PointsLedgerEntity)

    @Query("SELECT COALESCE(SUM(amount), 0) FROM points_ledger")
    fun observeTotal(): Flow<Long>
}
