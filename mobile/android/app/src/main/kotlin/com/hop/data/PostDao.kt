package com.hop.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PostDao {
    /**
     * Upsert -- a post can legitimately be re-stored (e.g. re-received via a
     * different peer, or a re-share extending its decay window) without this
     * failing on the primary-key conflict.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(post: PostEntity)

    /**
     * Feed ordering, most-recently-received first. See [PostEntity.receivedAtMs]'s
     * doc for why this is a recency proxy, not true proximity ordering, in
     * Phase 1.
     */
    @Query("SELECT * FROM posts ORDER BY receivedAtMs DESC")
    fun getAllOrderedByReceivedDesc(): Flow<List<PostEntity>>

    @Query("SELECT * FROM posts WHERE clipHash = :clipHash")
    suspend fun getByClipHash(clipHash: String): PostEntity?
}
