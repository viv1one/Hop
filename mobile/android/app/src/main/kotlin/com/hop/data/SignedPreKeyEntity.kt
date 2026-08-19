package com.hop.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A signed prekey this device has published (its signature, made with this
 * device's long-term identity key, is what lets an initiator verify the
 * bundle came from who it claims) -- backs libsignal-client's
 * `SignedPreKeyStore` contract inside [RoomSignalProtocolStore]. Room table
 * `signal_signed_prekeys`.
 *
 * [recordBytes] is the record's own `.serialize()` output -- see
 * [IdentityKeyPairEntity]'s doc for why this pattern is used consistently
 * across every entity in this store.
 *
 * No rotation in this slice, same named MVP simplification as [PreKeyEntity]
 * -- `DoubleRatchetSession.publishPreKeyBundle` always publishes fixed id 1.
 */
@Entity(tableName = "signal_signed_prekeys")
data class SignedPreKeyEntity(
    @PrimaryKey
    val signedPreKeyId: Int,
    val recordBytes: ByteArray,
    val createdAtMs: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SignedPreKeyEntity) return false
        return signedPreKeyId == other.signedPreKeyId &&
            recordBytes.contentEquals(other.recordBytes) &&
            createdAtMs == other.createdAtMs
    }

    override fun hashCode(): Int {
        var result = signedPreKeyId
        result = 31 * result + recordBytes.contentHashCode()
        result = 31 * result + createdAtMs.hashCode()
        return result
    }
}
