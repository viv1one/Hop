package com.hop.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A [com.hop.protocol.PreKeyBundleEnvelope] this device is holding for
 * prekey-bundle mesh flood relay -- the prekey-bundle relay/discovery
 * follow-up to Phase 2's four original slices (post relay, "don't relay"
 * flags, 1:1 store-and-forward, group messaging). Room table `bundle_queue`.
 *
 * **Deliberately peer-id-keyed with conditional replace, not content-hash-
 * keyed with `OnConflictStrategy.IGNORE`** -- the pattern every other relay
 * table in this codebase ([RelayQueueEntity], [DontRelayFlagEntity],
 * [PendingMessageEntity]) uses. Those three all hold **immutable** payloads
 * (a post, a flag, a message ciphertext), where "first custody wins" is
 * unambiguous: two independently-arriving copies of the same immutable
 * content are, by definition, the same content, so the first one received is
 * as good as any later one. A prekey bundle is different: it's **mutable
 * per-peer state** -- the same [peerId] announces a *new* bundle every time
 * its device rotates prekeys (see `com.hop.data.PreKeyRotationManager`), and
 * a fresher bundle for a peer should supersede a stale queued one, not lose
 * to it (first-custody-wins would pin this device's relay copy to whichever
 * bundle happened to arrive first, potentially far staler than one arriving
 * later) *or* be blindly clobbered by an out-of-order older copy arriving
 * over a slower relay path (naive last-write-wins would do that). See
 * `com.hop.repository.BundleRepository.considerForRelay`'s own doc for the
 * `@Transaction`-guarded read-compare-write this table's shape exists to
 * support: only replace if the incoming envelope's [originatedAtMs] is
 * strictly newer than what's already stored here.
 *
 * This table's DAO ([BundleQueueDao]) stays dumb CRUD, matching every other
 * DAO in this codebase -- the conditional-replace logic lives in
 * [com.hop.repository.BundleRepository], not here.
 */
@Entity(tableName = "bundle_queue")
data class BundleQueueEntity(
    /**
     * The bundle owner's peer id -- **not** a content hash, unlike every
     * other relay table's primary key (see this entity's own class doc for
     * why: a bundle is mutable per-peer state, so "one row per peer" is the
     * correct shape, not "one row per distinct payload ever seen").
     */
    @PrimaryKey
    val peerId: String,
    /**
     * The full [com.hop.protocol.PreKeyBundleEnvelope.encode] output as
     * received, with the hop count at which *this device* took custody
     * already baked in (matching [hopCount] below) -- the retransmission
     * source of truth, mirroring [PendingMessageEntity.encodedEnvelope]'s own
     * doc/posture exactly.
     */
    val encodedEnvelope: ByteArray,
    /**
     * The hop count at which this device took custody of this bundle --
     * never mutated after insert/replace. Relaying onward re-encodes a *new*
     * outgoing copy at `hopCount + 1`; it never rewrites this row's own
     * [hopCount].
     */
    val hopCount: Int,
    /**
     * The epoch millis this bundle was originally announced by its owner --
     * the field [com.hop.repository.BundleRepository.considerForRelay]'s
     * conditional-replace compares against to decide whether an incoming
     * envelope is fresher than what's already held here. Never denormalized
     * from anywhere except the envelope itself.
     */
    val originatedAtMs: Long,
    /** Local wall-clock time this device took custody -- diagnostic only, not read by any relay-eligibility or freshness decision. */
    val receivedAtMs: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BundleQueueEntity) return false
        return peerId == other.peerId &&
            encodedEnvelope.contentEquals(other.encodedEnvelope) &&
            hopCount == other.hopCount &&
            originatedAtMs == other.originatedAtMs &&
            receivedAtMs == other.receivedAtMs
    }

    override fun hashCode(): Int {
        var result = peerId.hashCode()
        result = 31 * result + encodedEnvelope.contentHashCode()
        result = 31 * result + hopCount
        result = 31 * result + originatedAtMs.hashCode()
        result = 31 * result + receivedAtMs.hashCode()
        return result
    }
}
