package com.hop.app.dht

import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.hop.dht.Contact
import com.hop.dht.DhtNode
import com.hop.dht.DhtUdpTransport
import com.hop.dht.IntroduceResult
import com.hop.dht.NodeId
import com.hop.dht.PeerAddress
import com.hop.dht.RoutingTable
import com.hop.p2p.PeerChannel
import com.hop.p2p.PeerListener
import com.hop.topics.TopicSubscription
import java.io.IOException
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
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
 * **Inbound internet-mode TCP (the [PeerListener] wiring):** this class also
 * owns the one [PeerListener] this device runs, bound to **the exact same
 * port number** [socket] (the UDP DHT socket) got from `DatagramSocket(0)` --
 * see [start]'s own doc for why that's not a coincidence, and why it needs no
 * change to [Contact]/`dht/`'s wire format at all. TCP and UDP are
 * independent port namespaces on the same host; sharing the port number is
 * purely a convenience so this device's one advertised [PeerAddress] (already
 * used for DHT FIND_NODE/introduction/[RelayDirectory] purposes) is *also*
 * where a peer dialing in for content
 * ([com.hop.transport.InternetPeerConnection.connectTo]) will actually find
 * something listening -- before [PeerListener] existed, nothing did, making
 * every such dial silently unreachable. See [start]'s own doc for exactly
 * where this bind happens and how a bind failure is handled (never fatal to
 * DHT participation), and
 * [com.hop.transport.InternetPeerConnectionManager]'s own `acceptInbound` doc
 * for what happens to an accepted connection on the other side of
 * [onInboundInternetConnection].
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
    /**
     * The last piece of the Phase 4 hole-punching thread: called with a
     * [Contact] built from whatever [DhtNode.onIntroductionReceived] just
     * fired with, whenever a rendezvous/DHT node relays an unsolicited
     * introduction naming this device's peer and its self-reported address
     * (see `IntroductionMessage.kt`'s own file doc in `dht/`). In production
     * (see [com.hop.app.AppContainer]) this is
     * `InternetPeerConnectionManager.connectToIntroducedPeer` -- a received
     * introduction is treated as just another way of learning about a
     * [Contact] worth trying, same dedup/cap posture a DHT topic browse
     * result already uses via `connectToDiscoveredHolders`. [relayId]/
     * [relayAddress] are Phase 4's relay-fallback-coordination addition
     * (see [IntroduceResult]'s own doc): the same relay suggestion R handed
     * this device alongside the introduction itself, both `null` together
     * if R had none to suggest -- passed straight through so the direct-dial
     * fallback can attempt a relay bridge without a second round trip to R.
     * Defaults to a no-op for tests that have no
     * [com.hop.transport.InternetPeerConnectionManager] to hand it.
     */
    private val connectToIntroducedPeer: suspend (contact: Contact, relayId: NodeId?, relayAddress: PeerAddress?) -> Unit = { _, _, _ -> },
    /**
     * Called, synchronously on [PeerListener]'s own `hop-peer-listener-accept`
     * thread, with the [PeerChannel] for every inbound internet-mode TCP
     * connection this device accepts (see [PeerListener]'s own doc for the
     * accept-loop/callback contract this class relies on unchanged). In
     * production (see [com.hop.app.AppContainer])
     * this is `InternetPeerConnectionManager::acceptInbound` -- see that
     * method's own doc for what "handling" an inbound connection actually
     * means (connect-time backlog offer, receive-loop start, live-relay
     * fanout participation), none of which this class knows or needs to know
     * anything about. Defaults to a no-op for every test that has no
     * `InternetPeerConnectionManager` to hand it -- an accepted inbound
     * connection is then simply wrapped in a [PeerChannel] and otherwise
     * ignored, matching [PeerListener]'s own "caller's job" posture.
     */
    private val onInboundInternetConnection: (channel: PeerChannel) -> Unit = {},
    registerWithProcessLifecycle: Boolean = true,
) : DefaultLifecycleObserver {

    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var transport: DhtUdpTransport? = null
    @Volatile private var node: DhtNode? = null
    @Volatile private var topicSubscription: TopicSubscription? = null
    @Volatile private var nodeScope: CoroutineScope? = null
    @Volatile private var starting = false

    /**
     * This device's inbound internet-mode TCP listener, once [start] has
     * (successfully) bound it -- `null` before then, after [stop], or
     * permanently for the lifetime of one session if the TCP bind itself
     * failed (see [start]'s own doc for exactly how that failure is handled:
     * logged, never fatal to DHT participation or outbound dialing).
     */
    @Volatile private var peerListener: PeerListener? = null

    /**
     * This device's own currently-bound [PeerAddress], once [start] has
     * finished (or `null` before then / after [stop]). Not consumed by any
     * production code path today -- exposed primarily so a test can exercise
     * a real two-node bootstrap round trip end-to-end without a real
     * `rendezvous/` node to join through (see `DhtNodeManagerTest`).
     */
    @Volatile var ownAddress: PeerAddress? = null
        private set

    /**
     * This device's own [NodeId], once [start] has finished (or `null`
     * before then / after [stop]). Phase 4's relay-fallback-coordination
     * addition: `com.hop.transport.InternetPeerConnectionManager` needs this
     * device's own id for the `[32B ownId][32B bridgeToId]` handshake a
     * volunteer relay (`tools/relay-node/`'s `RelayNode`) expects -- see
     * that class's own "Wire shape" doc.
     */
    @Volatile var ownNodeId: NodeId? = null
        private set

    /**
     * The one rendezvous/DHT contact this device knows how to introduce
     * through, once [maybeBootstrap] has (successfully) run -- `null` before
     * that, if bootstrap is disabled (every production build today -- see
     * [maybeBootstrap]'s own doc), or if it failed. **This is the answer to
     * "which contact do we introduce through" for
     * `InternetPeerConnectionManager.connectToDiscoveredHolders`'s own
     * relay-fallback trigger (Phase 4): reuse the same bootstrap node
     * [maybeBootstrap] already joins via, captured as a real [Contact] here,
     * rather than inventing a second, separate "known rendezvous contacts"
     * concept.** See [maybeBootstrap]'s own doc for exactly how this is
     * captured -- no new `dht/` lookup primitive was needed for it.
     */
    @Volatile var rendezvousContact: Contact? = null
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
     *
     * **Also binds this device's inbound internet-mode TCP listener
     * ([peerListener]), on the exact same port number [boundSocket] (the UDP
     * DHT socket) got from `DatagramSocket(0)`.** TCP and UDP are independent
     * port namespaces on the same host, so this is a free, deliberate choice,
     * not a conflict -- it means the single [PeerAddress] this device already
     * advertises everywhere ([boundAddress], used for DHT FIND_NODE
     * responses, address reflection, introductions, and
     * [com.hop.dht.RelayDirectory]) is *also* the address a peer dialing in
     * via [com.hop.transport.InternetPeerConnection.connectTo] will actually
     * find something listening on -- **no change to [Contact] or any `dht/`
     * wire format was needed for this.** See [startPeerListener]'s own doc
     * for exactly how a bind failure is handled (never fatal to this
     * function or to DHT participation generally).
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
                // DhtNode's ownAddresses is a List<PeerAddress> (Phase 4's IPv6-first/
                // dual-stack slice) -- this device only has one bound address today
                // (see localBindAddress's own doc: no IPv6/dual-stack address selection
                // here yet), so wrap it as a single-entry list rather than inventing a
                // parallel single-address convenience constructor.
                val dhtNode = DhtNode(
                    routingTable,
                    dhtTransport,
                    scope,
                    listOf(boundAddress),
                    onIntroductionReceived = { fromId, claimedAddress, relayId, relayAddress ->
                        // Fires synchronously from DhtUdpTransport's receive
                        // thread, not a coroutine -- scope.launch is required
                        // to call the suspend connectToIntroducedPeer lambda
                        // at all. Reuses `scope` (this function's own
                        // CoroutineScope), never a second, separate one.
                        val contact = Contact(
                            id = fromId,
                            address = claimedAddress.encode(),
                            lastSeenAtMs = System.currentTimeMillis(),
                        )
                        scope.launch { connectToIntroducedPeer(contact, relayId, relayAddress) }
                    },
                )
                dhtTransport.start()

                // Order relative to DhtNode construction above doesn't matter
                // (the two are independent) -- this just needs boundSocket's
                // own port number, already known by this point. See
                // startPeerListener's own doc for the bind-failure posture.
                val listener = startPeerListener(boundSocket.localPort)

                socket = boundSocket
                transport = dhtTransport
                node = dhtNode
                ownAddress = boundAddress
                ownNodeId = ownId
                peerListener = listener

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
     * Binds a wildcard [ServerSocket] to [port] (the exact port number the
     * UDP DHT socket already got from `DatagramSocket(0)` -- see [start]'s
     * own doc for why sharing the number, not the socket, is what's shared),
     * wraps it in a [PeerListener] wired to [onInboundInternetConnection],
     * starts it, and returns it -- or returns `null`, logged, if the bind
     * itself fails.
     *
     * **Deliberately wildcard-bound** (`ServerSocket(port)`, no explicit bind
     * address), matching [DatagramSocket(0)]'s own wildcard-bind posture --
     * [localBindAddress] is only ever used to build the *advertised*
     * [PeerAddress], never to restrict what this device actually binds to
     * (see that function's own doc).
     *
     * **A bind failure here is caught, logged, and never rethrown or allowed
     * to fail [start].** TCP and UDP are independent port-allocation
     * namespaces, so this is a genuinely different failure mode from the UDP
     * bind succeeding -- rare in practice (something else would have to hold
     * that exact port number for TCP specifically), but possible, and this
     * function's contract is that it must never be the reason a DHT node
     * fails to come up. A device that hits this failure simply cannot accept
     * inbound internet-mode connections for this session -- DHT participation
     * (routing table, publish/browse) and outbound dialing
     * ([com.hop.transport.InternetPeerConnection.connectTo]) are both
     * completely unaffected, since neither depends on this listener existing.
     */
    private fun startPeerListener(port: Int): PeerListener? {
        return try {
            val serverSocket = ServerSocket(port)
            val listener = PeerListener(serverSocket, onConnected = onInboundInternetConnection)
            listener.start()
            listener
        } catch (e: IOException) {
            Log.e(TAG, "Failed to bind inbound internet-mode TCP listener on port $port -- this device will not be able to accept inbound internet connections this session (DHT participation and outbound dialing are unaffected)", e)
            null
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
     *
     * **Also captures [rendezvousContact] on success** -- Phase 4's relay-
     * fallback-coordination slice's answer to "which contact do we introduce
     * through" (see that property's own doc). [DhtNode.bootstrapJoin]'s own
     * PING step already causes [DhtUdpTransport.onMessageObserved] (wired to
     * [DhtNode.observe] in [DhtNode]'s own `init` block) to record a
     * correctly-id'd [RoutingTable] entry for the bootstrap node itself --
     * keyed under its REAL [NodeId], not the zero-id placeholder
     * [DhtNode.bootstrapJoin] only ever hands to [DhtUdpTransport.ping] --
     * strictly before that PING call can return (`handlePacket`'s `PONG`
     * case calls `observe` before completing the pending request). Scanning
     * [DhtNode.routingTable] for the one entry whose stored address matches
     * the bootstrap address just dialed recovers that real [Contact] with
     * zero new `dht/` lookup-by-address primitive -- [RoutingTable.findClosest]
     * is already public and, at a large enough `count`, simply returns every
     * known contact sorted by XOR distance to the id supplied (that id's
     * own identity is irrelevant here; only the full result list is used).
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

            val addressBytes = address.encode()
            rendezvousContact = dhtNode.routingTable
                .findClosest(dhtNode.routingTable.ownId, Int.MAX_VALUE)
                .firstOrNull { it.address.contentEquals(addressBytes) }
        } catch (e: Exception) {
            Log.e(TAG, "Bootstrap join failed -- continuing without one", e)
        }
    }

    /**
     * Phase 4's client-side relay-fallback trigger's rendezvous-introduce
     * primitive: asks [rendezvousContact] to introduce this device to
     * [targetId], returning whatever [IntroduceResult] comes back (the
     * target's address plus, per [IntroduceResult]'s own doc, whatever relay
     * suggestion the rendezvous node chose to pair with it) -- or `null`,
     * logged and swallowed, whenever this device has no [rendezvousContact]
     * to ask (every production build today, per [maybeBootstrap]'s own
     * doc -- this always returns `null` in production until a real
     * `rendezvous/` node is actually deployed, a real, current, already-
     * flagged limitation, not something this slice solves), the DHT node
     * hasn't finished starting yet, or [DhtUdpTransport.reflectOwnAddress]
     * doesn't get an answer back from [rendezvousContact] in time.
     *
     * Called by `com.hop.transport.InternetPeerConnectionManager` as its own
     * relay-fallback trigger's rendezvous-introduce step -- see that class's
     * own doc.
     */
    suspend fun introduceViaRendezvous(targetId: NodeId): IntroduceResult? {
        val dhtTransport = transport ?: return null
        val via = rendezvousContact ?: return null
        val ownReflectedAddress = dhtTransport.reflectOwnAddress(via) ?: return null
        return dhtTransport.introduce(via, targetId, ownReflectedAddress)
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
     * Stops the receive thread, closes the socket, stops [peerListener] (if
     * this session's TCP bind actually succeeded -- a no-op via `?.` if it
     * didn't), and cancels this node's coroutine scope. Idempotent. Matches
     * [com.hop.transport.TransportManager.stop]'s posture: no attempt to
     * gracefully deregister from the network first -- no such RPC exists in
     * this protocol; a Kademlia node simply stops answering.
     */
    fun stop() {
        transport?.stop()
        peerListener?.stop()
        nodeScope?.cancel()
        socket = null
        transport = null
        node = null
        topicSubscription = null
        nodeScope = null
        ownAddress = null
        ownNodeId = null
        rendezvousContact = null
        peerListener = null
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
