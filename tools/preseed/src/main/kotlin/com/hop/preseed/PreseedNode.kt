package com.hop.preseed

import com.hop.crypto.DecayKeyStore
import com.hop.dht.DhtNode
import com.hop.dht.DhtUdpTransport
import com.hop.dht.NodeId
import com.hop.dht.PeerAddress
import com.hop.dht.RoutingTable
import com.hop.p2p.PeerListener
import com.hop.topics.TopicSubscription
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

/**
 * BUILD_PLAN.md Phase 4's internet-mode pre-seeding tool (memo §8 Phase 2
 * GTM): a standalone, operator-run JVM process that pushes a starter batch
 * of clips into a new venue/city ahead of local user arrival, so a real
 * device's first browse of that area's DHT topics finds something. **This is
 * operator-run tooling, never a HOP-operated hosted service** -- same
 * "volunteer/partner runs it, HOP never does" posture as
 * `tools/relay-node/`'s `RelayNode` (see that class's own class doc): it
 * joins the same public DHT any real client joins, through the same
 * address-only `rendezvous/` bootstrap carve-out (ADR 0002) any real client
 * uses, and speaks nothing but the real, versioned wire protocol every other
 * peer already speaks. Nothing about this class gives HOP (or whoever runs
 * it) a content, discovery, or message-delivery path a real client doesn't
 * already have by design -- it is, deliberately, "essentially a headless,
 * content-serving sibling of [com.hop.app.dht.DhtNodeManager]" (this task's
 * own framing), not a new kind of privileged node.
 *
 * ## What this class does
 *
 * Mirrors [com.hop.app.dht.DhtNodeManager]'s own shape closely, minus the
 * Android `ProcessLifecycleOwner`/`DefaultLifecycleObserver` wiring this
 * plain-JVM tool has no use for: binds a UDP socket and constructs
 * [RoutingTable] + [DhtUdpTransport] + [DhtNode], attempts [DhtNode.bootstrapJoin]
 * against the `rendezvous/`-or-equivalent address supplied at construction,
 * exposes a [TopicSubscription] once ready, and binds a [PeerListener] on
 * the exact same port number the UDP socket got (same reasoning
 * [com.hop.app.dht.DhtNodeManager.start]'s own doc gives: TCP and UDP are
 * independent port namespaces, so this is a free convenience, not a
 * coincidence -- it means this tool's one advertised [PeerAddress] is also
 * where a peer dialing in for content will actually find something
 * listening). [publishSeededContent] then announces this node as a holder
 * for every seeded clip's target cell via [TopicSubscription.publish].
 *
 * **Unlike [com.hop.app.dht.DhtNodeManager], a TCP bind failure here IS
 * fatal** ([start] lets it propagate rather than logging and continuing).
 * `DhtNodeManager`'s app can still usefully browse/publish as a client with
 * no inbound listener; this tool's entire purpose is being dialed into --
 * without a working [PeerListener] it has no reason to run at all.
 *
 * ## Device identity: no attestation, and that's a documented existing fact, not a gap this tool works around
 *
 * ADR 0004 binds device identity to hardware attestation (Play Integrity /
 * App Attest) for exactly three things: token faucet caps, "don't relay"
 * signal-counting, and blocking -- all *sender/flagger*-side controls this
 * tool never exercises (it never posts as if it were a real user broadcasting
 * to nearby peers, never flags, never blocks). The one identity-adjacent
 * decision this tool DOES make -- whether to release a Town/City/Country
 * post's decryption key to a connecting peer -- is
 * [com.hop.protocol.ReachTierKeyDistribution.releaseKeyFor], and that
 * function (see its own doc, and [PreseedContentServer.handleTierKeyRequest]
 * which calls it unchanged) checks only the REQUESTER's self-asserted
 * [com.hop.protocol.TierMembershipClaim] (geohash prefix + timestamp,
 * "locally-verifiable... not a server-verified one -- no server exists to
 * ask," per ADR 0003) -- it never inspects, and has no mechanism to inspect,
 * anything about the RESPONDER's own identity or attestation status. A real
 * user's phone answering the exact same request goes through the identical
 * unattested code path. This tool therefore needs no attestation token to
 * function correctly, and does not fake one -- [ownNodeIdSeed] is a plain,
 * unattested, per-run seed for this device's Kademlia [NodeId], same
 * lightweight posture `tools/relay-node/`'s own node-id handling already
 * has, used ONLY for DHT routing/addressing, never as an input to any
 * access-control decision.
 *
 * **Flagged, not silently papered over:** this means the current protocol
 * has zero attested-identity gate anywhere in the tier-key-RELEASE path --
 * only sender-side controls (faucets, don't-relay counting, rate limiting)
 * are attestation-gated today. That is a pre-existing property of
 * [com.hop.protocol.ReachTierKeyDistribution] this tool inherits and does
 * not change, worth surfacing explicitly per this codebase's own habit of
 * stating limits plainly rather than letting them go unstated.
 *
 * ## Decay is real, not waived
 *
 * Every [SeedClip] this node serves was packaged by [ClipPackager] with a
 * real, finite `ttlSeconds` (CLI-configurable, see [PreseedCli]) -- this
 * class adds no special-cased "seeded content never decays" behavior
 * anywhere. Once a clip's decay window closes, [PreseedContentServer]'s own
 * [com.hop.protocol.ReachTierKeyDistribution.releaseKeyFor] call denies the
 * key exactly like it would for any other expired post (see
 * [DecayKeyStore]'s own enforcement-by-deletion-on-read mechanism) -- this
 * tool still offers the (now-undecryptable) [SeedClip.encodedFrame] as
 * backlog even past decay, matching how a real device's own `PostEntity`
 * row still exists and still renders as `DecryptResult.Decayed` past its
 * key's expiry, rather than the ciphertext vanishing outright (ADR 0003:
 * decay means the key becomes unrecoverable, not that the DHT-stored
 * ciphertext disappears).
 */
