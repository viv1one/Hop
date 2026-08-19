package com.hop.data

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Local persistence for posts, message history, the block list, decay keys,
 * and (as of version 3) persistent Double Ratchet session/key state -- the
 * data-layer slice that makes posts/messages/blocks/sessions survive an app
 * restart (they don't by default; everything built before this slice is
 * in-memory only).
 *
 * Deliberately NOT under `com.hop.spike` -- that package is Phase 0 throwaway
 * code that gets replaced, not incrementally reskinned (BUILD_PLAN.md), and
 * this is real data-layer infrastructure the eventual Phase 1 UI builds on.
 *
 * Version bumped 1 -> 2 for [BlockedSenderDeviceEntity]/[ReportedPostEntity]
 * (Feed tab slice). Version bumped 2 -> 3 for [IdentityKeyPairEntity],
 * [RemoteIdentityEntity], [PreKeyEntity], [SignedPreKeyEntity],
 * [KyberPreKeyEntity], and [SessionEntity] (the six tables backing
 * [RoomSignalProtocolStore], libsignal-client's `SignalProtocolStore`
 * persisted for the first time -- see its doc for what this fixes:
 * previously every 1:1 conversation needed a fresh handshake after an app
 * restart, since [MessageEntity] preserved message *history* but not the
 * live ratchet session that produced it). No `Migration` is provided for
 * either bump -- `Room.databaseBuilder(...).fallbackToDestructiveMigration()`
 * (see `AppContainer`) is the deliberate choice here, not an oversight: no
 * real users/on-device data exist yet for this database, so there's nothing
 * a real migration would need to preserve.
 */
@Database(
    entities = [
        PostEntity::class,
        MessageEntity::class,
        BlockedIdentityEntity::class,
        DecayKeyEntity::class,
        BlockedSenderDeviceEntity::class,
        ReportedPostEntity::class,
        IdentityKeyPairEntity::class,
        RemoteIdentityEntity::class,
        PreKeyEntity::class,
        SignedPreKeyEntity::class,
        KyberPreKeyEntity::class,
        SessionEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class HopDatabase : RoomDatabase() {
    abstract fun postDao(): PostDao
    abstract fun messageDao(): MessageDao
    abstract fun blockedIdentityDao(): BlockedIdentityDao
    abstract fun decayKeyDao(): DecayKeyDao
    abstract fun blockedSenderDeviceDao(): BlockedSenderDeviceDao
    abstract fun reportedPostDao(): ReportedPostDao
    abstract fun signalIdentityDao(): SignalIdentityDao
    abstract fun signalPreKeyDao(): SignalPreKeyDao
    abstract fun signalSignedPreKeyDao(): SignalSignedPreKeyDao
    abstract fun signalKyberPreKeyDao(): SignalKyberPreKeyDao
    abstract fun signalSessionDao(): SignalSessionDao
}
