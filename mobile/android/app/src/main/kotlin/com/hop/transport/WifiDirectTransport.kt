package com.hop.transport

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import com.hop.crypto.DecayKeyStore
import com.hop.crypto.PreKeyBundleCodec
import com.hop.data.BundleQueueEntity
import com.hop.data.DontRelayFlagEntity
import com.hop.data.PendingMessageEntity
import com.hop.data.PostEntity
import com.hop.data.PreKeyRotationManager
import com.hop.protocol.DontRelayFlagEnvelope
import com.hop.protocol.Frame
import com.hop.protocol.MessageCiphertextEnvelope
import com.hop.protocol.PreKeyBundleEnvelope
import com.hop.protocol.RelayPolicy
import com.hop.protocol.WireEnvelope
import com.hop.protocol.WirePayloadType
import com.hop.repository.BundleRepository
import com.hop.repository.DontRelayRepository
import com.hop.repository.PendingMessageRepository
import com.hop.repository.PointsRepository
import com.hop.repository.PostRepository
import com.hop.repository.RelayRepository
import kotlinx.coroutines.runBlocking
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Real (non-spike) WiFi Direct transport. Group formation/negotiation
 * handling, the multi-client accept-loop server ([activeServerSocket]/
 * [stopServer]), the [activeGroupKey] duplicate-event guard, and continuous
 * discovery are ported near-verbatim from `com.hop.spike.WifiDirectSpike`
 * (left untouched, not imported from -- see that file's own doc for the
 * hardware-validated findings behind these mechanisms, and BUILD_PLAN.md
 * open decision #5 for the density concerns that shaped the accept loop and
 * the duplicate-connection-event guard).
 *
 * Structural differences from the spike:
 * - Send path pushes a new post live to every currently-connected peer
 *   ([broadcastPost]) instead of a single `pendingClip` -- every queued post
 *   is offered to every peer this device connects to, not just the most
 *   recently queued one. As of Phase 2 Slice 1, both the live-push set and
 *   the new-connection backlog are backed by [RelayRepository]'s persisted
 *   relay queue (not a session-scoped in-memory list), so a post survives
 *   this device's own process restart, not just a downstream relay's (see
 *   [broadcastPost]/[registerConnectionAndGetBacklog]'s own docs).
 * - Receive path decodes and stores every queued frame a peer sends (loops
 *   until the socket closes/EOF), and stores ciphertext + key separately
 *   (matching [PostRepository]'s on-demand-decrypt design) instead of
 *   force-decrypting and handing off to a system viewer. A genuinely-new
 *   frame also gets relay custody and an immediate live-push to every
 *   *other* connected peer (see [handleNewlyReceivedFrame]) -- multi-hop
 *   store-and-forward relay, not direct-peer-only delivery.
 *
 * As of the Phase 1 messaging slice, every blob crossing the socket is a
 * [WireEnvelope] (`[1-byte type][4-byte length][payload]`), not a bare
 * `[4-byte length][Frame bytes]` -- see [WireEnvelope]'s own doc and
 * `/protocol/WIRE_FORMAT.md` for why this is an intentional, non-backward-
 * compatible break. Posts still fan out to every connected peer
 * ([broadcastPost]); prekey bundle announcements and message ciphertext are
 * peer-specific ([sendToPeer]), never broadcast.
 */
class WifiDirectTransport(
    context: Context,
    private val channel: WifiP2pManager.Channel,
    postRepository: PostRepository,
    decayKeyStore: DecayKeyStore,
    /**
     * Phase 2 Slice 1's persisted store-and-forward relay queue (see its own
     * doc) -- backs both [broadcastPost]'s self-authored-post custody
     * (Finding B: a post surviving *this* device's own process restart, not
     * just a downstream relay's) and [registerConnectionAndGetBacklog]'s
     * new-connection backlog, replacing the old session-scoped in-memory
     * `outbox` that never survived a process restart.
     */
    private val relayRepository: RelayRepository,
    /**
     * Phase 2 Slice 2's "don't relay" distinct-attested-device flag counter
     * (see its own doc). Backs [broadcastDontRelayFlag] (this device's own
     * outgoing flags), the [WirePayloadType.DONT_RELAY_FLAG] branch of
     * [EnvelopeDispatcher.dispatch] (recording a peer's incoming flag), and
     * [registerConnectionAndGetBacklog]'s flag backlog -- the same
     * three-call-site shape [relayRepository] already has for posts.
     */
    private val dontRelayRepository: DontRelayRepository,
    /**
     * Phase 2 Slice 2's non-tradeable local points counter for relay
     * operators (see its own doc). Awarded from [handleNewlyReceivedFrame]'s
     * live-push loop and [sendBacklog] -- see each method's own doc for the
     * exact award point and the self-authored-post exclusion.
     */
    private val pointsRepository: PointsRepository,
    /**
     * Phase 2 Slice 3's persisted relay-custody queue for offline 1:1
     * message recipients (see its own doc). Backs [sendMessage]'s
     * flood-on-failure fallback, the [WirePayloadType.MESSAGE_CIPHERTEXT]
     * branch of [EnvelopeDispatcher.dispatch] (a message arriving for a
     * peer other than this device -- taking relay custody rather than
     * decrypting, since this device structurally has no session for that
     * conversation), and [registerConnectionAndGetBacklog]'s message
     * backlog -- the same three-call-site shape [relayRepository] already
     * has for posts.
     */
    private val pendingMessageRepository: PendingMessageRepository,
    /**
     * The prekey-bundle relay/discovery follow-up's persisted mesh
     * flood-relay queue for prekey bundles (see its own doc). Backs
     * [registerConnectionAndGetBacklog]'s bundle backlog and the
     * [WirePayloadType.PREKEY_BUNDLE] branch of [EnvelopeDispatcher.dispatch]
     * for a *relayed* (`hopCount >= 1`) bundle -- a genuine direct announce
     * (`hopCount == 0`) never touches this repository, see that branch's own
     * doc for why.
     */
    private val bundleRepository: BundleRepository,
    /**
     * Owns this device's one-time/signed/Kyber prekey pool and rotation
     * policy -- needed here (not just in `com.hop.repository.MessageRepository`)
     * so this class can publish and announce this device's own current
     * [PreKeyBundle] ambiently on every new connection (see
     * [announceOwnPreKeyBundle]). See [PreKeyRotationManager]'s own doc for
     * why every connection gets a freshly-assembled bundle (a distinct,
     * never-before-handed-out one-time prekey each time) rather than the
     * single memoized bundle this class used before -- that memoization was
     * a real correctness bug: once one peer consumed the memoized bundle's
     * one-time prekey, every later peer's first message silently failed to
     * decrypt.
     */
    private val preKeyRotationManager: PreKeyRotationManager,
    /**
     * This device's own stable messaging identity (hex-encoded
     * `senderDeviceId`, see `com.hop.data.PeerIdentity`'s doc) -- a suspend
     * function rather than a plain value since it's ultimately backed by
     * `SettingsRepository.getOrCreateStableSenderDeviceId()` (DataStore,
     * suspend). Read lazily, off the main thread, only when actually needed
     * (announcing a bundle or sending a message), not at construction time.
     */
    private val getOwnPeerId: suspend () -> String,
    /**
     * Called (never on the main thread -- see [receivePosts]) whenever a
     * peer's [WirePayloadType.PREKEY_BUNDLE] announcement arrives, with the
     * announcing peer's id and their still-opaque bundle bytes. Deliberately
     * *not* deserialized into a libsignal [org.signal.libsignal.protocol.state.PreKeyBundle]
     * here -- that's libsignal-aware work this class avoids per the Phase 1
     * messaging plan (`PreKeyBundleCodec`/[com.hop.repository.MessageRepository]
     * own that instead); this class's only libsignal-aware code path is
     * *publishing* its own bundle (see [announceOwnPreKeyBundle]).
     */
    private val onPreKeyBundleReceived: (peerId: String, bundleBytes: ByteArray) -> Unit = { _, _ -> },
    /**
     * Called (off the main thread, see [receivePosts]) whenever a
     * [WirePayloadType.MESSAGE_CIPHERTEXT] envelope arrives, with the
     * sender's peer id and the opaque Double Ratchet ciphertext. Suspend
     * since the real implementation (`MessageRepository.onEnvelopeReceived`)
     * does real Room/libsignal work.
     */
    private val onMessageCiphertextReceived: suspend (senderPeerId: String, ciphertext: ByteArray) -> Unit = { _, _ -> },
    private val onLog: (String) -> Unit = {},
) {
    companion object {
        const val TRANSFER_PORT = 8988

        /**
         * See [connectWithRetry]'s doc for the real-hardware race this guards
         * against. Total budget (~20s: 20 attempts, ~1s apart) is deliberately
         * generous -- real-hardware testing found the gap between two
         * devices' app launches (and therefore between one side's server bind
         * and the other's client connect) can exceed 4 seconds even under
         * normal use, not just contrived timing. A short budget here doesn't
         * just fail this one connection attempt: see [activeGroupKey]'s doc
         * for why exhausting it used to leave the device permanently stuck.
         */
        private const val CONNECT_RETRY_ATTEMPTS = 20
        private const val CONNECT_RETRY_TIMEOUT_MS = 3_000
        private const val CONNECT_RETRY_DELAY_MS = 1_000L
    }

    private val manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    private val appContext = context.applicationContext

    private val receivedFrameStore = ReceivedFrameStore(
        postRepository = postRepository,
        decayKeyStore = decayKeyStore,
        postsDir = File(appContext.filesDir, "posts"),
    )

    /** Routes a decoded [WireEnvelope] to the right handler -- see [EnvelopeDispatcher]'s own doc. */
    private val envelopeDispatcher = EnvelopeDispatcher(
        receivedFrameStore = receivedFrameStore,
        dontRelayRepository = dontRelayRepository,
        pendingMessageRepository = pendingMessageRepository,
        bundleRepository = bundleRepository,
        getOwnPeerId = getOwnPeerId,
        onPreKeyBundleReceived = onPreKeyBundleReceived,
        onMessageCiphertextReceived = onMessageCiphertextReceived,
    )

    /**
     * Every socket connection currently open to a peer (this device may be
     * the group owner serving several clients, or the lone client of another
     * device's group -- see [handleConnection]). [broadcastPost] iterates
     * this to push new posts live; entries are removed as connections close
     * (see [receivePosts]'s `finally`). [CopyOnWriteArrayList] since reads
     * (broadcastPost's iteration) are far more frequent than writes (one add
     * per connection, one remove per disconnect), and iteration must never
     * throw `ConcurrentModificationException` while a send is in flight on
     * another thread.
     */
    private val activeConnections = CopyOnWriteArrayList<PeerConnection>()

    /**
     * Guards [relayRepository]'s persisted-queue writes/reads together with
     * [activeConnections] reads (in [broadcastPost]) and, symmetrically,
     * [activeConnections] mutation together with [relayRepository] reads (in
     * [registerConnectionAndGetBacklog]) -- the two operations that must
     * never interleave. Without a single lock spanning both, a post queued
     * exactly as a new connection is being registered could land in neither
     * the new connection's backlog nor its live-push set (silently dropped)
     * or in both (double-sent).
     *
     * Historical note: before Phase 2 Slice 1, this guarded an in-memory
     * `outbox` list plus [activeConnections], and both critical sections
     * were genuinely tiny (list add/read, no I/O). That's no longer strictly
     * true -- [relayRepository]'s persisted-queue calls now do real Room I/O
     * while this lock is held. That tradeoff is deliberate: correctness of
     * the write+live-push-vs-connect+backlog-query race matters more here
     * than strict "never block on I/O while holding a monitor" purity, and
     * both call sites already run on background threads (never the main
     * thread -- see [handleConnection]/[receivePosts]'s own docs), so this
     * never risks blocking UI work.
     */
    private val outboxLock = Any()

    /**
     * Decodes [encoded] once (this class never constructs a [Frame] on the
     * send path -- the caller, [com.hop.app.composer.PostComposerViewModel],
     * already built it), takes relay custody of it via [relayRepository]
     * (Finding B: without this, a self-authored post surviving *this
     * device's own* process restart -- not just a downstream relay's -- was
     * never possible, since the old in-memory `outbox` was wiped by any
     * process death, including this device's), then immediately pushes it to
     * every peer already connected right now. A peer that connects *after*
     * this call gets it from [registerConnectionAndGetBacklog]'s
     * [relayRepository]-backed backlog instead of a live push.
     *
     * Wraps [encoded] in a [WirePayloadType.POST_FRAME]-tagged [WireEnvelope]
     * exactly once here -- everything downstream ([sendBacklog], live push
     * below) deals in already-wrapped bytes.
     *
     * Note for future maintainers: this class does not build frames on send,
     * so the Phase 0 spike's `senderDeviceId`-regenerated-per-send bug
     * ([com.hop.spike.WifiDirectSpike.sendPayload]) has no structural
     * equivalent here -- the caller already builds the frame once, using a
     * stable per-install id from `SettingsRepository.getOrCreateStableSenderDeviceId()`.
     * Don't reintroduce per-send frame construction (and therefore a fresh
     * id) in this class later.
     */
    fun broadcastPost(encoded: ByteArray) {
        val envelope = WireEnvelope.encode(WirePayloadType.POST_FRAME, encoded)
        val connectionsSnapshot: List<PeerConnection>
        synchronized(outboxLock) {
            try {
                runBlocking { relayRepository.considerForRelay(Frame.decode(encoded)) }
            } catch (e: Exception) {
                onLog("Could not take relay custody of own broadcast post: ${e.message}")
            }
            connectionsSnapshot = activeConnections.toList()
        }
        onLog("Broadcasting post (${encoded.size} bytes) to ${connectionsSnapshot.size} connected peer(s)")
        for (connection in connectionsSnapshot) {
            if (!connection.trySend(envelope)) {
                onLog("Live send failed to a connected peer; dropping that connection")
                activeConnections.remove(connection)
            }
        }
    }

    /**
     * Records [row] locally via [dontRelayRepository] and, only if it was
     * genuinely new (not a duplicate flag from this device's own attested
     * key -- see [DontRelayRepository.recordFlag]'s own doc), pushes it live
     * to every peer already connected right now. A peer that connects
     * *after* this call gets it from [registerConnectionAndGetBacklog]'s
     * [dontRelayRepository]-backed flag backlog instead. Mirrors
     * [broadcastPost]'s own shape.
     *
     * Shares [outboxLock] with [broadcastPost]/[registerConnectionAndGetBacklog]
     * for the same reason [broadcastPost] does: without it, a flag recorded
     * exactly as a new connection is being registered could land in neither
     * that connection's backlog nor its live-push set, or in both.
     */
    fun broadcastDontRelayFlag(row: DontRelayFlagEntity) {
        val envelope = WireEnvelope.encode(WirePayloadType.DONT_RELAY_FLAG, row.toEnvelope().encode())
        val connectionsSnapshot: List<PeerConnection>
        val isNew: Boolean
        synchronized(outboxLock) {
            isNew = try {
                runBlocking { dontRelayRepository.recordFlag(row) }
            } catch (e: Exception) {
                onLog("Could not record own \"don't relay\" flag: ${e.message}")
                false
            }
            connectionsSnapshot = activeConnections.toList()
        }
        if (!isNew) {
            onLog("\"Don't relay\" flag was already recorded (or already expired); not re-broadcasting")
            return
        }
        onLog("Broadcasting a \"don't relay\" flag to ${connectionsSnapshot.size} connected peer(s)")
        for (connection in connectionsSnapshot) {
            if (!connection.trySend(envelope)) {
                onLog("Live \"don't relay\" flag send failed to a connected peer; dropping that connection")
                activeConnections.remove(connection)
            }
        }
    }

    /**
     * Sends [payload] wrapped in a [type]-tagged [WireEnvelope] to exactly
     * the one currently-connected peer whose most recent PREKEY_BUNDLE or
     * MESSAGE_CIPHERTEXT announcement identified them as [peerId] -- unlike
     * [broadcastPost], this never fans out to every open connection (prekey
     * bundles and message ciphertext are peer-specific; posts are not).
     *
     * Returns `false` (never throws) if no currently-open connection is
     * tagged with [peerId] -- either this peer isn't connected right now, or
     * this device has never received a PREKEY_BUNDLE/MESSAGE_CIPHERTEXT
     * envelope identifying which open connection is theirs. Still used
     * as-is for [WirePayloadType.PREKEY_BUNDLE] sends (bundles are never
     * relay-queued -- see [PendingMessageRepository]'s "No bundle-relay"
     * Limits note) and as [sendMessage]'s own fast-path attempt.
     */
    fun sendToPeer(peerId: String, type: WirePayloadType, payload: ByteArray): Boolean {
        val connection = activeConnections.firstOrNull { it.remotePeerId == peerId } ?: return false
        return connection.trySend(WireEnvelope.encode(type, payload))
    }

    /**
     * Phase 2 Slice 3: unicast-first, flood-on-failure send for a
     * [WirePayloadType.MESSAGE_CIPHERTEXT] envelope already addressed to
     * [recipientPeerId] (i.e. [payload] is a [MessageCiphertextEnvelope]-
     * encoded blob, decodable as one).
     *
     * **Fast path**: if [recipientPeerId] is a currently-connected, already-
     * identified peer, [sendToPeer] delivers it directly -- no relay custody
     * is taken, nothing is flooded, and no metadata beyond the direct peer is
     * exposed to anyone else. This is unchanged from pre-Slice-3 behavior and
     * is the common case ("both devices are right next to each other").
     *
     * **Fallback (only on fast-path failure)**: takes relay custody via
     * [pendingMessageRepository.considerForRelay] (so this message survives
     * this device's own process restart even if delivered to no one yet --
     * mirrors [broadcastPost]'s own Finding B rationale), then live-pushes
     * the same envelope bytes to every currently-connected peer, mirroring
     * [broadcastPost]'s shape exactly. This is the *only* path that ever
     * takes custody of a message: a message that unicasts successfully never
     * touches [PendingMessageEntity] at all, since there's nothing left
     * needing restart-survival once the bytes are already on the wire.
     *
     * Deliberately **not** symmetric with [broadcastPost]'s uniform flood --
     * see this class's own doc / the Phase 2 Slice 3 plan for why collapsing
     * the common "both devices in range" case into flood-and-persist would
     * leak sender/recipient metadata to every bystander for zero benefit and
     * contradict PRD §7's NFR that in-range delivery is a faster, implicitly
     * more private path than store-and-forward.
     *
     * Returns `false` when the fast path fails (even though custody was
     * taken and a flood was attempted) -- mirrors [sendToPeer]'s own
     * "delivered right now, yes or no" contract; callers (`MessageRepository.send`)
     * still persist the outgoing message to local history regardless, same
     * as before this slice.
     */
    fun sendMessage(recipientPeerId: String, payload: ByteArray): Boolean {
        if (sendToPeer(recipientPeerId, WirePayloadType.MESSAGE_CIPHERTEXT, payload)) {
            return true
        }

        val envelope = try {
            MessageCiphertextEnvelope.decode(payload)
        } catch (e: Exception) {
            onLog("Could not decode outgoing message envelope for relay custody: ${e.message}")
            return false
        }

        val wireBytes: ByteArray
        val connectionsSnapshot: List<PeerConnection>
        synchronized(outboxLock) {
            try {
                runBlocking { pendingMessageRepository.considerForRelay(envelope) }
            } catch (e: Exception) {
                onLog("Could not take relay custody of an outgoing message: ${e.message}")
            }
            wireBytes = WireEnvelope.encode(WirePayloadType.MESSAGE_CIPHERTEXT, payload)
            connectionsSnapshot = activeConnections.toList()
        }
        onLog("Recipient not directly connected -- flood-offering a message to ${connectionsSnapshot.size} connected peer(s)")
        for (connection in connectionsSnapshot) {
            if (!connection.trySend(wireBytes)) {
                onLog("Live message flood-offer failed to a connected peer; dropping that connection")
                activeConnections.remove(connection)
            }
        }
        return false
    }

    /**
     * Atomically registers [connection] as active and returns every
     * currently relay-eligible post from [relayRepository]'s persisted
     * queue, each already re-encoded with `hopCount + 1` and
     * [WireEnvelope]-wrapped, followed by every non-expired "don't relay"
     * flag from [dontRelayRepository]'s persisted queue, followed by every
     * relay-eligible message from [pendingMessageRepository]'s persisted
     * queue (Phase 2 Slice 3), followed by every relay-eligible bundle from
     * [bundleRepository]'s persisted queue (the prekey-bundle relay/discovery
     * follow-up) -- see
     * [outboxLock]'s doc for why this must share a lock with
     * [broadcastPost]/[broadcastDontRelayFlag]/[sendMessage] rather than
     * query any repository separately. [dontRelayRepository]'s flag backlog is offered
     * independent of whether this device also holds/relays the underlying
     * post -- see [DontRelayRepository.buildOutgoingFlagBacklog]'s own doc
     * for why (order-independence: a flag can legitimately exist here for a
     * clipHash this device has never received a post for). Room-backed, so
     * (unlike the old in-memory `outbox` this replaced) all three backlogs
     * survive this device's own process restart.
     *
     * The message backlog here is the blind, connect-time flood that gives
     * any newly-connected peer a chance to pick up custody or, if they turn
     * out to be the recipient, self-identify (see [DispatchResult.NewRelayableMessage])
     * and receive it -- offered independent of whether this connection's
     * remote peer id is known yet, mirroring the post/flag backlogs' own
     * unconditional-offer posture.
     */
    private fun registerConnectionAndGetBacklog(connection: PeerConnection): List<ByteArray> =
        synchronized(outboxLock) {
            activeConnections.add(connection)
            val postBacklog = runBlocking { relayRepository.buildOutgoingBacklog() }
            val flagBacklog = runBlocking { dontRelayRepository.buildOutgoingFlagBacklog() }
            val messageBacklog = runBlocking { pendingMessageRepository.buildOutgoingBacklog() }
            val bundleBacklog = runBlocking { bundleRepository.buildOutgoingBacklog() }
            postBacklog + flagBacklog + messageBacklog + bundleBacklog
        }

    /**
     * Thread-safe wrapper around one peer socket's output stream -- see
     * [activeConnections]'s doc. [remotePeerId] starts `null` and is set the
     * first time this connection carries a PREKEY_BUNDLE or
     * MESSAGE_CIPHERTEXT envelope identifying who's on the other end (see
     * [receivePosts]) -- a post-only connection may never learn this, which
     * is fine, since posts are never peer-targeted.
     */
    private class PeerConnection(socket: Socket) {
        private val out = DataOutputStream(socket.getOutputStream())
        private val writeLock = Any()

        @Volatile
        var remotePeerId: String? = null

        /**
         * Writes one already-[WireEnvelope]-encoded blob (self-framing: it
         * carries its own type + length header, so no additional outer
         * length prefix is written here). Returns `false` (never throws) on
         * any I/O failure.
         */
        fun trySend(envelope: ByteArray): Boolean = synchronized(writeLock) {
            try {
                out.write(envelope)
                out.flush()
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    @Volatile
    private var continuousDiscoveryActive = false

    /**
     * Identifies the group formation [onConnected] last actually acted on, as
     * `"$isGroupOwner:$groupOwnerAddress"`. WIFI_P2P_CONNECTION_CHANGED_ACTION
     * can fire more than once for the same real connection (observed on real
     * hardware in the spike) -- without this guard, [onConnected] would call
     * [runServer] twice for the same group, and the second bind to
     * [TRANSFER_PORT] would fail with EADDRINUSE.
     */
    @Volatile
    private var activeGroupKey: String? = null

    /**
     * The group owner's listening socket, held here (rather than only as a
     * local val in [runServer]) so [stopServer] can close it from another
     * thread to unblock a pending [ServerSocket.accept] call -- the only way
     * to interrupt a blocking accept() in Java.
     */
    @Volatile
    private var activeServerSocket: ServerSocket? = null

    /**
     * Publishes and sends this device's own *current* prekey bundle to
     * [connection] as a [WirePayloadType.PREKEY_BUNDLE]-tagged [WireEnvelope],
     * immediately on every new connection -- see this class's doc / the
     * Phase 1 messaging plan's "ambient bundle exchange" decision for why
     * this happens unconditionally rather than only on-demand at
     * message-send time: Phase 1 has no store-and-forward, so a peer who has
     * since walked away could otherwise never be messaged at all.
     *
     * Calls [PreKeyRotationManager.currentBundle] fresh on every connection
     * -- no memoization here (see [preKeyRotationManager]'s own doc for why
     * memoizing a single bundle across every peer was a correctness bug, not
     * just an efficiency shortcut). This stays cheap: [PreKeyRotationManager]
     * only does DB reads (plus, rarely, a batch-replenish/rotation write) on
     * the common path, never new EC/Kyber keypair generation per connection.
     *
     * [handleConnection] is the one place both the group-owner accept-loop
     * ([runServer]) and the client-connect path ([connectWithRetry]) already
     * converge, so calling this from there (rather than duplicating the call
     * in both) covers both sides of a WiFi Direct connection with one call
     * site.
     *
     * Always constructs the envelope with `hopCount = 0` -- this is, by
     * definition, a genuine direct announce (this device really is on the
     * other end of [connection]), never a relayed copy. See
     * [EnvelopeDispatcher.dispatch]'s `PREKEY_BUNDLE` branch for why that
     * distinction is load-bearing on the *receiving* end.
     */
    private fun announceOwnPreKeyBundle(connection: PeerConnection) {
        try {
            val ownPeerId = runBlocking { getOwnPeerId() }
            val bundleBytes = PreKeyBundleCodec.encode(preKeyRotationManager.currentBundle())
            val envelope = PreKeyBundleEnvelope(
                peerId = ownPeerId,
                hopCount = 0,
                originatedAtMs = System.currentTimeMillis(),
                bundleBytes = bundleBytes,
            )
            if (connection.trySend(WireEnvelope.encode(WirePayloadType.PREKEY_BUNDLE, envelope.encode()))) {
                onLog("Announced own prekey bundle to a newly connected peer")
            } else {
                onLog("Failed to announce own prekey bundle to a newly connected peer")
            }
        } catch (e: Exception) {
            onLog("Error announcing own prekey bundle: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun discoverPeers() {
        manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = onLog("WiFi Direct discovery initiated")
            override fun onFailure(reason: Int) = onLog("WiFi Direct discovery failed: reason $reason")
        })
    }

    /**
     * See `WifiDirectSpike.startContinuousDiscovery`'s original doc for the
     * bilateral-rediscovery finding this addresses: a single discovery burst
     * routinely misses another phone's own discovery window, so this device
     * stays in a continuously-discovering state for as long as it's running
     * (i.e. app foregrounded -- see [TransportManager]'s lifecycle binding),
     * matching the product's "open the app to see what's nearby" posture
     * rather than inventing always-on background scanning.
     */
    @SuppressLint("MissingPermission")
    fun startContinuousDiscovery(intervalMs: Long = 10_000) {
        if (continuousDiscoveryActive) return
        continuousDiscoveryActive = true
        onLog("Continuous WiFi Direct discovery started (every ${intervalMs}ms)")
        Thread({
            while (continuousDiscoveryActive) {
                discoverPeers()
                Thread.sleep(intervalMs)
            }
        }, "hop-wifi-discovery").start()
    }

    fun stopContinuousDiscovery() {
        if (!continuousDiscoveryActive) return
        continuousDiscoveryActive = false
        onLog("Continuous WiFi Direct discovery stopped")
    }

    @SuppressLint("MissingPermission")
    fun connectTo(device: WifiP2pDevice) {
        val config = WifiP2pConfig().apply { deviceAddress = device.deviceAddress }
        manager.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = onLog("Connect initiated to ${device.deviceAddress}")
            override fun onFailure(reason: Int) = onLog("Connect failed: reason $reason")
        })
    }

    /**
     * `true` once [onConnected] has handled a formed group and `false` again
     * once it's torn down -- [TransportManager.maybeConnect] checks this
     * before calling [connectTo]. Real-hardware finding: a classic WiFi
     * Direct device can only belong to one group at a time, so calling
     * `WifiP2pManager.connect()` again while already grouped doesn't start a
     * fresh negotiation -- the platform routes it through an "invite to
     * existing group" path instead, observed on real hardware to fail
     * outright (`SupplicantP2pIfaceHalAidlImpl.invite` returning a service
     * -specific exception) while doing nothing useful, and spamming logs with
     * misleading "Connect failed" lines every cooldown cycle even though the
     * device is, in fact, already connected.
     */
    fun hasActiveGroup(): Boolean = activeGroupKey != null

    /** Call on every WIFI_P2P_CONNECTION_CHANGED_ACTION event ([TransportManager] does this). */
    fun onConnected(info: WifiP2pInfo) {
        if (!info.groupFormed) {
            activeGroupKey = null
            stopServer()
            return
        }
        val groupKey = "${info.isGroupOwner}:${info.groupOwnerAddress?.hostAddress}"
        if (groupKey == activeGroupKey) {
            // Same group formation already being handled -- see activeGroupKey's doc.
            return
        }
        activeGroupKey = groupKey
        if (info.isGroupOwner) {
            runServer()
        } else {
            val host = info.groupOwnerAddress?.hostAddress ?: return
            runClient(host, groupKey)
        }
    }

    /**
     * Accepts and serves every client that joins this device's WiFi Direct
     * group, not just the first -- see [WifiDirectTransport]'s class doc /
     * the ported spike's own note on why a single-accept version silently
     * drops a 3rd+ phone joining the same group.
     */
    private fun runServer() {
        if (activeServerSocket != null) return
        Thread({
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
        }, "hop-wifi-server").start()
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

    /**
     * Retries the initial connect, backing off between attempts -- observed
     * on real hardware (Motorola client / Realme group owner):
     * WIFI_P2P_CONNECTION_CHANGED_ACTION fires on both devices at roughly the
     * same time, but [runServer]'s `ServerSocket(TRANSFER_PORT)` bind is not
     * guaranteed to complete before this device's own client-side `connect()`
     * fires -- a client that loses that race gets an immediate
     * `ECONNREFUSED` (nothing listening yet), not a slow timeout, so a single
     * connect attempt is not reliable. See [CONNECT_RETRY_ATTEMPTS]'s doc for
     * why the total budget is generous, and [connectWithRetry]'s doc for what
     * happens if it's exhausted anyway.
     */
    private fun runClient(hostAddress: String, groupKey: String) {
        Thread({ connectWithRetry(hostAddress, groupKey) }, "hop-wifi-client").start()
    }

    /**
     * [groupKey] is the value [activeGroupKey] held when this connection
     * attempt started. If every retry fails, this clears [activeGroupKey]
     * back to `null` -- but only if it still equals [groupKey], i.e. only if
     * nothing newer has superseded it in the meantime.
     *
     * Real-hardware bug this closes: before this guard existed, exhausting
     * [CONNECT_RETRY_ATTEMPTS] left [activeGroupKey] set forever (the OS-level
     * WiFi Direct group genuinely was still formed, so nothing else would
     * ever clear it) while no working socket existed and none of this
     * device's queued posts could ever be sent. Worse,
     * [TransportManager.maybeConnect] treats [hasActiveGroup] as "already
     * connected, don't bother reconnecting" -- so the device was stuck
     * permanently, with `dumpsys wifip2p` showing a healthy connection and no
     * error anywhere, until something external (toggling WiFi) forced a fresh
     * group teardown/reformation. That's not a workaround a real user knows
     * to reach for; a genuine failure here must be discoverable/recoverable
     * from the same opportunistic-connect loop everything else uses.
     */
    private fun connectWithRetry(hostAddress: String, groupKey: String) {
        var lastError: Exception? = null
        for (attempt in 1..CONNECT_RETRY_ATTEMPTS) {
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(hostAddress, TRANSFER_PORT), CONNECT_RETRY_TIMEOUT_MS)
                handleConnection(socket)
                return
            } catch (e: Exception) {
                lastError = e
                if (attempt < CONNECT_RETRY_ATTEMPTS) Thread.sleep(CONNECT_RETRY_DELAY_MS)
            }
        }
        onLog("Client error: ${lastError?.message}; giving up on this connection attempt")
        synchronized(this) {
            if (activeGroupKey == groupKey) activeGroupKey = null
        }
    }

    /**
     * Sends and receives over [socket] concurrently, independent of which
     * side WiFi Direct negotiated as group owner vs. client (ported
     * reasoning: group-owner assignment isn't something either device
     * controls, and tying "who sends" to a fixed role silently drops posts
     * queued on whichever side loses that coin flip). Both directions run on
     * background threads -- never the main thread -- since both do blocking
     * socket I/O plus (on receive) file/Room I/O.
     *
     * Also kicks off [announceOwnPreKeyBundle] on its own background thread
     * -- see that method's doc for why this is the single call site covering
     * both the group-owner accept-loop and the client-connect path.
     */
    private fun handleConnection(socket: Socket) {
        val connection = PeerConnection(socket)
        val backlog = registerConnectionAndGetBacklog(connection)
        Thread({ sendBacklog(connection, backlog) }, "hop-send").start()
        Thread({ announceOwnPreKeyBundle(connection) }, "hop-bundle-announce").start()
        Thread({ receivePosts(socket, connection) }, "hop-receive").start()
    }

    /**
     * Flushes every post/flag queued before this connection registered --
     * new posts/flags arrive via [broadcastPost]/[broadcastDontRelayFlag]'s
     * live push instead. After each successful send of a
     * [WirePayloadType.POST_FRAME] entry, awards a point for the hand-off
     * via [maybeAwardPointsForRelayedFrame] -- see that method's own doc for
     * the self-authored exclusion and why repeated calls across backlog
     * resends on reconnect are harmless by construction.
     */
    private fun sendBacklog(connection: PeerConnection, backlog: List<ByteArray>) {
        onLog("Sending ${backlog.size} queued item(s) to connected peer")
        for (envelopeBytes in backlog) {
            if (!connection.trySend(envelopeBytes)) {
                onLog("Send error while flushing backlog to a connected peer")
                break
            }
            maybeAwardPointsForBacklogEntry(envelopeBytes)
        }
    }

    /**
     * Decodes [envelopeBytes] only far enough to check whether it's a
     * [WirePayloadType.POST_FRAME] (flag backlog entries never earn points),
     * then delegates to [maybeAwardPointsForRelayedFrame]. Swallows decode
     * errors -- a malformed backlog entry should never crash the send loop
     * that already successfully wrote it to the socket.
     */
    private fun maybeAwardPointsForBacklogEntry(envelopeBytes: ByteArray) {
        try {
            val envelope = WireEnvelope.decode(envelopeBytes)
            if (envelope.type == WirePayloadType.POST_FRAME) {
                maybeAwardPointsForRelayedFrame(Frame.decode(envelope.payload))
            }
        } catch (e: Exception) {
            onLog("Could not evaluate a backlog entry for a points award: ${e.message}")
        }
    }

    /**
     * Awards one point via [pointsRepository] for successfully handing
     * [frame] to a peer -- gated on [frame] not being self-authored (its
     * `senderDeviceId` differs from this device's own [getOwnPeerId]) so a
     * device never earns credit for its own posts, only for genuinely
     * relaying someone else's. Called from both [handleNewlyReceivedFrame]'s
     * live-push loop and [sendBacklog] -- [pointsRepository]'s underlying
     * `points_ledger` table is clipHash-primary-keyed with an insert-IGNORE
     * conflict strategy, so repeated calls for the same clipHash (backlog
     * resend on every WiFi Direct reconnect, or a live push to several
     * peers) are harmless by construction. Never add a second "have I
     * already awarded this" check here -- let that DB constraint do it.
     */
    private fun maybeAwardPointsForRelayedFrame(frame: Frame) {
        runBlocking {
            val ownPeerId = getOwnPeerId()
            if (frame.senderDeviceId.toHexString() != ownPeerId) {
                pointsRepository.award(frame.clipHash.toHexString())
            }
        }
    }

    /**
     * Reads [WireEnvelope]s from [socket] until it closes/EOF -- a peer may
     * have several queued envelopes to send in one connection, not just one.
     * Each envelope's header (`[1-byte type][4-byte length]`) is read
     * manually via [DataInputStream] rather than buffering the whole
     * envelope and calling [WireEnvelope.decode] (which needs the complete
     * bytes up front, header included) -- functionally equivalent (same
     * [WirePayloadType.fromWireValue] validation, same [WireEnvelope]
     * construction), just without an extra header+payload copy.
     *
     * [connection] is removed from [activeConnections] once this loop ends
     * (peer disconnect, EOF, or error) -- this is the only place a
     * [PeerConnection] is deregistered, so [broadcastPost]/[sendToPeer] never
     * target a socket that's already gone.
     */
    private fun receivePosts(socket: Socket, connection: PeerConnection) {
        try {
            val input = DataInputStream(socket.getInputStream())
            while (true) {
                val typeByte = try {
                    input.readUnsignedByte()
                } catch (e: EOFException) {
                    break // peer closed the connection / sent everything it had
                }
                val length = try {
                    input.readInt()
                } catch (e: EOFException) {
                    onLog("Receive: connection closed mid-envelope-header -- ending this connection's receive loop")
                    break
                }
                if (length < 0) {
                    onLog("Receive: invalid envelope payload length $length -- ending this connection's receive loop")
                    break
                }
                val payload = ByteArray(length)
                input.readFully(payload)
                try {
                    val envelope = WireEnvelope(WirePayloadType.fromWireValue(typeByte), payload)
                    when (val result = runBlocking { envelopeDispatcher.dispatch(envelope) }) {
                        is DispatchResult.PeerIdentified -> {
                            connection.remotePeerId = result.peerId
                            deliverAnyPendingMessagesTo(result.peerId, connection)
                        }
                        is DispatchResult.NewPostFrame -> handleNewlyReceivedFrame(result.frame, arrivedOn = connection)
                        is DispatchResult.NewDontRelayFlag -> handleNewlyReceivedDontRelayFlag(result.row, arrivedOn = connection)
                        is DispatchResult.NewRelayableMessage -> {
                            connection.remotePeerId = result.senderPeerId
                            handleNewlyReceivedMessage(result.row, arrivedOn = connection)
                            deliverAnyPendingMessagesTo(result.senderPeerId, connection)
                        }
                        is DispatchResult.NewRelayableBundle -> handleNewlyReceivedBundle(result.row, arrivedOn = connection)
                        is DispatchResult.DirectBundleAnnounce -> {
                            connection.remotePeerId = result.peerId
                            if (result.row != null) handleNewlyReceivedBundle(result.row, arrivedOn = connection)
                            deliverAnyPendingMessagesTo(result.peerId, connection)
                        }
                        DispatchResult.NoOp -> Unit
                    }
                    onLog("Received and handled a ${envelope.type} envelope")
                } catch (e: Exception) {
                    // Don't let one bad/unexpected envelope kill the loop --
                    // framing is already correctly consumed above (we read
                    // exactly the declared header + payload byte count), so
                    // the socket's byte stream is still in a valid state to
                    // keep reading the next envelope.
                    onLog("Error handling received envelope: ${e.message}")
                }
            }
        } catch (e: SocketException) {
            // Expected on peer disconnect / stopServer() teardown.
        } catch (e: Exception) {
            onLog("Receive error: ${e.message}")
        } finally {
            activeConnections.remove(connection)
        }
    }

    /**
     * Called once per genuinely-new [Frame] this device receives (see
     * [DispatchResult.NewPostFrame]) -- takes relay custody of it via
     * [relayRepository] (so it survives this device's own process death and
     * gets offered as backlog to any peer that connects later, see
     * [registerConnectionAndGetBacklog]), then immediately live-pushes a
     * re-encoded copy (`hopCount + 1`) to every *other* currently-connected
     * peer, never back to [arrivedOn] -- there is no reason to echo a frame
     * to the peer that just sent it, and [ReceivedFrameStore]'s clipHash
     * dedup would just no-op it there anyway.
     *
     * This is what closes the "only reaches already-connected peers on
     * their *next* reconnect" gap: without this live push, a peer connected
     * to this device on a different link right now would not see a
     * just-relayed post until it happened to reconnect and re-request the
     * backlog. Mirrors [broadcastPost]'s own live-push for a self-authored
     * post.
     */
    private fun handleNewlyReceivedFrame(frame: Frame, arrivedOn: PeerConnection) {
        runBlocking { relayRepository.considerForRelay(frame) }
        val outgoingFrame = frame.copy(hopCount = frame.hopCount + 1)
        val envelope = WireEnvelope.encode(WirePayloadType.POST_FRAME, outgoingFrame.encode())
        for (connection in activeConnections) {
            if (connection === arrivedOn) continue
            if (connection.trySend(envelope)) {
                maybeAwardPointsForRelayedFrame(frame)
            } else {
                onLog("Live relay push failed to a connected peer; dropping that connection")
                activeConnections.remove(connection)
            }
        }
    }

    /**
     * Called once per genuinely-new [DontRelayFlagEntity] this device
     * records (see [DispatchResult.NewDontRelayFlag]) -- live-pushes it
     * onward to every *other* currently-connected peer, never back to
     * [arrivedOn], mirroring [handleNewlyReceivedFrame]'s own shape. Flags
     * never touch [relayRepository]/hop-count-based eligibility -- their own
     * propagation bound is TTL plus distinct-attested-key dedup alone (see
     * [DontRelayFlagEnvelope]'s own doc), and [dontRelayRepository.recordFlag]
     * has already been called by [EnvelopeDispatcher.dispatch] by the time
     * this runs.
     */
    private fun handleNewlyReceivedDontRelayFlag(row: DontRelayFlagEntity, arrivedOn: PeerConnection) {
        val envelope = WireEnvelope.encode(WirePayloadType.DONT_RELAY_FLAG, row.toEnvelope().encode())
        for (connection in activeConnections) {
            if (connection === arrivedOn) continue
            if (!connection.trySend(envelope)) {
                onLog("Live \"don't relay\" flag relay push failed to a connected peer; dropping that connection")
                activeConnections.remove(connection)
            }
        }
    }

    /**
     * Called once per genuinely-new [PendingMessageEntity] this device takes
     * custody of on behalf of someone else's conversation (see
     * [DispatchResult.NewRelayableMessage]) -- live-pushes a `hopCount + 1`
     * re-encoded copy to every *other* currently-connected peer, never back
     * to [arrivedOn], mirroring [handleNewlyReceivedFrame]'s own shape
     * exactly (Phase 2 Slice 3's flood-on-failure fallback applies just as
     * much to a message this device is only carrying as to one it
     * originated -- see [sendMessage]'s own doc). [pendingMessageRepository
     * .considerForRelay] has already been called by
     * [EnvelopeDispatcher.dispatch] by the time this runs.
     */
    private fun handleNewlyReceivedMessage(row: PendingMessageEntity, arrivedOn: PeerConnection) {
        val storedEnvelope = MessageCiphertextEnvelope.decode(row.encodedEnvelope)
        val outgoingEnvelope = storedEnvelope.copy(hopCount = storedEnvelope.hopCount + 1)
        val envelope = WireEnvelope.encode(WirePayloadType.MESSAGE_CIPHERTEXT, outgoingEnvelope.encode())
        for (connection in activeConnections) {
            if (connection === arrivedOn) continue
            if (!connection.trySend(envelope)) {
                onLog("Live message relay push failed to a connected peer; dropping that connection")
                activeConnections.remove(connection)
            }
        }
    }

    /**
     * Called once per genuinely-new [BundleQueueEntity] this device takes
     * relay custody of on behalf of a bundle it did not itself announce (see
     * [DispatchResult.NewRelayableBundle]) -- live-pushes a `hopCount + 1`
     * re-encoded copy to every *other* currently-connected peer, never back
     * to [arrivedOn], mirroring [handleNewlyReceivedMessage]'s own shape
     * exactly. [bundleRepository.considerForRelay] has already been called by
     * [EnvelopeDispatcher.dispatch] by the time this runs.
     *
     * Deliberately never touches `connection.remotePeerId` for *any*
     * connection here, including [arrivedOn] -- this method only exists for
     * a bundle this device is carrying, never one whose owner is genuinely on
     * the other end of a live connection (that case is
     * [DispatchResult.PeerIdentified], handled entirely separately). See
     * [EnvelopeDispatcher.dispatch]'s `PREKEY_BUNDLE` branch for the
     * message-misdirection bug this split closes.
     */
    private fun handleNewlyReceivedBundle(row: BundleQueueEntity, arrivedOn: PeerConnection) {
        val storedEnvelope = PreKeyBundleEnvelope.decode(row.encodedEnvelope)
        val outgoingEnvelope = storedEnvelope.copy(hopCount = storedEnvelope.hopCount + 1)
        val envelope = WireEnvelope.encode(WirePayloadType.PREKEY_BUNDLE, outgoingEnvelope.encode())
        for (connection in activeConnections) {
            if (connection === arrivedOn) continue
            if (!connection.trySend(envelope)) {
                onLog("Live bundle relay push failed to a connected peer; dropping that connection")
                activeConnections.remove(connection)
            }
        }
    }

    /**
     * Phase 2 Slice 3's local delivery-confirmation stop signal (see this
     * class's own doc for why no wire-level delivery-ack primitive exists
     * instead): once [connection]'s remote peer id is positively identified
     * as [identifiedPeerId] (via [DispatchResult.PeerIdentified] or
     * [DispatchResult.NewRelayableMessage]), checks
     * [pendingMessageRepository] for any locally-held rows addressed to
     * [identifiedPeerId], hands each directly over [connection], and on
     * success, deletes the row -- a self-observed fact ("I just handed this
     * directly to the peer it's addressed to"), never a claim asserted by
     * another device, so it isn't forgeable by a hostile peer. A `trySend`
     * failure leaves the row in place untouched -- it's still eligible for
     * [buildOutgoingBacklog]'s ordinary flood path next connection.
     */
    private fun deliverAnyPendingMessagesTo(identifiedPeerId: String, connection: PeerConnection) {
        val rows = runBlocking { pendingMessageRepository.findByRecipient(identifiedPeerId) }
        for (row in rows) {
            val envelope = WireEnvelope.encode(WirePayloadType.MESSAGE_CIPHERTEXT, row.encodedEnvelope)
            if (connection.trySend(envelope)) {
                runBlocking { pendingMessageRepository.delete(row.ciphertextHash) }
                onLog("Delivered a relay-held message directly to its now-identified recipient; dropped local custody")
            }
        }
    }
}

/**
 * Pure receive-path logic: decode a frame, dedupe against already-stored
 * posts, and (if new) store its decay key + ciphertext + [PostEntity].
 *
 * Factored out of [WifiDirectTransport] specifically so it's unit-testable
 * without a real Android `Context`/`WifiP2pManager` -- it depends only on
 * [PostRepository], [DecayKeyStore], and a plain [postsDir] `File`, the same
 * testability seam [com.hop.app.composer.PostComposerViewModel] already uses
 * for its own `postsDir: File` parameter.
 *
 * `internal` (not `private`) so a JVM unit test in this module can construct
 * it directly against a temp folder and hand-rolled fakes, without going
 * through sockets or [WifiDirectTransport] at all.
 */
internal class ReceivedFrameStore(
    private val postRepository: PostRepository,
    private val decayKeyStore: DecayKeyStore,
    private val postsDir: File,
    /**
     * Used only to decide whether this frame has already decayed as of
     * receipt (see [handle]'s "skip the store() call entirely" branch) --
     * a separate, injectable instance (rather than reaching for a
     * `RelayPolicy` owned elsewhere) so a test can advance its clock
     * independently of, or in lockstep with, [decayKeyStore]'s own clock.
     * Defaults to a real system clock in production, matching
     * [DecayKeyStore]'s own default.
     */
    private val relayPolicy: RelayPolicy = RelayPolicy(),
) {
    /**
     * Decodes [bytes] as a version-2 [Frame]. If a post with the same
     * `clipHash` is already stored (this device has already seen this post,
     * whether via this same peer reconnecting or a different peer), skips it
     * without touching [decayKeyStore] or the filesystem and returns `null`.
     * Otherwise anchors the decay-key expiry to this post's *original*
     * schedule (`frame.originatedAtMs + frame.ttlSeconds`, via
     * [RelayPolicy.expiresAtMs]) -- not to local receipt time -- stores the
     * wrapped content-encryption key under that absolute expiry unless it's
     * already in the past (see below), writes the ciphertext payload to
     * `$postsDir/$clipHash.enc`, inserts a [PostEntity] with
     * `receivedAtMs = now`, and returns the decoded [Frame].
     *
     * Anchoring to [Frame.originatedAtMs] rather than receipt time is a
     * correctness fix (Finding A), not a stylistic choice: at direct-hop
     * distance the two are nearly identical, so anchoring to receipt time
     * was harmless in Phase 1. Once multi-hop relay (Phase 2) adds queuing
     * delay between origination and this device's receipt, anchoring to
     * receipt time would silently re-extend a post's decryption-key
     * lifetime to "full TTL from whenever this relay hop happened to
     * forward it," defeating ADR 0003's decay guarantee for every
     * downstream recipient.
     *
     * If the frame has *already* decayed as of receipt (its origin-anchored
     * expiry is already in the past -- plausible after enough relay hops/
     * delay), the [decayKeyStore] `store()` call is skipped entirely rather
     * than storing a key that would be immediately unretrievable anyway.
     * The [PostEntity] row is still inserted either way, so the post still
     * renders as [com.hop.repository.PostRepository.DecryptResult.Decayed]
     * in the feed -- the same outcome as any other decayed post, not a
     * special case.
     *
     * This never decrypts -- it stores ciphertext + key separately, matching
     * [PostRepository]'s on-demand-decrypt design (`PostRepository.decrypt`),
     * not a force-decrypt-on-receive design.
     *
     * Runs real blocking I/O (file write, Room via [postRepository]/
     * [decayKeyStore]) synchronously via `runBlocking` -- callers must invoke
     * this off the main thread. Returns `null` (without throwing) if [bytes]
     * doesn't decode as a valid frame.
     */
    fun handle(bytes: ByteArray): Frame? {
        val frame = try {
            Frame.decode(bytes)
        } catch (e: Exception) {
            return null
        }

        val clipHashHex = frame.clipHash.toHexString()

        return runBlocking {
            if (postRepository.getByClipHash(clipHashHex) != null) {
                return@runBlocking null
            }

            if (!relayPolicy.isExpired(frame.originatedAtMs, frame.ttlSeconds)) {
                decayKeyStore.store(
                    contentId = clipHashHex,
                    wrappedCek = frame.contentEncryptionKey,
                    expiresAt = Instant.ofEpochMilli(
                        relayPolicy.expiresAtMs(frame.originatedAtMs, frame.ttlSeconds),
                    ),
                )
            }

            postsDir.mkdirs()
            val payloadFile = File(postsDir, "$clipHashHex.enc")
            payloadFile.writeBytes(frame.payload)

            postRepository.insert(
                PostEntity(
                    clipHash = clipHashHex,
                    senderDeviceId = frame.senderDeviceId.toHexString(),
                    contentType = frame.contentType.name,
                    originatedAtMs = frame.originatedAtMs,
                    ttlSeconds = frame.ttlSeconds,
                    reachTier = frame.reachTier.name,
                    dontRelay = frame.dontRelay,
                    receivedAtMs = System.currentTimeMillis(),
                    encryptedPayloadFilePath = payloadFile.absolutePath,
                ),
            )
            frame
        }
    }

    private fun ByteArray.toHexString(): String = joinToString(separator = "") { "%02x".format(it) }
}

/**
 * Pure receive-path dispatch logic: given one already-decoded [WireEnvelope],
 * routes to [ReceivedFrameStore.handle] (posts) or the
 * [onPreKeyBundleReceived]/[onMessageCiphertextReceived] callbacks
 * (messaging) -- the exact three-way split [WifiDirectTransport.receivePosts]
 * uses on the real socket receive loop.
 *
 * Factored out for the same reason [ReceivedFrameStore] is: fully testable
 * without a real Android `Context`/`WifiP2pManager`/live WiFi Direct group --
 * an instrumented test can drive it directly against real
 * [PreKeyBundleEnvelope]/[MessageCiphertextEnvelope] bytes and real
 * `MessageRepository` callbacks over a plain loopback socket, without a
 * `WifiP2pManager.Channel` in the loop at all. `internal` (not `private`) for
 * the same direct-construction reason as [ReceivedFrameStore].
 *
 * Deliberately contains zero libsignal-aware deserialization itself --
 * [onPreKeyBundleReceived] receives the bundle's bytes as-is, unparsed; only
 * this envelope's own protocol-level fields ([PreKeyBundleEnvelope.peerId] /
 * [MessageCiphertextEnvelope.senderPeerId]) are read here.
 */
internal class EnvelopeDispatcher(
    private val receivedFrameStore: ReceivedFrameStore,
    /**
     * Phase 2 Slice 2's "don't relay" flag counter -- [dispatch] calls
     * [DontRelayRepository.recordFlag] directly for a
     * [WirePayloadType.DONT_RELAY_FLAG] envelope, the same "this class does
     * the store/dedup step itself" shape [receivedFrameStore] already has
     * for posts.
     */
    private val dontRelayRepository: DontRelayRepository,
    /**
     * Phase 2 Slice 3's persisted relay-custody queue for offline 1:1
     * message recipients -- [dispatch]'s [WirePayloadType.MESSAGE_CIPHERTEXT]
     * branch calls [PendingMessageRepository.considerForRelay] directly
     * whenever a decoded envelope's `recipientPeerId` does **not** match
     * [getOwnPeerId] -- i.e. this device is being asked to carry the message
     * for someone else, not decrypt it. Only the *matching* branch ever
     * calls [onMessageCiphertextReceived] (`crypto/`-touching) -- see this
     * class's own doc for why that split is the load-bearing property here.
     */
    private val pendingMessageRepository: PendingMessageRepository,
    /**
     * The prekey-bundle relay/discovery follow-up's persisted mesh
     * flood-relay queue for prekey bundles -- [dispatch]'s
     * [WirePayloadType.PREKEY_BUNDLE] branch calls
     * [BundleRepository.considerForRelay] unconditionally, at every hop
     * including a genuine direct announce (`hopCount == 0`): this device
     * must take custody of a bundle it only ever learned directly too, or
     * the mesh never gets a first hop to relay from at all. What *does*
     * depend on [PreKeyBundleEnvelope.hopCount] is only whether the
     * connection's `remotePeerId` gets tagged with the bundle owner's id
     * ([DispatchResult.DirectBundleAnnounce] at hop 0 vs.
     * [DispatchResult.NewRelayableBundle] at `hopCount >= 1`, which must
     * never tag) -- see those cases' own docs for why conflating
     * "take custody" with "safe to tag" was the bug an earlier version of
     * this feature had.
     */
    private val bundleRepository: BundleRepository,
    /**
     * This device's own stable messaging identity -- a suspend function
     * (mirroring the same `getOwnPeerId: suspend () -> String` pattern
     * already used by [WifiDirectTransport]/`MessageRepository`) rather than
     * a plain value, since it's ultimately backed by
     * `SettingsRepository.getOrCreateStableSenderDeviceId()` (DataStore,
     * suspend). Used by [dispatch] to decide whether an arriving
     * [WirePayloadType.MESSAGE_CIPHERTEXT] envelope is genuinely addressed to
     * this device or must be relay-queued instead.
     */
    private val getOwnPeerId: suspend () -> String,
    private val onPreKeyBundleReceived: (peerId: String, bundleBytes: ByteArray) -> Unit,
    private val onMessageCiphertextReceived: suspend (senderPeerId: String, ciphertext: ByteArray) -> Unit,
) {
    /**
     * Handles [envelope] and returns what the caller needs to act on next --
     * see [DispatchResult]'s own doc for the seven cases.
     */
    suspend fun dispatch(envelope: WireEnvelope): DispatchResult = when (envelope.type) {
        WirePayloadType.POST_FRAME -> {
            val frame = receivedFrameStore.handle(envelope.payload)
            if (frame != null) DispatchResult.NewPostFrame(frame) else DispatchResult.NoOp
        }
        WirePayloadType.PREKEY_BUNDLE -> {
            val bundleEnvelope = PreKeyBundleEnvelope.decode(envelope.payload)
            // Cache unconditionally -- valid, cacheable bundle content
            // regardless of whether this connection's owner is the bundle's
            // owner or just a carrier. This is what makes group
            // member-introduction free (see BundleRepository's own doc).
            onPreKeyBundleReceived(bundleEnvelope.peerId, bundleEnvelope.bundleBytes)
            // Take relay custody at EVERY hop, including hop 0 -- a device
            // that only ever learns a bundle directly must still be able to
            // re-offer it onward, or the mesh has no first hop to relay from
            // at all (see DirectBundleAnnounce's own doc for the gap this
            // closes; a first version of this feature only took custody on
            // the hopCount >= 1 branch below, which meant nothing ever
            // seeded the relay queue in the first place).
            val row = bundleRepository.considerForRelay(bundleEnvelope)
            if (bundleEnvelope.hopCount == 0) {
                // Genuine direct announce -- the peer on the other end of
                // THIS connection really is bundleEnvelope.peerId. Safe to
                // tag, independent of whether custody-taking above succeeded.
                DispatchResult.DirectBundleAnnounce(row, bundleEnvelope.peerId)
            } else if (row != null) {
                // Being carried by someone else -- take relay custody, but
                // never tag this connection as bundleEnvelope.peerId (that
                // would make a later sendMessage() unicast fast-path believe
                // it reached the owner directly when it only reached a
                // carrier, skipping durable PendingMessageRepository custody
                // with nothing to fall back on).
                DispatchResult.NewRelayableBundle(row, bundleEnvelope.peerId)
            } else {
                DispatchResult.NoOp
            }
        }
        WirePayloadType.MESSAGE_CIPHERTEXT -> {
            val messageEnvelope = MessageCiphertextEnvelope.decode(envelope.payload)
            if (messageEnvelope.recipientPeerId == getOwnPeerId()) {
                // Genuinely for me -- unchanged pre-Slice-3 behavior.
                onMessageCiphertextReceived(messageEnvelope.senderPeerId, messageEnvelope.ciphertext)
                DispatchResult.PeerIdentified(messageEnvelope.senderPeerId)
            } else {
                // I'm being asked to carry this for someone else -- this
                // device has no Signal Protocol session for this
                // conversation at all and structurally cannot decrypt it
                // (see PendingMessageRepository's own doc).
                val row = pendingMessageRepository.considerForRelay(messageEnvelope)
                if (row != null) {
                    DispatchResult.NewRelayableMessage(row, messageEnvelope.senderPeerId)
                } else {
                    DispatchResult.NoOp
                }
            }
        }
        WirePayloadType.DONT_RELAY_FLAG -> {
            val flagEnvelope = DontRelayFlagEnvelope.decode(envelope.payload)
            val row = flagEnvelope.toEntity()
            val isNew = dontRelayRepository.recordFlag(row)
            if (isNew) DispatchResult.NewDontRelayFlag(row) else DispatchResult.NoOp
        }
    }
}

/**
 * The result of [EnvelopeDispatcher.dispatch] -- callers act differently
 * depending on which envelope type arrived:
 * - [PeerIdentified]: tag the connection this envelope arrived on with the
 *   peer id it identified (see
 *   [WifiDirectTransport.PeerConnection.remotePeerId]) so a later
 *   peer-targeted send ([WifiDirectTransport.sendToPeer]) can find it, and
 *   (Phase 2 Slice 3) check whether this device is holding any relay-custody
 *   messages addressed to that now-identified peer (see
 *   [WifiDirectTransport.deliverAnyPendingMessagesTo]).
 * - [NewPostFrame]: a genuinely-new [WirePayloadType.POST_FRAME] arrived
 *   ([ReceivedFrameStore.handle] stored it) -- take relay custody and
 *   live-push it onward (see
 *   [WifiDirectTransport.handleNewlyReceivedFrame]).
 * - [NewDontRelayFlag]: a genuinely-new [WirePayloadType.DONT_RELAY_FLAG]
 *   arrived (already recorded via [DontRelayRepository.recordFlag]) --
 *   live-push it onward (see
 *   [WifiDirectTransport.handleNewlyReceivedDontRelayFlag]).
 * - [NewRelayableMessage] (Phase 2 Slice 3): a genuinely-new
 *   [WirePayloadType.MESSAGE_CIPHERTEXT] envelope arrived addressed to a
 *   peer other than this device -- already taken into relay custody via
 *   [PendingMessageRepository.considerForRelay] by the time this is
 *   returned. Callers tag the connection with [senderPeerId] (same as
 *   [PeerIdentified]), live-push it onward (see
 *   [WifiDirectTransport.handleNewlyReceivedMessage]), and check for a local
 *   delivery-confirmation opportunity (same as [PeerIdentified]).
 * - [NewRelayableBundle] (the prekey-bundle relay/discovery follow-up): a
 *   genuinely-new (fresher than any row already held, or the first ever seen
 *   for this peer) [WirePayloadType.PREKEY_BUNDLE] envelope arrived at
 *   `hopCount >= 1` -- i.e. this connection merely *carried* it, its owner is
 *   not on the other end. Already taken into relay custody via
 *   [BundleRepository.considerForRelay] by the time this is returned.
 *   Callers live-push it onward (see
 *   [WifiDirectTransport.handleNewlyReceivedBundle]) but **must never** tag
 *   the connection's `remotePeerId` with [peerId] here -- unlike every other
 *   case above, this one is specifically the "not a direct line to this
 *   peer" case. See [EnvelopeDispatcher.dispatch]'s `PREKEY_BUNDLE` branch
 *   for the message-misdirection bug this distinction exists to close.
 * - [DirectBundleAnnounce]: a [WirePayloadType.PREKEY_BUNDLE] envelope
 *   arrived at `hopCount == 0` -- a genuine direct announce, the peer on the
 *   other end of *this* connection really is [peerId], so it's safe (and
 *   required, same as [PeerIdentified]) to tag the connection with it.
 *   Separately -- and this is the part a first version of this feature
 *   missed, since it only took relay custody on the `hopCount >= 1` branch
 *   below -- this device must *also* take relay custody of a bundle it only
 *   ever learned directly, or the mesh never gets a first hop to relay from
 *   at all (no device would ever re-offer a bundle it hadn't itself received
 *   via relay, so nothing would ever seed the flood). [row] is non-null iff
 *   [BundleRepository.considerForRelay] took genuinely-new custody, in which
 *   case callers must live-push it
 *   onward the same way [NewRelayableBundle] does (see
 *   [WifiDirectTransport.handleNewlyReceivedBundle]), *in addition to*
 *   tagging the connection -- these two actions are independent, not
 *   mutually exclusive, unlike the hop-gating check that decides between
 *   [DirectBundleAnnounce] and [NewRelayableBundle] in the first place.
 * - [NoOp]: nothing further for the caller to do -- either an envelope that
 *   turned out to be an already-seen clipHash/already-recorded flag/
 *   already-held message custody/a stale-or-ineligible bundle, or bytes that
 *   didn't decode.
 */
internal sealed interface DispatchResult {
    data class PeerIdentified(val peerId: String) : DispatchResult
    data class NewPostFrame(val frame: Frame) : DispatchResult
    data class NewDontRelayFlag(val row: DontRelayFlagEntity) : DispatchResult
    data class NewRelayableMessage(val row: PendingMessageEntity, val senderPeerId: String) : DispatchResult
    data class NewRelayableBundle(val row: BundleQueueEntity, val peerId: String) : DispatchResult
    data class DirectBundleAnnounce(val row: BundleQueueEntity?, val peerId: String) : DispatchResult
    data object NoOp : DispatchResult
}

/** Converts a decoded [DontRelayFlagEnvelope] to the hex-encoded [DontRelayFlagEntity] shape Room persists. */
private fun DontRelayFlagEnvelope.toEntity(): DontRelayFlagEntity = DontRelayFlagEntity(
    clipHash = clipHash.toHexString(),
    attestedDeviceKey = attestedDeviceKey.toHexString(),
    flaggedAtMs = flaggedAtMs,
    originatedAtMs = originatedAtMs,
    ttlSeconds = ttlSeconds,
)

/** Converts a persisted [DontRelayFlagEntity] back to its on-wire [DontRelayFlagEnvelope] shape. */
private fun DontRelayFlagEntity.toEnvelope(): DontRelayFlagEnvelope = DontRelayFlagEnvelope(
    clipHash = clipHash.hexToByteArray(),
    attestedDeviceKey = attestedDeviceKey.hexToByteArray(),
    flaggedAtMs = flaggedAtMs,
    originatedAtMs = originatedAtMs,
    ttlSeconds = ttlSeconds,
)

private fun ByteArray.toHexString(): String = joinToString(separator = "") { "%02x".format(it) }

private fun String.hexToByteArray(): ByteArray =
    ByteArray(length / 2) { i -> ((Character.digit(this[i * 2], 16) shl 4) + Character.digit(this[i * 2 + 1], 16)).toByte() }
