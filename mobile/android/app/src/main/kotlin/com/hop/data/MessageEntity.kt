package com.hop.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A decrypted 1:1 message, persisted for local history display. Room table
 * `messages`.
 *
 * Phase 1 messaging is text-only. Storing plaintext here (rather than
 * re-decrypting on every read) is deliberate: Double Ratchet message keys are
 * single-use and get erased from the session store immediately after
 * decryption (see `DoubleRatchetSession`'s forward-secrecy note in `crypto/`)
 * -- there is no ciphertext to re-decrypt later even in principle. This row is
 * plaintext-at-rest by necessity of how the ratchet works, not a shortcut.
 *
 * Out of scope for this slice (see task spec): a persistent `SignalProtocolStore`
 * (ratchet/session state). Without it, a 1:1 conversation needs a fresh
 * handshake after an app restart even though its message history (this table)
 * survives -- the history and the live session are two different persistence
 * problems, and only the former is solved here.
 */
@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /**
     * Conversation partner identifier. Uses `SignalProtocolAddress.name`'s
     * string form for consistency with `DoubleRatchetSession`'s own addressing
     * scheme, rather than inventing a separate identifier here.
     */
    val peerId: String,
    val plaintext: String,
    val sentAtMs: Long,
    val isOutgoing: Boolean,
)
