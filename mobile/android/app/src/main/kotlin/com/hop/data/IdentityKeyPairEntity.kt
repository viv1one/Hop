package com.hop.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * This device's own long-term Double Ratchet identity key pair (ADR 0001;
 * PRD §4.3-4.4) -- backs the `getIdentityKeyPair()`/`getLocalRegistrationId()`
 * half of libsignal-client's `IdentityKeyStore` contract inside
 * [RoomSignalProtocolStore]. Room table `signal_identity_key_pair`, a fixed
 * singleton row (`id` is always [SINGLETON_ID] -- there is exactly one of
 * these per device, never one per conversation).
 *
 * Created lazily on first real use by [RoomSignalProtocolStore] (not seeded
 * eagerly at DB-creation time, matching this table's own doc precedent set
 * by [HopDatabase]) -- most app sessions never open the Inbox at all, so
 * there's no reason to generate/persist identity key material before it's
 * actually needed.
 *
 * [identityKeyPairBytes] is the key pair's own `.serialize()` output,
 * reconstructed later via `IdentityKeyPair(bytes)` -- never re-derived from
 * any other representation, matching `DoubleRatchetSessionTest`'s own
 * pattern of round-tripping libsignal-client records through their native
 * serialize/deserialize rather than a hand-rolled encoding.
 *
 * Stored as raw bytes, not Android-Keystore-wrapped -- a named, deliberate
 * MVP simplification carried over from [DecayKeyEntity]'s precedent, flagged
 * separately here because identity-key compromise has a materially worse
 * blast radius than a decay-key compromise (an attacker with this key pair
 * can forge this device's identity in every *future* session, not just
 * decrypt one already-expired post). Needs a Keystore-wrapping pass before
 * any real pilot -- not done in this slice.
 */
@Entity(tableName = "signal_identity_key_pair")
data class IdentityKeyPairEntity(
    @PrimaryKey
    val id: Int = SINGLETON_ID,
    val identityKeyPairBytes: ByteArray,
    val registrationId: Int,
    val createdAtMs: Long,
) {
    companion object {
        const val SINGLETON_ID = 0
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IdentityKeyPairEntity) return false
        return id == other.id &&
            identityKeyPairBytes.contentEquals(other.identityKeyPairBytes) &&
            registrationId == other.registrationId &&
            createdAtMs == other.createdAtMs
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + identityKeyPairBytes.contentHashCode()
        result = 31 * result + registrationId
        result = 31 * result + createdAtMs.hashCode()
        return result
    }
}
