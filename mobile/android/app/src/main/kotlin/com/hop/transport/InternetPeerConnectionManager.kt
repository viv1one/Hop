package com.hop.transport

import com.hop.crypto.DecayKeyStore
import com.hop.data.DontRelayFlagEntity
import com.hop.dht.Contact
import com.hop.dht.IntroduceResult
import com.hop.dht.NodeId
import com.hop.dht.PeerAddress
import com.hop.dht.PeerAddressDecodeException
import com.hop.p2p.PeerChannel
import com.hop.p2p.PeerDialException
import com.hop.p2p.PeerDialer
import com.hop.protocol.TierKeyRequestEnvelope
import com.hop.protocol.WireEnvelope
import com.hop.protocol.WirePayloadType
import com.hop.repository.BundleRepository
import com.hop.repository.DontRelayRepository
import com.hop.repository.PendingMessageRepository
import com.hop.repository.PostRepository
import com.hop.repository.RelayRepository
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Closes the gap [FeedViewModel.discoveredRemoteHolders]'s own doc names
 * verbatim: a Town/City/Country DHT browse finds [Contact]s claiming to hold
 * content, but nothing previously turned that into a real connection. This
 * class is that "something" -- it takes the [Contact] list a browse cycle
 * returned and drives [InternetPeerConnection.connectTo] for whichever of
 * them this device doesn't already have an open connection to, up to a
 * small per-call cap.
 *
 * Owns exactly one internal [InternetPeerConnection] instance (constructed
 * from the same repository/capability set that instance itself already
 * takes -- not duplicated here, just threaded through once at construction)
 * and a small connection registry keyed by [NodeId] (which has real,
 * content-based `equals`/`hashCode` -- see that class's own doc -- so it's
 * safe as a [ConcurrentHashMap] key directly, no string-encoding step
 * needed). Deduping is by [Contact.id], deliberately not by
 * [Contact.address]: the same peer reappearing in a later browse with a
 * changed/refreshed address entry must not be treated as a second, distinct
 * peer worth a second connection.
 *
 * **[ioDispatcher]** defaults to [Dispatchers.IO] in production but is an
 * injectable constructor parameter -- same pattern as
 * `PostComposerViewModel.ioDispatcher`/`MessageRepository.ioDispatcher` in
 * this codebase -- because [InternetPeerConnection.connectTo] is a genuinely
 * blocking call ([com.hop.p2p.PeerDialer.dial] blocks the calling thread for
 * up to [com.hop.p2p.PeerDialer.FALLBACK_CONNECT_TIMEOUT_MS] per candidate
 * family). [connectToDiscoveredHolders] is called from
 * [com.hop.app.feed.FeedViewModel.launchDiscovery], which runs on
 * `viewModelScope` (`Dispatchers.Main.immediate` by default) -- running a
 * blocking socket connect there directly would stall the main thread, not
 * just this one coroutine. A test supplies a `TestDispatcher` instead, same
 * reasoning as those other two classes' own doc comments.
 *
 * **The per-call cap ([MAX_NEW_CONNECTIONS_PER_CALL]) is a bound on
 * concurrent internet dials per browse cycle, not a retry/backoff policy.**
 * A contact skipped this cycle for being over the cap, or because its dial
 * failed, is simply absent from the registry afterward -- logged, not
 * retried here. The next browse/refresh cycle (`FeedViewModel.refresh`) will
 * naturally try it again, since a skipped/failed contact was never
 * registered. Unmeasured placeholder, same posture as
 * `TransportManager.CONNECT_COOLDOWN_MS`/`FeedViewModel.TIER_KEY_REQUEST_COOLDOWN_MS`
 * -- revisit once real mesh/internet-mode density data exists.
 *
 * **Registration and cleanup:** [InternetPeerConnection.connectTo]'s
 * `onConnected` callback registers `connections[contact.id]` synchronously,
 * strictly before that connection's receive thread can possibly call
 * `onClosed` (see [connectToDiscoveredHolders]'s own doc for the real
 * registration race this ordering closes), and `onClosed` is wired, per
 * connection, to remove that same registry entry the moment its receive loop
 * ends for any reason -- together, a dead connection never permanently
 * occupies a slot under the cap, including one that closes essentially
 * immediately after connecting.
 *
 * **Connect-time backlog offer:** every newly-established connection gets
 * this device's queued outgoing content offered to it once, unconditionally
 * -- the same four backlogs [com.hop.transport.WifiDirectTransport
 * .registerConnectionAndGetBacklog] already builds and offers a newly-
 * connected WiFi Direct peer: [relayRepository]'s queued posts,
 * [dontRelayRepository]'s "don't relay" flags, [pendingMessageRepository]'s
 * pending 1:1 messages, and [bundleRepository]'s prekey bundles, in that
 * order, each already [com.hop.protocol.WireEnvelope.encode]d and ready to
 * write straight to the socket. See [sendBacklog]'s own doc for why this
 * happens on its own dedicated thread rather than inline in
 * [connectToDiscoveredHolders].
 *
 * **Live relay-flood fanout:** the other half of internet-mode relay-flood
 * fanout, alongside the connect-time backlog offer above -- a freshly-received
 * post/message/bundle/"don't relay" flag on any one open connection ([internetPeerConnection]'s
 * own [InternetPeerConnection.onLiveRelay] callback, invoked from that
 * connection's own `hop-internet-receive` thread) gets pushed, live, to every
 * *other* connection currently in [connections] via [PeerChannel.sendRawBytes] --
 * see [onLiveRelay]'s own doc. This is the only place that can do this fanout:
 * [InternetPeerConnection] itself has no visibility into any connection but
 * its own (see that class's own doc), only this manager's [connections]
 * registry sees every open connection at once. Mirrors
 * [WifiDirectTransport.handleNewlyReceivedFrame] et al.'s own
 * "push to every other currently-connected peer, drop a connection whose send
 * fails" shape, just split across two classes instead of one because of that
 * visibility difference.
 *
 * **Locally-authored broadcast fanout ([broadcastPost]/[broadcastDontRelayFlag]/
 * [broadcastTierKeyRequest]):** the piece the note directly above used to
 * flag as "separate, later work" -- now built. [TransportManager] calls each
 * of these *alongside* the matching [WifiDirectTransport] method, so a
 * post/flag/tier-key-request this device itself just authored also reaches
 * every open internet connection, not only WiFi Direct peers. Deliberately
 * **pure fanout, no custody-taking of their own**: [WifiDirectTransport]'s
 * own three methods already take whatever custody is needed (posts via
 * [RelayRepository.considerForRelay], flags via [DontRelayRepository.recordFlag],
 * none for a tier-key request) against the exact same shared repository
 * instances [com.hop.app.AppContainer] wires into both this class and
 * [WifiDirectTransport] -- a second custody call here would be redundant at
 * best (posts: `insert`'s `putIfAbsent`-shaped no-op) and a double-write at
 * worst (flags: [DontRelayRepository.recordFlag]'s distinct-attested-device
 * counter incrementing twice for one flag). See each method's own doc for
 * its exact mirror in [WifiDirectTransport].
 *
 * **Explicitly out of scope here (see [InternetPeerConnection]'s own doc for
 * the same boundary at its layer):** [TransportManager.sendToPeer]/
 * [TransportManager.sendMessage] (peer-id-targeted unicast) stay WiFi-Direct-
 * only -- this class has no peer-id-to-connection lookup, only the
 * [NodeId]-keyed [connections] registry, and building a unicast path is real
 * scope beyond unifying the three *broadcast* paths. Retry/backoff for a
 * failed dial, and any UI surfacing of connection count
 * ([FeedViewModel.discoveredRemoteHolders] was deliberately left unrendered
 * for its own product/UX reason -- this class doesn't invent a new rendering
 * for connection count either).
 *
 * **Volunteer relay-node fallback (Phase 4, last resort only):** [connectToDiscoveredHolders]
 * and [connectToIntroducedPeer] each fall back to [fallBackToRelay] the
 * moment their own direct dial fails -- never a first attempt. See
 * [fallBackToRelay]'s own doc for the shared tail both entry points funnel
 * into, and [bridgeViaRelay]'s own doc for the actual relay-dial/handshake
 * mechanism (dials `tools/relay-node/`'s `RelayNode` via the exact same
 * [PeerDialer] this class already uses for direct dials, writes that class's
 * own fixed 64-byte handshake, unmodified). [getOwnNodeId]/
 * [introduceViaRendezvous] are this class's two new capabilities, both
 * supplied from `com.hop.app.AppContainer`/`com.hop.app.dht.DhtNodeManager`
 * (the module that actually owns the DHT transport and this device's own
 * [NodeId] -- see each parameter's own doc for why nothing DHT-specific is
 * reimplemented here).
 */
class InternetPeerConnectionManager(
    postRepository: PostRepository,
    decayKeyStore: DecayKeyStore,
    private val relayRepository: RelayRepository,
    private val dontRelayRepository: DontRelayRepository,
    private val pendingMessageRepository: PendingMessageRepository,
    private val bundleRepository: BundleRepository,
    getOwnPeerId: suspend () -> String,
    /**
     * Shared correlation tracker for outgoing `TIER_KEY_REQUEST`s -- see
     * [PendingTierKeyRequests]'s own doc. Must be the exact same instance
     * [WifiDirectTransport] and [TransportManager.broadcastTierKeyRequest]
     * use (all three composed once, from the same singleton, in
     * `com.hop.app.AppContainer`), since a single request goes out over both
     * transports and a legitimate response can arrive on either.
     */
    pendingTierKeyRequests: PendingTierKeyRequests,
    onPreKeyBundleReceived: (peerId: String, bundleBytes: ByteArray) -> Unit = { _, _ -> },
    onMessageCiphertextReceived: suspend (senderPeerId: String, ciphertext: ByteArray) -> Unit = { _, _ -> },
    postsDir: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /**
     * This device's own [NodeId], if the DHT node has finished starting --
     * `null` otherwise (or on a test that has no DHT participation at all).
     * Needed for [bridgeViaRelay]'s `[32B ownId][32B bridgeToId]` handshake
     * -- `tools/relay-node/`'s `RelayNode` bridges by matching two mutual
     * `(ownId, bridgeToId)` declarations, so this device must declare its own
     * real id, not a placeholder. In production, `com.hop.app.AppContainer`
     * supplies `com.hop.app.dht.DhtNodeManager::ownNodeId` -- the exact same
     * per-DHT-node id [connectToDiscoveredHolders]'s/[connectToIntroducedPeer]'s
     * own [Contact]s are keyed against elsewhere in this class, never a
     * second identity concept.
     */
    private val getOwnNodeId: () -> NodeId? = { null },
    /**
     * Phase 4's rendezvous-relayed-introduction primitive
     * ([com.hop.dht.DhtUdpTransport.introduce]), reached one hop removed
     * from this class -- [connectToDiscoveredHolders]'s own relay-fallback
     * trigger needs SOME rendezvous/DHT contact to introduce through, and the
     * only one this app already knows about is the bootstrap node
     * `com.hop.app.dht.DhtNodeManager.maybeBootstrap` joins via (see that
     * property's own `rendezvousContact` doc for why reusing it, rather than
     * inventing a second "known rendezvous contacts" concept, is this app's
     * answer to that question today). In production,
     * `com.hop.app.AppContainer` supplies
     * `com.hop.app.dht.DhtNodeManager::introduceViaRendezvous`. Defaults to
     * `{ null }` -- always "no rendezvous contact available" -- for every
     * test that has no DHT node to introduce through, matching this class's
     * other injected-capability defaults.
     */
    private val introduceViaRendezvous: suspend (targetId: NodeId) -> IntroduceResult? = { null },
    /**
     * Bounds [bridgeViaRelay]'s own relay TCP connect + 64-byte handshake
     * write -- see that function's own doc for exactly what this does and
     * does NOT bound (there is no bridge-formed acknowledgement on the wire
     * to wait for; this is not a "wait for a live match" timeout). Mirrors
     * `tools/relay-node/`'s own [com.hop.relaynode.RelayNode.DEFAULT_WAIT_TIMEOUT_MS]
     * order of magnitude by default, per this slice's own guidance to mirror
     * that constant (or choose a tighter client-side budget) -- unmeasured
     * placeholder, same posture as every other timeout constant in this
     * codebase.
     */
    private val relayBridgeTimeoutMs: Long = DEFAULT_RELAY_BRIDGE_TIMEOUT_MS,
    private val onLog: (String) -> Unit = {},
) {
    /** The single real content-transfer connection driver this manager dials through -- see class doc; not duplicated, threaded through once. */
    private val internetPeerConnection = InternetPeerConnection(
        postRepository = postRepository,
        decayKeyStore = decayKeyStore,
        relayRepository = relayRepository,
        dontRelayRepository = dontRelayRepository,
        pendingMessageRepository = pendingMessageRepository,
        bundleRepository = bundleRepository,
        getOwnPeerId = getOwnPeerId,
        pendingTierKeyRequests = pendingTierKeyRequests,
        onPreKeyBundleReceived = onPreKeyBundleReceived,
        onMessageCiphertextReceived = onMessageCiphertextReceived,
        postsDir = postsDir,
        onLog = onLog,
    )

    /**
     * Every currently-open internet connection this manager dialed, keyed by
     * the remote peer's [NodeId]. A [ConcurrentHashMap] since a connection's
     * `onClosed` callback removes its own entry from a background
     * `hop-internet-receive` thread ([InternetPeerConnection.receiveLoop]'s
     * own thread), concurrently with whatever thread calls
     * [connectToDiscoveredHolders].
     */
    private val connections = ConcurrentHashMap<NodeId, PeerChannel>()

    /**
     * For each [holders] entry this device doesn't already have an open
     * connection to (by [Contact.id]), dials it via
     * [InternetPeerConnection.connectTo] and registers the resulting
     * [PeerChannel] -- up to [MAX_NEW_CONNECTIONS_PER_CALL] *new* attempts
     * per call (an already-registered contact doesn't count against this
     * cap; it's skipped before the count is even checked).
     *
     * A failed dial ([PeerDialException] -- every candidate address
     * unreachable -- or [PeerAddressDecodeException] -- undecodable address
     * bytes) is caught, logged, and that contact simply stays absent from
     * the registry this cycle; no exception propagates out of this
     * function.
     *
     * Registers `connections[contact.id]` via [InternetPeerConnection.connectTo]'s
     * `onConnected` callback -- invoked synchronously, strictly before the
     * connection's own receive thread starts (see that parameter's own doc)
     * -- rather than from this function's own post-[connectTo]-return
     * assignment. This closes a real registration race: [connectTo] dials,
     * wraps the socket in a [PeerChannel], starts the receive thread (which
     * can call `onClosed` the instant it hits EOF/an error -- e.g. the
     * remote peer resetting the connection immediately after connecting),
     * and only then used to return the channel to this function. Since
     * `onClosed` only ever fires once, registering *after* [connectTo]
     * returned meant a fast-closing connection's `onClosed` (which removes
     * this registry entry) could run and complete before this function's own
     * `connections[contact.id] = channel` line ever executed -- leaving a
     * permanently dead entry registered with nothing left to ever remove it,
     * silently occupying a slot under [MAX_NEW_CONNECTIONS_PER_CALL] forever
     * (contradicting this class's own "a dead connection never permanently
     * occupies a slot under the cap" claim above). Registering from
     * `onConnected` instead guarantees registration always happens-before
     * `onClosed` can possibly run, for any connection, regardless of how
     * quickly the remote peer closes it.
     *
     * **Dials the (up to [MAX_NEW_CONNECTIONS_PER_CALL]) new candidates
     * concurrently, not sequentially.** The candidate list -- everything in
     * [holders] not already in [connections], capped at
     * [MAX_NEW_CONNECTIONS_PER_CALL] -- is computed once, up front, exactly
     * mirroring the old sequential loop's own "check dedup, then check cap"
     * order per contact; only *which contacts get attempted* is decided up
     * front, not how many run at once. Each candidate's dial then runs as its
     * own [kotlinx.coroutines.async] child inside a [coroutineScope], so a
     * slow dial to one candidate (each can block for up to
     * [com.hop.p2p.PeerDialer.FALLBACK_CONNECT_TIMEOUT_MS] under
     * [ioDispatcher]) never delays starting the next candidate's own dial --
     * this function still doesn't return until every one of them (success or
     * failure) has settled, via [awaitAll]. Registration/cleanup and
     * per-contact failure handling are unchanged, just running concurrently
     * instead of one after another.
     *
     * **Phase 4's relay-fallback trigger, the more common real-world one:**
     * on a direct-dial failure for a given [contact] ([PeerDialException] or
     * [PeerAddressDecodeException]), this is the entry point that calls
     * [fallBackToRelay] with no relay suggestion already in hand -- unlike
     * [connectToIntroducedPeer], which may already have one from the
     * INTRODUCTION that triggered it, a browse-discovered [Contact] never
     * came with a relay suggestion attached, so [fallBackToRelay] must ask
     * [introduceViaRendezvous] for one first. See that function's own doc.
     */
    suspend fun connectToDiscoveredHolders(holders: List<Contact>) = withContext(ioDispatcher) {
        val candidates = holders.filterNot { connections.containsKey(it.id) }
        val toDial = candidates.take(MAX_NEW_CONNECTIONS_PER_CALL)
        if (candidates.size > toDial.size) {
            onLog(
                "Reached the per-call cap of $MAX_NEW_CONNECTIONS_PER_CALL new internet connection " +
                    "attempt(s); skipping the remaining discovered holder(s) this cycle -- the next " +
                    "browse/refresh will try them again"
            )
        }
        coroutineScope {
            toDial.map { contact ->
                async {
                    val directChannel = try {
                        internetPeerConnection.connectTo(
                            contact,
                            onConnected = { connectedChannel -> connections[contact.id] = connectedChannel },
                            onClosed = {
                                connections.remove(contact.id)
                                onLog("Internet connection closed; removed from the connection registry")
                            },
                            onLiveRelay = ::fanOutLiveRelay,
                        )
                    } catch (e: PeerDialException) {
                        onLog("Failed to dial a discovered internet peer: ${e.message}")
                        null
                    } catch (e: PeerAddressDecodeException) {
                        onLog("Failed to decode a discovered internet peer's address: ${e.message}")
                        null
                    }

                    if (directChannel != null) {
                        Thread({ sendBacklog(directChannel) }, "hop-internet-send").start()
                    } else {
                        // Last resort only, after the direct dial above has
                        // already failed -- see fallBackToRelay's own doc.
                        fallBackToRelay(contact.id, knownRelayId = null, knownRelayAddress = null)
                    }
                }
            }.awaitAll()
        }
    }

    /**
     * Phase 4's relay-fallback trigger for a device that just RECEIVED a
     * rendezvous-relayed introduction (`com.hop.dht.IntroductionMessage`) --
     * wired from `com.hop.app.dht.DhtNodeManager`'s own `onIntroductionReceived`
     * forwarding. Attempts an ordinary direct dial to [contact] (the
     * introduced peer's self-reported claimed address) first, exactly like
     * [connectToDiscoveredHolders] does for a browse-discovered [Contact];
     * only on failure does it fall back to [fallBackToRelay] -- and unlike
     * [connectToDiscoveredHolders], this entry point may already have a
     * relay suggestion in hand ([relayId]/[relayAddress], carried on the very
     * same INTRODUCTION that triggered this call -- see
     * [com.hop.dht.IntroduceResponseMessage]'s own doc for why R hands the
     * IDENTICAL suggestion to both sides), so [fallBackToRelay] doesn't need
     * to ask [introduceViaRendezvous] for one a second time.
     *
     * Already-connected [contact]s are skipped, same dedup posture as
     * [connectToDiscoveredHolders] -- an introduction naming a peer this
     * device already has an open connection to is a no-op, not a second
     * redundant dial.
     */
    suspend fun connectToIntroducedPeer(contact: Contact, relayId: NodeId?, relayAddress: PeerAddress?) = withContext(ioDispatcher) {
        if (connections.containsKey(contact.id)) return@withContext

        val directChannel = try {
            internetPeerConnection.connectTo(
                contact,
                onConnected = { connectedChannel -> connections[contact.id] = connectedChannel },
                onClosed = {
                    connections.remove(contact.id)
                    onLog("Internet connection closed; removed from the connection registry")
                },
                onLiveRelay = ::fanOutLiveRelay,
            )
        } catch (e: PeerDialException) {
            onLog("Direct dial to an introduced peer failed: ${e.message}")
            null
        } catch (e: PeerAddressDecodeException) {
            onLog("Failed to decode an introduced peer's claimed address: ${e.message}")
            null
        }

        if (directChannel != null) {
            Thread({ sendBacklog(directChannel) }, "hop-internet-send").start()
            return@withContext
        }

        // Last resort only, after the direct dial above has already failed.
        fallBackToRelay(contact.id, knownRelayId = relayId, knownRelayAddress = relayAddress)
    }

    /**
     * Shared relay-fallback tail for [connectToDiscoveredHolders] and
     * [connectToIntroducedPeer], both of which only ever call this AFTER
     * their own direct dial has already failed -- this is a last resort, not
     * a first attempt, matching every other best-effort connection posture
     * in this class.
     *
     * If [knownRelayId]/[knownRelayAddress] are already both non-null (the
     * [connectToIntroducedPeer] case -- the introduction that triggered this
     * call already carried a relay suggestion), that pair is used directly.
     * Otherwise (the [connectToDiscoveredHolders] case), asks
     * [introduceViaRendezvous] for one: this both gives [targetId] a chance
     * to hole-punch back on its own (the ordinary rendezvous-relayed-
     * introduction mechanism, entirely out-of-band from this call -- nothing
     * further to do here if that's what ends up working) and, via that same
     * INTRODUCE_RESPONSE, returns a relay suggestion to fall back to if that
     * doesn't pan out either.
     *
     * Any failure along the way -- no rendezvous contact to introduce
     * through, no relay known, or [bridgeViaRelay] itself failing/timing
     * out -- is logged and this function simply returns: no retry loop, no
     * new persistent state, the caller simply doesn't get this connection
     * this cycle, matching [fanOutLiveRelay]/[sendBacklog]'s own best-effort
     * posture.
     */
    private suspend fun fallBackToRelay(targetId: NodeId, knownRelayId: NodeId?, knownRelayAddress: PeerAddress?) {
        val relayId: NodeId
        val relayAddress: PeerAddress
        if (knownRelayId != null && knownRelayAddress != null) {
            relayId = knownRelayId
            relayAddress = knownRelayAddress
        } else {
            val introduced: IntroduceResult? = try {
                introduceViaRendezvous(targetId)
            } catch (e: Exception) {
                onLog("Rendezvous introduce fallback failed: ${e.message}")
                null
            }
            val candidateId = introduced?.relayId
            val candidateAddress = introduced?.relayAddress
            if (candidateId == null || candidateAddress == null) {
                onLog("No relay suggestion available for a peer that failed direct dial; giving up on this connection attempt")
                return
            }
            relayId = candidateId
            relayAddress = candidateAddress
        }

        val ownId = getOwnNodeId()
        if (ownId == null) {
            onLog("This device's own node id isn't available yet; can't attempt a relay bridge")
            return
        }

        // relayId itself isn't part of RelayNode's own handshake (only its
        // dialable TCP relayAddress and the [ownId, targetId] pair are) --
        // logged here purely for diagnostics, not used for dialing.
        onLog("Attempting a last-resort relay bridge via relay $relayId at $relayAddress")
        val channel = bridgeViaRelay(relayAddress, ownId, targetId)
        if (channel == null) {
            onLog("Relay bridge attempt failed or timed out; giving up on this connection attempt")
            return
        }

        connections[targetId] = channel
        Thread({ sendBacklog(channel) }, "hop-internet-send").start()
        Thread({
            internetPeerConnection.receiveLoop(
                channel,
                onClosed = {
                    connections.remove(targetId)
                    onLog("Relay-bridged internet connection closed; removed from the connection registry")
                },
                onLiveRelay = ::fanOutLiveRelay,
            )
        }, "hop-internet-receive").start()
    }

    /**
     * Dials [relayAddress] (a volunteer relay's real TCP bridge address, per
     * [com.hop.dht.RelayAnnounceRequestMessage]'s own trust-limitation doc --
     * exactly as trustworthy as whichever relay announced it) via the same
     * [PeerDialer] this class already uses for every other TCP dial -- no new
     * dial logic -- then writes `tools/relay-node/`'s `RelayNode` own fixed
     * [com.hop.relaynode.RelayNode.HANDSHAKE_SIZE_BYTES]-byte handshake:
     * `[32B ownId][32B bridgeToId]`, with `ownId` = [ownId] (this device's
     * own [NodeId]) and `bridgeToId` = [targetId] (the peer this device is
     * trying to reach) -- matching that class's own "Wire shape" doc exactly,
     * unmodified. Deliberately NOT [com.hop.protocol.WireEnvelope] framing;
     * `RelayNode` has zero dependency on `protocol/` and never will (see that
     * class's own doc) -- the handshake bytes are written raw, directly to
     * the socket's output stream, before this connection is wrapped in a
     * [PeerChannel] for anything else.
     *
     * **What this function does NOT do, and why:** `RelayNode`'s own wire
     * shape has no bridge-formed acknowledgement -- once a mutual
     * `(ownId, bridgeToId)`/`(bridgeToId, ownId)` pair is matched, `RelayNode`
     * just starts blind bidirectional byte-forwarding; it never writes
     * anything back to either side to confirm the match happened (see that
     * class's own "Pairing" doc). Inventing a bridge-formed handshake of this
     * class's own would mean speaking a wire shape `RelayNode` doesn't
     * implement -- explicitly out of scope for this slice ("dial a relay
     * using the wire shape RelayNode already implements, unmodified"). So
     * [relayBridgeTimeoutMs] bounds only the relay's own TCP connect (via
     * [PeerDialer]) and this handshake write -- mirroring
     * [com.hop.relaynode.RelayNode.handleConnection]'s own
     * `socket.soTimeout = waitTimeoutMs` (during its own handshake read)
     * `/socket.soTimeout = 0` (cleared once bridging -- which can legitimately
     * run far longer -- begins) pattern on this side of the same handshake. A
     * successful handshake write is treated as "the bridge attempt was made"
     * -- the same "a successful TCP connect is treated as connected" posture
     * [InternetPeerConnection.connectTo] already takes for a direct dial,
     * where liveness likewise isn't separately proven before the receive loop
     * starts. An attempt that never actually gets matched by `RelayNode`
     * (e.g. the target peer never dials in) is NOT silently left open
     * forever: `RelayNode` itself closes an unmatched connection after its
     * own [com.hop.relaynode.RelayNode.DEFAULT_WAIT_TIMEOUT_MS] wait, which
     * surfaces on this side as an ordinary [java.io.EOFException] from
     * [PeerChannel.receiveEnvelope] once [internetPeerConnection.receiveLoop]
     * starts reading -- the exact same dead-connection path every other
     * failure in this class already takes (`onClosed` fires, the registry
     * entry is removed), no new failure-detection logic needed for it.
     *
     * Returns the connected, handshake-written [PeerChannel] on success, or
     * `null` (logged) if the relay dial itself fails ([PeerDialException]) or
     * the handshake write fails (any [Exception] on the raw socket write) --
     * the socket is closed before returning `null` in either case, never
     * leaked.
     */
    private fun bridgeViaRelay(relayAddress: PeerAddress, ownId: NodeId, targetId: NodeId): PeerChannel? {
        val socket = try {
            PeerDialer.dial(listOf(relayAddress))
        } catch (e: PeerDialException) {
            onLog("Failed to dial a volunteer relay for bridging: ${e.message}")
            return null
        }
        return try {
            socket.soTimeout = relayBridgeTimeoutMs.toInt()
            val handshake = ByteBuffer.allocate(NodeId.SIZE_BYTES * 2).apply {
                put(ownId.bytes)
                put(targetId.bytes)
            }.array()
            socket.getOutputStream().write(handshake)
            socket.getOutputStream().flush()
            // Bridging itself can legitimately run far longer than
            // relayBridgeTimeoutMs (an ordinary chat/relay session, possibly
            // idle for stretches) -- cleared before this socket is handed off
            // to the ordinary receive loop, mirroring RelayNode's own
            // handshake-read-then-clear pattern on the other side of this
            // same handshake.
            socket.soTimeout = 0
            PeerChannel(socket)
        } catch (e: Exception) {
            onLog("Relay handshake failed while bridging to a target peer: ${e.message}")
            try {
                socket.close()
            } catch (closeError: Exception) {
                // Best-effort cleanup; the original handshake failure is what matters.
            }
            null
        }
    }

    /**
     * Builds the same four backlogs
     * [com.hop.transport.WifiDirectTransport.registerConnectionAndGetBacklog]
     * builds for a newly-connected WiFi Direct peer --
     * [relayRepository]'s queued posts, [dontRelayRepository]'s "don't
     * relay" flags, [pendingMessageRepository]'s pending 1:1 messages, and
     * [bundleRepository]'s prekey bundles, each already
     * [com.hop.protocol.WireEnvelope.encode]d -- and streams each entry out
     * over [channel] via [PeerChannel.sendRawBytes], in that order.
     *
     * Runs entirely on its own dedicated thread (`"hop-internet-send"`,
     * started by the caller) so building/sending this backlog -- which does
     * real Room I/O via `runBlocking` in each `buildOutgoing*Backlog()` call,
     * then a blocking socket write per entry -- never blocks
     * [connectToDiscoveredHolders]'s own dial loop for the *next* contact in
     * the same call, mirroring
     * [com.hop.transport.WifiDirectTransport.handleConnection]'s own
     * dedicated-`"hop-send"`-thread posture exactly.
     *
     * A send failure partway through (e.g. the peer closes mid-stream) is
     * logged and stops this backlog's own send loop -- it never throws out
     * of this method, so it can never crash the thread it's running on, let
     * alone the dial loop for a different contact.
     */
    private fun sendBacklog(channel: PeerChannel) {
        val backlog = runBlocking(ioDispatcher) {
            relayRepository.buildOutgoingBacklog() +
                dontRelayRepository.buildOutgoingFlagBacklog() +
                pendingMessageRepository.buildOutgoingBacklog() +
                bundleRepository.buildOutgoingBacklog()
        }
        onLog("Sending ${backlog.size} queued item(s) to a newly connected internet peer")
        for (entry in backlog) {
            try {
                channel.sendRawBytes(entry)
            } catch (e: Exception) {
                onLog("Send error while flushing the backlog to a connected internet peer: ${e.message}")
                break
            }
        }
    }

    /**
     * [InternetPeerConnection.connectTo]'s `onLiveRelay` callback -- called
     * from whichever internet connection's own `hop-internet-receive` thread
     * just took custody of a freshly-received post/message/bundle/"don't
     * relay" flag (see [InternetPeerConnection.receiveLoop]'s own doc).
     * Writes [outgoingEnvelopeBytes] (already `hopCount + 1` re-encoded, or
     * unchanged for a flag) to every [connections] entry except
     * [arrivedOn] -- reference equality (`!==`), matching
     * [WifiDirectTransport]'s own `connection === arrivedOn` check for the
     * identical local-mesh fanout. A send failure is logged and that entry is
     * removed from [connections] -- mirrors every one of
     * [WifiDirectTransport]'s own four live-relay handlers' "drop the
     * connection on failed send" posture exactly.
     *
     * Runs synchronously, inline, on the calling connection's own receive
     * thread rather than being dispatched to a separate thread/coroutine:
     * [connections] is small by construction ([MAX_NEW_CONNECTIONS_PER_CALL]
     * bounds how many *new* dials happen per browse cycle, so the realistic
     * fanout width here is at most a small handful of siblings, not a large
     * fanout), and each [PeerChannel.sendRawBytes] call is a single already-
     * buffered socket write -- not the same "real Room I/O plus a whole
     * backlog of writes" cost [sendBacklog] has, which is why *that* method
     * (not this one) gets a dedicated thread. A slow/failing write to one
     * sibling here does briefly delay the write to the next sibling in the
     * same call (a plain sequential loop), but never blocks a *different*
     * connection's own receive thread/[receiveEnvelope] call, since each
     * connection has always run on its own dedicated thread from the start
     * (see [connectToDiscoveredHolders]'s own `Thread(...)` per dial). Note
     * this accepts the exact same theoretical risk [WifiDirectTransport]'s
     * own `PeerConnection.trySend` already accepts for the identical local-
     * mesh problem -- a genuinely wedged (not merely disconnected) peer could
     * still block a plain blocking socket `write()` indefinitely, with no
     * per-write timeout on either path; this isn't a new risk introduced
     * here, it's the same one this codebase already lives with for WiFi
     * Direct fanout, now also accepted for the internet-mode case. If
     * real fanout width or a slow/wedged peer's write-blocking ever proves
     * this wrong, revisit with a per-sibling-send timeout or an async
     * dispatch -- unmeasured placeholder reasoning, same posture as
     * [MAX_NEW_CONNECTIONS_PER_CALL] itself.
     */
    private fun fanOutLiveRelay(outgoingEnvelopeBytes: ByteArray, arrivedOn: PeerChannel) {
        broadcastToConnections(outgoingEnvelopeBytes, excludeChannel = arrivedOn, failureDescription = "Live relay push")
    }

    /**
     * Shared "iterate every open [connections] entry, send [bytes], drop the
     * entry on a failed send" tail every broadcast/live-relay method in this
     * class ([fanOutLiveRelay], [broadcastPost], [broadcastDontRelayFlag],
     * [broadcastTierKeyRequest]) otherwise duplicated verbatim. [excludeChannel],
     * when non-null, skips that one connection by reference equality (`===`)
     * -- only [fanOutLiveRelay] ever passes this, to avoid echoing content
     * back to the connection it just arrived on; the other three callers
     * broadcast this device's own authored content/flag/request to *every*
     * open connection, so they never exclude one.
     *
     * [failureDescription] is a short, call-site-specific phrase (e.g.
     * `"Broadcast post send"`) that [onLog] gets folded into, preserving each
     * call site's own previous log-message text exactly: `"$failureDescription
     * failed to a connected internet peer; dropping that connection: ${e.message}"`.
     */
    private fun broadcastToConnections(bytes: ByteArray, excludeChannel: PeerChannel? = null, failureDescription: String) {
        for ((nodeId, channel) in connections) {
            if (channel === excludeChannel) continue
            try {
                channel.sendRawBytes(bytes)
            } catch (e: Exception) {
                onLog("$failureDescription failed to a connected internet peer; dropping that connection: ${e.message}")
                connections.remove(nodeId, channel)
            }
        }
    }

    /**
     * Broadcasts [encoded] (a caller-built [com.hop.protocol.Frame]'s already-
     * encoded bytes, the same bytes [TransportManager.broadcastPost] also
     * hands to [WifiDirectTransport.broadcastPost] unchanged) to every
     * currently-open internet connection, wrapped exactly once here as a
     * [WirePayloadType.POST_FRAME] [WireEnvelope]
     * -- mirrors [WifiDirectTransport.broadcastPost]'s own wrap-and-fan-out
     * shape, minus that method's [RelayRepository.considerForRelay] custody
     * call (see this class's own doc for why that's deliberately not
     * duplicated here: [WifiDirectTransport.broadcastPost] already takes that
     * custody, against the same shared [relayRepository] instance, and
     * [TransportManager] calls both methods for the same post).
     *
     * Unlike [fanOutLiveRelay], there is no `arrivedOn` connection to exclude
     * -- this is this device's own outbound broadcast of its own content, so
     * every open connection is a legitimate recipient, not just every
     * *sibling*. A connection whose send fails is logged and evicted from
     * [connections], exactly as [fanOutLiveRelay] already does; delivery to
     * every other connection continues regardless.
     */
    fun broadcastPost(encoded: ByteArray) {
        val envelope = WireEnvelope.encode(WirePayloadType.POST_FRAME, encoded)
        onLog("Broadcasting a self-authored post to ${connections.size} connected internet peer(s)")
        broadcastToConnections(envelope, failureDescription = "Broadcast post send")
    }

    /**
     * Broadcasts [row] to every currently-open internet connection, wrapped
     * as a [WirePayloadType.DONT_RELAY_FLAG] [WireEnvelope] via the same
     * [DontRelayFlagEntity.toEnvelope] helper [InternetPeerConnection] already
     * uses for the identical conversion on its own live-relay path (kept
     * `internal`, not re-duplicated a third time -- see that function's own
     * doc).
     *
     * Deliberately **no** [DontRelayRepository.recordFlag]/`isNew` check
     * here -- [WifiDirectTransport.broadcastDontRelayFlag] already ran that
     * check (against the same shared [dontRelayRepository] instance) by the
     * time [TransportManager.broadcastDontRelayFlag] calls this method;
     * calling it here too would increment the same distinct-attested-device
     * counter a second time for one flag. [TransportManager] is responsible
     * for only calling this method when the WiFi Direct side's own check
     * found the flag genuinely new -- see [TransportManager
     * .broadcastDontRelayFlag]'s own doc for exactly how that's sequenced.
     */
    fun broadcastDontRelayFlag(row: DontRelayFlagEntity) {
        val envelope = WireEnvelope.encode(WirePayloadType.DONT_RELAY_FLAG, row.toEnvelope().encode())
        onLog("Broadcasting a \"don't relay\" flag to ${connections.size} connected internet peer(s)")
        broadcastToConnections(envelope, failureDescription = "Broadcast \"don't relay\" flag send")
    }

    /**
     * Broadcasts [request] to every currently-open internet connection,
     * wrapped as a [WirePayloadType.TIER_KEY_REQUEST] [WireEnvelope] --
     * mirrors [WifiDirectTransport.broadcastTierKeyRequest] exactly: no
     * custody concern at all (a tier-key request is never persisted, on
     * either transport), just a fire-and-forget broadcast to whichever
     * currently-connected peer, if any, happens to hold the key.
     */
    fun broadcastTierKeyRequest(request: TierKeyRequestEnvelope) {
        val envelope = WireEnvelope.encode(WirePayloadType.TIER_KEY_REQUEST, request.encode())
        onLog("Broadcasting a tier-key request to ${connections.size} connected internet peer(s)")
        broadcastToConnections(envelope, failureDescription = "Broadcast tier-key request send")
    }

    private companion object {
        /**
         * Bounds concurrent internet dials attempted per
         * [connectToDiscoveredHolders] call -- an unmeasured placeholder, same
         * posture as `TransportManager.CONNECT_COOLDOWN_MS`/
         * `FeedViewModel.TIER_KEY_REQUEST_COOLDOWN_MS` (see this class's own
         * doc). Deliberately small: bounding this browse cycle's own dial
         * fanout, not a tuned capacity number.
         */
        const val MAX_NEW_CONNECTIONS_PER_CALL = 3

        /**
         * Default for [relayBridgeTimeoutMs] -- see that parameter's own doc
         * for exactly what this bounds (the relay TCP connect + handshake
         * write only, not a "wait for a live match" timeout). Mirrors
         * [com.hop.relaynode.RelayNode.DEFAULT_WAIT_TIMEOUT_MS]'s own ~30s
         * order of magnitude, per this slice's own guidance -- unmeasured
         * placeholder, same posture as every other timeout constant in this
         * codebase.
         */
        const val DEFAULT_RELAY_BRIDGE_TIMEOUT_MS = 30_000L
    }
}
