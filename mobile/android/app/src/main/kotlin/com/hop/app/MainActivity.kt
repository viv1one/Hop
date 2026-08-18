package com.hop.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.hop.app.theme.HopTheme
import com.hop.transport.TransportManager

/**
 * [ComponentActivity], not [androidx.appcompat.app.AppCompatActivity] -- this
 * is a pure-Compose activity (Stage 1's whole UI, and every later slice's),
 * so there's no reason to carry AppCompat's View-system machinery into it.
 *
 * Hosts a single [HopNavHost] composable; every screen below it obtains its
 * dependencies via [HopApplication.container], never by constructing them
 * itself.
 *
 * Requests every runtime permission [TransportManager.REQUIRED_PERMISSIONS]
 * lists (BLE + WiFi Direct discovery/connect) unconditionally on every launch
 * -- Android no-ops the system dialog for anything already granted, so this
 * is safe to call every time, not just first run. No UI is shown here beyond
 * the system permission dialogs themselves -- per PRD §5, mesh mechanics stay
 * invisible to the user, and that includes not explaining *why* these
 * permissions are needed in HOP's own UI (the system dialog's own copy is
 * what the user sees).
 *
 * The permission list lives on [TransportManager] itself (not duplicated
 * here) and [permissionLauncher]'s result callback calls
 * [TransportManager.start] again -- both exist to close a real fresh-install
 * crash found via hardware testing: `TransportManager` registers on
 * [androidx.lifecycle.ProcessLifecycleOwner] and its `onStart` fires the
 * instant this Activity starts, synchronously and well before this
 * `permissionLauncher`'s async dialog resolves. On a brand-new install (no
 * permissions granted yet), the unconditional BLE-advertise call that used to
 * run from that early `onStart` threw an unguarded `SecurityException` and
 * crashed the app before the first-run screen even finished rendering.
 * `TransportManager.start` now no-ops its radio calls until
 * [TransportManager.REQUIRED_PERMISSIONS] are all granted, so re-calling it
 * here -- once the user actually answers the dialogs -- is what makes
 * discovery/advertising begin at all on a fresh install, instead of silently
 * never starting until the next app foreground.
 */
class MainActivity : ComponentActivity() {

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            (application as HopApplication).container.transportManager.start()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        permissionLauncher.launch(TransportManager.REQUIRED_PERMISSIONS.toTypedArray())

        val container = (application as HopApplication).container

        setContent {
            HopTheme {
                HopNavHost(container = container)
            }
        }
    }
}
