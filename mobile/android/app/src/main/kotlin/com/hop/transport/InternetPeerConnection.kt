package com.hop.transport

import com.hop.crypto.DecayKeyStore
import com.hop.data.BundleQueueEntity
import com.hop.data.DontRelayFlagEntity
import com.hop.data.PendingMessageEntity
import com.hop.dht.Contact
import com.hop.dht.PeerAddress
import com.hop.p2p.PeerChannel
import com.hop.p2p.PeerDialer
import com.hop.protocol.DontRelayFlagEnvelope
import com.hop.protocol.Frame
import com.hop.protocol.MessageCiphertextEnvelope
import com.hop.protocol.PreKeyBundleEnvelope
import com.hop.protocol.TierKeyResponseEnvelope
import com.hop.protocol.WireEnvelope
import com.hop.protocol.WirePayloadType
import com.hop.repository.BundleRepository
import com.hop.repository.DontRelayRepository
import com.hop.repository.PendingMessageRepository
import com.hop.repository.PostRepository
import com.hop.repository.RelayRepository
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
 *   [DispatchResult.NewRelayableBundle], [DispatchResult.NewDontRelayFlag]:
 *   custody-taking for the latter three already happened *inside*
 *   [EnvelopeDispatcher.dispatch] itself; [DispatchResult.NewPostFrame] is the
 *   one exception -- this class calls [RelayRepository.considerForRelay]
 *   itself (mirroring [WifiDirectTransport.handleNewlyReceivedFrame]'s own
 *   extra step, since [EnvelopeDispatcher.dispatch]'s `POST_FRAME` branch only
 *   ever calls `receivedFrameStore.handle(...)`). Each of these four then gets
 *   a `hopCount + 1` re-encoded copy handed to [onLiveRelay] -- this class has
 *   no visibility into any *other* internet connection ([InternetPeerConnectionManager]
 *   is the only thing that does, see its own doc), so it never fans out
 *   directly; it only ever hands the encoded bytes + this connection (to
 *   exclude on fanout) to the caller-supplied callback. [DispatchResult.DirectBundleAnnounce]
 *   is logged only, same as [DispatchResult.PeerIdentified] -- a genuine
 *   direct announce is not itself something to live-relay onward (see
 *   [WifiDirectTransport]'s own doc for why that case is handled separately
 *   from [WifiDirectTransport.handleNewlyReceivedBundle]).
 *
 * **Explicitly out of scope for this class (separate, later slices):**
 * - Wiring into [TransportManager]'s `start()`/`stop()` lifecycle, or having
 *   `topics/`'s DHT browse results automatically trigger a [connectTo] call.
 *   This class only needs to correctly connect-and-dispatch when handed a
 *   [Contact] directly, by a test today or a future slice's own trigger
 *   logic later.
 * - Fanning a live-relayed item out to *other* internet connections -- that's
 *   [InternetPeerConnectionManager]'s job (see [onLiveRelay]'s own doc); this
 *   class only produces the re-encoded bytes and reports which connection to
 *   exclude.
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
    /**
     * Only used by [handleNewlyReceivedFrame] -- [EnvelopeDispatcher.dispatch]'s
     * `POST_FRAME` branch does not itself call [RelayRepository.considerForRelay]
     * (unlike its `PREKEY_BUNDLE`/`MESSAGE_CIPHERTEXT`/`DONT_RELAY_FLAG`
     * branches, which already do their own custody-taking), mirroring
     * [WifiDirectTransport]'s own split between [EnvelopeDispatcher.dispatch]
     * and [WifiDirectTransport.handleNewlyReceivedFrame].
     */
    private val relayRepository: RelayRepository,
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
     * [onLiveRelay] is invoked, from [receiveLoop]'s own thread, once per
     * freshly-received (not-a-duplicate) post/message/bundle/"don't relay"
     * flag this connection takes custody of -- see [receiveLoop]'s doc for
     * the full reasoning. Defaulted to a no-op for the same
     * every-existing-caller-keeps-working reason as [onClosed];
     * [InternetPeerConnectionManager] is the first real caller that supplies
     * one, to fan a freshly-received item out to its *other* open internet
     * connections.
     *
     * Throws [com.hop.p2p.PeerDialException] if every candidate address
     * fails to connect, or [com.hop.dht.PeerAddressDecodeException] if
     * [contact]'s address bytes don't decode -- neither is caught here;
     * callers decide how to handle a failed connection attempt (this slice
     * has no retry/backoff, per the class doc). Neither [onClosed] nor
     * [onLiveRelay] is ever invoked for either of these -- the connection
     * never started, so it never "closed" and never received anything.
     */
    fun connectTo(
        contact: Contact,
        onClosed: () -> Unit = {},
        onLiveRelay: (outgoingEnvelopeBytes: ByteArray, arrivedOn: PeerChannel) -> Unit = { _, _ -> },
    ): PeerChannel {
        val candidates = PeerAddress.decodeList(contact.address)
        val socket = PeerDialer.dial(candidates)
        onLog("Dialed an internet peer connection (${candidates.size} candidate address(es))")
        val channel = PeerChannel(socket)
        Thread({ receiveLoop(channel, onClosed, onLiveRelay) }, "hop-internet-receive").start()
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
     *
     * [onLiveRelay] is called once per genuinely-new (not-a-duplicate)
     * [DispatchResult.NewPostFrame]/[DispatchResult.NewRelayableMessage]/
     * [DispatchResult.NewRelayableBundle]/[DispatchResult.NewDontRelayFlag]
     * this loop dispatches, with a `hopCount + 1` re-encoded (unchanged for
     * a flag, which carries no hop count) [WireEnvelope]-wrapped copy of the
     * item plus [channel] itself (so the caller knows which connection to
     * exclude when it fans the item out to any *other* open internet
     * connections -- this class has no visibility into siblings, only
     * [InternetPeerConnectionManager] does; see that class's own doc). This
     * is the live-push half of internet-mode relay-flood fanout -- the
     * connect-time backlog offer ([InternetPeerConnectionManager.sendBacklog])
     * is the other, already-built half; together they mirror
     * [WifiDirectTransport.registerConnectionAndGetBacklog] (connect-time)
     * plus [WifiDirectTransport.handleNewlyReceivedFrame] et al. (live-push)
     * for local WiFi Direct peers.
     */
    fun receiveLoop(
        channel: PeerChannel,
        onClosed: () -> Unit = {},
        onLiveRelay: (outgoingEnvelopeBytes: ByteArray, arrivedOn: PeerChannel) -> Unit = { _, _ -> },
    ) {
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
                        is DispatchResult.NewPostFrame -> handleNewlyReceivedFrame(result.frame, channel, onLiveRelay)
                        is DispatchResult.NewRelayableMessage -> handleNewlyReceivedMessage(result.row, channel, onLiveRelay)
                        is DispatchResult.NewRelayableBundle -> handleNewlyReceivedBundle(result.row, channel, onLiveRelay)
                        is DispatchResult.NewDontRelayFlag -> handleNewlyReceivedDontRelayFlag(result.row, channel, onLiveRelay)
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
     * Called once per genuinely-new [Frame] this connection receives (see
     * [DispatchResult.NewPostFrame]) -- mirrors
     * [WifiDirectTransport.handleNewlyReceivedFrame] exactly: takes relay
     * custody via [relayRepository] (the one custody call
     * [EnvelopeDispatcher.dispatch]'s `POST_FRAME` branch does NOT already do
     * itself -- see this class's own constructor doc), then hands
     * [onLiveRelay] a `hopCount + 1` re-encoded [WirePayloadType.POST_FRAME]
     * copy plus [channel] (the connection to exclude on fanout). Does not
     * itself iterate any other connection -- see [onLiveRelay]'s own doc for
     * why.
     */
    private fun handleNewlyReceivedFrame(
        frame: Frame,
        channel: PeerChannel,
        onLiveRelay: (ByteArray, PeerChannel) -> Unit,
    ) {
        runBlocking { relayRepository.considerForRelay(frame) }
        val outgoingFrame = frame.copy(hopCount = frame.hopCount + 1)
        val envelope = WireEnvelope.encode(WirePayloadType.POST_FRAME, outgoingFrame.encode())
        onLiveRelay(envelope, channel)
    }

    /**
     * Called once per genuinely-new [DontRelayFlagEntity] this connection
     * records (see [DispatchResult.NewDontRelayFlag]) -- mirrors
     * [WifiDirectTransport.handleNewlyReceivedDontRelayFlag] exactly: no
     * extra custody call needed ([DontRelayRepository.recordFlag] already ran
     * inside [EnvelopeDispatcher.dispatch]), no hop-count bump (a flag never
     * carries one -- see [DontRelayFlagEnvelope]'s own doc), just re-wraps
     * [row] and hands it to [onLiveRelay] alongside [channel].
     */
    private fun handleNewlyReceivedDontRelayFlag(
        row: DontRelayFlagEntity,
        channel: PeerChannel,
        onLiveRelay: (ByteArray, PeerChannel) -> Unit,
    ) {
        val envelope = WireEnvelope.encode(WirePayloadType.DONT_RELAY_FLAG, row.toEnvelope().encode())
        onLiveRelay(envelope, channel)
    }

    /**
     * Called once per genuinely-new [PendingMessageEntity] this connection
     * takes custody of on behalf of someone else's conversation (see
     * [DispatchResult.NewRelayableMessage]) -- mirrors
     * [WifiDirectTransport.handleNewlyReceivedMessage] exactly: no extra
     * custody call needed ([PendingMessageRepository.considerForRelay]
     * already ran inside [EnvelopeDispatcher.dispatch]), decodes [row],
     * bumps `hopCount + 1`, re-wraps, hands it to [onLiveRelay] alongside
     * [channel].
     */
    private fun handleNewlyReceivedMessage(
        row: PendingMessageEntity,
        channel: PeerChannel,
        onLiveRelay: (ByteArray, PeerChannel) -> Unit,
    ) {
        val storedEnvelope = MessageCiphertextEnvelope.decode(row.encodedEnvelope)
        val outgoingEnvelope = storedEnvelope.copy(hopCount = storedEnvelope.hopCount + 1)
        val envelope = WireEnvelope.encode(WirePayloadType.MESSAGE_CIPHERTEXT, outgoingEnvelope.encode())
        onLiveRelay(envelope, channel)
    }

    /**
     * Called once per genuinely-new [BundleQueueEntity] this connection takes
     * relay custody of on behalf of a bundle it did not itself announce (see
     * [DispatchResult.NewRelayableBundle]) -- mirrors
     * [WifiDirectTransport.handleNewlyReceivedBundle] exactly: no extra
     * custody call needed ([BundleRepository.considerForRelay] already ran
     * inside [EnvelopeDispatcher.dispatch]), decodes [row], bumps
     * `hopCount + 1`, re-wraps, hands it to [onLiveRelay] alongside
     * [channel].
     */
    private fun handleNewlyReceivedBundle(
        row: BundleQueueEntity,
        channel: PeerChannel,
        onLiveRelay: (ByteArray, PeerChannel) -> Unit,
    ) {
        val storedEnvelope = PreKeyBundleEnvelope.decode(row.encodedEnvelope)
        val outgoingEnvelope = storedEnvelope.copy(hopCount = storedEnvelope.hopCount + 1)
        val envelope = WireEnvelope.encode(WirePayloadType.PREKEY_BUNDLE, outgoingEnvelope.encode())
        onLiveRelay(envelope, channel)
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

/**
 * Converts a persisted [DontRelayFlagEntity] back to its on-wire
 * [DontRelayFlagEnvelope] shape -- a small, deliberate duplicate of
 * [WifiDirectTransport]'s own private file-scoped `DontRelayFlagEntity.toEnvelope()`
 * (that one is private to its own file, not reachable from here, and this
 * slice does not touch [WifiDirectTransport.kt]). Same shape as
 * [DontRelayRepository.buildOutgoingFlagBacklog]'s own inline conversion.
 */
private fun DontRelayFlagEntity.toEnvelope(): DontRelayFlagEnvelope = DontRelayFlagEnvelope(
    clipHash = clipHash.hexToByteArray(),
    attestedDeviceKey = attestedDeviceKey.hexToByteArray(),
    flaggedAtMs = flaggedAtMs,
    originatedAtMs = originatedAtMs,
    ttlSeconds = ttlSeconds,
)

private fun String.hexToByteArray(): ByteArray =
    ByteArray(length / 2) { i -> ((Character.digit(this[i * 2], 16) shl 4) + Character.digit(this[i * 2 + 1], 16)).toByte() }
