package com.hop.transport

import com.hop.crypto.DecayKeyStore
import com.hop.dht.Contact
import com.hop.dht.PeerAddress
import com.hop.p2p.PeerChannel
import com.hop.p2p.PeerDialer
import com.hop.protocol.TierKeyResponseEnvelope
import com.hop.protocol.WireEnvelope
import com.hop.protocol.WirePayloadType
import com.hop.repository.BundleRepository
import com.hop.repository.DontRelayRepository
import com.hop.repository.PendingMessageRepository
import com.hop.repository.PostRepository
import kotlinx.coroutines.runBlocking
import java.io.EOFException
import java.io.File

/**
 * Phase 4's internet-mode counterpart to [WifiDirectTransport]: dials a
 * `dht/`-discovered [Contact] over a real internet socket (`p2p/`'s
 * [PeerDialer]/[PeerChannel]) and dispatches every [WireEnvelope] that
 * arrives on it through the exact same [ReceivedFrameStore]/[EnvelopeDispatcher]
 * logic [WifiDirectTransport] already uses for local WiFi Direct peers --
 * neither of those two classes is duplicated or reimplemented here, only
 * constructed a second time against a different transport (see this class's
 * own constructor, which mirrors [WifiDirectTransport]'s construction of
 * both at lines ~198-215 exactly). Two dispatcher instances sharing the same
 * underlying repositories is fine: both are stateless glue, all real state
 * lives in the Room-backed repositories passed in below.
 *
 * **What this class does:**
 * - [connectTo]: decodes a [Contact]'s address list ([PeerAddress.decodeList]),
 *   dials it ([PeerDialer.dial] -- IPv6-first/IPv4-fallback), wraps the
 *   resulting socket in a [PeerChannel], and starts [receiveLoop] on a
 *   dedicated background thread (matching [WifiDirectTransport.receivePosts]'s
 *   own thread-per-connection posture, not a coroutine dispatcher).
 * - [receiveLoop]: reads [WireEnvelope]s off a [PeerChannel] until it throws
 *   (peer closed, or another I/O error), dispatching each through
 *   [envelopeDispatcher]. Public (not just called internally by [connectTo])
 *   specifically so a test can drive it directly against one side of a real
 *   loopback [PeerChannel] pair, without going through [PeerDialer]/a real
 *   [Contact] at all -- the same "internal, not private" testability seam
 *   [ReceivedFrameStore]/[EnvelopeDispatcher] themselves already establish,
 *   just one level up.
 * - [sendEnvelope]: a thin delegate to [PeerChannel.sendEnvelope], exposed for
 *   symmetry/testability only -- **not** wired to [TransportManager.broadcastPost]
 *   or any other automatic trigger. Hooking an internet connection into this
 *   device's own outgoing broadcast path is explicit follow-on,
 *   app-lifecycle-integration work (see below).
 *
 * **[DispatchResult] handling posture -- what's real here vs. explicitly
 * flagged as follow-on scope:**
 * - [DispatchResult.TierKeyRequestAnswered] gets a REAL answer, written back
 *   on the very connection the request arrived on -- this is the point of
 *   this slice: a peer reached over the internet (not just local mesh) asking
 *   for a Town/City/Country post's tiered key is exactly ADR 0003's
 *   key-distribution mechanism doing its job over a new transport. Mirrors
 *   [WifiDirectTransport.handleTierKeyRequestAnswered]'s own reasoning for
 *   replying on the arrival connection rather than a peer-id lookup: a
 *   [com.hop.protocol.TierKeyRequestEnvelope] carries no requester peer id at
 *   all, so there is nothing else to reply *to*.
 * - [DispatchResult.PeerIdentified] is logged (meaningful context even though
 *   nothing else consumes it yet in this slice -- there is no per-peer-id
 *   connection registry here the way [WifiDirectTransport.activeConnections]
 *   is for WiFi Direct, since this slice only ever drives one connection at a
 *   time; see "Explicitly out of scope" below).
 * - [DispatchResult.NoOp] is a true no-op.
 * - [DispatchResult.NewPostFrame], [DispatchResult.NewRelayableMessage],
 *   [DispatchResult.NewRelayableBundle], [DispatchResult.DirectBundleAnnounce],
 *   [DispatchResult.NewDontRelayFlag]: custody-taking for every one of these
 *   already happened *inside* [EnvelopeDispatcher.dispatch] itself -- that's
 *   that class's own job, already correct, unchanged by this slice. What
 *   this class explicitly does NOT do is push any of these onward to *other*
 *   internet-connected peers: there is no internet-mode relay-flood/broadcast
 *   concept yet (no `broadcastPost`-equivalent for internet mode, no
 *   multi-connection registry the way [WifiDirectTransport.activeConnections]
 *   is for WiFi Direct). This mirrors how [WifiDirectTransport]'s own
 *   local-mesh relay-flood logic ([WifiDirectTransport.handleNewlyReceivedFrame]
 *   et al.) was itself built as a distinctly later slice, after its basic
 *   dispatch/custody logic already existed and worked. Each of these cases is
 *   logged, not silently swallowed, so the gap stays visible in the logs
 *   rather than looking like a bug.
 *
 * **Explicitly out of scope for this class (separate, later slices):**
 * - Wiring into [TransportManager]'s `start()`/`stop()` lifecycle, or having
 *   `topics/`'s DHT browse results automatically trigger a [connectTo] call.
 *   This class only needs to correctly connect-and-dispatch when handed a
 *   [Contact] directly, by a test today or a future slice's own trigger
 *   logic later.
 * - Internet-mode relay-flood fanout (see above).
 * - Connection retry/backoff, managing multiple simultaneous internet
 *   connections, reconnection logic -- this class drives exactly one
 *   connection per instance, once.
 * - NAT hole-punching, volunteer relay-node fallback for symmetric NAT -- a
 *   volunteer relay cannot be assumed honest (hop-dev's own "extra scrutiny"
 *   posture for NAT traversal / relay-node trust logic); unrelated, separate
 *   slices, same as `p2p/`'s own [PeerDialer]/[PeerChannel] already note.
 *
 * As with every other reach-tier surface in this codebase: a
 * [DispatchResult.TierKeyRequestAnswered] grant here means "the reference
 * client only releases this key to a peer presenting a valid, fresh in-cell
 * tier claim" -- never "this content is inaccessible to a determined custom
 * client." Geohash-prefix topics aren't secret; the guarantee is
 * key-wrapping discipline in the stock client, not an access-control boundary
 * (ADR 0003).
 */
