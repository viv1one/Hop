package com.hop.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A received (or sent) post, persisted so it survives an app restart. Room
 * table `posts`.
 *
 * Storage split, deliberately: this row holds metadata and a file-system
 * pointer to the still-encrypted payload -- never plaintext media. Decryption
 * happens on demand via `EncryptedFrameCodec.decryptFromStore` (`protocol/`),
 * which looks up the still-live content-encryption key from `crypto/`'s
 * `DecayKeyStore` (backed by [RoomDecayKeyStorage] here) using [clipHash].
 * Writing decrypted media straight to disk would defeat the entire point of
 * ADR 0003's decay-by-key-expiry primitive: once the key is gone, ciphertext
 * on disk is exactly as opaque as ciphertext anywhere else, which is the whole
 * mechanism. Keeping ciphertext-on-disk and key-in-DecayKeyStore as two
 * separate lifecycles is what makes that true.
 */
@Entity(tableName = "posts")
data class PostEntity(
    /** Hex-encoded `Frame.clipHash` (matches `EncryptedFrameCodec`'s hex encoding). */
    @PrimaryKey
    val clipHash: String,
    /** Hex-encoded `Frame.senderDeviceId`. */
    val senderDeviceId: String,
    /** [com.hop.protocol.ContentType] enum name ("PHOTO"/"VIDEO") -- never inferred from a file extension. */
    val contentType: String,
    val originatedAtMs: Long,
    val ttlSeconds: Long,
    /** [com.hop.protocol.ReachTier] enum name. */
    val reachTier: String,
    val dontRelay: Boolean,
    /**
     * Local wall-clock time this device stored the post -- used for feed
     * ordering. Phase 1 has no real distance/proximity metric to order by yet
     * (hopCount is always 0, since Phase 1 is direct-peer-only with no relay),
     * so recency is the only meaningful ordering proxy right now. This is NOT
     * true proximity ordering -- don't read it that way once Phase 2's
     * multi-hop relay makes hopCount meaningful.
     */
    val receivedAtMs: Long,
    /**
     * Path to the on-disk file holding the raw `Frame.payload` ciphertext blob
     * -- NOT plaintext. See class doc for why this is stored separately from
     * the decay key.
     */
    val encryptedPayloadFilePath: String,
)
