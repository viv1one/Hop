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
import com.hop.dht.NodeId
import com.hop.dht.PeerAddress
import com.hop.p2p.PeerChannel
import com.hop.protocol.ContentType
import com.hop.protocol.EncryptedFrameCodec
import com.hop.protocol.Frame
import com.hop.protocol.MessageCiphertextEnvelope
import com.hop.protocol.PreKeyBundleEnvelope
import com.hop.protocol.ReachTier
import com.hop.protocol.RelayPolicy
import com.hop.protocol.WirePayloadType
import com.hop.repository.BundleRepository
import com.hop.repository.DontRelayRepository
import com.hop.repository.PendingMessageRepository
import com.hop.repository.PostRepository
import com.hop.repository.RelayRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.LinkedBlockingQueue
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
        postsDir = tempFolder.newFolder("posts-${System.nanoTime()}"),
        onLog = onLog,
    )

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

    /** A real loopback [ServerSocket] that abortively closes (RST, via `SO_LINGER(true, 0)`) every connection it accepts, immediately, without reading anything -- deterministically forces a write failure on the other end. */
    private class AbortiveCloseListener {
        private val serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = serverSocket.localPort
        init {
            Thread({
                try {
                    while (true) {
                        val socket = serverSocket.accept()
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