class PreseedNode(
    /** Plain, unattested per-run seed for this node's Kademlia [NodeId] -- see this class's own "Device identity" doc for why no attestation is needed here. */
    private val ownNodeIdSeed: ByteArray,
    private val bootstrapHost: String,
    private val bootstrapPort: Int,
    private val seededClips: List<SeedClip>,
    private val decayKeyStore: DecayKeyStore,
    /** `0` (the default) binds an ephemeral port, matching `DatagramSocket(0)`'s own convention elsewhere in this codebase. */
    private val bindPort: Int = 0,
    private val onLog: (String) -> Unit = {},
) {
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var transport: DhtUdpTransport? = null
    @Volatile private var node: DhtNode? = null
    @Volatile private var peerListener: PeerListener? = null
    @Volatile private var nodeScope: CoroutineScope? = null

    /** This node's own bound [PeerAddress], once [start] has finished (or `null` before then / after [stop]). */
    @Volatile var ownAddress: PeerAddress? = null
        private set

    /** This node's own [NodeId], once [start] has finished (or `null` before then / after [stop]). */
    @Volatile var ownNodeId: NodeId? = null
        private set

    /** Ready once [start] has finished (or `null` before then / after [stop]) -- see [publishSeededContent], the only other caller that needs this. */
    @Volatile var topicSubscription: TopicSubscription? = null
        private set

    /**
     * Binds the UDP DHT socket and the TCP [PeerListener] (same port
     * number), constructs [DhtNode], attempts [DhtNode.bootstrapJoin]
     * against `(`[bootstrapHost]`, `[bootstrapPort]`)` (logged and swallowed
     * on failure -- an unreachable/misconfigured bootstrap address must not
     * prevent this node from otherwise coming up, same posture
     * [com.hop.app.dht.DhtNodeManager.maybeBootstrap] already establishes;
     * this is the low-density/near-broken-chain case this codebase always
     * treats as the real case to design for), and sets [topicSubscription].
     *
     * Suspends until fully ready -- unlike
     * [com.hop.app.dht.DhtNodeManager.start] (which cannot suspend the
     * Android lifecycle callback that calls it, and so launches its own
     * background coroutine and exposes readiness via polling instead), this
     * is a plain CLI tool with no such constraint: a direct suspend function
     * is simpler for both [PreseedCli]'s `main()` and this module's own
     * tests to drive.
     */
    suspend fun start() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        nodeScope = scope

        val ownId = NodeId.fromKeyMaterial(ownNodeIdSeed)
        val boundSocket = DatagramSocket(bindPort)
        val boundAddress = PeerAddress.from(localBindAddress(), boundSocket.localPort)
        val routingTable = RoutingTable(ownId = ownId)
        val dhtTransport = DhtUdpTransport(boundSocket, ownId)
        val dhtNode = DhtNode(routingTable, dhtTransport, scope, listOf(boundAddress))
        dhtTransport.start()

        // Deliberately NOT caught here -- see this class's own doc for why a
        // TCP bind failure is fatal for this tool, unlike
        // com.hop.app.dht.DhtNodeManager's own "never fatal" posture for its
        // app (which can still usefully run without inbound connections).
        val contentServer = PreseedContentServer(seededClips, decayKeyStore, onLog)
        val serverSocket = ServerSocket(boundSocket.localPort)
        val listener = PeerListener(serverSocket, onConnected = contentServer::acceptInbound)
        listener.start()

        socket = boundSocket
        transport = dhtTransport
        node = dhtNode
        peerListener = listener
        ownAddress = boundAddress
        ownNodeId = ownId

        maybeBootstrap(dhtNode)

        topicSubscription = TopicSubscription(dhtNode)
    }

    /**
     * Announces this node as a holder for every distinct
     * (latitude, longitude, reachTier) target among [seededClips], via
     * [TopicSubscription.publish] -- deduplicated so two clips seeded at the
     * exact same cell/tier don't announce twice. Must be called after
     * [start] has finished ([topicSubscription] is set); throws
     * [IllegalStateException] otherwise, since publishing before this node's
     * own [DhtNode] exists is a caller ordering bug, not a recoverable
     * runtime condition.
     */
    suspend fun publishSeededContent() {
        val subscription = checkNotNull(topicSubscription) {
            "start() must be called, and must have finished, before publishSeededContent()"
        }
        val targets = seededClips.map { Triple(it.latitude, it.longitude, it.reachTier) }.distinct()
        for ((latitude, longitude, tier) in targets) {
            subscription.publish(latitude, longitude, tier)
            onLog("Published presence for reachTier=$tier at ($latitude, $longitude)")
        }
    }

    /**
     * Attempts [DhtNode.bootstrapJoin] against `(`[bootstrapHost]`,
     * `[bootstrapPort]`)`. Any failure (unreachable address, DNS failure,
     * timeout) is caught and logged, never rethrown -- matches
     * [com.hop.app.dht.DhtNodeManager.maybeBootstrap]'s own posture exactly.
     * Unlike that function, [bootstrapHost]/[bootstrapPort] are NOT
     * optional here: this tool's entire purpose depends on being reachable
     * from real peers, so [PreseedCli] always requires a real bootstrap
     * address as a CLI argument (see that class's own doc) rather than
     * defaulting to "don't attempt bootstrapJoin at all."
     */
    private suspend fun maybeBootstrap(dhtNode: DhtNode) {
        try {
            val address = PeerAddress.from(InetAddress.getByName(bootstrapHost), bootstrapPort)
            val discovered = dhtNode.bootstrapJoin(address)
            onLog("Bootstrap join via $bootstrapHost:$bootstrapPort discovered ${discovered.size} contact(s)")
        } catch (e: Exception) {
            onLog("Bootstrap join failed -- continuing without one: ${e.message}")
        }
    }

    /**
     * Stops the TCP listener, stops the UDP transport, cancels this node's
     * coroutine scope, and clears every exposed property. Idempotent.
     * Matches [com.hop.app.dht.DhtNodeManager.stop]'s posture exactly: no
     * attempt to gracefully deregister from the network first -- no such RPC
     * exists in this protocol; a Kademlia node simply stops answering.
     */
    fun stop() {
        peerListener?.stop()
        transport?.stop()
        nodeScope?.cancel()
        socket = null
        transport = null
        node = null
        peerListener = null
        nodeScope = null
        ownAddress = null
        ownNodeId = null
        topicSubscription = null
    }

    companion object {
        /**
         * Picks *a* locally-bound, non-loopback IPv4 address for this node's
         * advertised [PeerAddress] -- byte-for-byte the same best-effort
         * heuristic [com.hop.app.dht.DhtNodeManager]'s own private
         * `localBindAddress` uses (see that function's own doc for why this
         * is NOT NAT traversal or external-address discovery: a node behind
         * NAT is not actually reachable at this address from outside its own
         * local network/subnet -- an operator running this tool on a real
         * public-internet-reachable host, e.g. a VPS with a public IP bound
         * directly to its network interface, is the deployment shape this
         * heuristic actually works correctly for without further NAT-traversal
         * work).
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
