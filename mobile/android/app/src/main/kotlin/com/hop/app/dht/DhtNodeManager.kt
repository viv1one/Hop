package com.hop.app.dht

import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.hop.dht.DhtNode
import com.hop.dht.DhtUdpTransport
import com.hop.dht.NodeId
import com.hop.dht.PeerAddress
import com.hop.dht.RoutingTable
import com.hop.topics.TopicSubscription
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Owns this device's DHT participation: binds a UDP socket and constructs
 * [RoutingTable] + [DhtUdpTransport] + [DhtNode] (Phase 4 Slices 2-5), then
 * exposes a [TopicSubscription] (Phase 4 Slice 6) once started -- the app-side
 * lifecycle owner [TopicSubscription]'s own class doc explicitly deferred to
 * "a later slice's job." This is that slice.
 *
 * Mirrors [com.hop.transport.TransportManager]'s established shape closely on
 * purpose (see that class's own doc): same [ProcessLifecycleOwner]/
 * [DefaultLifecycleObserver] start/stop posture, same "open the app to see
 * what's nearby" MVP stance, no foreground service. **Named consequence,
 * carried over from that same precedent:** DHT participation -- and
 * therefore this device's Town/City/Country discoverability and its ability
 * to discover others -- stops the instant the app is backgrounded, exactly
 * like BLE/WiFi Direct already does. Revisit deliberately alongside any
 * future relay/store-and-forward work for reach tiers above Locality, don't
 * silently paper over it with a foreground service without re-deciding this.
 *
 * **Why this is a separate class from [com.hop.transport.TransportManager],
 * not folded into it:** that class owns Locality-tier, fully-offline BLE/WiFi
 * Direct mesh transport. This class owns the DHT -- Town/City/Country
 * topic-subscription only (ADR 0003: Locality never touches the DHT, and
 * nothing in this class or [TopicSubscription] ever resolves it). Keeping
 * them as two separate classes keeps that a structural fact (two different
 * construction sites, two different lifecycles) instead of a convention
 * someone has to remember inside one shared god class.
 *
 * **Bootstrap: explicitly deferred, not solved here.** See [maybeBootstrap]'s
 * own doc.
 *
 * **Address/NAT: no external-address discovery.** See [localBindAddress]'s
 * own doc.
 *
 * **[registerWithProcessLifecycle]:** `true` at every real construction site
 * (see [com.hop.app.AppContainer]). `false` is a test-only escape hatch --
 * [ProcessLifecycleOwner.get] requires a real Android app process
 * (content-provider-based auto-init) and cannot run in a plain JVM unit
 * test -- the same constraint that already keeps
 * [com.hop.transport.TransportManager] itself untested outside an
 * instrumented environment. Guarding the one line that actually touches
 * [ProcessLifecycleOwner] behind this flag is what lets `DhtNodeManagerTest`
 * exercise everything else here -- real [DatagramSocket]s, a real two-node
 * bootstrap/publish/browse round trip -- as a plain JVM test.
 */
class DhtNodeManager(
    /**
     * Seed bytes this device's [NodeId] is derived from
     * ([NodeId.fromKeyMaterial]). In production this is
     * `SettingsRepository.getOrCreateStableSenderDeviceId()` -- the exact
     * same per-install identity already reused for messaging/blocking (see
     * `com.hop.data.PeerIdentity`'s "one identity, reused everywhere" doc).
     * Deliberately NOT a fresh random id generated here: reusing the existing
     * stable id means this device's DHT node id, its messaging peer id, and
     * its Feed sender id are all derivable from one already-persisted value,
     * with no new identity concept introduced just for the DHT.
     */
    private val getOwnNodeIdSeed: suspend () -> ByteArray,
    /**
     * Dev/test-only bootstrap address (host, port). Blank host or port `0`
     * (the production default -- see `app/build.gradle.kts`'s
     * `DHT_BOOTSTRAP_HOST`/`DHT_BOOTSTRAP_PORT` `BuildConfig` fields) means
     * "don't attempt bootstrapJoin at all." See [maybeBootstrap]'s doc for
     * why this is the deliberate call made here, not a real rendezvous node.
     */
    private val bootstrapHost: String,
    private val bootstrapPort: Int,
    registerWithProcessLifecycle: Boolean = true,
) : DefaultLifecycleObserver {

    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var transport: DhtUdpTransport? = null
    @Volatile private var node: DhtNode? = null
    @Volatile private var topicSubscription: TopicSubscription? = null
    @Volatile private var nodeScope: CoroutineScope? = null
    @Volatile private var starting = false

    /**
     * This device's own currently-bound [PeerAddress], once [start] has
     * finished (or `null` before then / after [stop]). Not consumed by any
     * production code path today -- exposed primarily so a test can exercise
     * a real two-node bootstrap round trip end-to-end without a real
     * `rendezvous/` node to join through (see `DhtNodeManagerTest`).
     */
    @Volatile var ownAddress: PeerAddress? = null
        private set

    init {
        if (registerWithProcessLifecycle) {
            ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        }
    }

    override fun onStart(owner: LifecycleOwner) = start()

    override fun onStop(owner: LifecycleOwner) = stop()

    /**
     * Idempotent: a no-op if already started or already starting. Does real
     * socket I/O (bind + [getOwnNodeIdSeed]'s own I/O, e.g. a DataStore read
     * in production) -- launched onto a fresh [Dispatchers.IO]-backed
     * [CoroutineScope], never on the caller's thread. This matters because
     * [onStart] is invoked synchronously on the main thread by
     * [ProcessLifecycleOwner], the same constraint
     * [com.hop.transport.TransportManager.start] documents for itself.
     *
     * [topicSubscription] (and therefore [awaitTopicSubscription] returning
     * non-null) is only set AFTER [maybeBootstrap] finishes, deliberately --
     * so a caller that gets a non-null [TopicSubscription] back can rely on
     * this device's routing table already reflecting whatever bootstrapJoin
     * managed to learn (or definitively didn't), rather than racing an
     * in-flight bootstrap attempt.
     */
    fun start() {
        if (node != null || starting) return
        starting = true
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        nodeScope = scope
        scope.launch {
            try {
                val ownId = NodeId.fromKeyMaterial(getOwnNodeIdSeed())
                val boundSocket = DatagramSocket(0)
                val boundAddress = PeerAddress.from(localBindAddress(), boundSocket.localPort)
                val routingTable = RoutingTable(ownId = ownId)
                val dhtTransport = DhtUdpTransport(boundSocket, ownId)
                val dhtNode = DhtNode(routingTable, dhtTransport, scope, boundAddress)
                dhtTransport.start()

                socket = boundSocket
                transport = dhtTransport
                node = dhtNode
                ownAddress = boundAddress

                maybeBootstrap(dhtNode)

                topicSubscription = TopicSubscription(dhtNode)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start DHT node", e)
            } finally {
                starting = false
            }
        }
    }

    /**
     * Attempts [DhtNode.bootstrapJoin] ONLY when [bootstrapHost] is
     * non-blank and [bootstrapPort] is non-zero -- both are
     * `BuildConfig`-sourced and unset (blank/`0`) in every production build
     * (see this class's own constructor doc).
     *
     * **This is this slice's explicit, documented answer to the bootstrap
     * problem, not a silent gap.** `rendezvous/` (ADR 0002) is still an
     * empty module -- no real bootstrap/rendezvous node exists to join
     * through yet, and BUILD_PLAN.md treats recruiting a real bootstrap
     * operator as a launch condition, not something to fake in application
     * code. Until that exists, a device's [DhtNode] simply never calls
     * [DhtNode.bootstrapJoin] automatically in production; it only becomes
     * reachable from another device once it learns of a peer some other way
     * (e.g. a future peer-exchange path once a device has any live peer --
     * ADR 0002's own phase-out plan -- or, for local development/testing
     * only, a developer manually pointing `DHT_BOOTSTRAP_HOST`/
     * `DHT_BOOTSTRAP_PORT` at a known test node's address via a local Gradle
     * property override). A [DhtNode] with an empty routing table is not
     * "broken" by this -- [TopicSubscription.publish]/[TopicSubscription.browse]
     * simply find nothing beyond this device's own local self-registration
     * until it's introduced to at least one peer -- but it does mean nothing
     * here makes this device discoverable over the internet-mode DHT by
     * itself yet. Revisit the moment a real `rendezvous/` node exists; don't
     * quietly grow a second, competing bootstrap mechanism instead of
     * finishing that one.
     *
     * Any failure (unreachable address, DNS failure, timeout) is caught and
     * logged, never rethrown -- an unreachable/misconfigured dev bootstrap
     * address must not crash the DHT node or block it from otherwise coming
     * up standalone. This is deliberately biased toward the low-density,
     * near-broken-chain case (no peer to join through at all) over the
     * dense-venue happy path.
     */
    private suspend fun maybeBootstrap(dhtNode: DhtNode) {
        if (bootstrapHost.isBlank() || bootstrapPort == 0) {
            Log.d(TAG, "No bootstrap address configured -- this DHT node will only learn of peers some other way (see maybeBootstrap's doc)")
            return
        }
        try {
            val address = PeerAddress.from(InetAddress.getByName(bootstrapHost), bootstrapPort)
            val discovered = dhtNode.bootstrapJoin(address)
            Log.d(TAG, "Bootstrap join via $bootstrapHost:$bootstrapPort discovered ${discovered.size} contact(s)")
        } catch (e: Exception) {
            Log.e(TAG, "Bootstrap join failed -- continuing without one", e)
        }
    }

    /**
     * Suspends until this device's own [TopicSubscription] is ready (see
     * [start]'s doc for exactly what "ready" means), or `null` after
     * [timeoutMs] elapses (e.g. [start] was never called, is still
     * mid-flight, or [stop] tore it down). Plain bounded polling, not a
     * [kotlinx.coroutines.CompletableDeferred] -- deliberately simple: this
     * is a short best-effort wait for a fast local socket bind plus one
     * bootstrap attempt, not a long-lived synchronization primitive.
     */
    suspend fun awaitTopicSubscription(timeoutMs: Long = DEFAULT_READY_TIMEOUT_MS): TopicSubscription? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (topicSubscription == null && System.currentTimeMillis() < deadline) {
            delay(POLL_INTERVAL_MS)
        }
        return topicSubscription
    }

    /**
     * Stops the receive thread, closes the socket, and cancels this node's
     * coroutine scope. Idempotent. Matches
     * [com.hop.transport.TransportManager.stop]'s posture: no attempt to
     * gracefully deregister from the network first -- no such RPC exists in
     * this protocol; a Kademlia node simply stops answering.
     */
    fun stop() {
        transport?.stop()
        nodeScope?.cancel()
        socket = null
        transport = null
        node = null
        topicSubscription = null
        nodeScope = null
        ownAddress = null
    }

    companion object {
        private const val TAG = "DhtNodeManager"
        private const val DEFAULT_READY_TIMEOUT_MS = 3_000L
        private const val POLL_INTERVAL_MS = 25L

        /**
         * Picks *a* locally-bound, non-loopback IPv4 address for this
         * device's [DhtNode.ownAddress] -- falls back to loopback if none is
         * found (e.g. airplane mode, no active network interface).
         *
         * **This is NOT NAT traversal or external-address discovery** -- see
         * [DhtNode]'s own `ownAddress` constructor-parameter doc, which
         * states plainly that no such mechanism exists in this codebase yet.
         * A device behind NAT or on a cellular network is not actually
         * reachable at this address from outside its own local
         * network/subnet; this only helps devices on the same LAN/subnet
         * find each other directly. Real internet-mode reachability across
         * NAT boundaries needs a materially bigger, separate addition
         * (STUN/TURN-style hole punching, or a relay-assisted rendezvous) --
         * carried forward as an explicit limitation, not solved here,
         * matching how this exact gap is already flagged in `dht/`'s own
         * code.
         */
        private fun localBindAddress(): InetAddress {
            val interfaces = runCatching { Collections.list(NetworkInterface.getNetworkInterfaces()) }.getOrDefault(emptyList())
            for (iface in interfaces) {
                val isUp = runCatching { iface.isUp }.getOrDefault(false)
                if (!isUp || iface.isLoopback) continue
                val addresses = runCatching { Collections.list(iface.inetAddresses) }.getOrDefault(emptyList())
                for (address in addresses) {
                    if (address is Inet4Address && !address.isLoopbackAddress) return address
                }
            }
            return InetAddress.getLoopbackAddress()
        }
    }
}
