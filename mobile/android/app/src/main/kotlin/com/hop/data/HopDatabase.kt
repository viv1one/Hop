package com.hop.data

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Local persistence for posts, message history, the block list, and decay
 * keys -- the data-layer slice that makes posts/messages/blocks survive an
 * app restart (they don't today; everything built before this slice is
 * in-memory only).
 *
 * Deliberately NOT under `com.hop.spike` -- that package is Phase 0 throwaway
 * code that gets replaced, not incrementally reskinned (BUILD_PLAN.md), and
 * this is real data-layer infrastructure the eventual Phase 1 UI builds on.
 *
 * Out of scope here (see task spec): a persistent `SignalProtocolStore`
 * (Double Ratchet session/ratchet state). Without it, 1:1 conversations need a
 * fresh handshake after an app restart -- [MessageEntity] preserves message
 * *history* across a restart, but not the live ratchet session that produced
 * it. That's a substantial separate piece of work, deferred to a later slice.
 */
@Database(
    entities = [
        PostEntity::class,
        MessageEntity::class,
        BlockedIdentityEntity::class,
        DecayKeyEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class HopDatabase : RoomDatabase() {
    abstract fun postDao(): PostDao
    abstract fun messageDao(): MessageDao
    abstract fun blockedIdentityDao(): BlockedIdentityDao
    abstract fun decayKeyDao(): DecayKeyDao
}
