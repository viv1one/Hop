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
 * directory and no Signal-style "safety number" comparison UI. The first
 * identity key ever seen for a given [peerId] is trusted automatically and
 * stored in [identityKeyBytes]; if a later message claims to be from the
 * same [peerId] but presents a *different* identity key,
 * [RoomSignalProtocolStore.isTrustedIdentity] returns false (refusing to
 * proceed, matching libsignal-client's own `UntrustedIdentityException`
 * posture rather than silently re-trusting) **and** records that
 * newly-seen, not-yet-trusted key in [pendingIdentityKeyBytes] /
 * [identityChangeDetectedAtMs] as a side effect -- this is what lets the
 * Inbox UI surface a plain-language warning instead of the peer's messages
 * just silently failing to send/decrypt with no visible cause.
 * [com.hop.repository.MessageRepository.trustChangedIdentity] is the
 * corresponding "acknowledge and continue" action: it promotes
 * [pendingIdentityKeyBytes] into [identityKeyBytes], clears the pending
 * fields, and drops any now-stale session for this peer so the next
 * handshake starts clean.
 */
@Entity(tableName = "signal_remote_identities")
data class RemoteIdentityEntity(
    @PrimaryKey
    val peerId: String,
    val identityKeyBytes: ByteArray,
    val firstSeenAtMs: Long,
    val pendingIdentityKeyBytes: ByteArray? = null,
    val identityChangeDetectedAtMs: Long? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RemoteIdentityEntity) return false
        return peerId == other.peerId &&
            identityKeyBytes.contentEquals(other.identityKeyBytes) &&
            firstSeenAtMs == other.firstSeenAtMs &&
            pendingIdentityKeyBytes.contentEqualsOrBothNull(other.pendingIdentityKeyBytes) &&
            identityChangeDetectedAtMs == other.identityChangeDetectedAtMs
    }

    override fun hashCode(): Int {
        var result = peerId.hashCode()
        result = 31 * result + identityKeyBytes.contentHashCode()
        result = 31 * result + firstSeenAtMs.hashCode()
        result = 31 * result + (pendingIdentityKeyBytes?.contentHashCode() ?: 0)
        result = 31 * result + (identityChangeDetectedAtMs?.hashCode() ?: 0)
        return result
    }
}

private fun ByteArray?.contentEqualsOrBothNull(other: ByteArray?): Boolean =
    if (this == null || other == null) this == null && other == null else contentEquals(other)
