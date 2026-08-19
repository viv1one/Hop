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
 * Rotated periodically by [PreKeyRotationManager] (see its own doc for the
 * interval/grace-period policy) -- this table can hold more than one row at
 * once (the current signed prekey plus, briefly, a just-superseded one still
 * within its grace period), not always exactly one.
 * `com.hop.crypto.DoubleRatchetSession.publishPreKeyBundle`'s own fixed-id-1
 * default remains a simple single-shot convenience for tests/one-off
 * handshakes; production bundle announcement goes through
 * [PreKeyRotationManager] instead.
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
