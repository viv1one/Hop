package com.hop.app

import android.content.Context
import androidx.room.Room
import com.hop.app.dht.DhtNodeManager
import com.hop.app.location.FusedLocationProvider
import com.hop.app.location.LocationProvider
import com.hop.crypto.AttestationProvider
import com.hop.crypto.DecayKeyStore
import com.hop.crypto.StubAttestationProvider
import com.hop.data.HopDatabase
import com.hop.data.IdentityKeyPairKeystoreCipher
import com.hop.data.PEER_DEVICE_ID
import com.hop.data.PreKeyRotationManager
import com.hop.data.RoomDecayKeyStorage
import com.hop.data.RoomSignalProtocolStore
import com.hop.data.SettingsRepository
import com.hop.data.toPeerIdHex
import com.hop.protocol.RelayPolicy
import com.hop.repository.BlockRepository
import com.hop.repository.BundleRepository
import com.hop.repository.DontRelayRepository
import com.hop.repository.MessageRepository
import com.hop.repository.PendingMessageRepository
import com.hop.repository.PointsRepository
import com.hop.repository.PostRepository
import com.hop.repository.RelayRepository
import com.hop.repository.ReportRepository
import com.hop.transport.TransportManager

/**
 * Hand-rolled dependency-injection holder -- no Hilt, the object graph is
 * small enough that a DI framework's build-time cost isn't worth it.
 *
 * Deliberately a plain constructor-injected class (not, say, a lazy
 * service-locator or a singleton object) so a later slice can extend the
 * constructor parameter list (TransportManager, ...) without restructuring
 * how existing callers obtain it -- callers always go through
 * `(application as HopApplication).container`, never `AppContainer` directly.
 *
 * [attestationProvider] is [StubAttestationProvider] -- the real Android Play
 * Integrity implementation doesn't exist yet (needs a Google Cloud/Play
 * Console project not yet provisioned). Not a substitute for real attestation
 * in any build that ships to users; see StubAttestationProvider's own doc in
 * crypto/ for why.
 *
 * [hopDatabase] is this app's one `Room.databaseBuilder(...)` call --
 * `fallbackToDestructiveMigration()` is the deliberate choice given no real
 * users/on-device data exist for this database yet (see [HopDatabase]'s own
 * version-bump doc). [decayKeyStore] is explicitly constructed with
 * [RoomDecayKeyStorage] (never the no-arg `DecayKeyStore()` default, which
 * uses in-memory storage) -- using the default here would silently lose every
 * decay key on process death, which defeats the entire point of wiring Room
 * persistence in at all.
 *
 * [transportManager] registers itself against `ProcessLifecycleOwner` at
 * construction time (see [TransportManager]'s own doc) -- constructing this
 * container is therefore what wires transport into the app's lifecycle,
 * there is no separate "start transport" call anywhere else.
 */
class AppContainer(applicationContext: Context) {
    val settingsRepository: SettingsRepository = SettingsRepository(applicationContext)
    val attestationProvider: AttestationProvider = StubAttestationProvider()

    /** Real on-device location read, used only to compute this device's current geohash-prefix cell -- see [LocationProvider]'s own doc for the "never put raw coordinates on the wire" invariant. */
    val locationProvider: LocationProvider = FusedLocationProvider(applicationContext)

    /**
     * Phase 4 Slice 7: this device's DHT participation lifecycle owner (see
     * [DhtNodeManager]'s own doc for the full lifecycle/bootstrap/address
     * posture). Registers itself against `ProcessLifecycleOwner` at
     * construction time -- same posture as [transportManager] below, and
     * constructing this container is what wires the DHT into the app's
     * lifecycle, exactly as with transport.
     *
     * [DhtNodeManager.getOwnNodeIdSeed] reuses
     * [SettingsRepository.getOrCreateStableSenderDeviceId] -- the same
     * per-install identity already used for messaging/blocking/Feed sender
     * ids (see `com.hop.data.PeerIdentity`'s doc) -- rather than minting a
     * new identity concept just for the DHT.
     *
     * `BuildConfig.DHT_BOOTSTRAP_HOST`/`DHT_BOOTSTRAP_PORT` are blank/`0` in
     * every real build (see `app/build.gradle.kts`'s own comment) --
     * `rendezvous/` (ADR 0002) is still an empty module, so there is no real
     * bootstrap node to join through yet; see
     * [DhtNodeManager]'s `maybeBootstrap` doc for the full explanation of
     * what that means for this device's DHT reachability today.
     */
    val dhtNodeManager: DhtNodeManager = DhtNodeManager(
        getOwnNodeIdSeed = { settingsRepository.getOrCreateStableSenderDeviceId() },
        bootstrapHost = BuildConfig.DHT_BOOTSTRAP_HOST,
        bootstrapPort = BuildConfig.DHT_BOOTSTRAP_PORT,
    )

