package com.hop.transport

import com.hop.crypto.DecayKeyStore
import com.hop.dht.Contact
import com.hop.dht.NodeId
import com.hop.dht.PeerAddressDecodeException
import com.hop.p2p.PeerChannel
import com.hop.p2p.PeerDialException
import com.hop.repository.BundleRepository
import com.hop.repository.DontRelayRepository
import com.hop.repository.PendingMessageRepository
import com.hop.repository.PostRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
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
 * **Explicitly out of scope here (see [InternetPeerConnection]'s own doc for
 * the same boundary at its layer):** internet-mode relay-flood fanout,
 * retry/backoff for a failed dial, NAT hole-punching, volunteer relay-node
 * fallback, and any UI surfacing of connection count
 * ([FeedViewModel.discoveredRemoteHolders] was deliberately left unrendered
 * for its own product/UX reason -- this class doesn't invent a new rendering
 * for connection count either).
 */
class InternetPeerConnectionManager(
    postRepository: PostRepository,
    decayKeyStore: DecayKeyStore,
    dontRelayRepository: DontRelayRepository,
    pendingMessageRepository: PendingMessageRepository,
    bundleRepository: BundleRepository,
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
                val channel = internetPeerConnection.connectTo(contact) {
                    connections.remove(contact.id)
                    onLog("Internet connection closed; removed from the connection registry")
                }
                connections[contact.id] = channel
            } catch (e: PeerDialException) {
                onLog("Failed to dial a discovered internet peer: ${e.message}")
            } catch (e: PeerAddressDecodeException) {
                onLog("Failed to decode a discovered internet peer's address: ${e.message}")
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
