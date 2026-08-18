package com.hop.app

import android.content.Context
import androidx.room.Room
import com.hop.crypto.AttestationProvider
import com.hop.crypto.DecayKeyStore
import com.hop.crypto.StubAttestationProvider
import com.hop.data.HopDatabase
import com.hop.data.RoomDecayKeyStorage
import com.hop.data.SettingsRepository
import com.hop.repository.BlockRepository
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

    val transportManager: TransportManager = TransportManager(applicationContext, postRepository, decayKeyStore)
}
