package com.hop.relaynode

import com.hop.dht.Contact
import com.hop.dht.DhtUdpTransport
import com.hop.dht.NodeId
import com.hop.dht.PeerAddress
import java.io.File
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves `RelayNodeCli`'s own construction path -- [loadOrCreateNodeIdSeed] +
 * a bound [ServerSocket] + `RelayNode(serverSocket, ...)`, and separately a
 * bound [DatagramSocket] + [DhtUdpTransport] + [RelayAnnouncer] -- produces a
 * genuinely reachable relay, not just "main() doesn't throw." Real
 * test-side sockets matching `RelayNodeTest`'s own style dial in and get
 * bridged; a real [DhtUdpTransport] plays the role of the rendezvous contact
 * for the announce half.
 *
 * [main][RelayNodeCli]'s own args-parsing and blocking shutdown-latch wait
 * are deliberately NOT exercised here -- this test drives the exact
 * construction and scheduling steps that precede them.
 */
class RelayNodeCliReachabilityTest {

    private fun nodeId(byteValue: Int): NodeId {
        val bytes = ByteArray(NodeId.SIZE_BYTES)
        bytes[NodeId.SIZE_BYTES - 1] = byteValue.toByte()
        return NodeId(bytes)
    }

    private fun loopbackUdpSocket(): DatagramSocket = DatagramSocket(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))

    private fun contactFor(socket: DatagramSocket, id: NodeId): Contact =
        Contact(id = id, address = PeerAddress.from(InetAddress.getLoopbackAddress(), socket.localPort).encode(), lastSeenAtMs = 0L)

    private fun connect(port: Int): Socket {
        val socket = Socket()
        socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 2_000)
        return socket
    }

    private fun sendHandshake(socket: Socket, ownId: NodeId, bridgeToId: NodeId) {
        socket.getOutputStream().write(ownId.bytes + bridgeToId.bytes)
        socket.getOutputStream().flush()
    }

    private fun readExactly(socket: Socket, expectedLength: Int): ByteArray {
        val buffer = ByteArray(expectedLength)
        var read = 0
        while (read < expectedLength) {
            val n = socket.getInputStream().read(buffer, read, expectedLength - read)
            assertTrue(n >= 0, "connection closed before receiving all expected bytes")
            read += n
        }
        return buffer
    }

    @Test
    fun `loadOrCreateNodeIdSeed persists a stable seed across repeated calls, same as across a real process restart`() {
        val seedFile = File.createTempFile("relay-node-cli-test-seed", ".bin")
        seedFile.delete()
        try {
            val first = loadOrCreateNodeIdSeed(seedFile)
            assertTrue(seedFile.isFile, "a fresh seed must be persisted to disk on first run")

            val second = loadOrCreateNodeIdSeed(seedFile)
            assertEquals(first.toList(), second.toList(), "a second call against the same seed file (simulating a process restart) must reuse the identical seed")
        } finally {
            seedFile.delete()
        }
    }

    @Test
    fun `a RelayNode built via the CLI's own construction path bridges two real test-side sockets`() {
        val serverSocket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val relay = RelayNode(
            serverSocket = serverSocket,
            maxConcurrentSlots = RelayNode.DEFAULT_MAX_CONCURRENT_SLOTS,
            waitTimeoutMs = 2_000L,
        )
        relay.start()
        try {
            val a = connect(serverSocket.localPort)
            val b = connect(serverSocket.localPort)
            val idA = nodeId(1)
            val idB = nodeId(2)

            sendHandshake(a, idA, idB)
            sendHandshake(b, idB, idA)

            val fromA = "hello-from-cli-constructed-relay".toByteArray()
            a.getOutputStream().write(fromA)
            a.getOutputStream().flush()
            assertEquals(
                String(fromA),
                String(readExactly(b, fromA.size)),
                "a RelayNode built the exact way RelayNodeCli's main() builds one must genuinely bridge two real sockets",
            )

            a.close()
            b.close()
        } finally {
            relay.stop()
        }
    }

    @Test
    fun `a RelayAnnouncer built via the CLI's own construction path announces this relay's real bridge address, acked by a real rendezvous-shaped contact`() {
        val announceSocket = loopbackUdpSocket()
        val relayOwnId = nodeId(3)
        val announceTransport = DhtUdpTransport(announceSocket, relayOwnId)

        val bootstrapSocket = loopbackUdpSocket()
        val bootstrapId = nodeId(4)
        var receivedRelayId: NodeId? = null
        var receivedRelayAddress: PeerAddress? = null
        val bootstrapTransport = DhtUdpTransport(bootstrapSocket, bootstrapId)
        bootstrapTransport.onRelayAnnounceRequested = { announcedRelayId, announcedRelayAddress ->
            receivedRelayId = announcedRelayId
            receivedRelayAddress = announcedRelayAddress
        }

        val ownBridgeAddress = PeerAddress.from(InetAddress.getLoopbackAddress(), 54322)

        announceTransport.start()
        bootstrapTransport.start()
        try {
            val announcer = RelayAnnouncer(
                transport = announceTransport,
                bootstrapContact = contactFor(bootstrapSocket, bootstrapId),
                ownBridgeAddress = ownBridgeAddress,
            )

            val acked = announcer.announceOnce()
            assertTrue(acked, "a RelayAnnouncer built the exact way RelayNodeCli's main() builds one must genuinely get acked by a live rendezvous-shaped contact")
            assertEquals(relayOwnId, receivedRelayId)
            assertEquals(ownBridgeAddress, receivedRelayAddress)
        } finally {
            announceTransport.stop()
            bootstrapTransport.stop()
        }
    }

    @Test
    fun `startReannounceLoop re-announces on the given interval until interrupted, and DEFAULT_REANNOUNCE_INTERVAL_MS stays comfortably under RelayDirectory's own entry TTL`() {
        assertTrue(
            DEFAULT_REANNOUNCE_INTERVAL_MS < com.hop.dht.RelayDirectory.DEFAULT_ENTRY_TTL_MS,
            "the default re-announce cadence must stay comfortably under RelayDirectory's own entry TTL, or announced entries could expire between re-announcements",
        )

        val announceCount = LinkedBlockingQueue<Long>()
        val announceSocket = loopbackUdpSocket()
        val announceTransport = DhtUdpTransport(announceSocket, nodeId(5))
        val bootstrapSocket = loopbackUdpSocket()
        val bootstrapTransport = DhtUdpTransport(bootstrapSocket, nodeId(6))
        bootstrapTransport.onRelayAnnounceRequested = { _, _ -> announceCount.add(System.currentTimeMillis()) }

        announceTransport.start()
        bootstrapTransport.start()
        try {
            val announcer = RelayAnnouncer(
                transport = announceTransport,
                bootstrapContact = contactFor(bootstrapSocket, nodeId(6)),
                ownBridgeAddress = PeerAddress.from(InetAddress.getLoopbackAddress(), 54323),
            )

            val loopThread = startReannounceLoop(announcer, intervalMs = 200L)
            try {
                // Two full intervals' worth of margin: expect at least 2
                // re-announces within that window if the loop is genuinely
                // periodic, not just a single fire-and-forget call.
                val first = announceCount.poll(2, TimeUnit.SECONDS)
                val second = announceCount.poll(2, TimeUnit.SECONDS)
                assertTrue(first != null && second != null, "startReannounceLoop must re-announce more than once when left running past its interval")
            } finally {
                loopThread.interrupt()
            }
        } finally {
            announceTransport.stop()
            bootstrapTransport.stop()
        }
    }
}
