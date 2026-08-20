package com.hop.repository

import com.hop.data.PointsLedgerDao
import com.hop.data.PointsLedgerEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Plain-logic JVM tests for [PointsRepository] against a hand-rolled fake
 * [PointsLedgerDao] that mimics `OnConflictStrategy.IGNORE` on the real
 * (clipHash-primary-keyed) `points_ledger` table -- matching
 * [RelayRepositoryTest]'s own testability seam.
 */
class PointsRepositoryTest {

    // --- Case 6: single award per clipHash, even across N repeated calls ---

    @Test
    fun `awarding the same clipHash N times results in exactly one ledger row and one point`() {
        val dao = FakePointsLedgerDao()
        val repository = PointsRepository(dao)

        runBlocking {
            // Simulates N reconnects/backlog resends all crediting the same
            // relay hand-off -- WifiDirectTransport.sendBacklog's real-world
            // trigger for this, per PointsLedgerEntity's own doc.
            repeat(5) { repository.award("clip-a") }
        }

        assertEquals(1, dao.rows.size)
        assertEquals(1L, runBlocking { repository.observeTotalPoints().first() })
    }

    @Test
    fun `awarding distinct clipHashes accumulates the total`() {
        val dao = FakePointsLedgerDao()
        val repository = PointsRepository(dao)

        runBlocking {
            repository.award("clip-a")
            repository.award("clip-b")
            repository.award("clip-a") // duplicate -- must not double count
            repository.award("clip-c")
        }

        assertEquals(3, dao.rows.size)
        assertEquals(3L, runBlocking { repository.observeTotalPoints().first() })
    }

    /** Minimal fake [PointsLedgerDao] mimicking the real table's clipHash-primary-key insert-IGNORE dedup. */
    private class FakePointsLedgerDao : PointsLedgerDao {
        val rows = mutableMapOf<String, PointsLedgerEntity>()
        private val totalFlow = MutableStateFlow(0L)

        override suspend fun insert(row: PointsLedgerEntity) {
            if (rows.containsKey(row.clipHash)) return // IGNORE: dedup by clipHash primary key
            rows[row.clipHash] = row
            totalFlow.value = rows.values.sumOf { it.amount }.toLong()
        }

        override fun observeTotal() = totalFlow
    }
}
