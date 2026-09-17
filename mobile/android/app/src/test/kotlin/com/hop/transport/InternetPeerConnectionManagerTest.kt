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
import com.hop.protocol.RelayPolicy
import com.hop.repository.BundleRepository
import com.hop.repository.DontRelayRepository
import com.hop.repository.PendingMessageRepository
import com.hop.repository.PostRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
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

    private fun newManager(): InternetPeerConnectionManager = InternetPeerConnectionManager(
        postRepository = PostRepository(FakePostDao(), DecayKeyStore()),
        decayKeyStore = DecayKeyStore(),
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
        getOwnPeerId = { "me" },
        postsDir = tempFolder.newFolder("posts-${System.nanoTime()}"),
    )

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
        val manager = InternetPeerConnectionManager(
            postRepository = PostRepository(FakePostDao(), DecayKeyStore()),
            decayKeyStore = DecayKeyStore(),
            dontRelayRepository = DontRelayRepository(FakeDontRelayFlagDao(), FakeRelayQueueDao(), RelayPolicy()),
            pendingMessageRepository = PendingMessageRepository(FakePendingMessageDao(), RelayPolicy()),
            bundleRepository = BundleRepository(FakeBundleQueueDao(), RelayPolicy()),
            getOwnPeerId = { "me" },
            postsDir = tempFolder.newFolder("posts-${System.nanoTime()}"),
            onLog = { message -> logs.add(message) },
        )

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
