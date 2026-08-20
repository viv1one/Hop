package com.hop.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A locally known group (Phase 2 Slice 4: per-member pairwise Double Ratchet
 * fan-out, PRD §4.3, ADR 0001). Room table `groups`.
 *
 * Deliberately a **separate** table from [MessageEntity]/[MessageDao], not a
 * repurposing of them: v1 requires every group member to already be a 1:1
 * contact, so a group message keyed by the sender's `peerId` (as
 * [MessageEntity] already is) would collide with that same person's existing
 * 1:1 conversation and leak into the wrong thread. Every other new concept
 * added in Slices 1-3 ([RelayQueueEntity], [DontRelayFlagEntity],
 * [PendingMessageEntity]) got its own table rather than repurposing an
 * existing one -- this follows that precedent.
 *
 * [creatorPeerId] is the **trust anchor** for this group's membership, pinned
 * from the sender of the *first* `GroupInvite` this device ever saw for
 * [groupId] (trust-on-first-use, the same local posture
 * [RoomSignalProtocolStore.isTrustedIdentity] already uses for a 1:1 peer's
 * identity key). A [groupId] is not secret -- it's plaintext, visible to every
 * member and, if a member forwards or leaks it, to anyone else -- so without
 * this pin, any current member could unilaterally redefine another member's
 * view of who's in the group, or an unrelated party who merely learned this
 * [groupId] could mint a colliding "group" under the same id. See
 * `com.hop.repository.MessageRepository.onEnvelopeReceived`'s `GroupInvite`
 * handling for the exact check this backs: once [creatorPeerId] is pinned, any
 * *later* invite for this same [groupId] is only honored if it comes from the
 * same sender.
 *
 * Membership itself lives in the separate [GroupMemberEntity] table
 * (everyone *besides* the creator) -- [creatorPeerId] is intentionally not
 * duplicated as a [GroupMemberEntity] row; `GroupDao.isKnownMember`/
 * `GroupDao.getFanoutTargetPeerIds` both treat this column and the
 * [GroupMemberEntity] table as one combined membership set.
 */
@Entity(tableName = "groups")
data class GroupEntity(
    /** Locally generated (random hex) by whichever device created the group -- not wire-negotiated, and not secret (see class doc). */
    @PrimaryKey val groupId: String,
    val name: String,
    val creatorPeerId: String,
    val createdAtMs: Long,
)
