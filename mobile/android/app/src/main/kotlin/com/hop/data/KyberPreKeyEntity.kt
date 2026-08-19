package com.hop.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A one-time post-quantum (PQXDH/Kyber) prekey this device has published --
 * backs libsignal-client's `KyberPreKeyStore` contract inside
 * [RoomSignalProtocolStore]. Room table `signal_kyber_prekeys`.
 *
 * [recordBytes] is the record's own `.serialize()` output -- see
 * [IdentityKeyPairEntity]'s doc for why this pattern is used consistently
 * across every entity in this store.
 *
 * [used] is a replay-protection flag, not a lifecycle/deletion flag:
 * libsignal-client's `KyberPreKeyStore` contract *marks* a one-time Kyber
 * prekey used on consumption (`markKyberPreKeyUsed`) rather than deleting
 * it, since the id still needs to resolve for e.g. re-processing an
 * already-in-flight `PreKeySignalMessage` -- verified against the actual
 * compiled 0.86.5 interface (`javap`), not assumed from memory of another
 * libsignal-client version. This entity's schema tracks only this boolean,
 * not a per-base-key reuse table the way libsignal-client's own
 * `InMemoryKyberPreKeyStore` does internally -- see
 * [RoomSignalProtocolStore.markKyberPreKeyUsed]'s doc for the consequence of
 * that scope cut.
 *
 * Rotated periodically by [PreKeyRotationManager] on the same interval/
 * grace-period policy as the EC signed prekey (see its own doc, and
 * [com.hop.crypto.DoubleRatchetSession.generateKyberPreKey]'s doc for why
 * this app treats the Kyber prekey as "signed-prekey-shaped" rather than a
 * one-time batch pool). Rotation-driven pruning of a long-superseded row
 * uses [RoomSignalProtocolStore.pruneKyberPreKey] -- a distinct lifecycle
 * event from [used] above, which never triggers deletion.
 * `com.hop.crypto.DoubleRatchetSession.publishPreKeyBundle`'s own fixed-id-1
 * default remains a simple single-shot convenience for tests/one-off
 * handshakes; production bundle announcement goes through
 * [PreKeyRotationManager] instead.
 */
@Entity(tableName = "signal_kyber_prekeys")
data class KyberPreKeyEntity(
    @PrimaryKey
    val kyberPreKeyId: Int,
    val recordBytes: ByteArray,
    val used: Boolean = false,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is KyberPreKeyEntity) return false
        return kyberPreKeyId == other.kyberPreKeyId &&
            recordBytes.contentEquals(other.recordBytes) &&
            used == other.used
    }

    override fun hashCode(): Int {
        var result = kyberPreKeyId
        result = 31 * result + recordBytes.contentHashCode()
        result = 31 * result + used.hashCode()
        return result
    }
}
