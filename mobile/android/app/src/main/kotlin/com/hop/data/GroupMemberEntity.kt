package com.hop.data

import androidx.room.Entity

/**
 * One non-creator member of a group ([GroupEntity]). Room table
 * `group_members`, composite primary key `(groupId, peerId)`.
 *
 * A device's own peer id is **never** stored here for its own groups -- every
 * row represents someone *else* to fan a message out to
 * (`com.hop.repository.MessageRepository.sendToGroup` iterates
 * `GroupDao.getFanoutTargetPeerIds` directly as its send target list, with no
 * separate "exclude myself" filter needed as a result). The group's creator is
 * likewise never a row in this table -- it's tracked once, on
 * [GroupEntity.creatorPeerId] -- so any membership check ([GroupDao.isKnownMember])
 * or fan-out target list ([GroupDao.getFanoutTargetPeerIds]) must combine this
 * table with that column, never query this table alone.
 */
@Entity(tableName = "group_members", primaryKeys = ["groupId", "peerId"])
data class GroupMemberEntity(val groupId: String, val peerId: String)
