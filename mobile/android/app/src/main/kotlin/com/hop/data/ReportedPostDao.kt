package com.hop.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Backs [ReportedPostEntity] -- see its doc for this table's scope/limits. */
@Dao
interface ReportedPostDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ReportedPostEntity)

    @Query("SELECT clipHash FROM reported_posts")
    fun observeAll(): Flow<List<String>>
}
