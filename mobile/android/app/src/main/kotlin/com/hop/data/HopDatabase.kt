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
 * live ratchet session that produced it). Version bumped 3 -> 4 to add
 * [RemoteIdentityEntity.pendingIdentityKeyBytes]/[RemoteIdentityEntity.identityChangeDetectedAtMs]
 * (the "safety-number-changed" warning gap named in that entity's own doc,
 * closed by surfacing an identity-key mismatch to the Inbox UI instead of
 * it silently blocking send/decrypt with no visible cause). Version bumped
 * 4 -> 5 to add [SignalPreKeyCounterEntity] (the persisted id-allocation
 * state backing [PreKeyRotationManager]: one-time EC prekey batch
 * replenishment plus signed/Kyber prekey rotation, closing the "no
 * rotation, fixed ids" gap named in [PreKeyEntity]/[SignedPreKeyEntity]/
 * [KyberPreKeyEntity]'s docs and the correctness bug that gap caused -- see
 * [PreKeyRotationManager]'s own doc for both). Version bumped 5 -> 6 to add
 * [RelayQueueEntity] (Phase 2 Slice 1's persisted store-and-forward relay
 * queue -- see its own doc and `com.hop.repository.RelayRepository`'s doc
 * for why relay custody needs a table separate from [PostEntity]): without
 * this, a post's relay backlog lived only in [WifiDirectTransport]'s
 * in-memory `outbox`, so both a self-authored post and anything received
 * for onward relay were silently lost the moment this device's own app
 * process died, not just when a downstream peer's did. Version bumped 6 -> 7
 * to add [DontRelayFlagEntity] (Phase 2 Slice 2's persisted "don't relay"
 * distinct-attested-device flag counter -- see its own doc and
 * `com.hop.repository.DontRelayRepository`'s doc for the order-independence
 * design this backs) and [PointsLedgerEntity] (the clipHash-keyed,
 * insert-IGNORE points-award table -- see its own doc for the
 * backlog-resend double-counting bug this specific shape closes). Version
 * bumped 7 -> 8 to add [PendingMessageEntity] (Phase 2 Slice 3's persisted
 * relay-custody queue for offline 1:1 message recipients -- see its own doc
 * and `com.hop.repository.PendingMessageRepository`'s doc for why a message
 * relay-custody row needs a table separate from [RelayQueueEntity]): without
 * this, a message that unicast delivery couldn't reach right now had no
 * durable fallback at all -- see `MessageRepository.send`'s pre-Slice-3 doc
 * for the exact gap this closes. Version bumped 8 -> 9 to add [GroupEntity],
 * [GroupMemberEntity], and [GroupMessageEntity] (Phase 2 Slice 4's group
 * messaging -- per-member pairwise Double Ratchet fan-out, PRD §4.3, ADR 0001;
 * see [GroupEntity]'s own doc for why groups get their own tables rather than
 * reusing [MessageEntity]/[MessageDao]). Version bumped 9 -> 10 to add
 * [BundleQueueEntity] (the prekey-bundle relay/discovery follow-up's
 * persisted mesh flood-relay queue for prekey bundles -- see its own doc and
 * `com.hop.repository.BundleRepository`'s doc for why this table is
 * peer-id-keyed with conditional replace rather than content-hash-keyed with
 * first-custody-wins like every other relay table above): without this, a
 * prekey bundle could only ever be learned via direct radio contact, which
 * is also what silently capped Phase 2 Slice 4's group messaging to
 * creator<->member reachability only (two members who'd each only met the
 * creator, never each other, could never actually message directly -- see
 * [GroupEntity]'s own doc for that named gap). No
 * `Migration` is provided for any bump -- `Room.databaseBuilder(...).fallbackToDestructiveMigration()`
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
        SignalPreKeyCounterEntity::class,
        RelayQueueEntity::class,
        DontRelayFlagEntity::class,
        PointsLedgerEntity::class,
        PendingMessageEntity::class,
        GroupEntity::class,
        GroupMemberEntity::class,
        GroupMessageEntity::class,
        BundleQueueEntity::class,
    ],
    version = 10,
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
    abstract fun signalPreKeyCounterDao(): SignalPreKeyCounterDao
    abstract fun relayQueueDao(): RelayQueueDao
    abstract fun dontRelayFlagDao(): DontRelayFlagDao
    abstract fun pointsLedgerDao(): PointsLedgerDao
    abstract fun pendingMessageDao(): PendingMessageDao
    abstract fun groupDao(): GroupDao
    abstract fun groupMessageDao(): GroupMessageDao
    abstract fun bundleQueueDao(): BundleQueueDao
}
