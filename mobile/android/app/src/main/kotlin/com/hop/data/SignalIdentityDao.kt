package com.hop.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

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
 * off the main thread.
 */
@Dao
interface SignalIdentityDao {
    @Query("SELECT * FROM signal_identity_key_pair WHERE id = 0")
    fun getOwnIdentity(): IdentityKeyPairEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertOwnIdentity(entity: IdentityKeyPairEntity)

    @Query("SELECT * FROM signal_remote_identities WHERE peerId = :peerId")
    fun getRemoteIdentity(peerId: String): RemoteIdentityEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertRemoteIdentity(entity: RemoteIdentityEntity)
}