    val hopDatabase: HopDatabase = Room.databaseBuilder(
        applicationContext,
        HopDatabase::class.java,
        "hop.db",
    )
        .fallbackToDestructiveMigration()
        .build()

    val decayKeyStore: DecayKeyStore = DecayKeyStore(
        storage = RoomDecayKeyStorage(hopDatabase.decayKeyDao()),
    )

    val postRepository: PostRepository = PostRepository(hopDatabase.postDao(), decayKeyStore)
    val blockRepository: BlockRepository = BlockRepository(hopDatabase.blockedSenderDeviceDao())
    val reportRepository: ReportRepository = ReportRepository(hopDatabase.reportedPostDao())

    /**
     * Phase 2 Slice 2's "don't relay" distinct-attested-device flag counter
     * (see [DontRelayRepository]'s own doc). Shares the same [RelayPolicy]
     * instance [relayRepository] uses below, so flag expiry and post expiry
     * are evaluated against the same clock/`isExpired` logic.
     */
    val dontRelayRepository: DontRelayRepository = DontRelayRepository(
        hopDatabase.dontRelayFlagDao(),
        hopDatabase.relayQueueDao(),
        RelayPolicy(),
    )

    /** Phase 2 Slice 2's non-tradeable local points counter for relay operators (see [PointsRepository]'s own doc). */
    val pointsRepository: PointsRepository = PointsRepository(hopDatabase.pointsLedgerDao())

    /**
     * Phase 2 Slice 1's persisted store-and-forward relay queue (see
     * [RelayRepository]'s own doc). [RelayPolicy] uses its default
     * `maxHops`/system clock -- no tuning input for either exists yet (see
     * [RelayPolicy.DEFAULT_MAX_HOPS]'s own doc). [dontRelayRepository]'s
     * [DontRelayRepository.isSuppressed] is threaded in as the order-
     * independence suppression check (Phase 2 Slice 2) -- see
     * [RelayRepository]'s own `isFlaggedForSuppression` doc.
     */
    val relayRepository: RelayRepository = RelayRepository(
        hopDatabase.relayQueueDao(),
        RelayPolicy(),
        isFlaggedForSuppression = dontRelayRepository::isSuppressed,
    )

    /**
     * Phase 2 Slice 3's persisted relay-custody queue for offline 1:1
     * message recipients (see [PendingMessageRepository]'s own doc). Shares
     * the same default [RelayPolicy] shape [relayRepository] uses -- no
     * separate tuning input exists for messages either (see
     * `MessageCiphertextEnvelope.DEFAULT_TTL_SECONDS`'s own doc for why its
     * TTL is a separate, shorter, unmeasured placeholder from posts', even
     * though the hop-count bound is shared).
     */
    val pendingMessageRepository: PendingMessageRepository = PendingMessageRepository(
        hopDatabase.pendingMessageDao(),
        RelayPolicy(),
    )

    /**
     * The prekey-bundle relay/discovery follow-up's persisted mesh
     * flood-relay queue for prekey bundles (see [BundleRepository]'s own
     * doc). Shares the same default [RelayPolicy] shape [relayRepository]/
     * [pendingMessageRepository] use -- no separate tuning input exists for
     * bundles either.
     */
    val bundleRepository: BundleRepository = BundleRepository(
        hopDatabase.bundleQueueDao(),
        RelayPolicy(),
    )

