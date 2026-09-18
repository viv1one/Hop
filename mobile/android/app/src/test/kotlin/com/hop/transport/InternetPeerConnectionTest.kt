package com.hop.transport

import com.hop.crypto.ContentEncryption
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
import com.hop.protocol.ContentType
import com.hop.protocol.EncryptedFrameCodec
import com.hop.protocol.Frame
import com.hop.protocol.ReachTier
import com.hop.protocol.ReachTierKeyDistribution
import com.hop.protocol.RelayPolicy
import com.hop.protocol.TierKeyRequestEnvelope
import com.hop.protocol.TierKeyResponseEnvelope
import com.hop.protocol.TierMembershipClaim
import com.hop.protocol.WireEnvelope
import com.hop.protocol.WirePayloadType
import com.hop.dht.Contact
import com.hop.dht.NodeId
import com.hop.dht.PeerAddress
import com.hop.p2p.PeerChannel
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
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Plain-JVM loopback-socket coverage for [InternetPeerConnection] -- no real
 * `PeerDialer`/`Contact`/DHT lookup, no Android instrumentation needed (this
 * class has no direct `Context`/`WifiP2pManager` dependency, unlike
 * [WifiDirectTransport]). Mirrors `p2p/`'s own `PeerChannelTest.kt` loopback
 * setup for "get two connected sockets" and [EnvelopeDispatcherTierKeyTest]'s
 * hand-rolled-DAO-fake pattern for driving real repository/dispatch logic.
 *
 * Each test drives [InternetPeerConnection.receiveLoop] directly against one
 * side of a real loopback [PeerChannel] pair -- the exact "internal
 * testability seam" [InternetPeerConnection]'s own doc describes, proving the
 * same dispatch logic [EnvelopeDispatcherTierKeyTest]/[WifiDirectTransportTest]
 * already prove for the WiFi Direct path now also works correctly when
 * driven over a `p2p/` socket instead.
 *
 * One exception: the `onConnected`-happens-before-`onClosed` ordering test
 * below drives [InternetPeerConnection.connectTo] itself (a real dial against
 * a real loopback [Contact]/[PeerAddress]), since that's the only entry
 * point where the registration-race bug this test guards against could ever
 * occur -- [receiveLoop] alone has no `onConnected` callback to race against.
 */
class InternetPeerConnectionTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val postDao = FakePostDao()
    private val decayKeyStore = DecayKeyStore()

    private fun newConnection(
        ownPeerId: String = "me",
        relayRepository: RelayRepository = RelayRepository(FakeRelayQueueDao(), RelayPolicy()),
        pendingTierKeyRequests: PendingTierKeyRequests = PendingTierKeyRequests(),
    ): InternetPeerConnection = InternetPeerConnection(
        postRepository = PostRepository(postDao, decayKeyStore),
        decayKeyStore = decayKeyStore,
        relayRepository = relayRepository,
        dontRelayRepository = DontRelayRepository(
            flagDao = FakeDontRelayFlagDao(),
            relayQueueDao = FakeRelayQueueDao(),
            relayPolicy = RelayPolicy(),
        ),
        pendingMessageRepository = PendingMessageRepository(
            dao = FakePendingMessageDao(),
            relayPolicy = RelayPolicy(),
        ),
        bundleRepository = BundleRepository(dao = FakeBundleQueueDao(), relayPolicy = RelayPolicy()),
        getOwnPeerId = { ownPeerId },
        pendingTierKeyRequests = pendingTierKeyRequests,
        postsDir = tempFolder.newFolder("posts-${System.nanoTime()}"),
    )

    /** Opens a real loopback TCP socket pair, mirroring `PeerChannelTest`'s own setup. */
    private fun loopbackSocketPair(): Pair<Socket, Socket> {
        val loopback = InetAddress.getByName("127.0.0.1")
        val server = ServerSocket(0, 50, loopback)
        try {
            val client = Socket()
            client.connect(InetSocketAddress(loopback, server.localPort), 2_000)
            val accepted = server.accept()
            return client to accepted
        } finally {
            server.close()
        }
    }

    private fun encodedFrame(payloadTag: String): ByteArray {
        val plaintext = "post bytes for $payloadTag".toByteArray()
        val clipHash = MessageDigest.getInstance("SHA-256").digest(plaintext)
        return EncryptedFrameCodec.encode(
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
    }

    @Test
    fun `a POST_FRAME envelope sent over a real socket is received, decoded, and stored via the real ReceivedFrameStore path`() {
        val (client, accepted) = loopbackSocketPair()
        val clientChannel = PeerChannel(client)
        val serverChannel = PeerChannel(accepted)
        val connection = newConnection()

        val frameBytes = encodedFrame("internet-post")
        val clipHashHex = Frame.decode(frameBytes).clipHash.toHexString()
        clientChannel.sendEnvelope(WireEnvelope(WirePayloadType.POST_FRAME, frameBytes))
        // Close immediately after sending -- receiveLoop must process the one
        // queued envelope, then cleanly end on the resulting EOF (see the
        // dedicated EOF test below for that behavior in isolation).
        clientChannel.close()

        // Run on the calling (test) thread -- deterministic, no join needed:
        // exactly one envelope is queued, then the socket is already closed,
        // so this call processes it and returns as soon as the next read hits EOF.
        connection.receiveLoop(serverChannel)

        assertEquals(1, postDao.inserted.size, "a genuinely-new POST_FRAME envelope must be stored via the real ReceivedFrameStore/PostRepository path")
        assertEquals(clipHashHex, postDao.inserted.single().clipHash)
    }

    @Test
    fun `receiveLoop cleanly stops without crashing when the peer closes the connection`() {
        val (client, accepted) = loopbackSocketPair()
        val serverChannel = PeerChannel(accepted)
        val connection = newConnection()

        client.close()

        // Must return normally (no exception propagated) -- this is the
        // assertion: if receiveLoop crashed instead of returning cleanly on
        // EOF, this test method itself would fail with the propagated
        // exception.
        connection.receiveLoop(serverChannel)
    }

    @Test
    fun `connectTo's onConnected callback is always recorded strictly before onClosed, even when the remote peer closes essentially immediately`() {
        // Bug B's regression test: InternetPeerConnectionManager used to
        // register a freshly-dialed channel in its registry only *after*
        // connectTo() returned -- but connectTo() starts the receive thread
        // (which can call onClosed the instant it hits EOF/an error) before
        // returning. If the remote peer resets the connection immediately
        // after accepting, the receive thread could call onClosed before the
        // caller's own post-return registration line ever ran, leaving a
        // permanently dead registry entry with nothing left to ever remove
        // it. The fix moves registration into a new onConnected callback,
        // invoked synchronously right after the channel is constructed but
        // strictly before the receive thread starts.
        //
        // Reproducing the exact race window is inherently timing-sensitive
        // (per this task's own guidance), so this test instead proves the
        // *ordering guarantee* directly: a real server that accepts and
        // immediately RST-closes the connection forces the receive thread to
        // hit an I/O error as fast as physically possible after connectTo()
        // starts it -- if onConnected and onClosed were ever misordered,
        // this is the setup most likely to expose it.
        val serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val serverThread = Thread({
            try {
                val accepted = serverSocket.accept()
                accepted.setSoLinger(true, 0)
                accepted.close()
            } catch (e: Exception) {
                // Expected once serverSocket.close() runs below.
            }
        }, "abortive-close-server")
        serverThread.start()

        val contact = Contact(
            id = NodeId(ByteArray(NodeId.SIZE_BYTES) { it.toByte() }),
            address = PeerAddress.encodeList(
                listOf(PeerAddress.from(InetAddress.getByName("127.0.0.1"), serverSocket.localPort)),
            ),
            lastSeenAtMs = 0L,
        )
        val connection = newConnection()
        val events = CopyOnWriteArrayList<String>()
        val closedLatch = CountDownLatch(1)

        connection.connectTo(
            contact,
            onConnected = { events.add("connected") },
            onClosed = {
                events.add("closed")
                closedLatch.countDown()
            },
        )

        assertTrue(closedLatch.await(5, TimeUnit.SECONDS), "the abortively-closed connection must end its receive loop within the timeout")
        assertEquals(
            listOf("connected", "closed"),
            events.toList(),
            "onConnected must always be recorded strictly before onClosed, even when the remote peer closes essentially immediately",
        )

        serverSocket.close()
        serverThread.join(2_000)
    }

    @Test
    fun `a TIER_KEY_REQUEST with a valid fresh in-cell claim gets back a real granted TIER_KEY_RESPONSE on the same connection`() {
        val originGeohashPrefix = "9q8yy"
        val plaintext = "a town-tier post reached over the internet".toByteArray()
        val clipHash = MessageDigest.getInstance("SHA-256").digest(plaintext)
        val clipHashHex = clipHash.joinToString("") { "%02x".format(it) }
        val result = EncryptedFrameCodec.encode(
            plaintext = plaintext,
            clipHash = clipHash,
            senderDeviceId = ByteArray(Frame.SENDER_DEVICE_ID_SIZE) { it.toByte() },
            contentType = ContentType.PHOTO,
            hopCount = 0,
            originatedAtMs = System.currentTimeMillis(),
            ttlSeconds = 3600L,
            reachTier = ReachTier.TOWN,
            dontRelay = false,
            originGeohashPrefix = originGeohashPrefix,
        )
        val frame = Frame.decode(result.encoded)

        decayKeyStore.store(
            contentId = ReachTierKeyDistribution.decayKeyStorageKey(clipHashHex, ReachTier.TOWN),
            wrappedCek = result.contentEncryptionKey,
            decayWindow = java.time.Duration.ofSeconds(3600L),
        )
        val payloadFile = java.io.File(tempFolder.newFolder("payloads-${System.nanoTime()}"), "$clipHashHex.enc")
        payloadFile.writeBytes(frame.payload)
        runBlocking {
            postDao.upsert(
                PostEntity(
                    clipHash = clipHashHex,
                    senderDeviceId = "sender",
                    contentType = ContentType.PHOTO.name,
                    originatedAtMs = System.currentTimeMillis(),
                    ttlSeconds = 3600L,
                    reachTier = ReachTier.TOWN.name,
                    originGeohashPrefix = originGeohashPrefix,
                    dontRelay = false,
                    receivedAtMs = System.currentTimeMillis(),
                    encryptedPayloadFilePath = payloadFile.absolutePath,
                ),
            )
        }

        val (client, accepted) = loopbackSocketPair()
        val clientChannel = PeerChannel(client)
        val serverChannel = PeerChannel(accepted)
        val connection = newConnection()

        // The receive loop must keep running after answering (a real
        // connection can carry more than one request) -- so it's driven on
        // its own thread here, and the test closes the client side once it
        // has what it needs, which is what lets the loop end cleanly.
        val receiveThread = Thread { connection.receiveLoop(serverChannel) }
        receiveThread.start()

        val claim = TierMembershipClaim(
            reachTier = ReachTier.TOWN,
            geohashPrefix = originGeohashPrefix,
            claimedAtMs = System.currentTimeMillis(),
        )
        val requestEnvelope = TierKeyRequestEnvelope(contentId = clipHash, claim = claim)
        clientChannel.sendEnvelope(WireEnvelope(WirePayloadType.TIER_KEY_REQUEST, requestEnvelope.encode()))

        val responseWireEnvelope = clientChannel.receiveEnvelope()
        assertEquals(WirePayloadType.TIER_KEY_RESPONSE, responseWireEnvelope.type)
        val response = TierKeyResponseEnvelope.decode(responseWireEnvelope.payload)
        assertTrue(response.granted, "a valid in-cell fresh claim for a held post must be granted, same as the WiFi Direct path")
        assertContentEquals(clipHash, response.contentId)

        val cek = ContentEncryption.keyFromBytes(response.wrappedCek)
        val decrypted = ContentEncryption.decrypt(cek, frame.payload)
        assertContentEquals(plaintext, decrypted, "the granted key must actually decrypt this post's stored ciphertext")

        clientChannel.close()
        receiveThread.join(5_000)
        assertTrue(!receiveThread.isAlive, "receiveLoop must end once the client closes the connection")
    }

    private fun ByteArray.toHexString(): String = joinToString(separator = "") { "%02x".format(it) }

    // -- Minimal hand-rolled fakes, matching EnvelopeDispatcherTierKeyTest/WifiDirectTransportTest's established pattern. --

    private class FakePostDao : PostDao {
        private val state = MutableStateFlow<List<PostEntity>>(emptyList())
        val inserted: List<PostEntity> get() = state.value
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
