package com.hop.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A one-time EC prekey this device has published for a prospective
 * initiator to consume when starting a new Double Ratchet session -- backs
 * libsignal-client's `PreKeyStore` contract inside [RoomSignalProtocolStore].
 * Room table `signal_prekeys`.
 *
 * [recordBytes] is the record's own `.serialize()` output, reconstructed
 * later via `PreKeyRecord(bytes)` -- never re-derived from any other
 * representation (see [IdentityKeyPairEntity]'s doc for why this pattern is
 * used consistently across every entity in this store).
 *
 * Replenished in batches by [PreKeyRotationManager] as ids get handed out
 * (see its own doc) -- this table typically holds many rows at once (a
 * standing pool of currently-unconsumed one-time prekeys), not just one.
 * `com.hop.crypto.DoubleRatchetSession.publishPreKeyBundle`'s own fixed-id-1
 * default remains a simple single-shot convenience for tests/one-off
 * handshakes; production bundle announcement goes through
 * [PreKeyRotationManager] instead.
 */
@Entity(tableName = "signal_prekeys")
data class PreKeyEntity(
    @PrimaryKey
    val preKeyId: Int,
    val recordBytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PreKeyEntity) return false
        return preKeyId == other.preKeyId && recordBytes.contentEquals(other.recordBytes)
    }

    override fun hashCode(): Int {
        var result = preKeyId
        result = 31 * result + recordBytes.contentHashCode()
        return result
    }
}
