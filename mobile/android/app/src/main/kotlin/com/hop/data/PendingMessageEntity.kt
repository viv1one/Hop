package com.hop.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A [com.hop.protocol.MessageCiphertextEnvelope] this device has taken relay
 * custody of on behalf of someone else's conversation (Phase 2 Slice 3:
 * store-and-forward for offline 1:1 recipients), persisted so it survives an
 * app restart -- the same "surviving process death in between" requirement
 * [RelayQueueEntity] ships for posts. Room table `pending_messages`.
 *
 * Deliberately a **separate table** from [RelayQueueEntity], not a reuse of
 * it: posts have no single recipient (they flood to everyone within a reach
 * tier) and no `dontRelay`/community-flagging concept applies to a private
 * message, while a pending message has exactly one intended [recipientPeerId]
 * and no `dontRelay` concept at all. Collapsing the two shapes into one table
 * would force one of them to carry a field that means nothing for it.
 *
 * A device holding a row here has **no Signal Protocol session for this
 * conversation on-device at all** -- see
 * [com.hop.repository.PendingMessageRepository]'s own doc for why that
 * matters (this table's existence never implies decrypt capability, even in
 * principle).
 */
@Entity(tableName = "pending_messages")
data class PendingMessageEntity(
    /**
     * Hex SHA-256 of [com.hop.protocol.MessageCiphertextEnvelope.ciphertext]
     * only -- **not** the whole envelope (`senderPeerId`/`recipientPeerId`
     * don't change across relay hops, but this key must stay stable across
     * `hopCount` bumps the same way [RelayQueueEntity.clipHash] does for
     * posts, so hashing the whole envelope would break custody dedup the
     * moment a second carrier bumps `hopCount` before re-offering it).
     */
    @PrimaryKey
    val ciphertextHash: String,
    val recipientPeerId: String,
    /**
     * The full [com.hop.protocol.MessageCiphertextEnvelope.encode] output as
     * received, with the hop count at which *this device* took custody
     * already baked in (matching [hopCount] below) -- the retransmission
     * source of truth, mirroring [RelayQueueEntity.encodedFrame]'s own
     * doc/posture exactly.
     */
    val encodedEnvelope: ByteArray,
    /**
     * The hop count at which this device took custody of this message --
     * never mutated after insert, mirroring [RelayQueueEntity.hopCount]'s own
     * "first custody wins" doc. Relaying onward re-encodes a *new* outgoing
     * copy at `hopCount + 1`; it never rewrites this row.
     */
    val hopCount: Int,
    val originatedAtMs: Long,
    /** Local wall-clock time this device took custody -- diagnostic only, not read by any relay-eligibility decision. */
    val receivedAtMs: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PendingMessageEntity) return false
        return ciphertextHash == other.ciphertextHash &&
            recipientPeerId == other.recipientPeerId &&
            encodedEnvelope.contentEquals(other.encodedEnvelope) &&
            hopCount == other.hopCount &&
            originatedAtMs == other.originatedAtMs &&
            receivedAtMs == other.receivedAtMs
    }

    override fun hashCode(): Int {
        var result = ciphertextHash.hashCode()
        result = 31 * result + recipientPeerId.hashCode()
        result = 31 * result + encodedEnvelope.contentHashCode()
        result = 31 * result + hopCount
        result = 31 * result + originatedAtMs.hashCode()
        result = 31 * result + receivedAtMs.hashCode()
        return result
    }
}
