package com.hop.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A post reported by this device, persisted so the report survives an app
 * restart. Room table `reported_posts`.
 *
 * This hides the reported post locally, on this device only. It is NOT the
 * real distributed "don't relay" flag-counting mechanism described in PRD
 * §4.6 and ADR 0004 -- that's a Phase 2 relay-protocol mechanism gated by a
 * threshold of distinct *attested* devices reporting the same content, and
 * there is no relay to suppress propagation to yet (Phase 1 is direct-peer
 * only, per BUILD_PLAN.md). Don't let any UI copy surfacing "Report" imply
 * this suppresses the post for anyone but the reporting device.
 */
@Entity(tableName = "reported_posts")
data class ReportedPostEntity(
    /** Hex-encoded `Frame.clipHash`, matching [PostEntity.clipHash]. */
    @PrimaryKey
    val clipHash: String,
    val reportedAtMs: Long,
)
