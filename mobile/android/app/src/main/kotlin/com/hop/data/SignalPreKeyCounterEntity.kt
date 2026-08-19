package com.hop.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Singleton row (always [ID]) tracking this device's own prekey
 * id-allocation state for [PreKeyRotationManager] -- Room table
 * `signal_prekey_counters`.
 *
 * A small persisted monotonic counter, not a `MAX(preKeyId) FROM
 * signal_prekeys`-style derivation, is the deliberate choice here: a
 * consumed one-time EC prekey is *deleted* from `signal_prekeys`
 * ([RoomSignalProtocolStore.removePreKey], per libsignal-client's own
 * `PreKeyStore` contract), so `MAX(remaining rows)+1` would still be
 * collision-safe in practice (deleting a row can only ever lower the max of
 * what's left) -- but that safety depends on that reasoning continuing to
 * hold with every future change to this table, and it can't answer "how many
 * unconsumed prekeys are left" or "which id was already handed out to a
 * peer but not yet consumed" at all, both of which
 * [PreKeyRotationManager.currentBundle] needs on every single connection.
 * An explicit counter answers both directly and doesn't depend on nothing
 * else ever touching this table in a way that would break the MAX-based
 * reasoning.
 *
 * [nextOneTimePreKeyIdToGenerate] / [nextOneTimePreKeyIdToHandOut] track two
 * different things on purpose: *generation* happens in batches, ahead of
 * time, only when the pool is running low (expensive relative to a
 * connection: EC keypair generation); *hand-out* happens once per new
 * connection, always advancing to a ***previously-never-handed-out*** id
 * from the already-generated pool (cheap: a DB read of an existing row) --
 * this is what guarantees two different peers connecting in the same
 * process lifetime are never handed the same one-time prekey, closing the
 * exact bug `com.hop.transport.WifiDirectTransport`'s old
 * `ownPreKeyBundleBytesMemo` had (memoizing and handing out the *same*
 * bundle -- and therefore the same already-possibly-consumed one-time
 * prekey id -- to every peer for the rest of the process's life).
 *
 * [currentSignedPreKeyId] / [currentKyberPreKeyId] are the ids this device
 * currently advertises in [PreKeyRotationManager.currentBundle] -- unlike
 * the one-time pool, these are *not* handed out one-per-connection; the same
 * current signed/Kyber prekey is announced to every peer until
 * [PreKeyRotationManager] decides it's time to rotate (see its own doc for
 * the rotation interval/grace period policy). Their *age* (used to decide
 * "is it time to rotate yet") is read from the already-stored
 * `SignedPreKeyRecord.timestamp`/`KyberPreKeyRecord.timestamp` at rotation
 * -check time, not duplicated here as a separate column.
 */
@Entity(tableName = "signal_prekey_counters")
data class SignalPreKeyCounterEntity(
    @PrimaryKey
    val id: Int = ID,
    val nextOneTimePreKeyIdToGenerate: Int,
    val nextOneTimePreKeyIdToHandOut: Int,
    val currentSignedPreKeyId: Int,
    val currentKyberPreKeyId: Int,
) {
    companion object {
        const val ID = 0
    }
}
