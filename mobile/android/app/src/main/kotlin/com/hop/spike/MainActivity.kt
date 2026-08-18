package com.hop.spike

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pInfo
import android.media.MediaMetadataRetriever
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.hop.app.R
import com.hop.protocol.ContentType
import java.io.File
import java.security.MessageDigest

/**
 * Phase 0 spike harness — wires the BLE and WiFi Direct spikes, plus the
 * upload -> transfer -> view end-to-end spike, to buttons and a log view.
 * See BUILD_PLAN.md "Phase 0 — Feasibility spikes" for what this is measuring
 * and why. Not the app; this is throwaway code to get go/no-go numbers and a
 * first working loop before Phase 1 starts.
 *
 * Posting is upload-from-device-media only in v1 (PRD §4.1, §8) — there is no
 * in-app camera capture here, and none should be added; the media picker
 * below is the whole posting flow.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var logView: TextView
    private lateinit var bleSpike: BleDiscoverySpike
    private lateinit var wifiDirectSpike: WifiDirectSpike
    private lateinit var wifiP2pManager: WifiP2pManager
    private lateinit var wifiP2pChannel: WifiP2pManager.Channel
    private var lastDiscoveredPeer: WifiP2pDevice? = null
    private var lastDiscoveredPeers: List<WifiP2pDevice> = emptyList()

    /**
     * Set when "Send to Nearby Phone" is tapped with no peer discovered yet,
     * so the peers-changed receiver can connect automatically once one
     * appears — collapses "Discover, wait, then Connect" into one tap for
     * the common case. Manual "WiFi Direct Discover" (used to populate
     * [lastDiscoveredPeers] for the density test) is unaffected.
     */
    @Volatile
    private var autoConnectPending = false

    /** Tracks button state only — [WifiDirectSpike] owns the actual on/off state. */
    private var continuousDiscoveryOn = false
    private lateinit var discoverWifiButton: Button
    private lateinit var discoverWifiButtonDefaultLabel: CharSequence

    private val fileProviderAuthority: String
        get() = "$packageName.fileprovider"

    /**
     * Modern Android Photo Picker (androidx-backported via Google Play
     * services on pre-API-33 devices, per activity-ktx 1.9.1 — already a
     * dependency of this module). Requests both images and videos so the
     * user can pick either post type in one flow; no runtime storage
     * permission is needed, and the returned URI's read grant is temporary
     * and scoped automatically by the picker.
     */
    private val pickMediaLauncher =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri == null) {
                log("Media pick cancelled")
                return@registerForActivityResult
            }
            onMediaPicked(uri)
        }

    private val requiredPermissions = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(android.Manifest.permission.BLUETOOTH_SCAN)
            add(android.Manifest.permission.BLUETOOTH_ADVERTISE)
            add(android.Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(android.Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        add(android.Manifest.permission.ACCESS_FINE_LOCATION)
        add(android.Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val denied = results.filterValues { !it }.keys
            if (denied.isEmpty()) {
                log("All permissions granted")
            } else {
                log("Denied: $denied — the corresponding spike button won't work until granted")
            }
        }

    private val wifiP2pReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    wifiP2pManager.requestPeers(wifiP2pChannel) { peers: WifiP2pDeviceList ->
                        log("WiFi Direct peers found: ${peers.deviceList.size}")
                        peers.deviceList.forEach { log("  ${it.deviceName} ${it.deviceAddress}") }
                        lastDiscoveredPeer = peers.deviceList.firstOrNull()
                        lastDiscoveredPeers = peers.deviceList.toList()
                        val peer = lastDiscoveredPeer
                        if (autoConnectPending && peer != null) {
                            autoConnectPending = false
                            setContinuousDiscovery(false)
                            log("Auto-connecting to ${peer.deviceName} now that a peer was found")
                            wifiDirectSpike.connectTo(peer)
                        }
                    }
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    wifiP2pManager.requestConnectionInfo(wifiP2pChannel) { info: WifiP2pInfo ->
                        log("WiFi Direct connection changed: groupFormed=${info.groupFormed} owner=${info.isGroupOwner}")
                        wifiDirectSpike.onConnected(info)
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        logView = findViewById(R.id.logView)
        bleSpike = BleDiscoverySpike(this, ::log)

        wifiP2pManager = getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
        wifiP2pChannel = wifiP2pManager.initialize(this, mainLooper, null)
        wifiDirectSpike = WifiDirectSpike(this, wifiP2pChannel, ::log) { receivedFile, contentType ->
            runOnUiThread { viewReceivedPost(receivedFile, contentType) }
        }

        permissionLauncher.launch(requiredPermissions.toTypedArray())

        findViewById<Button>(R.id.btnPickMedia).setOnClickListener {
            pickMediaLauncher.launch(
                PickVisualMediaRequest(mediaType = ActivityResultContracts.PickVisualMedia.ImageAndVideo)
            )
        }
        findViewById<Button>(R.id.btnAdvertise).setOnClickListener {
            bleSpike.startAdvertising()
        }
        findViewById<Button>(R.id.btnScan).setOnClickListener {
            bleSpike.startScan()
        }
        discoverWifiButton = findViewById(R.id.btnDiscoverWifi)
        discoverWifiButtonDefaultLabel = discoverWifiButton.text
        discoverWifiButton.setOnClickListener { setContinuousDiscovery(!continuousDiscoveryOn) }
        findViewById<Button>(R.id.btnConnectTransfer).setOnClickListener {
            val peer = lastDiscoveredPeer
            if (peer == null) {
                log(
                    "No peer discovered yet — starting continuous discovery, will connect " +
                        "automatically once a peer's found (leave the other phone's app open too)"
                )
                autoConnectPending = true
                setContinuousDiscovery(true)
            } else {
                wifiDirectSpike.connectTo(peer)
            }
        }
        findViewById<Button>(R.id.btnDensityRotationTest).setOnClickListener {
            val peers = lastDiscoveredPeers
            if (peers.isEmpty()) {
                log("No WiFi Direct peers discovered yet — tap 'WiFi Direct Discover' first")
            } else {
                wifiDirectSpike.runDensityRotationTest(peers)
            }
        }
        findViewById<Button>(R.id.btnLoopbackTest).setOnClickListener {
            log("Loopback test starting — pick a post first if you want to exercise a real post, otherwise a synthetic payload is used")
            wifiDirectSpike.runLoopbackTest()
        }
    }

    /**
     * Single place that flips [continuousDiscoveryOn], the button label, and
     * the actual [WifiDirectSpike] state together, so "Send to Nearby Phone"'s
     * auto-connect path and the manual "WiFi Direct Discover" toggle button
     * can't disagree about whether discovery is currently running.
     */
    private fun setContinuousDiscovery(on: Boolean) {
        continuousDiscoveryOn = on
        if (on) {
            wifiDirectSpike.startContinuousDiscovery()
            discoverWifiButton.text = "WiFi Direct Discover: ON (tap to stop)"
        } else {
            wifiDirectSpike.stopContinuousDiscovery()
            discoverWifiButton.text = discoverWifiButtonDefaultLabel
        }
    }

    /**
     * Reads the picked media's mime type to determine its [ContentType]
     * (an "image" mime type maps to PHOTO, "video" maps to VIDEO), reads its
     * bytes, hashes them, and queues it for the next WiFi Direct send. Aborts
     * rather than guessing if the mime type is neither — content type is an
     * explicit wire field, not something inferred from a file extension
     * (BUILD_PLAN.md decision #4).
     */
    private fun onMediaPicked(uri: Uri) {
        val mimeType = contentResolver.getType(uri)
        val contentType = when {
            mimeType?.startsWith("image/") == true -> ContentType.PHOTO
            mimeType?.startsWith("video/") == true -> ContentType.VIDEO
            else -> {
                log("Picked media has unrecognized mime type '$mimeType' — aborting")
                return
            }
        }

        // BUILD_PLAN.md open decision #3 (settled via real-device spike): real
        // phone camera output runs ~20-22 Mbps native (H.264 or HEVC, varies by
        // device — v1 has no in-app camera, so HOP never controls the source
        // encode). At that bitrate, PRD §4.1's nominal 60s target would produce
        // a ~150-165MB file, missing the §7 "low single-digit seconds" transfer
        // NFR at this repo's own measured real WiFi Direct throughput. A ~15s
        // clip stays inside the NFR, so v1 enforces that cap at pick time
        // instead of transcoding (no encode step to bound at capture without an
        // in-app camera, and re-encoding an already-compressed clip is real
        // complexity worth deferring past v1).
        if (contentType == ContentType.VIDEO) {
            val retriever = MediaMetadataRetriever()
            val durationMs = try {
                retriever.setDataSource(this, uri)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            } finally {
                retriever.release()
            }
            if (durationMs == null) {
                log("Could not read picked video's duration — aborting rather than guessing")
                return
            }
            if (durationMs > MAX_VIDEO_DURATION_MS) {
                log(
                    "Picked video is ${durationMs / 1000}s, longer than the " +
                        "${MAX_VIDEO_DURATION_MS / 1000}s cap (BUILD_PLAN.md decision #3) — pick a shorter clip"
                )
                return
            }
        }

        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
        if (bytes == null || bytes.isEmpty()) {
            log("Could not read picked media at $uri, or it was empty")
            return
        }

        val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
        wifiDirectSpike.setPendingClip(bytes, hash, contentType)
        log(
            "Picked $contentType: ${bytes.size} bytes, sha256=${hash.toHex()} — " +
                "will be sent on the next WiFi Direct 'Connect + Transfer'"
        )
    }

    /**
     * Hands a received post to the system's default photo/video viewer via
     * ACTION_VIEW — no custom viewer UI, per Phase 0's "no UI polish" scope.
     * Note this is the mesh-transport half of "phone B views it"; there is no
     * BLE-MAC-to-WiFi-P2P-peer-identity correlation here on purpose (that's a
     * Phase 1 problem — see the task scoping note), so triggering this still
     * requires manually tapping "Connect + Transfer" after BLE/WiFi discovery.
     */
    private fun viewReceivedPost(file: File, contentType: ContentType) {
        val uri = FileProvider.getUriForFile(this, fileProviderAuthority, file)
        val mimeType = when (contentType) {
            ContentType.PHOTO -> "image/*"
            ContentType.VIDEO -> "video/*"
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        log("Launching viewer for received post ($contentType): ${file.absolutePath}")
        startActivity(intent)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(wifiP2pReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(wifiP2pReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(wifiP2pReceiver)
    }

    private fun log(message: String) {
        runOnUiThread {
            logView.append("$message\n")
        }
    }

    private companion object {
        // Settled by real-device measurement — see the comment in onMediaPicked
        // and BUILD_PLAN.md open decision #3.
        const val MAX_VIDEO_DURATION_MS = 15_000L
    }
}