class InternetPeerConnection(
    postRepository: PostRepository,
    decayKeyStore: DecayKeyStore,
    dontRelayRepository: DontRelayRepository,
    pendingMessageRepository: PendingMessageRepository,
    bundleRepository: BundleRepository,
    getOwnPeerId: suspend () -> String,
    onPreKeyBundleReceived: (peerId: String, bundleBytes: ByteArray) -> Unit = { _, _ -> },
    onMessageCiphertextReceived: suspend (senderPeerId: String, ciphertext: ByteArray) -> Unit = { _, _ -> },
    postsDir: File,
    private val onLog: (String) -> Unit = {},
) {
    /** Mirrors [WifiDirectTransport]'s own construction of this exact class (~line 198) -- not a reimplementation, a second instance sharing the same repositories. */
    private val receivedFrameStore = ReceivedFrameStore(
        postRepository = postRepository,
        decayKeyStore = decayKeyStore,
        postsDir = postsDir,
    )

    /** Mirrors [WifiDirectTransport]'s own construction of this exact class (~line 205) -- see this class's own doc for why a second instance is fine. */
    private val envelopeDispatcher = EnvelopeDispatcher(
        receivedFrameStore = receivedFrameStore,
        postRepository = postRepository,
        decayKeyStore = decayKeyStore,
        dontRelayRepository = dontRelayRepository,
        pendingMessageRepository = pendingMessageRepository,
        bundleRepository = bundleRepository,
        getOwnPeerId = getOwnPeerId,
        onPreKeyBundleReceived = onPreKeyBundleReceived,
        onMessageCiphertextReceived = onMessageCiphertextReceived,
    )

    /**
     * Decodes [contact]'s address list, dials it (IPv6-first/IPv4-fallback --
     * see [PeerDialer.dial]'s own doc), wraps the connected socket in a
     * [PeerChannel], and starts [receiveLoop] for it on a dedicated
     * background thread (never the calling thread -- matches
     * [WifiDirectTransport.handleConnection]'s own posture of never blocking
     * whatever thread triggers a new connection).
     *
     * Returns the [PeerChannel] immediately (the receive loop runs
     * concurrently on its own thread) so a caller can also [sendEnvelope] on
     * it, e.g. to push this device's own content to [contact] right after
     * connecting -- that push logic itself is a future slice's job, not
     * built here (see class doc's "Explicitly out of scope").
     *
     * [onClosed] is invoked exactly once, from [receiveLoop]'s own thread,
     * the moment the receive loop for this connection ends for any reason
     * (orderly peer close or I/O error) -- see [receiveLoop]'s doc. Defaulted
     * to a no-op so every existing caller/test that doesn't care about
     * connection lifecycle keeps working unchanged; [InternetPeerConnectionManager]
     * is the first real caller that supplies one, to know when to evict a
     * dead connection from its registry.
     *
     * Throws [com.hop.p2p.PeerDialException] if every candidate address
     * fails to connect, or [com.hop.dht.PeerAddressDecodeException] if
     * [contact]'s address bytes don't decode -- neither is caught here;
     * callers decide how to handle a failed connection attempt (this slice
     * has no retry/backoff, per the class doc). [onClosed] is never invoked
     * for either of these -- the connection never started, so it never
     * "closed."
     */
    fun connectTo(contact: Contact, onClosed: () -> Unit = {}): PeerChannel {
        val candidates = PeerAddress.decodeList(contact.address)
        val socket = PeerDialer.dial(candidates)
        onLog("Dialed an internet peer connection (${candidates.size} candidate address(es))")
        val channel = PeerChannel(socket)
        Thread({ receiveLoop(channel, onClosed) }, "hop-internet-receive").start()
        return channel
    }

    /** Delegates to [PeerChannel.sendEnvelope] -- see class doc for why this is exposed for symmetry/testability only, not wired to any automatic broadcast trigger. */
    fun sendEnvelope(channel: PeerChannel, envelope: WireEnvelope) {
        channel.sendEnvelope(envelope)
    }

    /**
     * Reads [WireEnvelope]s off [channel] in a loop, dispatching each through
     * [envelopeDispatcher], until [PeerChannel.receiveEnvelope] throws --
     * [java.io.EOFException] on an orderly peer close (the expected, common
     * case) or any other exception (an unexpected I/O failure) -- both end
     * this loop cleanly, without crashing whatever thread is running it. See
     * class doc for exactly which [DispatchResult] cases get real handling
     * here vs. are explicitly flagged as later-slice scope.
     *
     * Public (not just invoked internally by [connectTo]) specifically so a
     * test can call this directly, synchronously, against one side of a
     * real loopback [PeerChannel] pair -- no [PeerDialer]/real [Contact]
     * required to exercise this logic.
     *
     * [onClosed] runs in a `finally` wrapping the whole loop, so it fires
     * exactly once no matter which exit path (EOF, other I/O error) ends
     * this loop -- see [connectTo]'s doc for why this exists.
     */
    fun receiveLoop(channel: PeerChannel, onClosed: () -> Unit = {}) {
        try {
            while (true) {
                val envelope = try {
                    channel.receiveEnvelope()
                } catch (e: EOFException) {
                    onLog("Internet peer connection closed by remote; ending receive loop")
                    return
                } catch (e: Exception) {
                    onLog("Internet peer connection receive error: ${e.message}; ending receive loop")
                    return
                }
                try {
                    when (val result = runBlocking { envelopeDispatcher.dispatch(envelope) }) {
                        is DispatchResult.TierKeyRequestAnswered -> handleTierKeyRequestAnswered(result.response, channel)
                        is DispatchResult.PeerIdentified ->
                            onLog("Internet peer identified as ${result.peerId}")
                        is DispatchResult.DirectBundleAnnounce ->
                            onLog("Received a direct prekey bundle announce over an internet connection from ${result.peerId}")
                        is DispatchResult.NewPostFrame ->
                            onLog(
                                "Received a new post over an internet connection and took local custody of it -- " +
                                    "internet-mode relay-flood fanout to other internet-connected peers is separate " +
                                    "follow-up work (see this class's own doc), not done here"
                            )
                        is DispatchResult.NewRelayableMessage ->
                            onLog(
                                "Took relay custody of a message carried over an internet connection -- " +
                                    "internet-mode relay-flood fanout is separate follow-up work, not done here"
                            )
                        is DispatchResult.NewRelayableBundle ->
                            onLog(
                                "Took relay custody of a prekey bundle carried over an internet connection -- " +
                                    "internet-mode relay-flood fanout is separate follow-up work, not done here"
                            )
                        is DispatchResult.NewDontRelayFlag ->
                            onLog(
                                "Recorded a new \"don't relay\" flag received over an internet connection -- " +
                                    "internet-mode relay-flood fanout is separate follow-up work, not done here"
                            )
                        DispatchResult.NoOp -> Unit
                    }
                } catch (e: Exception) {
                    onLog("Error handling a received internet-connection envelope: ${e.message}")
                }
            }
        } finally {
            onClosed()
        }
    }

    /**
     * Writes [response] back on [channel] -- the same connection the
     * originating `TIER_KEY_REQUEST` arrived on. Deliberately not any kind of
     * peer-id-keyed lookup/send (this class has no connection registry the
     * way [WifiDirectTransport.activeConnections] is): mirrors
     * [WifiDirectTransport.handleTierKeyRequestAnswered]'s own reasoning --
     * a [com.hop.protocol.TierKeyRequestEnvelope] carries no requester peer
     * id at all, so replying on the connection it arrived on is the only
     * correct option, not just the simplest one.
     *
     * A send failure is logged and otherwise swallowed -- matches every
     * other best-effort send posture in this codebase (e.g.
     * [WifiDirectTransport.handleTierKeyRequestAnswered]'s own `trySend`);
     * the requester's own follow-up (retry, treat as a timeout) is
     * requesting-side work this slice does not build, same as
     * [WifiDirectTransport]'s.
     */
    private fun handleTierKeyRequestAnswered(response: TierKeyResponseEnvelope, channel: PeerChannel) {
        try {
            channel.sendEnvelope(WireEnvelope(WirePayloadType.TIER_KEY_RESPONSE, response.encode()))
        } catch (e: Exception) {
            onLog("Failed to send a tier-key response back over an internet connection: ${e.message}")
        }
    }
}
