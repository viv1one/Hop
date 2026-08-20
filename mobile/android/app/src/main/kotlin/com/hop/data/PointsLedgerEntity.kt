package com.hop.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A single points award for successfully handing a relayed (never
 * self-authored) post to a peer -- Phase 2 Slice 2's "visible credit from
 * day one" for relay-operating devices. Room table `points_ledger`.
 *
 * **Keyed by [clipHash], deliberately not autoIncrement.** This is the fix
 * for a real double-counting bug, not a stylistic choice: `WifiDirectTransport`'s
 * backlog-resend-on-reconnect mechanism (`registerConnectionAndGetBacklog` ->
 * `sendBacklog`) resends the *entire* eligible relay backlog on every
 * reconnect -- routine on real WiFi Direct hardware, not an edge case. An
 * append-only ledger with no dedup key would credit the same relay hand-off
 * repeatedly, once per reconnect. Keying this table by `clipHash` plus
 * [PointsLedgerDao.insert]'s `OnConflictStrategy.IGNORE` makes "award once
 * per clipHash, ever" a DB constraint, not an application-level check --
 * see that DAO method's own doc for why no call site should ever add a
 * second "have I already awarded this" check on top.
 */
@Entity(tableName = "points_ledger")
data class PointsLedgerEntity(
    /** Hex-encoded `Frame.clipHash` -- same encoding [PostEntity.clipHash] uses. */
    @PrimaryKey val clipHash: String,
    val awardedAtMs: Long,
    /** Always `1` for now -- no reason-code enum yet, only one award type (successful relay hand-off) exists. */
    val amount: Int,
)
