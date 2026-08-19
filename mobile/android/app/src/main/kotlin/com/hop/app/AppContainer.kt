package com.hop.app

import android.content.Context
import androidx.room.Room
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
import com.hop.repository.BlockRepository
import com.hop.repository.MessageRepository
import com.hop.repository.PostRepository
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
     * References [transportManager] inside its `sendToPeer` lambda via a
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
        getOwnPeerId = getOwnPeerId,
        sendToPeer = { peerId, type, payload -> transportManager.sendToPeer(peerId, type, payload) },
    )

    val transportManager: TransportManager = TransportManager(
        context = applicationContext,
        postRepository = postRepository,
        decayKeyStore = decayKeyStore,
        preKeyRotationManager = preKeyRotationManager,
        getOwnPeerId = getOwnPeerId,
        onPreKeyBundleReceived = messageRepository::cachePeerBundle,
        onMessageCiphertextReceived = messageRepository::onEnvelopeReceived,
    )
}
