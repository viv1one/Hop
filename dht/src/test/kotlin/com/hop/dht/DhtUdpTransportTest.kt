package com.hop.dht

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking

/**
 * Real loopback UDP [DatagramSocket] pairs, driving real production classes
 * over real sockets -- matching this codebase's established "drive real
 * production classes over real sockets" convention (see
 * `com.hop.transport.RelayTest`/`PendingMessageRelayTest`'s pattern), just
 * plain-JVM here rather than Android-instrumented.
 */
class DhtUdpTransportTest {

    private fun loopbackSocket(): DatagramSocket = DatagramSocket(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))

    private fun nodeId(byteValue: Int): NodeId {
        val bytes = ByteArray(NodeId.SIZE_BYTES)
        bytes[NodeId.SIZE_BYTES - 1] = byteValue.toByte()
        return NodeId(bytes)
    }

    private fun contactFor(socket: DatagramSocket, id: NodeId): Contact =
        Contact(
            id = id,
            address = PeerAddress.from(InetAddress.getLoopbackAddress(), socket.localPort).encode(),
            lastSeenAtMs = 0L,
        )

    @Test
    fun `successful ping returns true and fires onMessageObserved on both sides`() = runBlocking {
        val aId = nodeId(1)
        val bId = nodeId(2)
        val aSocket = loopbackSocket()
        val bSocket = loopbackSocket()
        val aObserved = CopyOnWriteArrayList<Contact>()
        val bObserved = CopyOnWriteArrayList<Contact>()
        val aTransport = DhtUdpTransport(aSocket, aId, onMessageObserved = { aObserved.add(it) })
        val bTransport = DhtUdpTransport(bSocket, bId, onMessageObserved = { bObserved.add(it) })
        aTransport.start()
        bTransport.start()
        try {
            val result = aTransport.ping(contactFor(bSocket, bId))
            assertTrue(result, "ping to a live, responding peer must return true")

            // The PONG's receipt on a's side (which is what makes ping() return)
            // happens strictly after a's own onMessageObserved fired for that same
            // packet (handlePacket reports the observed sender before completing
            // the pending ping) -- so this assertion is not racy.
            assertEquals(1, aObserved.size, "a must have observed exactly one message: b's PONG")
            assertEquals(bId, aObserved[0].id)

            // b's onMessageObserved for the inbound PING happens strictly before b
            // sends its PONG -- and a only received that PONG after this call
            // returned, so this is also not racy.
            assertEquals(1, bObserved.size, "b must have observed exactly one message: a's PING")
            assertEquals(aId, bObserved[0].id)
        } finally {
            aTransport.stop()
            bTransport.stop()
        }
    }

    @Test
    fun `ping to a closed, non-listening port times out within the injected requestTimeoutMs`() = runBlocking {
        val aId = nodeId(1)
        val aSocket = loopbackSocket()
        val aTransport = DhtUdpTransport(aSocket, aId, onMessageObserved = {}, requestTimeoutMs = 200)
        aTransport.start()
        try {
            // Bind and immediately close a socket to obtain a real port number
            // guaranteed to have nothing listening on it.
            val deadSocket = loopbackSocket()
            val deadPort = deadSocket.localPort
            deadSocket.close()
            val deadContact = Contact(
                id = nodeId(99),
                address = PeerAddress.from(InetAddress.getLoopbackAddress(), deadPort).encode(),
                lastSeenAtMs = 0L,
            )

            val startedAtMs = System.currentTimeMillis()
            val result = aTransport.ping(deadContact)
            val elapsedMs = System.currentTimeMillis() - startedAtMs

            assertFalse(result, "a ping with no responder must time out, not hang or throw")
            assertTrue(
                elapsedMs < DhtUdpTransport.DEFAULT_REQUEST_TIMEOUT_MS,
                "timeout must be bounded by the short injected requestTimeoutMs (200ms), not the production default (${DhtUdpTransport.DEFAULT_REQUEST_TIMEOUT_MS}ms); took ${elapsedMs}ms",
            )
        } finally {
            aTransport.stop()
        }
    }

    @Test
    fun `a malformed packet does not crash the receive loop -- proven by a subsequent successful real ping`() = runBlocking {
        val aId = nodeId(1)
        val bId = nodeId(2)
        val aSocket = loopbackSocket()
        val bSocket = loopbackSocket()
        val aTransport = DhtUdpTransport(aSocket, aId, onMessageObserved = {})
        val bTransport = DhtUdpTransport(bSocket, bId, onMessageObserved = {})
        aTransport.start()
        bTransport.start()
        try {
            // Send garbage bytes at b from an unrelated plain socket -- not
            // decodable as a DhtMessage at all (wrong size, no valid version byte).
            DatagramSocket().use { attacker ->
                val garbage = byteArrayOf(1, 2, 3, 4, 5)
                attacker.send(DatagramPacket(garbage, garbage.size, InetAddress.getLoopbackAddress(), bSocket.localPort))
            }
            // Give b's receive thread a moment to process (and discard) it.
            Thread.sleep(150)

            // The real assertion: b's receive loop must still be alive and able to
            // answer a genuine ping -- not just "no exception escaped" from the
            // garbage send above.
            val result = aTransport.ping(contactFor(bSocket, bId))
            assertTrue(result, "receive loop must survive a malformed packet and still answer a subsequent real ping")
        } finally {
            aTransport.stop()
            bTransport.stop()
        }
    }

    @Test
    fun `concurrent in-flight pings resolve independently, and an unmatched PONG is silently dropped`() = runBlocking {
        val aId = nodeId(1)
        val bId = nodeId(2)
        val aSocket = loopbackSocket()
        val bSocket = loopbackSocket()
        val aTransport = DhtUdpTransport(aSocket, aId, onMessageObserved = {}, requestTimeoutMs = 3000)
        val bTransport = DhtUdpTransport(bSocket, bId, onMessageObserved = {})
        aTransport.start()
        bTransport.start()
        try {
            // A PONG carrying a transaction ID a never issued (crafted and sent
            // directly at a, bypassing any real ping() call) must be dropped
            // silently: no crash, and it must not corrupt any real pending ping.
            val bogusPong = DhtMessage(DhtMessageType.PONG, TransactionId.random(), bId).encode()
            DatagramSocket().use { attacker ->
                attacker.send(DatagramPacket(bogusPong, bogusPong.size, InetAddress.getLoopbackAddress(), aSocket.localPort))
            }
            Thread.sleep(100)

            val bContact = contactFor(bSocket, bId)
            val results = coroutineScope {
                (1..5).map { async { aTransport.ping(bContact) } }.awaitAll()
            }
            assertTrue(results.all { it }, "every concurrent ping must independently resolve true, keyed to its own transaction id, unaffected by the earlier bogus PONG")
        } finally {
            aTransport.stop()
            bTransport.stop()
        }
    }

    @Test
    fun `findNode round trip returns the responder's answer over real loopback sockets`() = runBlocking {
        val aId = nodeId(1)
        val bId = nodeId(2)
        val targetId = nodeId(42)
        val aSocket = loopbackSocket()
        val bSocket = loopbackSocket()

        val answerContact = Contact(
            id = nodeId(7),
            address = PeerAddress.from(InetAddress.getLoopbackAddress(), 12345).encode(),
            lastSeenAtMs = 0L,
        )
        var receivedTargetId: NodeId? = null
        var receivedExcludeId: NodeId? = null

        val aTransport = DhtUdpTransport(aSocket, aId, onMessageObserved = {})
        val bTransport = DhtUdpTransport(bSocket, bId, onMessageObserved = {})
        bTransport.onFindNodeRequested = { targetIdArg, excludeIdArg ->
            receivedTargetId = targetIdArg
            receivedExcludeId = excludeIdArg
            listOf(answerContact)
        }
        aTransport.start()
        bTransport.start()
        try {
            val result = aTransport.findNode(contactFor(bSocket, bId), targetId)
            assertEquals(listOf(answerContact.id), result?.map { it.id })
            assertEquals(targetId, receivedTargetId, "b's onFindNodeRequested must receive the requested targetId")
            assertEquals(aId, receivedExcludeId, "b's onFindNodeRequested must receive a's id as the id to exclude")
        } finally {
            aTransport.stop()
            bTransport.stop()
        }
    }

    @Test
    fun `findNode returns null when the response's type byte doesn't match what was expected`() = runBlocking {
        val aId = nodeId(1)
        val aSocket = loopbackSocket()
        val aTransport = DhtUdpTransport(aSocket, aId, onMessageObserved = {}, requestTimeoutMs = 1000)
        aTransport.start()
        try {
            // An "attacker" socket that answers any inbound FIND_NODE_REQUEST with
            // a PONG carrying the same transaction id -- a's findNode() must not
            // trust this as a real FIND_NODE_RESPONSE.
            val attackerSocket = DatagramSocket(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
            val attackerThread = Thread {
                val buffer = ByteArray(2048)
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    attackerSocket.receive(packet)
                    val request = FindNodeRequestMessage.decode(packet.data.copyOfRange(packet.offset, packet.offset + packet.length))
                    val bogusPong = DhtMessage(DhtMessageType.PONG, request.transactionId, nodeId(2)).encode()
                    attackerSocket.send(DatagramPacket(bogusPong, bogusPong.size, packet.address, packet.port))
                } catch (e: Exception) {
                    // socket closed underneath us at test teardown -- fine.
                }
            }
            attackerThread.start()

            val attackerContact = Contact(
                id = nodeId(2),
                address = PeerAddress.from(InetAddress.getLoopbackAddress(), attackerSocket.localPort).encode(),
                lastSeenAtMs = 0L,
            )
            val result = aTransport.findNode(attackerContact, nodeId(42))
            assertEquals(null, result, "a type-mismatched response must be treated as failure, never trusted")

            attackerSocket.close()
            attackerThread.join(1000)
        } finally {
            aTransport.stop()
        }
    }

    // ---- Slice 5 additions ----

    @Test
    fun `store round trip acks and fires onStoreRequested with the announced key and observed-address announcer`() = runBlocking {
        val aId = nodeId(1)
        val bId = nodeId(2)
        val key = nodeId(42)
        val aSocket = loopbackSocket()
        val bSocket = loopbackSocket()

        var receivedKey: NodeId? = null
        var receivedAnnouncer: Contact? = null

        val aTransport = DhtUdpTransport(aSocket, aId, onMessageObserved = {})
        val bTransport = DhtUdpTransport(bSocket, bId, onMessageObserved = {})
        bTransport.onStoreRequested = { keyArg, announcerArg ->
            receivedKey = keyArg
            receivedAnnouncer = announcerArg
        }
        aTransport.start()
        bTransport.start()
        try {
            val result = aTransport.store(contactFor(bSocket, bId), key)
            assertTrue(result, "a STORE_REQUEST to a live, responding peer must ack true")
            assertEquals(key, receivedKey)
            assertEquals(aId, receivedAnnouncer?.id, "the announcer's id must be StoreRequestMessage.senderId")
            assertEquals(
                PeerAddress.from(InetAddress.getLoopbackAddress(), aSocket.localPort).encode().toList(),
                receivedAnnouncer?.address?.toList(),
                "the announcer's address must be the packet's OBSERVED source, never a self-reported field -- StoreRequestMessage carries none",
            )
        } finally {
            aTransport.stop()
            bTransport.stop()
        }
    }

    @Test
    fun `findValue round trip returns holders when the responder has them`() = runBlocking {
        val aId = nodeId(1)
        val bId = nodeId(2)
        val key = nodeId(42)
        val aSocket = loopbackSocket()
        val bSocket = loopbackSocket()

        val holder = Contact(
            id = nodeId(7),
            address = PeerAddress.from(InetAddress.getLoopbackAddress(), 12345).encode(),
            lastSeenAtMs = 0L,
        )

        val aTransport = DhtUdpTransport(aSocket, aId, onMessageObserved = {})
        val bTransport = DhtUdpTransport(bSocket, bId, onMessageObserved = {})
        bTransport.onFindValueRequested = { _, _ -> FindValueOutcome.Holders(listOf(holder)) }
        aTransport.start()
        bTransport.start()
        try {
            val result = aTransport.findValue(contactFor(bSocket, bId), key)
            assertTrue(result is FindValueOutcome.Holders, "a found=true response must decode as FindValueOutcome.Holders")
            assertEquals(listOf(holder.id), result.contacts.map { it.id })
        } finally {
            aTransport.stop()
            bTransport.stop()
        }
    }

    @Test
    fun `findValue round trip returns closer nodes and passes the requester's id to exclude when nobody holds the key`() = runBlocking {
        val aId = nodeId(1)
        val bId = nodeId(2)
        val key = nodeId(42)
        val aSocket = loopbackSocket()
        val bSocket = loopbackSocket()

        val closer = Contact(
            id = nodeId(8),
            address = PeerAddress.from(InetAddress.getLoopbackAddress(), 54321).encode(),
            lastSeenAtMs = 0L,
        )
        var receivedExcludeId: NodeId? = null

        val aTransport = DhtUdpTransport(aSocket, aId, onMessageObserved = {})
        val bTransport = DhtUdpTransport(bSocket, bId, onMessageObserved = {})
        bTransport.onFindValueRequested = { _, excludeIdArg ->
            receivedExcludeId = excludeIdArg
            FindValueOutcome.CloserNodes(listOf(closer))
        }
        aTransport.start()
        bTransport.start()
        try {
            val result = aTransport.findValue(contactFor(bSocket, bId), key)
            assertTrue(result is FindValueOutcome.CloserNodes, "a found=false response must decode as FindValueOutcome.CloserNodes")
            assertEquals(listOf(closer.id), result.contacts.map { it.id })
            assertEquals(aId, receivedExcludeId, "b's onFindValueRequested must receive a's id as the id to exclude")
        } finally {
            aTransport.stop()
            bTransport.stop()
        }
    }
}
