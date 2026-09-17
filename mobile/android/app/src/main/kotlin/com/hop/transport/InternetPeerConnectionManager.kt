package com.hop.transport

import com.hop.crypto.DecayKeyStore
import com.hop.data.DontRelayFlagEntity
import com.hop.dht.Contact
import com.hop.dht.NodeId
import com.hop.dht.PeerAddressDecodeException
import com.hop.p2p.PeerChannel
import com.hop.p2p.PeerDialException
import com.hop.protocol.TierKeyRequestEnvelope
import com.hop.protocol.WireEnvelope
import com.hop.protocol.WirePayloadType
import com.hop.repository.BundleRepository
import com.hop.repository.DontRelayRepository
import com.hop.repository.PendingMessageRepository
import com.hop.repository.PostRepository
import com.hop.repository.RelayRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

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
 * **Cleanup on close:** [InternetPeerConnection.connectTo]'s `onClosed`
 * callback (added alongside this class) is wired, per connection, to remove
 * that connection's registry entry the moment its receive loop ends for any
 * reason -- a dead connection never permanently occupies a slot under the
 * cap.
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
 * failed dial, NAT hole-punching, volunteer relay-node fallback, and any UI
 * surfacing of connection count ([FeedViewModel.discoveredRemoteHolders] was
 * deliberately left unrendered for its own product/UX reason -- this class
 * doesn't invent a new rendering for connection count either).
 */
class InternetPeerConnectionManager(
    postRepository: PostRepository,
    decayKeyStore: DecayKeyStore,
    private val relayRepository: RelayRepository,
    private val dontRelayRepository: DontRelayRepository,
    private val pendingMessageRepository: PendingMessageRepository,
    private val bundleRepository: BundleRepository,
    getOwnPeerId: suspend () -> String,
    onPreKeyBundleReceived: (peerId: String, bundleBytes: ByteArray) -> Unit = { _, _ -> },
    onMessageCiphertextReceived: suspend (senderPeerId: String, ciphertext: ByteArray) -> Unit = { _, _ -> },
    postsDir: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
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
     */
    suspend fun connectToDiscoveredHolders(holders: List<Contact>) = withContext(ioDispatcher) {
        var newAttempts = 0
        for (contact in holders) {
            if (connections.containsKey(contact.id)) continue
            if (newAttempts >= MAX_NEW_CONNECTIONS_PER_CALL) {
                onLog(
                    "Reached the per-call cap of $MAX_NEW_CONNECTIONS_PER_CALL new internet connection " +
                        "attempt(s); skipping the remaining discovered holder(s) this cycle -- the next " +
                        "browse/refresh will try them again"
                )
                break
            }
            newAttempts++
            try {
                val channel = internetPeerConnection.connectTo(
                    contact,
                    onClosed = {
                        connections.remove(contact.id)
                        onLog("Internet connection closed; removed from the connection registry")
                    },
                    onLiveRelay = ::fanOutLiveRelay,
                )
                connections[contact.id] = channel
                Thread({ sendBacklog(channel) }, "hop-internet-send").start()
            } catch (e: PeerDialException) {
                onLog("Failed to dial a discovered internet peer: ${e.message}")
            } catch (e: PeerAddressDecodeException) {
                onLog("Failed to decode a discovered internet peer's address: ${e.message}")
            }
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
        for ((nodeId, channel) in connections) {
            if (channel === arrivedOn) continue
            try {
                channel.sendRawBytes(outgoingEnvelopeBytes)
            } catch (e: Exception) {
                onLog("Live relay push failed to a connected internet peer; dropping that connection: ${e.message}")
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
        for ((nodeId, channel) in connections) {
            try {
                channel.sendRawBytes(envelope)
            } catch (e: Exception) {
                onLog("Broadcast post send failed to a connected internet peer; dropping that connection: ${e.message}")
                connections.remove(nodeId, channel)
            }
        }
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
        for ((nodeId, channel) in connections) {
            try {
                channel.sendRawBytes(envelope)
            } catch (e: Exception) {
                onLog("Broadcast \"don't relay\" flag send failed to a connected internet peer; dropping that connection: ${e.message}")
                connections.remove(nodeId, channel)
            }
        }
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
        for ((nodeId, channel) in connections) {
            try {
                channel.sendRawBytes(envelope)
            } catch (e: Exception) {
                onLog("Broadcast tier-key request send failed to a connected internet peer; dropping that connection: ${e.message}")
                connections.remove(nodeId, channel)
            }
        }
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
    }
}
