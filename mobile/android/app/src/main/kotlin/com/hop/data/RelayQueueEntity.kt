package com.hop.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A post frame this device has taken custody of for further store-and-
 * forward relay (Phase 2 Slice 1), persisted so it survives an app restart
 * -- the exact "surviving process death in between" requirement this slice
 * ships. Room table `relay_queue`.
 *
 * Distinct from [PostEntity]: this table exists purely to drive *outgoing*
 * relay (what to re-offer to the next peer this device connects to), while
 * [PostEntity] drives the local feed. A device relaying a post it will never
 * itself display (e.g. Town-tier content this device's own reach radius
 * doesn't include -- not yet enforced in this slice, see the reach-tier
 * key-wrapping note in `RelayRepository`'s doc) still needs a row here even
 * though it may have no corresponding [PostEntity]. The two tables are
 * written independently and neither implies the other.
 */
@Entity(tableName = "relay_queue")
data class RelayQueueEntity(
    /** Hex-encoded `Frame.clipHash` -- same encoding [PostEntity.clipHash] uses. */
    @PrimaryKey
    val clipHash: String,
    /**
     * The full `Frame.encode()` output as received/produced, with the hop
     * count at which *this device* took custody already baked in (matching
     * [hopCount] below) -- the retransmission source of truth. [hopCount]/
     * [originatedAtMs]/[ttlSeconds]/[dontRelay] are denormalized out of this
     * blob purely so [RelayQueueDao]/`RelayPolicy` can evaluate relay
     * eligibility at query time without decoding every row; if they ever
     * disagree with what's actually encoded here, this field wins for
     * anything that gets sent on the wire.
     */
    val encodedFrame: ByteArray,
    /**
     * The hop count at which this device took custody of this frame --
     * never mutated after insert (see [RelayQueueDao.insert]'s
     * `OnConflictStrategy.IGNORE` doc for why: first custody wins). Relaying
     * onward re-encodes a *new* outgoing copy at `hopCount + 1`; it never
     * rewrites this row.
     */
    val hopCount: Int,
    val originatedAtMs: Long,
    val ttlSeconds: Long,
    val dontRelay: Boolean,
    /** Local wall-clock time this device took custody -- diagnostic only, not read by any relay-eligibility decision. */
    val receivedAtMs: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RelayQueueEntity) return false
        return clipHash == other.clipHash &&
            encodedFrame.contentEquals(other.encodedFrame) &&
            hopCount == other.hopCount &&
            originatedAtMs == other.originatedAtMs &&
            ttlSeconds == other.ttlSeconds &&
            dontRelay == other.dontRelay &&
            receivedAtMs == other.receivedAtMs
    }

    override fun hashCode(): Int {
        var result = clipHash.hashCode()
        result = 31 * result + encodedFrame.contentHashCode()
        result = 31 * result + hopCount
        result = 31 * result + originatedAtMs.hashCode()
        result = 31 * result + ttlSeconds.hashCode()
        result = 31 * result + dontRelay.hashCode()
        result = 31 * result + receivedAtMs.hashCode()
        return result
    }
}
