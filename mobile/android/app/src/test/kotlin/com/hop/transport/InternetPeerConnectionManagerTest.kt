package com.hop.transport

import com.hop.crypto.DecayKeyStore
import com.hop.data.BundleQueueDao
import com.hop.data.BundleQueueEntity
import com.hop.data.DontRelayFlagDao
import com.hop.data.DontRelayFlagEntity
import com.hop.data.PendingMessageDao
import com.hop.data.PendingMessageEntity
import com.hop.data.PostDao
import com.hop.data.PostEntity
import com.hop.data.RelayQueueDao
import com.hop.data.RelayQueueEntity
import com.hop.dht.Contact
import com.hop.dht.IntroduceResult
import com.hop.dht.NodeId
import com.hop.dht.PeerAddress
import com.hop.p2p.PeerChannel
import com.hop.relaynode.RelayNode
import com.hop.protocol.ContentType
import com.hop.protocol.DontRelayFlagEnvelope
import com.hop.protocol.EncryptedFrameCodec
import com.hop.protocol.Frame
import com.hop.protocol.MessageCiphertextEnvelope
import com.hop.protocol.PreKeyBundleEnvelope
import com.hop.protocol.ReachTier
import com.hop.protocol.RelayPolicy
import com.hop.protocol.TierKeyRequestEnvelope
import com.hop.protocol.TierMembershipClaim
import com.hop.protocol.WireEnvelope
import com.hop.protocol.WirePayloadType
import com.hop.repository.BundleRepository
import com.hop.repository.DontRelayRepository
import com.hop.repository.PendingMessageRepository
import com.hop.repository.PostRepository
import com.hop.repository.RelayRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Plain-JVM loopback-socket coverage for [InternetPeerConnectionManager] --
 * same "hand-rolled fakes + real loopback sockets" style as
 * [InternetPeerConnectionTest]/`p2p/`'s own `PeerChannelTest`. Since this
 * class's registry ([InternetPeerConnectionManager.connections]) is
 * deliberately private (no test-only accessor added for it -- this class's
 * own public surface is just [InternetPeerConnectionManager.connectToDiscoveredHolders]),
 * every assertion here observes registry behavior indirectly, through real
 * socket-level side effects: how many times a given loopback listener
 * actually [ServerSocket.accept]ed a connection, and whether a *second*
 * dial attempt happens after a connection genuinely closed.
 */
class InternetPeerConnectionManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    /**
     * [relayQueueDao] defaults to a fresh fake but can be shared with a
     * caller-supplied [DontRelayRepository]-backing instance -- mirroring
     * [com.hop.app.AppContainer]'s own production wiring, where
     * `relayRepository`/`dontRelayRepository` share one real
     * `RelayQueueDao` -- so a test seeding one repository's queue can assert
     * against the manager's actual combined backlog output.
     */
    private fun newManager(
        relayQueueDao: RelayQueueDao = FakeRelayQueueDao(),
        dontRelayFlagDao: DontRelayFlagDao = FakeDontRelayFlagDao(),
        pendingMessageDao: PendingMessageDao = FakePendingMessageDao(),
        bundleQueueDao: BundleQueueDao = FakeBundleQueueDao(),
        getOwnNodeId: () -> NodeId? = { null },
        introduceViaRendezvous: suspend (NodeId) -> IntroduceResult? = { null },
        relayBridgeTimeoutMs: Long = 3_000L,
        onLog: (String) -> Unit = {},
    ): InternetPeerConnectionManager = InternetPeerConnectionManager(
        postRepository = PostRepository(FakePostDao(), DecayKeyStore()),
        decayKeyStore = DecayKeyStore(),
        relayRepository = RelayRepository(relayQueueDao, RelayPolicy()),
        dontRelayRepository = DontRelayRepository(
            flagDao = dontRelayFlagDao,
            relayQueueDao = relayQueueDao,
            relayPolicy = RelayPolicy(),
        ),
        pendingMessageRepository = PendingMessageRepository(
            dao = pendingMessageDao,
            relayPolicy = RelayPolicy(),
        ),
        bundleRepository = BundleRepository(dao = bundleQueueDao, relayPolicy = RelayPolicy()),
        getOwnPeerId = { "me" },
        pendingTierKeyRequests = PendingTierKeyRequests(),
        postsDir = tempFolder.newFolder("posts-${System.nanoTime()}"),
        getOwnNodeId = getOwnNodeId,
        introduceViaRendezvous = introduceViaRendezvous,
        relayBridgeTimeoutMs = relayBridgeTimeoutMs,
        onLog = onLog,
    )

    /**
     * Raw [Frame.encode] bytes for a fresh, valid Locality post -- the same
     * shape [freshRelayQueueRow] wraps into a [RelayQueueEntity], but as the
     * bare bytes needed to build a `POST_FRAME` [WireEnvelope] a test can
     * send directly over a socket, simulating a post genuinely arriving on
     * one internet connection (as opposed to [freshRelayQueueRow]'s use --
     * seeding this device's own *outgoing* backlog).
     */
    private fun encodedFrameBytes(tag: String, hopCount: Int = 0): ByteArray {
        val plaintext = "post bytes for $tag".toByteArray()
        val clipHash = MessageDigest.getInstance("SHA-256").digest(plaintext)
        return EncryptedFrameCodec.encode(
            plaintext = plaintext,
            clipHash = clipHash,
            senderDeviceId = ByteArray(Frame.SENDER_DEVICE_ID_SIZE) { it.toByte() },
            contentType = ContentType.PHOTO,
            hopCount = hopCount,
            originatedAtMs = System.currentTimeMillis(),
            ttlSeconds = 3600L,
            reachTier = ReachTier.LOCALITY,
            dontRelay = false,
            originGeohashPrefix = "",
        ).encoded
    }

    /** One valid, relay-eligible [RelayQueueEntity] row -- same [Frame] shape [InternetPeerConnectionTest]'s own `encodedFrame` helper builds. */
    private fun freshRelayQueueRow(tag: String): RelayQueueEntity {
        val plaintext = "post bytes for $tag".toByteArray()
        val clipHash = MessageDigest.getInstance("SHA-256").digest(plaintext)
        val encoded = EncryptedFrameCodec.encode(
            plaintext = plaintext,
            clipHash = clipHash,
            senderDeviceId = ByteArray(Frame.SENDER_DEVICE_ID_SIZE) { it.toByte() },
            contentType = ContentType.PHOTO,
            hopCount = 0,
            originatedAtMs = System.currentTimeMillis(),
            ttlSeconds = 3600L,
            reachTier = ReachTier.LOCALITY,
            dontRelay = false,
            originGeohashPrefix = "",
        ).encoded
        val clipHashHex = clipHash.joinToString(separator = "") { "%02x".format(it) }
        return RelayQueueEntity(
            clipHash = clipHashHex,
            encodedFrame = encoded,
            hopCount = 0,
            originatedAtMs = System.currentTimeMillis(),
            ttlSeconds = 3600L,
            dontRelay = false,
            receivedAtMs = System.currentTimeMillis(),
        )
    }

    private fun freshDontRelayFlagRow(tag: String): DontRelayFlagEntity = DontRelayFlagEntity(
        clipHash = MessageDigest.getInstance("SHA-256").digest(tag.toByteArray()).joinToString(separator = "") { "%02x".format(it) },
        attestedDeviceKey = MessageDigest.getInstance("SHA-256").digest("$tag-device".toByteArray()).joinToString(separator = "") { "%02x".format(it) },
        flaggedAtMs = System.currentTimeMillis(),
        originatedAtMs = System.currentTimeMillis(),
        ttlSeconds = 3600L,
    )

    private fun freshPendingMessageRow(tag: String): PendingMessageEntity {
        val envelope = MessageCiphertextEnvelope(
            senderPeerId = "sender-$tag",
            recipientPeerId = "recipient-$tag",
            hopCount = 0,
            originatedAtMs = System.currentTimeMillis(),
            ciphertext = "ciphertext-$tag".toByteArray(),
        )
        return PendingMessageEntity(
            ciphertextHash = MessageDigest.getInstance("SHA-256").digest(envelope.ciphertext).joinToString(separator = "") { "%02x".format(it) },
            recipientPeerId = envelope.recipientPeerId,
            encodedEnvelope = envelope.encode(),
            hopCount = envelope.hopCount,
            originatedAtMs = envelope.originatedAtMs,
            receivedAtMs = System.currentTimeMillis(),
        )
    }

    private fun freshBundleQueueRow(tag: String): BundleQueueEntity {
        val envelope = PreKeyBundleEnvelope(
            peerId = "peer-$tag",
            hopCount = 0,
            originatedAtMs = System.currentTimeMillis(),
            bundleBytes = "bundle-$tag".toByteArray(),
        )
        return BundleQueueEntity(
            peerId = envelope.peerId,
            encodedEnvelope = envelope.encode(),
            hopCount = envelope.hopCount,
            originatedAtMs = envelope.originatedAtMs,
            receivedAtMs = System.currentTimeMillis(),
        )
    }

    private fun nodeId(seed: Int): NodeId = NodeId(ByteArray(NodeId.SIZE_BYTES) { (it + seed).toByte() })

    /** A real loopback [ServerSocket] that accepts connections onto [accepted] until [close]d, on a dedicated daemon thread. */
    private class LoopbackListener {
        private val serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = serverSocket.localPort
        val accepted = LinkedBlockingQueue<Socket>()

        init {
            Thread({
                try {
                    while (true) accepted.put(serverSocket.accept())
                } catch (e: Exception) {
                    // Expected once serverSocket.close() runs -- ends this loop.
                }
            }, "loopback-listener").apply { isDaemon = true; start() }
        }

        fun close() = serverSocket.close()
    }

    /** Frees up an ephemeral port and immediately closes it, so a dial against it is refused rather than accepted. */
    private fun unlistenedPort(): Int {
        val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val port = socket.localPort
        socket.close()
        return port
    }

    private fun loopbackContact(id: NodeId, port: Int): Contact = Contact(
        id = id,
        address = PeerAddress.encodeList(listOf(PeerAddress.from(InetAddress.getByName("127.0.0.1"), port))),
        lastSeenAtMs = 0L,
    )

    /**
     * A real, already-connected loopback [PeerChannel] pair -- `.first` is
     * what a real [com.hop.p2p.PeerListener] would have accepted (the side
     * [InternetPeerConnectionManager.acceptInbound] is handed in production),
     * `.second` is the "remote peer" side this test drives directly, playing
     * the role of whatever device dialed in. `soTimeoutMs` is applied to both
     * underlying sockets before wrapping, so a test's [PeerChannel.receiveEnvelope]
     * call never hangs indefinitely if the behavior under test is broken.
     */
    private fun loopbackChannelPair(soTimeoutMs: Int = 3_000): Pair<PeerChannel, PeerChannel> {
        val serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val clientRawSocket = Socket("127.0.0.1", serverSocket.localPort)
        val serverRawSocket = serverSocket.accept()
        serverSocket.close()
        serverRawSocket.soTimeout = soTimeoutMs
        clientRawSocket.soTimeout = soTimeoutMs
        return PeerChannel(serverRawSocket) to PeerChannel(clientRawSocket)
    }

    // -- acceptInbound: the PeerListener wiring's other half -- a connection
    // this device ACCEPTED (not dialed) must participate in the exact same
    // connect-time backlog + live-relay fanout machinery every outbound
    // connection already does, and must be evicted from inboundConnections
    // (not connections, which is NodeId-keyed and never applies here) once
    // its own receive loop ends. --

    @Test
    fun `acceptInbound delivers the connect-time backlog to a newly accepted inbound connection`() = runBlocking {
        val relayQueueDao = FakeRelayQueueDao()
        val dontRelayFlagDao = FakeDontRelayFlagDao()
        val pendingMessageDao = FakePendingMessageDao()
        val bundleQueueDao = FakeBundleQueueDao()
        relayQueueDao.insert(freshRelayQueueRow("inbound-post"))
        dontRelayFlagDao.insert(freshDontRelayFlagRow("inbound-flag"))
        pendingMessageDao.insert(freshPendingMessageRow("inbound-message"))
        bundleQueueDao.insertOrReplace(freshBundleQueueRow("inbound-bundle"))

        val manager = newManager(
            relayQueueDao = relayQueueDao,
            dontRelayFlagDao = dontRelayFlagDao,
            pendingMessageDao = pendingMessageDao,
            bundleQueueDao = bundleQueueDao,
        )
        val (acceptedSide, remoteSide) = loopbackChannelPair()

        manager.acceptInbound(acceptedSide)

        val receivedTypes = mutableSetOf<WirePayloadType>()
        repeat(4) { receivedTypes.add(remoteSide.receiveEnvelope().type) }

        assertEquals(
            setOf(
                WirePayloadType.POST_FRAME,
                WirePayloadType.DONT_RELAY_FLAG,
                WirePayloadType.MESSAGE_CIPHERTEXT,
                WirePayloadType.PREKEY_BUNDLE,
            ),
            receivedTypes,
            "an accepted inbound connection must receive the exact same four-repository backlog an outbound connection already does",
        )
    }

    @Test
    fun `an inbound connection receives a live-relay push relayed in from a different, outbound connection`() = runBlocking {
        val relayQueueDao = FakeRelayQueueDao()
        val manager = newManager(relayQueueDao = relayQueueDao)

        val outboundListener = LoopbackListener()
        val outboundContact = loopbackContact(nodeId(0), outboundListener.port)
        manager.connectToDiscoveredHolders(listOf(outboundContact))
        val outboundServerSocket = outboundListener.accepted.poll(2, TimeUnit.SECONDS)
        assertTrue(outboundServerSocket != null, "the outbound sibling must have been dialed")
        val outboundServerChannel = PeerChannel(outboundServerSocket!!)

        val (acceptedSide, remoteSide) = loopbackChannelPair()
        manager.acceptInbound(acceptedSide)

        val frameBytes = encodedFrameBytes("inbound-receives-outbound-live-relay")
        // This test plays the role of the remote peer on the OUTBOUND
        // connection, sending genuinely new content.
        outboundServerChannel.sendEnvelope(WireEnvelope(WirePayloadType.POST_FRAME, frameBytes))

        val relayed = remoteSide.receiveEnvelope()
        assertEquals(WirePayloadType.POST_FRAME, relayed.type)
        val relayedFrame = Frame.decode(relayed.payload)
        assertEquals(1, relayedFrame.hopCount, "a live-relayed frame must be re-encoded at hopCount + 1")
    }

    @Test
    fun `content received on an inbound connection propagates live to both an outbound sibling and another inbound sibling, but not back to itself`() = runBlocking {
        val relayQueueDao = FakeRelayQueueDao()
        val manager = newManager(relayQueueDao = relayQueueDao)

        val outboundListener = LoopbackListener()
        val outboundContact = loopbackContact(nodeId(1), outboundListener.port)
        manager.connectToDiscoveredHolders(listOf(outboundContact))
        val outboundServerSocket = outboundListener.accepted.poll(2, TimeUnit.SECONDS)
        assertTrue(outboundServerSocket != null, "the outbound sibling must have been dialed")
        outboundServerSocket!!.soTimeout = 3_000
        val outboundServerChannel = PeerChannel(outboundServerSocket)

        val (acceptedSideA, remoteSideA) = loopbackChannelPair()
        manager.acceptInbound(acceptedSideA)
        val (acceptedSideB, remoteSideB) = loopbackChannelPair()
        manager.acceptInbound(acceptedSideB)

        val frameBytes = encodedFrameBytes("inbound-fanout-to-both-registries")
        // remoteSideA plays the role of the remote peer on inbound connection
        // A, sending genuinely new content -- this is what "content arrives
        // on an inbound connection" means from the manager's own point of view.
        remoteSideA.sendEnvelope(WireEnvelope(WirePayloadType.POST_FRAME, frameBytes))

        val toOutboundSibling = outboundServerChannel.receiveEnvelope()
        assertEquals(WirePayloadType.POST_FRAME, toOutboundSibling.type, "an outbound sibling must receive content that arrived on an inbound connection")

        val toInboundSibling = remoteSideB.receiveEnvelope()
        assertEquals(WirePayloadType.POST_FRAME, toInboundSibling.type, "another inbound sibling must also receive it")

        // Must never be echoed back to the inbound connection it arrived on --
        // remoteSideA's underlying socket already has a bounded soTimeout
        // (set by loopbackChannelPair), so a read that times out (rather
        // than returning bytes) proves nothing was sent back.
        val echoedBack = try {
            remoteSideA.receiveEnvelope()
            true
        } catch (e: java.net.SocketTimeoutException) {
            false
        }
        assertTrue(!echoedBack, "content must never be echoed back to the inbound connection it arrived on")
    }

    @Test
    fun `an inbound connection's peer closing drives its receive loop to end and evicts it from the connection registry`() = runBlocking {
        val logs = mutableListOf<String>()
        val manager = newManager(onLog = { message -> synchronized(logs) { logs.add(message) } })
        val (acceptedSide, remoteSide) = loopbackChannelPair()

        manager.acceptInbound(acceptedSide)

        // Close the "remote" side -- this is what drives the manager's own
        // receive loop for this inbound connection to EOF and its onClosed
        // callback (which removes it from inboundConnections) to fire.
        remoteSide.close()

        var sawClosedLog = false
        repeat(30) {
            if (synchronized(logs) { logs.any { it.contains("Inbound internet connection closed", ignoreCase = true) } }) {
                sawClosedLog = true
                return@repeat
            }
            Thread.sleep(100)
        }
        assertTrue(sawClosedLog, "a closed inbound connection must be logged and removed from the registry: $logs")

        // Once removed, a later broadcast must never attempt (and therefore
        // never fail/log an eviction for) this already-gone connection --
        // this class's registry is deliberately private (see this test
        // class's own doc), so this is the same "observe indirectly" posture
        // every other eviction test in this file already uses.
        val logsBefore = synchronized(logs) { logs.size }
        manager.broadcastPost(encodedFrameBytes("post-after-inbound-already-closed"))
        Thread.sleep(200)
        val newLogs = synchronized(logs) { logs.drop(logsBefore) }
        assertTrue(
            newLogs.none { it.contains("Broadcast post send failed", ignoreCase = true) },
            "an already-removed inbound connection must not still be targeted (and fail) on a later broadcast: $newLogs",
        )
    }

    @Test
    fun `connects to each real loopback-reachable contact up to the cap and registers them`() = runBlocking {
        val listeners = List(3) { LoopbackListener() }
        val contacts = listeners.mapIndexed { index, listener -> loopbackContact(nodeId(index), listener.port) }
        val manager = newManager()

        manager.connectToDiscoveredHolders(contacts)

        listeners.forEach { listener ->
            val accepted = listener.accepted.poll(2, java.util.concurrent.TimeUnit.SECONDS)
            assertTrue(accepted != null, "each of the 3 (at-the-cap) contacts must have been dialed and accepted")
        }
    }

    @Test
    fun `a contact already registered from an earlier call is not dialed a second time`() = runBlocking {
        val listener = LoopbackListener()
        val contact = loopbackContact(nodeId(0), listener.port)
        val manager = newManager()

        manager.connectToDiscoveredHolders(listOf(contact))
        assertTrue(listener.accepted.poll(2, java.util.concurrent.TimeUnit.SECONDS) != null, "first call must dial and connect")

        // A second call with the exact same contact id must be skipped --
        // no second accept() should ever arrive.
        manager.connectToDiscoveredHolders(listOf(contact))
        val second = listener.accepted.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertTrue(second == null, "an already-registered contact must not be dialed a second time")
    }

    @Test
    fun `a contact beyond the per-call cap is skipped this cycle`() = runBlocking {
        // MAX_NEW_CONNECTIONS_PER_CALL is 3 -- hand this call 4 distinct
        // contacts and confirm only 3 are ever dialed.
        val listeners = List(4) { LoopbackListener() }
        val contacts = listeners.mapIndexed { index, listener -> loopbackContact(nodeId(index), listener.port) }
        val manager = newManager()

        manager.connectToDiscoveredHolders(contacts)

        val acceptedCounts = listeners.map { listener -> listener.accepted.poll(2, java.util.concurrent.TimeUnit.SECONDS) != null }
        assertEquals(3, acceptedCounts.count { it }, "exactly 3 of the 4 contacts (the per-call cap) must have been dialed")
    }

    @Test
    fun `a contact whose dial fails is logged and simply absent from the registry, no crash`() = runBlocking {
        val deadPort = unlistenedPort()
        val liveListener = LoopbackListener()
        val deadContact = loopbackContact(nodeId(0), deadPort)
        val liveContact = loopbackContact(nodeId(1), liveListener.port)
        val logs = mutableListOf<String>()
        val manager = newManager(onLog = { message -> synchronized(logs) { logs.add(message) } })

        // Must not throw -- a failed dial is caught internally.
        manager.connectToDiscoveredHolders(listOf(deadContact, liveContact))

        assertTrue(liveListener.accepted.poll(2, java.util.concurrent.TimeUnit.SECONDS) != null, "the live contact must still have been dialed and connected despite the dead one failing")
        assertTrue(logs.any { it.contains("Failed to dial", ignoreCase = true) }, "a failed dial must be logged: $logs")
    }

    @Test
    fun `the onClosed callback fires and the registry entry is removed once the peer closes the connection`() = runBlocking {
        val listener = LoopbackListener()
        val contact = loopbackContact(nodeId(0), listener.port)
        val manager = newManager()

        manager.connectToDiscoveredHolders(listOf(contact))
        val serverSideSocket = listener.accepted.poll(2, java.util.concurrent.TimeUnit.SECONDS)
        assertTrue(serverSideSocket != null, "first dial must succeed")

        // Close the server side of the connection the manager dialed --
        // this is what drives the manager's own receive loop to EOF and its
        // onClosed callback to fire, removing the registry entry.
        serverSideSocket!!.close()

        // Poll: a second connectToDiscoveredHolders call for the *same*
        // contact should now dial again (a fresh accept() arrives) once --
        // and only once -- the registry entry has actually been removed.
        // Retry a bounded number of times rather than a single race-prone
        // check, since onClosed fires asynchronously on the receive thread.
        var reconnected = false
        repeat(20) {
            manager.connectToDiscoveredHolders(listOf(contact))
            if (listener.accepted.poll(300, java.util.concurrent.TimeUnit.MILLISECONDS) != null) {
                reconnected = true
                return@repeat
            }
        }
        assertTrue(reconnected, "once the registry entry for a closed connection is removed, a later call must be able to dial that contact again")
    }

    @Test
    fun `a connection that closes essentially immediately after connecting never leaves a permanently stuck registry entry`() = runBlocking {
        // Bug B's regression test, isolated to a single abortively-closing
        // contact (no healthy sibling in the same call, unlike the existing
        // eviction tests further below): before the fix, registering
        // connections[contact.id] only *after* connectTo() returned could
        // lose the race against the receive thread's own onClosed callback
        // (which removes that same entry) when the remote peer resets the
        // connection immediately after accepting -- leaving contact.id
        // permanently "occupied" in the registry with nothing left to ever
        // remove it, so connectToDiscoveredHolders's own
        // `if (connections.containsKey(contact.id)) continue` guard would
        // skip this contact forever, on every future call.
        val abortiveListener = AbortiveCloseListener()
        val contact = loopbackContact(nodeId(0), abortiveListener.port)
        val manager = newManager()

        manager.connectToDiscoveredHolders(listOf(contact))

        // Poll: if the fix is in place, this contact's registry entry (if
        // ever registered at all) gets promptly removed once its receive
        // loop notices the abortive close, so a later call must be able to
        // dial (and be accepted by) it again. Before the fix, a lost-race
        // stuck entry would make this contact permanently skipped and this
        // assertion would time out.
        val acceptedBefore = abortiveListener.acceptedCount.get()
        var reconnected = false
        repeat(30) {
            manager.connectToDiscoveredHolders(listOf(contact))
            if (abortiveListener.acceptedCount.get() > acceptedBefore) {
                reconnected = true
                return@repeat
            }
            Thread.sleep(100)
        }
        assertTrue(
            reconnected,
            "a connection that closes essentially immediately after connecting must never permanently occupy its contact's registry slot",
        )
    }

    @Test
    fun `a newly-connected internet peer receives the full backlog, one entry from each of the four repositories`() = runBlocking {
        val relayQueueDao = FakeRelayQueueDao()
        val dontRelayFlagDao = FakeDontRelayFlagDao()
        val pendingMessageDao = FakePendingMessageDao()
        val bundleQueueDao = FakeBundleQueueDao()
        relayQueueDao.insert(freshRelayQueueRow("post"))
        dontRelayFlagDao.insert(freshDontRelayFlagRow("flag"))
        pendingMessageDao.insert(freshPendingMessageRow("message"))
        bundleQueueDao.insertOrReplace(freshBundleQueueRow("bundle"))

        val manager = newManager(
            relayQueueDao = relayQueueDao,
            dontRelayFlagDao = dontRelayFlagDao,
            pendingMessageDao = pendingMessageDao,
            bundleQueueDao = bundleQueueDao,
        )
        val listener = LoopbackListener()
        val contact = loopbackContact(nodeId(0), listener.port)

        manager.connectToDiscoveredHolders(listOf(contact))
        val serverSideSocket = listener.accepted.poll(2, java.util.concurrent.TimeUnit.SECONDS)
        assertTrue(serverSideSocket != null, "dial must succeed")
        serverSideSocket!!.soTimeout = 3_000

        val serverChannel = PeerChannel(serverSideSocket)
        val receivedTypes = mutableSetOf<WirePayloadType>()
        repeat(4) {
            receivedTypes.add(serverChannel.receiveEnvelope().type)
        }

        assertEquals(
            setOf(
                WirePayloadType.POST_FRAME,
                WirePayloadType.DONT_RELAY_FLAG,
                WirePayloadType.MESSAGE_CIPHERTEXT,
                WirePayloadType.PREKEY_BUNDLE,
            ),
            receivedTypes,
            "the backlog must carry one entry from each of the four repositories, correctly decodable by type",
        )
    }

    @Test
    fun `an empty backlog sends nothing and does not error`() = runBlocking {
        val manager = newManager()
        val listener = LoopbackListener()
        val contact = loopbackContact(nodeId(0), listener.port)

        manager.connectToDiscoveredHolders(listOf(contact))
        val serverSideSocket = listener.accepted.poll(2, java.util.concurrent.TimeUnit.SECONDS)
        assertTrue(serverSideSocket != null, "dial must succeed")

        // Nothing was queued in any of the four repositories, so nothing
        // should ever arrive on this connection -- a short bounded wait for
        // any byte is enough to distinguish "sent nothing" from "sent
        // something," without hanging the test indefinitely.
        serverSideSocket!!.soTimeout = 500
        val threw = try {
            serverSideSocket.getInputStream().read()
            false
        } catch (e: java.net.SocketTimeoutException) {
            true
        }
        assertTrue(threw, "an empty backlog must send nothing at all -- no bytes should ever arrive")
    }

    @Test
    fun `a send failure partway through one contact's backlog is logged, not thrown, and does not crash the connect loop for a different contact`() = runBlocking {
        val relayQueueDao = FakeRelayQueueDao()
        // Multiple queued posts so the backlog send loop has more than one
        // write to attempt -- increases the odds an abortive remote close is
        // actually observed mid-stream rather than only on the very first
        // (possibly kernel-buffered) write.
        relayQueueDao.insert(freshRelayQueueRow("post-1"))
        relayQueueDao.insert(freshRelayQueueRow("post-2"))
        relayQueueDao.insert(freshRelayQueueRow("post-3"))

        val logs = mutableListOf<String>()
        val manager = newManager(relayQueueDao = relayQueueDao, onLog = { message -> synchronized(logs) { logs.add(message) } })

        val abortiveListener = AbortiveCloseListener()
        val liveListener = LoopbackListener()
        val abortiveContact = loopbackContact(nodeId(0), abortiveListener.port)
        val liveContact = loopbackContact(nodeId(1), liveListener.port)

        // Must not throw, and must not prevent the second (live) contact in
        // the same call from being dialed and receiving its own backlog.
        manager.connectToDiscoveredHolders(listOf(abortiveContact, liveContact))

        val liveServerSideSocket = liveListener.accepted.poll(2, java.util.concurrent.TimeUnit.SECONDS)
        assertTrue(liveServerSideSocket != null, "the live contact must still have been dialed and connected despite the other connection's backlog send failing")
        liveServerSideSocket!!.soTimeout = 3_000
        val liveServerChannel = PeerChannel(liveServerSideSocket)
        // The live contact must receive its own full (unrelated, independently-registered) backlog --
        // but this test only seeded relayQueueDao, shared across both manager instances' repositories,
        // so the live contact's connection also offers the same 3 queued posts.
        repeat(3) {
            assertEquals(WirePayloadType.POST_FRAME, liveServerChannel.receiveEnvelope().type)
        }

        // Poll briefly for the send-error log line -- it's written from the
        // abortive contact's own dedicated "hop-internet-send" thread,
        // concurrently with everything above.
        var sawSendErrorLog = false
        repeat(20) {
            if (synchronized(logs) { logs.any { it.contains("Send error", ignoreCase = true) } }) {
                sawSendErrorLog = true
                return@repeat
            }
            Thread.sleep(100)
        }
        assertTrue(sawSendErrorLog, "a mid-backlog send failure must be logged, not thrown: $logs")
    }

    // -- Live relay-flood fanout: content arriving on one open internet
    // connection propagating onward to *other* already-open internet
    // connections, live -- as opposed to the connect-time backlog tests
    // above, which cover only already-queued content offered once at connect
    // time. --

    @Test
    fun `a POST_FRAME received on one internet connection propagates live to a second, with hopCount bumped, and is never echoed back`() = runBlocking {
        val relayQueueDao = FakeRelayQueueDao()
        val manager = newManager(relayQueueDao = relayQueueDao)
        val listenerA = LoopbackListener()
        val listenerB = LoopbackListener()
        val contactA = loopbackContact(nodeId(0), listenerA.port)
        val contactB = loopbackContact(nodeId(1), listenerB.port)

        manager.connectToDiscoveredHolders(listOf(contactA, contactB))
        val serverSocketA = listenerA.accepted.poll(2, TimeUnit.SECONDS)
        val serverSocketB = listenerB.accepted.poll(2, TimeUnit.SECONDS)
        assertTrue(serverSocketA != null && serverSocketB != null, "both contacts must have been dialed")
        serverSocketB!!.soTimeout = 3_000
        val serverChannelA = PeerChannel(serverSocketA!!)
        val serverChannelB = PeerChannel(serverSocketB)

        val frameBytes = encodedFrameBytes("live-post")
        val clipHashHex = Frame.decode(frameBytes).clipHash.joinToString(separator = "") { "%02x".format(it) }

        // This test plays the role of the remote peer on connection A,
        // sending a genuinely new post -- this is what "content arrives on
        // one internet connection" means from the manager's own point of view.
        serverChannelA.sendEnvelope(WireEnvelope(WirePayloadType.POST_FRAME, frameBytes))

        // Must propagate live to connection B, hopCount bumped by exactly one.
        val relayed = serverChannelB.receiveEnvelope()
        assertEquals(WirePayloadType.POST_FRAME, relayed.type)
        val relayedFrame = Frame.decode(relayed.payload)
        assertEquals(1, relayedFrame.hopCount, "a live-relayed frame must be re-encoded at hopCount + 1")
        assertEquals(clipHashHex, relayedFrame.clipHash.joinToString(separator = "") { "%02x".format(it) })

        // Must never be echoed back to the connection it arrived on (A).
        serverSocketA!!.soTimeout = 500
        val echoedBack = try {
            serverSocketA.getInputStream().read()
            true
        } catch (e: java.net.SocketTimeoutException) {
            false
        }
        assertTrue(!echoedBack, "a live-relayed frame must never be echoed back to the connection it arrived on")

        // relayRepository.considerForRelay must actually have been invoked --
        // the shared relayQueueDao now holds a row for this clipHash, stored
        // at the hop count it was *received* at (0), independent of the
        // hopCount+1 bumped copy sent onward to B.
        var tookCustody = false
        repeat(20) {
            if (relayQueueDao.getAll().any { it.clipHash == clipHashHex }) {
                tookCustody = true
                return@repeat
            }
            Thread.sleep(50)
        }
        assertTrue(tookCustody, "relayRepository.considerForRelay must have taken custody of the freshly-received frame")
    }

    @Test
    fun `a DONT_RELAY_FLAG received on one internet connection propagates live to a second, unchanged, and is never echoed back`() = runBlocking {
        val manager = newManager()
        val listenerA = LoopbackListener()
        val listenerB = LoopbackListener()
        val contactA = loopbackContact(nodeId(0), listenerA.port)
        val contactB = loopbackContact(nodeId(1), listenerB.port)

        manager.connectToDiscoveredHolders(listOf(contactA, contactB))
        val serverSocketA = listenerA.accepted.poll(2, TimeUnit.SECONDS)
        val serverSocketB = listenerB.accepted.poll(2, TimeUnit.SECONDS)
        assertTrue(serverSocketA != null && serverSocketB != null, "both contacts must have been dialed")
        serverSocketB!!.soTimeout = 3_000
        val serverChannelA = PeerChannel(serverSocketA!!)
        val serverChannelB = PeerChannel(serverSocketB)

        val clipHashBytes = MessageDigest.getInstance("SHA-256").digest("live-flag".toByteArray())
        val deviceKeyBytes = MessageDigest.getInstance("SHA-256").digest("live-flag-device".toByteArray())
        val flagEnvelope = DontRelayFlagEnvelope(
            clipHash = clipHashBytes,
            attestedDeviceKey = deviceKeyBytes,
            flaggedAtMs = System.currentTimeMillis(),
            originatedAtMs = System.currentTimeMillis(),
            ttlSeconds = 3600L,
        )

        serverChannelA.sendEnvelope(WireEnvelope(WirePayloadType.DONT_RELAY_FLAG, flagEnvelope.encode()))

        val relayed = serverChannelB.receiveEnvelope()
        assertEquals(WirePayloadType.DONT_RELAY_FLAG, relayed.type)
        val relayedFlag = DontRelayFlagEnvelope.decode(relayed.payload)
        assertContentEquals(clipHashBytes, relayedFlag.clipHash)
        assertContentEquals(deviceKeyBytes, relayedFlag.attestedDeviceKey)

        serverSocketA!!.soTimeout = 500
        val echoedBack = try {
            serverSocketA.getInputStream().read()
            true
        } catch (e: java.net.SocketTimeoutException) {
            false
        }
        assertTrue(!echoedBack, "a live-relayed \"don't relay\" flag must never be echoed back to the connection it arrived on")
    }

    @Test
    fun `with only one open internet connection, a live-relayed frame has nothing to fan out to, and nothing throws or is echoed back`() = runBlocking {
        val relayQueueDao = FakeRelayQueueDao()
        val manager = newManager(relayQueueDao = relayQueueDao)
        val listenerA = LoopbackListener()
        val contactA = loopbackContact(nodeId(0), listenerA.port)

        manager.connectToDiscoveredHolders(listOf(contactA))
        val serverSocketA = listenerA.accepted.poll(2, TimeUnit.SECONDS)
        assertTrue(serverSocketA != null, "the one contact must have been dialed")
        val serverChannelA = PeerChannel(serverSocketA!!)

        val frameBytes = encodedFrameBytes("live-post-lone-connection")
        val clipHashHex = Frame.decode(frameBytes).clipHash.joinToString(separator = "") { "%02x".format(it) }

        // Must not throw -- there is no sibling connection to fan out to.
        serverChannelA.sendEnvelope(WireEnvelope(WirePayloadType.POST_FRAME, frameBytes))

        // Custody must still be taken even though there is nothing to relay to.
        var tookCustody = false
        repeat(20) {
            if (relayQueueDao.getAll().any { it.clipHash == clipHashHex }) {
                tookCustody = true
                return@repeat
            }
            Thread.sleep(50)
        }
        assertTrue(tookCustody, "custody must be taken regardless of fanout width")

        // And nothing must ever be sent back on the one connection that exists.
        serverSocketA!!.soTimeout = 500
        val sentBack = try {
            serverSocketA.getInputStream().read()
            true
        } catch (e: java.net.SocketTimeoutException) {
            false
        }
        assertTrue(!sentBack, "with only one connection open, nothing should ever be sent back on it")
    }

    @Test
    fun `a sibling connection whose live-relay send fails is evicted, without preventing delivery to a healthy sibling`() = runBlocking {
        val relayQueueDao = FakeRelayQueueDao()
        val manager = newManager(relayQueueDao = relayQueueDao)
        val listenerA = LoopbackListener()
        val listenerB = LoopbackListener()
        val abortiveListenerC = AbortiveCloseListener()
        val contactA = loopbackContact(nodeId(0), listenerA.port)
        val contactB = loopbackContact(nodeId(1), listenerB.port)
        val contactC = loopbackContact(nodeId(2), abortiveListenerC.port)

        manager.connectToDiscoveredHolders(listOf(contactA, contactB, contactC))
        val serverSocketA = listenerA.accepted.poll(2, TimeUnit.SECONDS)
        val serverSocketB = listenerB.accepted.poll(2, TimeUnit.SECONDS)
        assertTrue(serverSocketA != null && serverSocketB != null, "the two healthy contacts must have been dialed")
        serverSocketB!!.soTimeout = 3_000
        val serverChannelA = PeerChannel(serverSocketA!!)
        val serverChannelB = PeerChannel(serverSocketB)
        val acceptedByAbortiveListenerBeforeEvent = abortiveListenerC.acceptedCount.get()

        // Give connection C's own dial/receive machinery a moment to have
        // actually reached the abortive close before the live-relay event
        // below -- avoids a benign race where C hasn't even connected yet.
        repeat(20) {
            if (abortiveListenerC.acceptedCount.get() > 0) return@repeat
            Thread.sleep(50)
        }

        val frameBytes = encodedFrameBytes("live-post-with-dead-sibling")
        serverChannelA.sendEnvelope(WireEnvelope(WirePayloadType.POST_FRAME, frameBytes))

        // The healthy sibling (B) must still receive the live-relayed frame
        // despite C being dead -- a slow/failing send to C must never block
        // or drop delivery to a different, healthy connection.
        val relayed = serverChannelB.receiveEnvelope()
        assertEquals(WirePayloadType.POST_FRAME, relayed.type, "the healthy sibling must still receive the live-relayed frame despite the dead sibling")

        // The dead connection (C) must have been evicted from the registry.
        // This class's registry is deliberately private (see this test
        // class's own doc), so eviction is verified indirectly: a later
        // connectToDiscoveredHolders call for the same contact must be able
        // to dial (and be accepted by) it again, which would be impossible
        // if a stale entry still occupied that contact's registry slot.
        // Eviction may happen via this connection's own receive loop noticing
        // the abortive close (onClosed) or via the live-relay fanout's own
        // failed-send cleanup -- both are correct, and TCP's coupled read/
        // write failure semantics on an already-reset connection make it
        // impossible to deterministically pin down which one fires first;
        // either way, the dead entry must not wedge future dials to this
        // contact.
        var reconnected = false
        repeat(20) {
            manager.connectToDiscoveredHolders(listOf(contactC))
            if (abortiveListenerC.acceptedCount.get() > acceptedByAbortiveListenerBeforeEvent) {
                reconnected = true
                return@repeat
            }
            Thread.sleep(100)
        }
        assertTrue(reconnected, "once the dead connection is evicted, a later dial to the same contact must succeed again")
    }

    /**
     * A [Contact] pointing at an RFC 5737 TEST-NET-1 address (192.0.2.0/24)
     * -- reserved for documentation/example use, so this looks like a
     * perfectly ordinary routable IPv4 address but is guaranteed to never be
     * assigned to a real host. In *this* codebase's own sandboxed test
     * environment, a dial attempt against it was empirically observed to
     * neither be refused nor "no route"-failed quickly -- it genuinely hangs
     * until [com.hop.p2p.PeerDialer]'s own `FALLBACK_CONNECT_TIMEOUT_MS`
     * (2000ms) elapses, faithfully simulating an unreachable/firewalled real
     * internet peer, the exact scenario this per-call cap's concurrency
     * matters for.
     *
     * That behavior is *not* guaranteed across every environment this test
     * might run in -- a different network/firewall/OS configuration could
     * legitimately fail-fast against this address instead of hanging. Doing
     * so wouldn't make this test *fail*, but it would make it silently stop
     * testing anything real: 3 fast failures finish quickly whether or not
     * the fix under test actually runs them concurrently, so the timing
     * assertion below would pass for the wrong reason. [assumeBlackHoleHangs]
     * guards against that, matching the same `Assume`-based "skip, don't
     * silently-pass" posture `com.hop.p2p.PeerDialerTest`'s
     * `assumeIpv6LoopbackAvailable` already established in this codebase for
     * the identical class of environment-dependent-network-behavior problem.
     */
    private fun blackHoleContact(id: NodeId): Contact = Contact(
        id = id,
        address = PeerAddress.encodeList(listOf(PeerAddress.from(InetAddress.getByName("192.0.2.1"), 9))),
        lastSeenAtMs = 0L,
    )

    /**
     * Probes whether [blackHoleContact]'s address genuinely hangs in *this*
     * environment by dialing it once and checking the single dial took
     * meaningfully close to the full timeout, not a fast failure -- skips
     * the calling test (reported as skipped, not failed) via [assumeTrue]
     * if it doesn't, so the concurrency test below never asserts a timing
     * claim it can't actually back up here.
     */
    private fun assumeBlackHoleHangs() {
        val probeElapsedMs = measureTimeMillis {
            try {
                com.hop.p2p.PeerDialer.dial(
                    listOf(PeerAddress.from(InetAddress.getByName("192.0.2.1"), 9)),
                )
            } catch (e: Exception) {
                // Expected -- a black-hole dial never succeeds; only its
                // *timing* is what this probe cares about.
            }
        }
        org.junit.Assume.assumeTrue(
            "192.0.2.1 did not hang for close to the full connect timeout in this environment " +
                "(took ${probeElapsedMs}ms) -- skipping, since the concurrency assertion below can't " +
                "be trusted without a genuinely slow dial to race against",
            probeElapsedMs > 1_000,
        )
    }

    @Test
    fun `new candidates are dialed concurrently, not sequentially -- total time for 3 slow dials is close to one dial's duration, not three`() = runBlocking {
        assumeBlackHoleHangs()

        // Each of these 3 contacts is a "black hole" address (see
        // blackHoleContact's own doc) -- a real dial attempt against any one
        // of them genuinely blocks for close to
        // PeerDialer.FALLBACK_CONNECT_TIMEOUT_MS (2000ms) before giving up,
        // faithfully simulating an unreachable real internet peer. Before
        // the fix, connectToDiscoveredHolders dialed its (up to 3) new
        // candidates in a sequential for loop, so 3 slow candidates would
        // take ~3 * 2000ms; the fix dials them concurrently, so 3 slow
        // candidates should take ~1 * 2000ms.
        val contacts = List(3) { index -> blackHoleContact(nodeId(index)) }
        val logs = mutableListOf<String>()
        val manager = newManager(onLog = { message -> synchronized(logs) { logs.add(message) } })

        val elapsedMs = measureTimeMillis {
            manager.connectToDiscoveredHolders(contacts)
        }

        // Generous tolerance for test-environment jitter: comfortably under
        // what 3 *sequential* ~2s timeouts would take (~6000ms, the pre-fix
        // behavior).
        assertTrue(
            elapsedMs < 4_500,
            "3 concurrently-dialed slow candidates should take close to one dial's ~2s timeout, not three sequential ~2s timeouts; took ${elapsedMs}ms",
        )
        // Also confirms every one of the 3 slow dials was genuinely
        // attempted (not silently skipped) -- each logs its own
        // "Failed to dial" line once its individual timeout elapses.
        assertEquals(3, logs.count { it.contains("Failed to dial", ignoreCase = true) }, "all 3 slow candidates must still have been attempted: $logs")
    }

    /** A real loopback [ServerSocket] that abortively closes (RST, via `SO_LINGER(true, 0)`) every connection it accepts, immediately, without reading anything -- deterministically forces a write failure on the other end. */
    private class AbortiveCloseListener {
        private val serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = serverSocket.localPort
        /** Counts every accepted (then immediately RST-closed) connection -- used by tests that need to confirm a *second*, later dial actually reached this listener again. */
        val acceptedCount = AtomicInteger(0)
        init {
            Thread({
                try {
                    while (true) {
                        val socket = serverSocket.accept()
                        acceptedCount.incrementAndGet()
                        socket.setSoLinger(true, 0)
                        socket.close()
                    }
                } catch (e: Exception) {
                    // Expected once serverSocket.close() runs -- ends this loop.
                }
            }, "abortive-close-listener").apply { isDaemon = true; start() }
        }
        fun close() = serverSocket.close()
    }

    // -- Locally-authored broadcast fanout: broadcastPost/broadcastDontRelayFlag/
    // broadcastTierKeyRequest -- a post/flag/tier-key-request this device itself
    // authors reaching every open internet connection, as opposed to the live
    // relay-flood fanout tests above, which cover only content *relayed through*
    // one internet connection. --

    @Test
    fun `broadcastPost sends to every open internet connection, decodable as POST_FRAME`() = runBlocking {
        val manager = newManager()
        val listenerA = LoopbackListener()
        val listenerB = LoopbackListener()
        val contactA = loopbackContact(nodeId(0), listenerA.port)
        val contactB = loopbackContact(nodeId(1), listenerB.port)

        manager.connectToDiscoveredHolders(listOf(contactA, contactB))
        val serverSocketA = listenerA.accepted.poll(2, TimeUnit.SECONDS)
        val serverSocketB = listenerB.accepted.poll(2, TimeUnit.SECONDS)
        assertTrue(serverSocketA != null && serverSocketB != null, "both contacts must have been dialed")
        serverSocketA!!.soTimeout = 3_000
        serverSocketB!!.soTimeout = 3_000
        val serverChannelA = PeerChannel(serverSocketA)
        val serverChannelB = PeerChannel(serverSocketB)

        val frameBytes = encodedFrameBytes("self-authored-post")
        manager.broadcastPost(frameBytes)

        for (channel in listOf(serverChannelA, serverChannelB)) {
            val received = channel.receiveEnvelope()
            assertEquals(WirePayloadType.POST_FRAME, received.type)
            assertContentEquals(frameBytes, received.payload)
        }
    }

    @Test
    fun `broadcastDontRelayFlag sends to every open internet connection, decodable as DONT_RELAY_FLAG`() = runBlocking {
        val manager = newManager()
        val listenerA = LoopbackListener()
        val listenerB = LoopbackListener()
        val contactA = loopbackContact(nodeId(0), listenerA.port)
        val contactB = loopbackContact(nodeId(1), listenerB.port)

        manager.connectToDiscoveredHolders(listOf(contactA, contactB))
        val serverSocketA = listenerA.accepted.poll(2, TimeUnit.SECONDS)
        val serverSocketB = listenerB.accepted.poll(2, TimeUnit.SECONDS)
        assertTrue(serverSocketA != null && serverSocketB != null, "both contacts must have been dialed")
        serverSocketA!!.soTimeout = 3_000
        serverSocketB!!.soTimeout = 3_000
        val serverChannelA = PeerChannel(serverSocketA)
        val serverChannelB = PeerChannel(serverSocketB)

        val row = freshDontRelayFlagRow("self-authored-flag")
        manager.broadcastDontRelayFlag(row)

        for (channel in listOf(serverChannelA, serverChannelB)) {
            val received = channel.receiveEnvelope()
            assertEquals(WirePayloadType.DONT_RELAY_FLAG, received.type)
            val decoded = DontRelayFlagEnvelope.decode(received.payload)
            assertEquals(row.clipHash, decoded.clipHash.joinToString(separator = "") { "%02x".format(it) })
            assertEquals(row.attestedDeviceKey, decoded.attestedDeviceKey.joinToString(separator = "") { "%02x".format(it) })
        }
    }

    @Test
    fun `broadcastDontRelayFlag does not itself take custody or dedup -- calling it twice for the same flag sends twice`() = runBlocking {
        // InternetPeerConnectionManager.broadcastDontRelayFlag deliberately has
        // no recordFlag/isNew check of its own -- TransportManager is
        // responsible for that dedup, calling this method only once per
        // genuinely-new flag (see WifiDirectTransport.broadcastDontRelayFlag's
        // Boolean return and TransportManager.broadcastDontRelayFlag's own
        // sequencing). This test locks in that this method itself is pure,
        // unconditional fanout with no dedup baked in.
        val manager = newManager()
        val listener = LoopbackListener()
        val contact = loopbackContact(nodeId(0), listener.port)

        manager.connectToDiscoveredHolders(listOf(contact))
        val serverSocket = listener.accepted.poll(2, TimeUnit.SECONDS)
        assertTrue(serverSocket != null, "dial must succeed")
        serverSocket!!.soTimeout = 3_000
        val serverChannel = PeerChannel(serverSocket)

        val row = freshDontRelayFlagRow("repeat-flag")
        manager.broadcastDontRelayFlag(row)
        manager.broadcastDontRelayFlag(row)

        assertEquals(WirePayloadType.DONT_RELAY_FLAG, serverChannel.receiveEnvelope().type)
        assertEquals(WirePayloadType.DONT_RELAY_FLAG, serverChannel.receiveEnvelope().type)
    }

    @Test
    fun `broadcastTierKeyRequest sends to every open internet connection, decodable as TIER_KEY_REQUEST`() = runBlocking {
        val manager = newManager()
        val listenerA = LoopbackListener()
        val listenerB = LoopbackListener()
        val contactA = loopbackContact(nodeId(0), listenerA.port)
        val contactB = loopbackContact(nodeId(1), listenerB.port)

        manager.connectToDiscoveredHolders(listOf(contactA, contactB))
        val serverSocketA = listenerA.accepted.poll(2, TimeUnit.SECONDS)
        val serverSocketB = listenerB.accepted.poll(2, TimeUnit.SECONDS)
        assertTrue(serverSocketA != null && serverSocketB != null, "both contacts must have been dialed")
        serverSocketA!!.soTimeout = 3_000
        serverSocketB!!.soTimeout = 3_000
        val serverChannelA = PeerChannel(serverSocketA)
        val serverChannelB = PeerChannel(serverSocketB)

        val contentId = MessageDigest.getInstance("SHA-256").digest("tier-key-request".toByteArray())
        val claim = TierMembershipClaim(
            reachTier = ReachTier.TOWN,
            geohashPrefix = "9q8yy",
            claimedAtMs = System.currentTimeMillis(),
        )
        val request = TierKeyRequestEnvelope(contentId = contentId, claim = claim)
        manager.broadcastTierKeyRequest(request)

        for (channel in listOf(serverChannelA, serverChannelB)) {
            val received = channel.receiveEnvelope()
            assertEquals(WirePayloadType.TIER_KEY_REQUEST, received.type)
            val decoded = TierKeyRequestEnvelope.decode(received.payload)
            assertEquals(request, decoded)
        }
    }

    @Test
    fun `a sibling connection whose broadcastPost send fails is evicted, without preventing delivery to a healthy sibling`() = runBlocking {
        val manager = newManager()
        val listenerA = LoopbackListener()
        val abortiveListenerB = AbortiveCloseListener()
        val contactA = loopbackContact(nodeId(0), listenerA.port)
        val contactB = loopbackContact(nodeId(1), abortiveListenerB.port)

        manager.connectToDiscoveredHolders(listOf(contactA, contactB))
        val serverSocketA = listenerA.accepted.poll(2, TimeUnit.SECONDS)
        assertTrue(serverSocketA != null, "the healthy contact must have been dialed")
        serverSocketA!!.soTimeout = 3_000
        val serverChannelA = PeerChannel(serverSocketA)

        // Give connection B's own dial/receive machinery a moment to have
        // actually reached the abortive close before broadcastPost below --
        // avoids a benign race where B hasn't even connected yet.
        repeat(20) {
            if (abortiveListenerB.acceptedCount.get() > 0) return@repeat
            Thread.sleep(50)
        }

        val frameBytes = encodedFrameBytes("broadcast-post-with-dead-sibling")

        // Must not throw -- a send failure to the dead sibling is caught internally.
        manager.broadcastPost(frameBytes)

        // The healthy connection must still receive the broadcast post.
        val received = serverChannelA.receiveEnvelope()
        assertEquals(WirePayloadType.POST_FRAME, received.type)
        assertContentEquals(frameBytes, received.payload)

        // The dead connection must have been evicted -- verified indirectly,
        // same posture as the live-relay-fanout eviction test above: a later
        // dial to the same contact must succeed again.
        val acceptedBefore = abortiveListenerB.acceptedCount.get()
        var reconnected = false
        repeat(20) {
            manager.connectToDiscoveredHolders(listOf(contactB))
            if (abortiveListenerB.acceptedCount.get() > acceptedBefore) {
                reconnected = true
                return@repeat
            }
            Thread.sleep(100)
        }
        assertTrue(reconnected, "once the dead connection is evicted, a later dial to the same contact must succeed again")
    }

    @Test
    fun `broadcastPost with zero open internet connections sends nothing and does not throw`() = runBlocking {
        val manager = newManager()

        // No connectToDiscoveredHolders call at all -- the registry is empty.
        manager.broadcastPost(encodedFrameBytes("no-connections"))
        manager.broadcastDontRelayFlag(freshDontRelayFlagRow("no-connections"))
        val claim = TierMembershipClaim(
            reachTier = ReachTier.TOWN,
            geohashPrefix = "9q8yy",
            claimedAtMs = System.currentTimeMillis(),
        )
        manager.broadcastTierKeyRequest(
            TierKeyRequestEnvelope(
                contentId = MessageDigest.getInstance("SHA-256").digest("no-connections".toByteArray()),
                claim = claim,
            ),
        )
        // Reaching this line without an exception is the assertion -- there is
        // nothing else observable with zero open connections.
    }

    // -- Phase 4's relay-fallback-coordination slice: connectToIntroducedPeer/
    // connectToDiscoveredHolders each fall back to a volunteer relay ONLY
    // after their own direct dial has already failed. Real com.hop.relaynode.RelayNode
    // instance (tools/relay-node/, added as a test-only dependency -- see
    // app/build.gradle.kts's own comment on why) reused unmodified, bridging
    // two simulated InternetPeerConnectionManager instances -- the most
    // valuable test here is the end-to-end one: content genuinely flows
    // through the bridge and dispatches correctly on the far side, not just
    // "a channel got registered." --

    /** A real, started [RelayNode] bound to loopback + an ephemeral port, torn down via [close]. */
    private class TestRelay {
        private val serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val address: PeerAddress = PeerAddress.from(InetAddress.getByName("127.0.0.1"), serverSocket.localPort)
        private val relayNode = RelayNode(serverSocket, waitTimeoutMs = 3_000L)
        init {
            relayNode.start()
        }
        fun close() {
            relayNode.stop()
        }
    }

    @Test
    fun `connectToIntroducedPeer falls back to a real volunteer relay when direct dial fails, and content flows end-to-end through the bridge and dispatches on the far side`() = runBlocking {
        val relay = TestRelay()
        // Any NodeId works here -- RelayNode's own handshake never carries a
        // "relay id" concept, only the two peers' ids (see bridgeViaRelay's
        // own doc for why relayId itself is diagnostic-only, not part of the
        // wire handshake).
        val relayId = nodeId(99)
        val idA = nodeId(10)
        val idB = nodeId(20)

        val relayQueueDaoB = FakeRelayQueueDao()
        val managerA = newManager(getOwnNodeId = { idA })
        val managerB = newManager(relayQueueDao = relayQueueDaoB, getOwnNodeId = { idB })

        // Each side's "introduced peer" contact claims an address nothing is
        // listening on -- guarantees the direct dial each manager attempts
        // FIRST genuinely fails fast (connection refused), so the relay
        // fallback is what's actually under test here, not a lucky direct
        // connect.
        val unreachableA = loopbackContact(idA, unlistenedPort())
        val unreachableB = loopbackContact(idB, unlistenedPort())

        try {
            // Both sides attempt concurrently, exactly as two independent
            // devices would -- neither waits for the other.
            val jobA = launch { managerA.connectToIntroducedPeer(unreachableB, relayId, relay.address) }
            val jobB = launch { managerB.connectToIntroducedPeer(unreachableA, relayId, relay.address) }
            jobA.join()
            jobB.join()

            val frameBytes = encodedFrameBytes("relay-bridged-post")
            val clipHashHex = Frame.decode(frameBytes).clipHash.joinToString(separator = "") { "%02x".format(it) }

            // A's own broadcastPost is pure fanout (see that method's own
            // doc) -- it writes to whatever open connection it has for B,
            // which by now must be the relay-bridged channel, not a direct
            // one (the direct dial above was guaranteed to fail).
            managerA.broadcastPost(frameBytes)

            // The actual property under test: this must reach B and dispatch
            // through B's own real EnvelopeDispatcher/ReceivedFrameStore
            // pipeline -- proven the same way every other live-relay test in
            // this file proves it, via the receiving side's own repository
            // state, not a raw socket read.
            var tookCustody = false
            repeat(40) {
                if (relayQueueDaoB.getAll().any { it.clipHash == clipHashHex }) {
                    tookCustody = true
                    return@repeat
                }
                Thread.sleep(100)
            }
            assertTrue(tookCustody, "content sent by A after the relay bridge formed must reach B and dispatch through its real receive pipeline")
        } finally {
            relay.close()
        }
    }

    @Test
    fun `connectToDiscoveredHolders falls back to introduceViaRendezvous, then bridges via the returned relay suggestion, when direct dial fails`() = runBlocking {
        val relay = TestRelay()
        val relayId = nodeId(99)
        val idA = nodeId(11)
        val idTarget = nodeId(21)

        var introduceCalledWith: NodeId? = null
        val managerA = newManager(
            getOwnNodeId = { idA },
            introduceViaRendezvous = { targetId ->
                introduceCalledWith = targetId
                IntroduceResult(targetAddress = PeerAddress.from(InetAddress.getByName("127.0.0.1"), unlistenedPort()), relayId = relayId, relayAddress = relay.address)
            },
        )

        // A "discovered holder" whose claimed address is unreachable -- the
        // direct dial connectToDiscoveredHolders always attempts first must
        // fail before introduceViaRendezvous is ever consulted.
        val unreachableTarget = loopbackContact(idTarget, unlistenedPort())

        // The target side of the bridge, played by a bare TCP client here
        // (not a second InternetPeerConnectionManager) -- this test's own
        // focus is proving connectToDiscoveredHolders calls
        // introduceViaRendezvous and bridges via ITS returned suggestion,
        // not re-proving end-to-end dispatch (already covered above).
        val targetSocket = Socket()
        try {
            managerA.connectToDiscoveredHolders(listOf(unreachableTarget))

            assertEquals(idTarget, introduceCalledWith, "connectToDiscoveredHolders must ask introduceViaRendezvous about the target whose direct dial just failed")

            // Declares the MUTUAL reverse of what A's own bridgeViaRelay call
            // just declared (ownId=idA, bridgeToId=idTarget) -- this is what
            // makes RelayNode match the two and start forwarding. RelayNode
            // itself CONSUMES each side's own handshake bytes (see
            // RelayNode.readHandshake) rather than forwarding them onward --
            // only bytes written AFTER the handshake are blind-forwarded.
            targetSocket.connect(relay.address.toInetSocketAddress(), 2_000)
            targetSocket.soTimeout = 3_000
            val handshake = ByteBuffer.allocate(NodeId.SIZE_BYTES * 2).apply {
                put(idTarget.bytes)
                put(idA.bytes)
            }.array()
            targetSocket.getOutputStream().write(handshake)
            targetSocket.getOutputStream().flush()

            // A's own broadcastPost is pure fanout -- writes to whatever
            // open connection it has for idTarget, which by now must be the
            // relay-bridged channel bridgeViaRelay built from
            // introduceViaRendezvous's returned relay suggestion (the direct
            // dial above was guaranteed to fail). The actual property under
            // test: this raw target-side socket -- reached only via the
            // exact relay address introduceViaRendezvous supplied -- must
            // receive it, proving the returned suggestion is what actually
            // got dialed.
            val frameBytes = encodedFrameBytes("introduce-fallback-post")
            managerA.broadcastPost(frameBytes)

            val received = PeerChannel(targetSocket).receiveEnvelope()
            assertEquals(WirePayloadType.POST_FRAME, received.type)
            assertContentEquals(frameBytes, received.payload)
        } finally {
            targetSocket.close()
            relay.close()
        }
    }

    @Test
    fun `connectToIntroducedPeer with no relay suggestion and no rendezvous fallback gives up cleanly after a failed direct dial`() = runBlocking {
        val idA = nodeId(12)
        val idTarget = nodeId(22)
        val logs = mutableListOf<String>()
        val manager = newManager(
            getOwnNodeId = { idA },
            introduceViaRendezvous = { null },
            onLog = { message -> synchronized(logs) { logs.add(message) } },
        )
        val unreachableTarget = loopbackContact(idTarget, unlistenedPort())

        // Must not throw -- no relay suggestion, no rendezvous contact to ask
        // (introduceViaRendezvous isn't even consulted by this entry point
        // when no suggestion was supplied -- see connectToIntroducedPeer's
        // own doc), so this is a clean give-up.
        manager.connectToIntroducedPeer(unreachableTarget, relayId = null, relayAddress = null)

        assertTrue(
            logs.any { it.contains("No relay suggestion available", ignoreCase = true) },
            "a failed direct dial with no relay suggestion at all must log and give up cleanly: $logs",
        )
    }

    @Test
    fun `a relay bridge attempt against an unreachable relay address is logged and gives up cleanly, no crash`() = runBlocking {
        val idA = nodeId(13)
        val idTarget = nodeId(23)
        val relayId = nodeId(98)
        val deadRelayAddress = PeerAddress.from(InetAddress.getByName("127.0.0.1"), unlistenedPort())
        val logs = mutableListOf<String>()
        val manager = newManager(
            getOwnNodeId = { idA },
            onLog = { message -> synchronized(logs) { logs.add(message) } },
        )
        val unreachableTarget = loopbackContact(idTarget, unlistenedPort())

        // Must not throw.
        manager.connectToIntroducedPeer(unreachableTarget, relayId = relayId, relayAddress = deadRelayAddress)

        assertTrue(
            logs.any { it.contains("Failed to dial a volunteer relay", ignoreCase = true) },
            "a relay dial failure must be logged, never thrown: $logs",
        )
    }

    // -- Minimal hand-rolled fakes, matching InternetPeerConnectionTest's own established pattern. --

    private class FakePostDao : PostDao {
        private val state = MutableStateFlow<List<PostEntity>>(emptyList())
        override suspend fun upsert(post: PostEntity) {
            state.value = state.value.filterNot { it.clipHash == post.clipHash } + post
        }
        override fun getAllOrderedByReceivedDesc(): Flow<List<PostEntity>> = state
        override suspend fun getByClipHash(clipHash: String): PostEntity? = state.value.find { it.clipHash == clipHash }
    }

    private class FakeDontRelayFlagDao : DontRelayFlagDao {
        private val rows = mutableListOf<DontRelayFlagEntity>()
        override suspend fun insert(row: DontRelayFlagEntity): Long {
            if (rows.any { it.clipHash == row.clipHash && it.attestedDeviceKey == row.attestedDeviceKey }) return -1L
            rows.add(row)
            return rows.size.toLong()
        }
        override suspend fun distinctFlaggerCount(clipHash: String): Int = rows.count { it.clipHash == clipHash }
        override suspend fun getAll(): List<DontRelayFlagEntity> = rows.toList()
        override suspend fun deleteAllForClip(clipHash: String) {
            rows.removeAll { it.clipHash == clipHash }
        }
    }

    private class FakeRelayQueueDao : RelayQueueDao {
        private val rows = mutableMapOf<String, RelayQueueEntity>()
        override suspend fun insert(row: RelayQueueEntity) {
            rows.putIfAbsent(row.clipHash, row)
        }
        override suspend fun getAll(): List<RelayQueueEntity> = rows.values.toList()
        override suspend fun delete(clipHash: String) {
            rows.remove(clipHash)
        }
        override suspend fun markDontRelay(clipHash: String) {
            rows[clipHash]?.let { rows[clipHash] = it.copy(dontRelay = true) }
        }
    }

    private class FakePendingMessageDao : PendingMessageDao {
        private val rows = mutableMapOf<String, PendingMessageEntity>()
        override suspend fun insert(row: PendingMessageEntity) {
            rows.putIfAbsent(row.ciphertextHash, row)
        }
        override suspend fun getAll(): List<PendingMessageEntity> = rows.values.toList()
        override suspend fun getByHash(hash: String): PendingMessageEntity? = rows[hash]
        override suspend fun delete(hash: String) {
            rows.remove(hash)
        }
    }

    private class FakeBundleQueueDao : BundleQueueDao {
        private val rows = mutableMapOf<String, BundleQueueEntity>()
        override suspend fun getByPeerId(peerId: String): BundleQueueEntity? = rows[peerId]
        override suspend fun insertOrReplace(row: BundleQueueEntity) {
            rows[row.peerId] = row
        }
        override suspend fun getAll(): List<BundleQueueEntity> = rows.values.toList()
        override suspend fun delete(peerId: String) {
            rows.remove(peerId)
        }
    }
}
