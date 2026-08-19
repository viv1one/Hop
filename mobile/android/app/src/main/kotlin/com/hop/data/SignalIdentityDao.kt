package com.hop.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Backs the [IdentityKeyPairEntity] (this device's own identity) and
 * [RemoteIdentityEntity] (TOFU trust table for remote peers) halves of
 * [RoomSignalProtocolStore]'s `IdentityKeyStore` implementation. Deliberately
 * one DAO for both tables -- they're the two persistence surfaces behind a
 * single libsignal-client sub-interface (`IdentityKeyStore`), matching this
 * repo's per-concern DAO granularity (see [DecayKeyDao]'s doc) rather than
 * strictly one DAO per table.
 *
 * `getOwnIdentity`'s query hardcodes id `0`, matching
 * [IdentityKeyPairEntity.SINGLETON_ID] -- kept as a literal rather than a
 * Kotlin string-template reference to that constant because Room's KSP
 * processor requires `@Query`'s value to be a literal compile-time constant
 * string it can validate against the schema at build time.
 *
 * Deliberately blocking (non-`suspend`): `IdentityKeyStore`'s methods are
 * plain synchronous calls in libsignal-client's `SignalProtocolStore`
 * contract. Callers (ultimately [RoomSignalProtocolStore]) must invoke this
 * off the main thread. [observeRemoteIdentity] is the one exception --
 * Room's `Flow`-returning queries are safe to collect from any thread (Room
 * dispatches the underlying query itself), and it exists specifically for
 * UI-layer observation of [RemoteIdentityEntity.pendingIdentityKeyBytes]
 * (see that field's doc), not for [RoomSignalProtocolStore]'s own use.
 */
@Dao
interface SignalIdentityDao {
    @Query("SELECT * FROM signal_identity_key_pair WHERE id = 0")
    fun getOwnIdentity(): IdentityKeyPairEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertOwnIdentity(entity: IdentityKeyPairEntity)

    @Query("SELECT * FROM signal_remote_identities WHERE peerId = :peerId")
    fun getRemoteIdentity(peerId: String): RemoteIdentityEntity?

    /** UI-facing counterpart to [getRemoteIdentity] -- see this DAO's own doc for why this one returns a [Flow]. */
    @Query("SELECT * FROM signal_remote_identities WHERE peerId = :peerId")
    fun observeRemoteIdentity(peerId: String): Flow<RemoteIdentityEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertRemoteIdentity(entity: RemoteIdentityEntity)

    /**
     * Records a newly-observed, not-yet-trusted identity key for [peerId]
     * without disturbing the currently-trusted [RemoteIdentityEntity.identityKeyBytes]
     * -- called from [RoomSignalProtocolStore.isTrustedIdentity] the moment
     * it detects a mismatch, so the Inbox UI has something concrete to warn
     * about and act on ([com.hop.repository.MessageRepository.trustChangedIdentity]).
     * A no-op if [peerId] has no existing trusted identity row yet (nothing
     * to compare a "change" against).
     */
    @Query(
        """
        UPDATE signal_remote_identities
        SET pendingIdentityKeyBytes = :newKeyBytes, identityChangeDetectedAtMs = :atMs
        WHERE peerId = :peerId
        """,
    )
    fun markIdentityChangePending(peerId: String, newKeyBytes: ByteArray, atMs: Long)

    /** Clears a pending-change flag without touching [RemoteIdentityEntity.identityKeyBytes] -- callers decide separately whether to promote or discard the pending key. */
    @Query(
        """
        UPDATE signal_remote_identities
        SET pendingIdentityKeyBytes = NULL, identityChangeDetectedAtMs = NULL
        WHERE peerId = :peerId
        """,
    )
    fun clearIdentityChangePending(peerId: String)
}