    /**
     * Persistent Double Ratchet session/key store (Stage 1). Typed as the
     * concrete [RoomSignalProtocolStore] (not the plain libsignal-client
     * `SignalProtocolStore` interface) since [preKeyRotationManager] below
     * needs [RoomSignalProtocolStore.pruneKyberPreKey], which isn't part of
     * that interface (see that method's own doc for why) -- assignable
     * anywhere a `SignalProtocolStore` is expected regardless
     * ([messageRepository]'s constructor parameter stays interface-typed).
     *
     * [IdentityKeyPairKeystoreCipher] Android-Keystore-wraps only the own
     * long-term identity key pair before it hits Room -- see its own doc
     * and [com.hop.data.IdentityKeyPairEntity]'s doc for why that field
     * specifically, and no other Signal-store field, gets this treatment.
     */
    val signalProtocolStore: RoomSignalProtocolStore = RoomSignalProtocolStore(
        hopDatabase.signalIdentityDao(),
        hopDatabase.signalPreKeyDao(),
        hopDatabase.signalSignedPreKeyDao(),
        hopDatabase.signalKyberPreKeyDao(),
        hopDatabase.signalSessionDao(),
        IdentityKeyPairKeystoreCipher(),
    )

    /**
     * One-time/signed/Kyber prekey batch replenishment + rotation policy
     * (see its own doc) -- [transportManager] uses this (instead of
     * [signalProtocolStore] directly) to assemble and announce this device's
     * own current prekey bundle on every new connection, replacing the old
     * "publish once, memoize forever" pattern that used to silently break
     * messaging with a second peer in the same app-process lifetime.
     */
    val preKeyRotationManager = PreKeyRotationManager(
        store = signalProtocolStore,
        counterDao = hopDatabase.signalPreKeyCounterDao(),
        deviceId = PEER_DEVICE_ID,
    )

    /**
     * This device's own stable messaging identity: the same hex string
     * [SettingsRepository.getOrCreateStableSenderDeviceId] persists (once
     * hex-encoded), reused as both the address peers know this device by
     * ([com.hop.data.peerSignalAddress]) and the `senderPeerId` this device
     * announces in every prekey bundle / message envelope it sends. One
     * identity, reused everywhere -- see `com.hop.data.PeerIdentity`'s doc.
     */
    private val getOwnPeerId: suspend () -> String = {
        settingsRepository.getOrCreateStableSenderDeviceId().toPeerIdHex()
    }

    /**
     * References [transportManager] inside its `sendMessage` lambda via a
     * forward property reference -- resolved lazily at call time (when a
     * message is actually sent), not at construction time, by which point
     * [transportManager] below is fully constructed. Breaks what would
     * otherwise be a circular dependency: [transportManager] needs
     * [messageRepository]'s callbacks to hand off received prekey
     * bundles/ciphertext, and [messageRepository] needs [transportManager]
     * to send outgoing ones.
     */
    val messageRepository: MessageRepository = MessageRepository(
        messageDao = hopDatabase.messageDao(),
        signalIdentityDao = hopDatabase.signalIdentityDao(),
        signalProtocolStore = signalProtocolStore,
        blockRepository = blockRepository,
        groupDao = hopDatabase.groupDao(),
        groupMessageDao = hopDatabase.groupMessageDao(),
        getOwnPeerId = getOwnPeerId,
        sendMessage = { peerId, payload -> transportManager.sendMessage(peerId, payload) },
    )

    val transportManager: TransportManager = TransportManager(
        context = applicationContext,
        postRepository = postRepository,
        decayKeyStore = decayKeyStore,
        relayRepository = relayRepository,
        dontRelayRepository = dontRelayRepository,
        pointsRepository = pointsRepository,
        pendingMessageRepository = pendingMessageRepository,
        bundleRepository = bundleRepository,
        preKeyRotationManager = preKeyRotationManager,
        getOwnPeerId = getOwnPeerId,
        onPreKeyBundleReceived = messageRepository::cachePeerBundle,
        onMessageCiphertextReceived = messageRepository::onEnvelopeReceived,
    )
}
