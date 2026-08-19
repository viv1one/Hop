package com.hop.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A trusted remote peer's long-term Double Ratchet identity public key --
 * backs the trust-on-first-use (TOFU) half of libsignal-client's
 * `IdentityKeyStore` contract (`saveIdentity`/`isTrustedIdentity`/
 * `getIdentity`) inside [RoomSignalProtocolStore]. Room table
 * `signal_remote_identities`.
 *
 * [peerId] is [org.signal.libsignal.protocol.SignalProtocolAddress.name]'s
 * string form (this app's addressing convention -- see [SessionEntity]'s
 * doc), not a Room foreign key to any other table.
 *
 * TOFU, not out-of-band verification: this app has no phone-number/account
 * directory and no Signal-style "safety number" comparison UI (named,
 * deliberate MVP simplification -- see the Phase 1 messaging plan's "no
 * re-verification UI if a peer's identity key changes" note). The first
 * identity key ever seen for a given [peerId] is trusted automatically; if a
 * later message claims to be from the same [peerId] but presents a
 * *different* identity key, [RoomSignalProtocolStore.isTrustedIdentity]
 * returns false and libsignal-client's `SessionBuilder`/`SessionCipher`
 * refuse to proceed (`UntrustedIdentityException`) rather than silently
 * re-trusting -- but nothing in this slice surfaces that refusal to the
 * user, unlike Signal's own safety-number-changed warning. That gap is
 * named, not forgotten.
 */
@Entity(tableName = "signal_remote_identities")
data class RemoteIdentityEntity(
    @PrimaryKey
    val peerId: String,
    val identityKeyBytes: ByteArray,
    val firstSeenAtMs: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RemoteIdentityEntity) return false
        return peerId == other.peerId &&
            identityKeyBytes.contentEquals(other.identityKeyBytes) &&
            firstSeenAtMs == other.firstSeenAtMs
    }

    override fun hashCode(): Int {
        var result = peerId.hashCode()
        result = 31 * result + identityKeyBytes.contentHashCode()
        result = 31 * result + firstSeenAtMs.hashCode()
        return result
    }
}
