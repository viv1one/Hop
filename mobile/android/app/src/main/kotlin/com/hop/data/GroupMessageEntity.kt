package com.hop.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A decrypted group message, persisted for local history display. Room table
 * `group_messages` -- deliberately separate from [MessageEntity]/`messages`,
 * see [GroupEntity]'s class doc for why (a group member's `peerId`/`authorPeerId`
 * would otherwise collide with that same person's existing 1:1 conversation
 * key).
 *
 * Like [MessageEntity], this is plaintext-at-rest by necessity of how Double
 * Ratchet works (single-use message keys are erased from the session store
 * immediately after decryption -- see [MessageEntity]'s own doc), not a
 * shortcut.
 */
@Entity(tableName = "group_messages")
data class GroupMessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val groupId: String,
    /** The sender's peer id for an incoming message, or this device's own peer id for an outgoing one. */
    val authorPeerId: String,
    val plaintext: String,
    val sentAtMs: Long,
    val isOutgoing: Boolean,
)
