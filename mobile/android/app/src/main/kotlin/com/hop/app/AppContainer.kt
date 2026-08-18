package com.hop.app

import android.content.Context
import com.hop.crypto.AttestationProvider
import com.hop.crypto.StubAttestationProvider
import com.hop.data.SettingsRepository

/**
 * Hand-rolled dependency-injection holder -- no Hilt, the object graph is
 * small enough that a DI framework's build-time cost isn't worth it.
 *
 * Stage 1 scope: only [settingsRepository] and [attestationProvider]. Deliberately
 * a plain constructor-injected class (not, say, a lazy service-locator or a
 * singleton object) so a later slice can extend the constructor parameter
 * list (HopDatabase, repositories, TransportManager, ...) without restructuring
 * how existing callers obtain it -- callers always go through
 * `(application as HopApplication).container`, never `AppContainer` directly.
 *
 * [attestationProvider] is [StubAttestationProvider] -- the real Android Play
 * Integrity implementation doesn't exist yet (needs a Google Cloud/Play
 * Console project not yet provisioned). Not a substitute for real attestation
 * in any build that ships to users; see StubAttestationProvider's own doc in
 * crypto/ for why.
 */
class AppContainer(applicationContext: Context) {
    val settingsRepository: SettingsRepository = SettingsRepository(applicationContext)
    val attestationProvider: AttestationProvider = StubAttestationProvider()
}
