package com.hop.spike

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import com.hop.protocol.ContentType
import com.hop.protocol.Frame
import com.hop.protocol.ReachTier
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Phase 0 spike: measures WiFi Direct connection-setup time and real transfer
 * throughput for a clip-sized payload (BUILD_PLAN.md "WiFi Direct: real-world
 * throughput and connection-setup time for a ~10-30MB clip"), and — for the
 * end-to-end upload -> discover -> transfer -> view spike — carries a real
 * picked-from-media-library post (photo or video) instead of only a synthetic
 * payload when one has been queued via [setPendingClip].
 *
 * Payloads are wrapped in a /protocol/WIRE_FORMAT.md version-1 Frame before
 * sending, so this spike measures real framing overhead on top of raw socket
 * throughput, not just raw bytes. Field values below (device ID, ttlSeconds,
 * reachTier) are spike placeholders, not production logic — clipHash is real
 * (SHA-256 of the actual payload) whenever a picked post is being sent.
 */
class WifiDirectSpike(
    context: Context,
    private val channel: WifiP2pManager.Channel,
    private val onLog: (String) -> Unit,
    private val onClipReceived: (File, ContentType) -> Unit = { _, _ -> },
) {
    companion object {
        const val TRANSFER_PORT = 8988
        // Sized at the low end of BUILD_PLAN's 10-30MB clip range.
        const val TEST_PAYLOAD_BYTES = 10 * 1024 * 1024

        // Placeholder TTL for spike frames — decay logic that acts on this lands
        // in Phase 1/2; this spike just exercises the frame carrying the field.
        const val SPIKE_TTL_SECONDS = 3600L

        // Subdirectory of cacheDir that received clips are written to before
        // being handed to the system video player. Cache-only, no persistence,
        // matching res/xml/file_paths.xml and BUILD_PLAN.md Phase 0 scope.
        const val RECEIVED_CLIPS_DIR = "received-clips"

        // Raw (outside-the-Frame) transport-framing tag written before each
        // length-prefixed Frame, so a receiver can tell a real picked post
        // apart from the synthetic throughput-test fallback without guessing
        // from content — and, more importantly, so it never has to *not*
        // receive anything: see handleConnection's note on why every
        // sendPayload call now always writes one or the other.
        const val KIND_REAL_POST = 1
        const val KIND_SYNTHETIC_TEST = 2
    }

    private val manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    private val appContext = context.applicationContext

    /** A picked-from-media-library post queued to be sent on the next transfer, instead of a random test payload. */
    private data class PendingClip(val bytes: ByteArray, val clipHash: ByteArray, val contentType: ContentType)

    @Volatile
    private var pendingClip: PendingClip? = null

    /**
     * Group-formed (or connection-changed) events, offered by [onConnected] on
     * every WIFI_P2P_CONNECTION_CHANGED_ACTION callback from MainActivity's
     * broadcast receiver. Exists so a background thread (the density rotation
     * test below) can block-wait for the next connection event via [poll]
     * without restructuring the existing receiver-driven single-peer flow —
     * [onConnected] keeps working exactly as before for that flow.
     */
    private val connectionEvents = LinkedBlockingQueue<WifiP2pInfo>()

    /** Result of one connect attempt in [runDensityRotationTest]. */
    data class DensityRotationResult(
        val device: WifiP2pDevice,
        val success: Boolean,
        val connectSetupMs: Long,
    )

    @Volatile
    private var pollingGroupMembership = false

    /**
     * Identifies the group formation [onConnected] last actually acted on
     * (started a server/client for), as `"$isGroupOwner:$groupOwnerAddress"`.
     * WIFI_P2P_CONNECTION_CHANGED_ACTION can fire more than once for the same
     * real connection (observed on real hardware: group-owner side received
     * two groupFormed=true events for one client joining) — without this
     * guard, [onConnected] called [runServer] twice for the same group,
     * and the second bind to [TRANSFER_PORT] failed with EADDRINUSE.
     */
    @Volatile
    private var activeGroupKey: String? = null

    /**
     * The group owner's listening socket, held here (rather than only as a
     * local val in [runServer]) so [stopServer] can close it from another
     * thread to unblock a pending [ServerSocket.accept] call and end the
     * accept loop — the only way to interrupt a blocking accept() in Java.
     */
    @Volatile
    private var activeServerSocket: ServerSocket? = null

    /**
     * Queues [bytes] (a real post picked from the device media library) with its
     * real SHA-256 [clipHash] and its [contentType] to be sent on the next
     * [sendPayload] call, instead of the default random test payload. Cleared
     * for the caller to re-set per pick; not consumed automatically on send,
     * since the spike's "Connect + Transfer" button is also meant to keep
     * working standalone as a pure-throughput test.
     */
    fun setPendingClip(bytes: ByteArray, clipHash: ByteArray, contentType: ContentType) {
        require(clipHash.size == Frame.CLIP_HASH_SIZE) {
            "clipHash must be ${Frame.CLIP_HASH_SIZE} bytes, was ${clipHash.size}"
        }
        pendingClip = PendingClip(bytes, clipHash, contentType)
        onLog("Queued picked post ($contentType) for next transfer: ${bytes.size} bytes")
    }

    @SuppressLint("MissingPermission")
    fun discoverPeers() {
        onLog("WiFi Direct peer discovery started")
        manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = onLog("WiFi Direct discovery initiated")
            override fun onFailure(reason: Int) = onLog("WiFi Direct discovery failed: reason $reason")
        })
    }

    @Volatile
    private var continuousDiscoveryActive = false

    /**
     * Design answer to the bilateral-rediscovery problem found during real-
     * hardware testing: after any WiFi Direct teardown, a peer only reappears
     * in this device's scan cache once a fresh discoverPeers() cycle runs —
     * and Android's own discovery window only lasts on the order of ~12s
     * before it needs reissuing. Two phones each tapping "Discover" once,
     * seconds apart, routinely missed each other's overlap window entirely.
     *
     * One side alone can't fix this — WiFi Direct discovery requires the
     * other device to be actively discoverable too, and nothing on this
     * device can make that happen remotely. What this device CAN control is
     * its own half: stay in a discovering state continuously instead of a
     * single burst, for as long as the app is in the foreground, so whenever
     * the *other* phone's own discovery window is open, this one is too. That
     * matches the product's existing posture (BUILD_PLAN.md's Phase 3 iOS
     * note: "open the app to see what's nearby," not ambient background
     * scanning) rather than inventing new always-on background behavior.
     *
     * A real fix that doesn't depend on both users tapping a button at
     * compatible moments would use BLE (already continuous/low-power here)
     * as a presence layer that triggers WiFi Direct discovery on both sides
     * once mutual BLE visibility is confirmed — that's real protocol design
     * work for Phase 1/2, not something to build into throwaway Phase 0 spike
     * code (per hop-dev's "build in phase order"). This is the Phase-0-scoped
     * mitigation: maximize overlap odds, don't solve the protocol problem.
     */
    @SuppressLint("MissingPermission")
    fun startContinuousDiscovery(intervalMs: Long = 10_000) {
        if (continuousDiscoveryActive) return
        continuousDiscoveryActive = true
        onLog("Continuous WiFi Direct discovery started (every ${intervalMs}ms) — leave both phones' apps open")
        Thread {
            while (continuousDiscoveryActive) {
                discoverPeers()
                Thread.sleep(intervalMs)
            }
        }.start()
    }

    fun stopContinuousDiscovery() {
        if (!continuousDiscoveryActive) return
        continuousDiscoveryActive = false
        onLog("Continuous WiFi Direct discovery stopped")
    }

    @SuppressLint("MissingPermission")
    fun connectTo(device: WifiP2pDevice) {
        val config = WifiP2pConfig().apply { deviceAddress = device.deviceAddress }
        val connectStartedAtMs = System.currentTimeMillis()
        manager.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                val kickoffMs = System.currentTimeMillis() - connectStartedAtMs
                onLog("Connect initiated to ${device.deviceAddress} (kickoff ${kickoffMs}ms)")
            }

            override fun onFailure(reason: Int) = onLog("Connect failed: reason $reason")
        })
    }

    /**
     * Call from the WIFI_P2P_CONNECTION_CHANGED_ACTION receiver on every
     * connection-changed event (group formed, torn down, or otherwise
     * changed). Offers [info] to [connectionEvents] unconditionally — every
     * raw event, including redundant ones — so a background poller (density
     * rotation test) can observe every state change, then acts on the
     * group-formed case below only once per actual group, guarded by
     * [activeGroupKey].
     */
    fun onConnected(info: WifiP2pInfo) {
        connectionEvents.offer(info)
        if (!info.groupFormed) {
            activeGroupKey = null
            stopGroupMembershipPolling()
            stopServer()
            return
        }
        val groupKey = "${info.isGroupOwner}:${info.groupOwnerAddress?.hostAddress}"
        if (groupKey == activeGroupKey) {
            // Same group formation already being handled — WIFI_P2P_CONNECTION_
            // CHANGED_ACTION can fire more than once for one real connection.
            // Acting on it again would double-bind the transfer port (owner
            // side) or open a second redundant client socket.
            return
        }
        activeGroupKey = groupKey
        if (info.isGroupOwner) {
            runServer()
            // This device is group owner — start tracking how many clients
            // actually join the group. This is what measures real dense-venue
            // fan-in (BUILD_PLAN.md open decision #5); the rotation test below
            // only measures this device's own connect/teardown cost as a
            // client, not fan-in as an owner.
            startGroupMembershipPolling()
        } else {
            stopGroupMembershipPolling()
            val host = info.groupOwnerAddress?.hostAddress ?: return
            runClient(host)
        }
    }

    /**
     * Sends and receives over [socket] concurrently, independent of which
     * side WiFi Direct negotiated as group owner vs client. Group-owner
     * assignment isn't something either device controls, but the previous
     * version tied "who sends" to "who is client" — so a picked post queued
     * on the device that happened to become owner was silently never sent,
     * while the other device (with nothing queued) sent the synthetic
     * fallback payload instead. That looked like "media transfer doesn't
     * work" from the outside; it was really "media transfer only works for
     * whichever side wins a coin flip you can't see."
     *
     * Running send and receive as separate threads on the same socket is
     * standard full-duplex usage (Java's socket input/output streams are
     * independent), and avoids a write-write deadlock if both sides happen
     * to be sending a large (10-30MB) payload at the same time.
     */
    private fun handleConnection(socket: Socket) {
        Thread({ sendPayload(socket) }, "hop-send").start()
        Thread({ receivePayload(socket) }, "hop-receive").start()
    }

    /**
     * De-risks BUILD_PLAN.md open decision #5 ("WiFi Direct at N-peer
     * density") from the rotation-cost side: since a device can only be in
     * one active P2P group at a time, reaching many peers means sequentially
     * connect -> transfer -> teardown -> connect-next. This measures the cost
     * of that cycle against [peers] one at a time, on a background thread.
     *
     * This does NOT measure group-owner fan-in (how many peers can
     * concurrently join a group this device owns) — see
     * [startGroupMembershipPolling] for that, which is wired automatically
     * via [onConnected] whenever this device becomes group owner during a
     * real multi-device test.
     *
     * Note: this only produces real numbers when run against real nearby
     * WiFi Direct peers — there is no software simulation of multiple
     * devices here, and none is possible for WiFi Direct without real radios.
     */
    @SuppressLint("MissingPermission")
    fun runDensityRotationTest(peers: List<WifiP2pDevice>, perPeerTimeoutMs: Long = 20_000) {
        if (peers.isEmpty()) {
            onLog("Density rotation test: no peers to test")
            return
        }
        Thread {
            onLog("Density rotation test starting: ${peers.size} peer(s), ${perPeerTimeoutMs}ms timeout each")
            // Ensure a clean slate before the first attempt. If this device is
            // already in a group (e.g. left over from an earlier manual "Send
            // to Nearby Phone" test), connecting to that same peer again won't
            // produce a fresh WIFI_P2P_CONNECTION_CHANGED_ACTION — nothing
            // actually changes — so the poll() below would time out on a peer
            // that's genuinely reachable. Observed on real hardware: the first
            // peer in the list was already connected, and the test reported a
            // false "FAILED or timed out" for it.
            onLog("Density rotation test: clearing any existing group first")
            teardownGroup()
            connectionEvents.clear()
            val results = mutableListOf<DensityRotationResult>()
            for ((index, device) in peers.withIndex()) {
                connectionEvents.clear() // drop any stale event from a prior iteration
                val label = "[${index + 1}/${peers.size}] ${device.deviceName} ${device.deviceAddress}"

                // Re-discover rather than reusing the WifiP2pDevice snapshot passed
                // into this call. Observed on real hardware: right after a
                // teardownGroup(), the peer this device just disconnected from
                // drops out of the P2P framework's own scan cache ("WiFi Direct
                // peers found: 0") until a fresh discoverPeers() cycle repopulates
                // it — connecting with the stale object then fails immediately
                // with a generic error (reason 0), not a timeout. This also makes
                // each loop iteration a more honest measurement of real rotation
                // cost: a real multi-peer rotation would need to re-discover
                // between peers too, not reuse a scan from minutes earlier.
                onLog("Density rotation $label: refreshing peer list")
                val freshPeers = discoverPeersBlocking()
                val freshDevice = freshPeers.find { it.deviceAddress == device.deviceAddress }
                if (freshDevice == null) {
                    onLog("Density rotation $label: not visible in a fresh scan — skipping")
                    results += DensityRotationResult(device, success = false, connectSetupMs = 0)
                    continue
                }

                onLog("Density rotation $label: connecting")
                val startedAtMs = System.currentTimeMillis()
                connectTo(freshDevice)
                val info = try {
                    connectionEvents.poll(perPeerTimeoutMs, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    null
                }
                val elapsedMs = System.currentTimeMillis() - startedAtMs
                val success = info != null && info.groupFormed
                results += DensityRotationResult(device, success, elapsedMs)
                onLog(
                    "Density rotation $label: " +
                        if (success) "connected in ${elapsedMs}ms" else "FAILED or timed out after ${elapsedMs}ms"
                )
                teardownGroup()
            }
            logDensityRotationSummary(results)
        }.start()
    }

    /**
     * Kicks off a fresh WiFi Direct scan and blocks (on the caller's thread —
     * always called from the rotation test's own background thread, never the
     * main thread) for [scanWindowMs] to let it populate, then returns the
     * P2P framework's current peer list. Real scans are asynchronous with no
     * single "done" signal short of a WIFI_P2P_PEERS_CHANGED_ACTION broadcast,
     * which this class doesn't receive directly (MainActivity's receiver
     * does) — a fixed wait window is simpler and adequate for spike code.
     */
    @SuppressLint("MissingPermission")
    private fun discoverPeersBlocking(scanWindowMs: Long = 12_000): List<WifiP2pDevice> {
        manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {}
            override fun onFailure(reason: Int) =
                onLog("Density rotation: peer re-discovery failed to start: reason $reason")
        })
        Thread.sleep(scanWindowMs)
        val latch = CountDownLatch(1)
        var result: List<WifiP2pDevice> = emptyList()
        manager.requestPeers(channel) { list ->
            result = list.deviceList.toList()
            latch.countDown()
        }
        latch.await(3, TimeUnit.SECONDS)
        return result
    }

    /**
     * Tears down whatever group this device is currently in so the next peer
     * in [runDensityRotationTest] can be connected to cleanly. Waits for
     * removeGroup's callback, then a fixed ~2s settle delay — acceptable for
     * spike-code teardown timing, not something to carry into production.
     */
    private fun teardownGroup() {
        val callbackLatch = CountDownLatch(1)
        manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                onLog("Group removed")
                callbackLatch.countDown()
            }

            override fun onFailure(reason: Int) {
                onLog("Group removal failed: reason $reason")
                callbackLatch.countDown()
            }
        })
        callbackLatch.await(5, TimeUnit.SECONDS)
        Thread.sleep(2000)
    }

    private fun logDensityRotationSummary(results: List<DensityRotationResult>) {
        val successes = results.filter { it.success }
        onLog(
            "Density rotation test complete: attempted=${results.size} " +
                "successes=${successes.size} failures=${results.size - successes.size}"
        )
        if (successes.isNotEmpty()) {
            val times = successes.map { it.connectSetupMs }
            onLog(
                "Connect-setup time (successful attempts only): min=${times.min()}ms " +
                    "max=${times.max()}ms avg=%.0fms".format(times.average())
            )
        } else {
            onLog("No successful connections in this run — no timing stats to report")
        }
        onLog(
            "Note: this measures sequential rotation cost only. It does not measure " +
                "group-owner fan-in (how many peers can join one group concurrently) — " +
                "that requires another device connecting TO this one while it is group " +
                "owner; see group membership polling, started automatically via onConnected."
        )
    }

    /**
     * Measures the other half of BUILD_PLAN.md open decision #5: while this
     * device is (or might become) group owner, periodically logs how many
     * clients are actually in the group (`group.clientList.size`) — the real
     * ceiling on dense-venue fan-in, which nothing short of real hardware
     * with several other phones actually connecting can reveal. Started
     * automatically by [onConnected] when this device becomes group owner;
     * callers shouldn't normally need to invoke this directly.
     */
    @SuppressLint("MissingPermission")
    fun startGroupMembershipPolling(intervalMs: Long = 2000) {
        if (pollingGroupMembership) return
        pollingGroupMembership = true
        onLog("Group membership polling started (every ${intervalMs}ms)")
        Thread {
            while (pollingGroupMembership) {
                manager.requestGroupInfo(channel) { group ->
                    val clientCount = group?.clientList?.size ?: 0
                    val addresses = group?.clientList?.joinToString(", ") { it.deviceAddress } ?: ""
                    onLog("Group membership poll: $clientCount client(s)${if (addresses.isNotEmpty()) " [$addresses]" else ""}")
                }
                Thread.sleep(intervalMs)
            }
        }.start()
    }

    fun stopGroupMembershipPolling() {
        if (!pollingGroupMembership) return
        pollingGroupMembership = false
        onLog("Group membership polling stopped")
    }

    /**
     * Exercises the pick -> frame -> socket -> decode -> view pipeline on a
     * single device, via a plain TCP loopback connection on 127.0.0.1 instead
     * of a WiFi Direct group. Real peer discovery and connection negotiation
     * over BLE/WiFi Direct radios genuinely cannot be tested without a second
     * device — that's a hardware fact, not a software gap — but everything
     * downstream of "two sockets are connected" (Frame encode/decode, socket
     * transfer, file write, FileProvider, launching the system viewer) is
     * identical whether the socket came from WiFi Direct or localhost, so
     * this covers that whole path on one phone.
     *
     * Binds the server socket synchronously before starting the client
     * connect (rather than reusing [runServer]/[runClient] as separate fire-
     * and-forget calls), so there's no race between "server is listening" and
     * "client tries to connect" — unlike the real WiFi Direct flow, nothing
     * here signals readiness back to us.
     *
     * Note: [pendingClip] is shared state read by both directions' send, so
     * if a post was picked, BOTH the "server" and "client" side of this
     * loopback will send the same picked post — that's expected, not a bug;
     * it means you may see the system viewer launch twice.
     */
    fun runLoopbackTest() {
        Thread {
            try {
                ServerSocket(TRANSFER_PORT).use { server ->
                    onLog("Loopback test: listening on 127.0.0.1:$TRANSFER_PORT (no WiFi Direct, single device)")
                    Thread {
                        try {
                            val clientSocket = Socket()
                            clientSocket.connect(InetSocketAddress("127.0.0.1", TRANSFER_PORT), 5_000)
                            onLog("Loopback test: client side connected")
                            handleConnection(clientSocket)
                        } catch (e: Exception) {
                            onLog("Loopback test client error: ${e.message}")
                        }
                    }.start()

                    val serverSideSocket = server.accept()
                    onLog("Loopback test: server side accepted")
                    handleConnection(serverSideSocket)
                }
            } catch (e: Exception) {
                onLog("Loopback test error: ${e.message}")
            }
        }.start()
    }

    /**
     * Accepts and serves every client that joins this device's WiFi Direct
     * group, not just the first. The original version called `server.accept()`
     * once and let the `.use {}` block close the socket right after — fine for
     * a single pair of phones, but it meant a 3rd, 4th, ... phone joining the
     * same group (the group-owner fan-in case [startGroupMembershipPolling]
     * measures) would show up in the membership poll yet never actually
     * receive anything, since nothing was listening for their connection
     * anymore. Looping accept() and handing each socket to [handleConnection]
     * on its own threads fixes that — each client gets its own send/receive
     * pair, all reading the same [pendingClip]/synthetic-fallback state.
     */
    private fun runServer() {
        if (activeServerSocket != null) return
        Thread {
            try {
                val server = ServerSocket(TRANSFER_PORT)
                activeServerSocket = server
                onLog("Transfer server listening on port $TRANSFER_PORT")
                while (true) {
                    val socket = try {
                        server.accept()
                    } catch (e: SocketException) {
                        // Expected: stopServer() closes the socket to unblock accept()
                        // once this device's group is torn down. Not an error.
                        break
                    }
                    onLog("Transfer server: accepted a client (${socket.inetAddress?.hostAddress})")
                    handleConnection(socket)
                }
            } catch (e: Exception) {
                onLog("Server error: ${e.message}")
            } finally {
                activeServerSocket = null
                onLog("Transfer server stopped")
            }
        }.start()
    }

    /** Closes the listening socket (if any) to unblock [runServer]'s accept() loop and end it. */
    private fun stopServer() {
        activeServerSocket?.let {
            try {
                it.close()
            } catch (e: Exception) {
                onLog("Error closing transfer server: ${e.message}")
            }
        }
    }

    private fun runClient(hostAddress: String) {
        Thread {
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(hostAddress, TRANSFER_PORT), 15_000)
                handleConnection(socket)
            } catch (e: Exception) {
                onLog("Client error: ${e.message}")
            }
        }.start()
    }

    /**
     * Always writes something — a real queued post if one exists, otherwise
     * the original synthetic random payload (preserving "Connect + Transfer"
     * as a standalone pure-throughput test when nothing's been picked).
     * Always sending means [receivePayload] on the peer never has to block
     * waiting to find out whether anything is coming.
     */
    private fun sendPayload(socket: Socket) {
        val random = SecureRandom()
        val clip = pendingClip

        val kind: Int
        val payload: ByteArray
        val clipHash: ByteArray
        val contentType: ContentType
        if (clip != null) {
            kind = KIND_REAL_POST
            payload = clip.bytes
            clipHash = clip.clipHash
            contentType = clip.contentType
            onLog("Sending picked post ($contentType): ${payload.size} bytes")
        } else {
            kind = KIND_SYNTHETIC_TEST
            payload = ByteArray(TEST_PAYLOAD_BYTES).also { random.nextBytes(it) }
            clipHash = ByteArray(Frame.CLIP_HASH_SIZE).also { random.nextBytes(it) }
            contentType = ContentType.VIDEO // unused on the receiving end for synthetic payloads
            onLog("Nothing queued on this side — sending synthetic throughput-test payload: ${payload.size} bytes")
        }

        val frame = Frame(
            clipHash = clipHash,
            senderDeviceId = ByteArray(Frame.SENDER_DEVICE_ID_SIZE).also { random.nextBytes(it) },
            contentType = contentType,
            hopCount = 0,
            originatedAtMs = System.currentTimeMillis(),
            ttlSeconds = SPIKE_TTL_SECONDS,
            reachTier = ReachTier.LOCALITY,
            dontRelay = false,
            // Mechanical update for the version-2 wire format's new required fields
            // (see /protocol/WIRE_FORMAT.md). This spike still sends `payload`
            // unencrypted, same as before the v2 bump -- it does not adopt
            // EncryptedFrameCodec, since wiring real content encryption into this
            // throwaway harness is UI/feature work out of scope here (it "gets
            // replaced, not incrementally reskinned"). `keyIncluded = false` with a
            // zeroed key accurately reflects that: this frame carries no real CEK.
            keyIncluded = false,
            contentEncryptionKey = ByteArray(Frame.CONTENT_ENCRYPTION_KEY_SIZE),
            payload = payload,
        )
        val encoded = frame.encode()

        val out = DataOutputStream(socket.getOutputStream())
        val startedAtMs = System.currentTimeMillis()
        out.writeInt(kind)
        out.writeInt(encoded.size)
        out.write(encoded)
        out.flush()
        logThroughput("Sent", encoded.size, System.currentTimeMillis() - startedAtMs)
    }

    private fun receivePayload(socket: Socket) {
        val input = DataInputStream(socket.getInputStream())
        val kind = input.readInt()
        val startedAtMs = System.currentTimeMillis()
        val size = input.readInt()
        val buffer = ByteArray(size)
        input.readFully(buffer)
        val elapsedMs = System.currentTimeMillis() - startedAtMs
        logThroughput("Received", size, elapsedMs)

        val frame = Frame.decode(buffer)
        onLog(
            "Decoded frame: contentType=${frame.contentType} hopCount=${frame.hopCount} reachTier=${frame.reachTier} " +
                "dontRelay=${frame.dontRelay} payload=${frame.payload.size} bytes " +
                "(frame overhead ${size - frame.payload.size} bytes)"
        )

        if (kind == KIND_SYNTHETIC_TEST) {
            onLog("Received payload was a synthetic throughput-test payload, not a real post — not opening a viewer")
            return
        }

        // Write the decoded payload out so it can be handed to the system
        // photo/video viewer (cache-only — see RECEIVED_CLIPS_DIR and
        // res/xml/file_paths.xml).
        val extension = when (frame.contentType) {
            ContentType.PHOTO -> "jpg"
            ContentType.VIDEO -> "mp4"
        }
        val receivedDir = File(appContext.cacheDir, RECEIVED_CLIPS_DIR).apply { mkdirs() }
        val clipFile = File(receivedDir, "received_${System.currentTimeMillis()}.$extension")
        clipFile.writeBytes(frame.payload)
        onLog("Wrote received post to ${clipFile.absolutePath}")
        onClipReceived(clipFile, frame.contentType)
    }

    private fun logThroughput(verb: String, bytes: Int, elapsedMsRaw: Long) {
        val elapsedMs = elapsedMsRaw.coerceAtLeast(1)
        val throughputMbps = (bytes * 8.0 / 1_000_000) / (elapsedMs / 1000.0)
        onLog("$verb $bytes bytes in ${elapsedMs}ms (%.2f Mbps)".format(throughputMbps))
    }
}
