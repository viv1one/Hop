package com.hop.transport

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.hop.crypto.DecayKeyStore
import com.hop.protocol.WirePayloadType
import com.hop.repository.PostRepository
import org.signal.libsignal.protocol.state.SignalProtocolStore
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns the `WifiP2pManager`/`Channel`, the WIFI_P2P_PEERS_CHANGED_ACTION /
 * WIFI_P2P_CONNECTION_CHANGED_ACTION [BroadcastReceiver] (ported from
 * `com.hop.spike.MainActivity`'s receiver logic -- that file is left
 * untouched, not imported from), [WifiDirectTransport], and [BleDiscovery].
 * Public surface is deliberately narrow: [start]/[stop] and [broadcastPost].
 *
 * **Lifecycle (deliberate MVP posture, not an oversight):** registers itself
 * as a [DefaultLifecycleObserver] on [ProcessLifecycleOwner] at construction
 * time. [onStart] (app foregrounded -- at least one activity started) calls
 * [start]; [onStop] (app fully backgrounded) calls [stop]. There is no
 * foreground service here, matching BUILD_PLAN.md Phase 3's "open the app to
 * see what's nearby" note applied a phase early, and consistent with
 * Android's own background BLE/WiFi scanning restrictions this product
 * already designs around. **Named consequence:** discovery and receiving
 * both stop the instant the app is backgrounded -- a post broadcast by a
 * nearby peer while this device's app isn't foregrounded will not be
 * received until the app is reopened and a fresh connection happens. Revisit
 * explicitly alongside Phase 2 relay/store-and-forward work, don't quietly
 * paper over it with a foreground service later without re-deciding this.
 *
 * **Opportunistic connection strategy:** on every WIFI_P2P_PEERS_CHANGED_ACTION,
 * attempts [WifiDirectTransport.connectTo] for every discovered peer not
 * already attempted within the last [CONNECT_COOLDOWN_MS] (a simple
 * per-peer-address cooldown map) -- avoids connect-storming a peer that's
 * currently failing or rejecting connections. This replaces the Phase 0
 * spike's manual "Connect + Transfer" button entirely; there is no
 * user-facing connect action anywhere in this app -- BLE/WiFi Direct/hop
 * count/relay mechanics stay invisible to the user (hop-dev skill invariant
 * #5, PRD §5). Reach radius remains the only mesh concept the user ever sees,
 * and only at post time.
 */
class TransportManager(
    private val context: Context,
    postRepository: PostRepository,
    decayKeyStore: DecayKeyStore,
    signalProtocolStore: SignalProtocolStore,
    getOwnPeerId: suspend () -> String,
    onPreKeyBundleReceived: (peerId: String, bundleBytes: ByteArray) -> Unit = { _, _ -> },
    onMessageCiphertextReceived: suspend (senderPeerId: String, ciphertext: ByteArray) -> Unit = { _, _ -> },
) : DefaultLifecycleObserver {

    private val manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    private val channel: WifiP2pManager.Channel = manager.initialize(context, context.mainLooper, null)

    private val wifiDirectTransport = WifiDirectTransport(
        context = context,
        channel = channel,
        postRepository = postRepository,
        decayKeyStore = decayKeyStore,
        signalProtocolStore = signalProtocolStore,
        getOwnPeerId = getOwnPeerId,
        onPreKeyBundleReceived = onPreKeyBundleReceived,
        onMessageCiphertextReceived = onMessageCiphertextReceived,
        onLog = { message -> Log.d(TAG, message) },
    )

    private val bleDiscovery = BleDiscovery(context, onLog = { message -> Log.d(TAG, message) })

    /**
     * Device address -> last [WifiDirectTransport.connectTo] attempt time
     * (epoch millis). Backs the per-peer cooldown described in this class's
     * doc; a [ConcurrentHashMap] since [receiver] callbacks and [start]/[stop]
     * can run from different threads (WifiP2pManager callbacks post to the
     * looper this [channel] was initialized with -- the main looper here --
     * but this map is cheap to make correct regardless).
     */
    private val lastConnectAttemptMs = ConcurrentHashMap<String, Long>()

    private var receiverRegistered = false

    private val receiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(receiverContext: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    manager.requestPeers(channel) { peers: WifiP2pDeviceList ->
                        peers.deviceList.forEach { device -> maybeConnect(device) }
                    }
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    manager.requestConnectionInfo(channel) { info: WifiP2pInfo ->
                        Log.d(TAG, "Connection info: groupFormed=${info.groupFormed} isGroupOwner=${info.isGroupOwner} goAddr=${info.groupOwnerAddress}")
                        wifiDirectTransport.onConnected(info)
                    }
                }
            }
        }
    }

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) = start()

    override fun onStop(owner: LifecycleOwner) = stop()

    /**
     * Begins continuous WiFi Direct discovery + BLE advertise/scan and
     * registers the WiFi P2P receiver. Idempotent.
     *
     * Real-hardware crash this guards against: [ProcessLifecycleOwner] fires
     * [onStart] the instant `MainActivity` starts, which happens
     * synchronously and well before the async runtime-permission dialog
     * (`MainActivity`'s `permissionLauncher`) resolves -- on a fresh install
     * with no permissions yet granted, the old unconditional
     * `bleDiscovery.startAdvertising()` call threw an unguarded
     * `SecurityException` (`BLUETOOTH_ADVERTISE` not granted) and crashed the
     * whole app before the first-run screen finished rendering. [start] now
     * no-ops the actual radio calls (though it still registers the WiFi P2P
     * receiver, which needs no dangerous permission) when
     * [REQUIRED_PERMISSIONS] aren't all granted yet; `MainActivity` calls
     * [start] again from its permission-result callback so discovery/
     * advertising actually begins the moment the user grants them, instead of
     * silently never starting until the next app foreground.
     */
    fun start() {
        registerReceiver()
        if (!hasRequiredPermissions()) {
            Log.d(TAG, "Skipping discovery/advertising start -- required permissions not yet granted")
            return
        }
        reconcileExistingConnection()
        wifiDirectTransport.startContinuousDiscovery()
        bleDiscovery.startAdvertising()
        bleDiscovery.startScan()
    }

    /**
     * Queries current WiFi P2P connection state directly, instead of waiting
     * for a WIFI_P2P_CONNECTION_CHANGED_ACTION broadcast to report one.
     *
     * Real-hardware finding (reproduced repeatedly, including by an actual
     * user on a clean install with no prior test tampering): a WiFi Direct
     * group can be -- and, on these devices, very often is -- already formed
     * by the time this device's receiver registers, e.g. via the platform's
     * own persistent-group auto-reconnect. When that happens, no
     * CONNECTION_CHANGED broadcast is ever delivered to *this* app process,
     * so [WifiDirectTransport.onConnected] never runs, no client/server
     * socket ever opens, and every post silently sits in the outbox forever
     * -- `dumpsys wifip2p` shows `groupFormed: true` the entire time, and
     * there is no error anywhere, because from the platform's perspective
     * nothing went wrong. This looked, to a real user, exactly like "nothing
     * happens" when posting. Calling [WifiP2pManager.requestConnectionInfo]
     * once here reconciles that gap on every [start] (i.e. every time the app
     * is foregrounded, not just the first launch) instead of depending
     * entirely on a broadcast that may simply never come.
     */
    @SuppressLint("MissingPermission")
    private fun reconcileExistingConnection() {
        manager.requestConnectionInfo(channel) { info: WifiP2pInfo ->
            Log.d(TAG, "Reconciled connection info: groupFormed=${info.groupFormed} isGroupOwner=${info.isGroupOwner} goAddr=${info.groupOwnerAddress}")
            wifiDirectTransport.onConnected(info)
        }
    }

    private fun hasRequiredPermissions(): Boolean =
        REQUIRED_PERMISSIONS.all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }

    /** Stops discovery/advertising/scanning and unregisters the receiver. Idempotent. Does not tear down an in-progress WiFi Direct group. */
    fun stop() {
        unregisterReceiver()
        wifiDirectTransport.stopContinuousDiscovery()
        bleDiscovery.stopAdvertising()
        bleDiscovery.stopScan()
    }

    /** Delegates to [WifiDirectTransport.broadcastPost] -- see its doc for the outbox's session-scoped, no-ack/retry semantics. */
    fun broadcastPost(encoded: ByteArray) = wifiDirectTransport.broadcastPost(encoded)

    /** Delegates to [WifiDirectTransport.sendToPeer] -- see its doc for the peer-specific (non-broadcast) send semantics. */
    fun sendToPeer(peerId: String, type: WirePayloadType, payload: ByteArray): Boolean =
        wifiDirectTransport.sendToPeer(peerId, type, payload)

    @SuppressLint("MissingPermission")
    private fun maybeConnect(device: WifiP2pDevice) {
        // A device can only belong to one WiFi Direct group at a time -- see
        // WifiDirectTransport.hasActiveGroup's doc for the real-hardware
        // failure (misrouted "invite", not a fresh connect) this skips.
        if (wifiDirectTransport.hasActiveGroup()) return
        val now = System.currentTimeMillis()
        val lastAttempt = lastConnectAttemptMs[device.deviceAddress]
        if (lastAttempt != null && now - lastAttempt < CONNECT_COOLDOWN_MS) return
        lastConnectAttemptMs[device.deviceAddress] = now
        wifiDirectTransport.connectTo(device)
    }

    private fun registerReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        receiverRegistered = true
    }

    private fun unregisterReceiver() {
        if (!receiverRegistered) return
        context.unregisterReceiver(receiver)
        receiverRegistered = false
    }

    companion object {
        private const val TAG = "TransportManager"

        /**
         * Skip re-attempting `connectTo()` for a peer address seen again
         * within this window -- avoids connect-storming a peer that's
         * currently failing/rejecting. Not tuned against real multi-peer
         * density data (BUILD_PLAN.md open decision #5's N-peer spike);
         * revisit once real hardware numbers exist for how long a failed
         * WiFi Direct connect attempt takes to resolve.
         */
        private const val CONNECT_COOLDOWN_MS = 30_000L

        /**
         * Every runtime permission this class's radio operations (BLE
         * advertise/scan + WiFi Direct discover/connect) need. Single source
         * of truth -- `MainActivity` requests exactly this list and re-calls
         * [start] once they're granted, and [hasRequiredPermissions] checks
         * exactly this list before touching any radio API. Keeping one list
         * (rather than a duplicate in `MainActivity`) is what the fresh-install
         * crash this file's [start] doc describes -- a second, drifted copy
         * would silently reintroduce the same class of bug.
         */
        val REQUIRED_PERMISSIONS: List<String> = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }
}
