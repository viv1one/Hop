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
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

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

    /**
     * Regression test for the multi-address dial crash flagged when
     * [Contact.address] widened to potentially hold more than one
     * [PeerAddress] (Phase 4's dual-stack self-registration, see [Contact]'s
     * own class doc): before this class's dial functions switched to
     * [firstDialableAddress]/[PeerAddress.decodeList], an unconditional
     * [PeerAddress.decode] call on a multi-entry `address` blob would throw
     * [PeerAddressDecodeException] uncaught, contradicting every one of
     * [ping]/[findNode]/[store]/[findValue]'s own "never throws except on a
     * genuine local error" doc for what is, in fact, a perfectly legitimate
     * [Contact] this same codebase now constructs.
     */
    @Test
    fun `ping to a Contact whose address carries two PeerAddress entries dials the first one without throwing`() = runBlocking {
        val aId = nodeId(1)
        val bId = nodeId(2)
        val aSocket = loopbackSocket()
        val bSocket = loopbackSocket()
        val aTransport = DhtUdpTransport(aSocket, aId)
        val bTransport = DhtUdpTransport(bSocket, bId)
        aTransport.start()
        bTransport.start()
        try {
            // b's real dialable loopback address first, then a second,
            // deliberately unreachable one -- proving the first entry is what
            // actually gets dialed, not that any decode failure was merely
            // swallowed into a false/timeout result.
            val unreachablePort = bSocket.localPort.let { if (it == 1) 2 else it - 1 }
            val multiAddress = PeerAddress.encodeList(
                listOf(
                    PeerAddress.from(InetAddress.getLoopbackAddress(), bSocket.localPort),
                    PeerAddress.from(InetAddress.getLoopbackAddress(), unreachablePort),
                ),
            )
            val dualStackContact = Contact(id = bId, address = multiAddress, lastSeenAtMs = 0L)

            val result = aTransport.ping(dualStackContact)
            assertTrue(result, "ping must dial the first (real) address in a multi-address Contact and succeed")
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

    // ---- Phase 4 additions: ADDRESS_REFLECTION (NAT hole-punching step 1) ----

    @Test
    fun `reflectOwnAddress returns the requester's own loopback address, as genuinely observed by the responder`() = runBlocking {
        val aId = nodeId(1)
        val bId = nodeId(2)
        val aSocket = loopbackSocket()
        val bSocket = loopbackSocket()
        val aTransport = DhtUdpTransport(aSocket, aId, onMessageObserved = {})
        val bTransport = DhtUdpTransport(bSocket, bId, onMessageObserved = {})
        aTransport.start()
        bTransport.start()
        try {
            val reflected = aTransport.reflectOwnAddress(contactFor(bSocket, bId))
            requireNotNull(reflected) { "a live responder must answer with a reflected address, not null" }

            // The real assertion: this is genuinely the OBSERVED address b's own
            // socket saw the request arrive from -- a's actual bound loopback
            // port -- not a value a itself supplied anywhere (there is no such
            // field in AddressReflectionRequestMessage for a to inject one).
            assertEquals(
                PeerAddress.from(InetAddress.getLoopbackAddress(), aSocket.localPort),
                reflected,
                "reflectOwnAddress must return exactly what the responder observed as the request's source address",
            )
        } finally {
            aTransport.stop()
            bTransport.stop()
        }
    }

    @Test
    fun `reflectOwnAddress to a closed, non-listening port times out cleanly, returning null`() = runBlocking {
        val aId = nodeId(1)
        val aSocket = loopbackSocket()
        val aTransport = DhtUdpTransport(aSocket, aId, onMessageObserved = {}, requestTimeoutMs = 200)
        aTransport.start()
        try {
            val deadSocket = loopbackSocket()
            val deadPort = deadSocket.localPort
            deadSocket.close()
            val deadContact = Contact(
                id = nodeId(99),
                address = PeerAddress.from(InetAddress.getLoopbackAddress(), deadPort).encode(),
                lastSeenAtMs = 0L,
            )

            val startedAtMs = System.currentTimeMillis()
            val result = aTransport.reflectOwnAddress(deadContact)
            val elapsedMs = System.currentTimeMillis() - startedAtMs

            assertEquals(null, result, "reflectOwnAddress with no responder must time out to null, not hang or throw")
            assertTrue(
                elapsedMs < DhtUdpTransport.DEFAULT_REQUEST_TIMEOUT_MS,
                "timeout must be bounded by the short injected requestTimeoutMs (200ms), not the production default (${DhtUdpTransport.DEFAULT_REQUEST_TIMEOUT_MS}ms); took ${elapsedMs}ms",
            )
        } finally {
            aTransport.stop()
        }
    }

    @Test
    fun `reflectOwnAddress returns null when the response's type byte doesn't match what was expected`() = runBlocking {
        val aId = nodeId(1)
        val aSocket = loopbackSocket()
        val aTransport = DhtUdpTransport(aSocket, aId, onMessageObserved = {}, requestTimeoutMs = 1000)
        aTransport.start()
        try {
            // An "attacker" socket that answers any inbound
            // ADDRESS_REFLECTION_REQUEST with a bare PONG carrying the same
            // transaction id -- reflectOwnAddress must not trust this as a
            // real ADDRESS_REFLECTION_RESPONSE.
            val attackerSocket = DatagramSocket(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
            val attackerThread = Thread {
                val buffer = ByteArray(2048)
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    attackerSocket.receive(packet)
                    val request = AddressReflectionRequestMessage.decode(packet.data.copyOfRange(packet.offset, packet.offset + packet.length))
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
            val result = aTransport.reflectOwnAddress(attackerContact)
            assertEquals(null, result, "a type-mismatched response must be treated as failure, never trusted")

            attackerSocket.close()
            attackerThread.join(1000)
        } finally {
            aTransport.stop()
        }
    }

    // ---- Phase 4 additions: rendezvous-relayed introduction ----

    @Test
    fun `introduce returns the found address and the target receives a real INTRODUCTION naming the requester`() = runBlocking {
        val aId = nodeId(1)
        val rId = nodeId(2)
        val targetId = nodeId(3)
        val aSocket = loopbackSocket()
        val rSocket = loopbackSocket()
        val targetSocket = loopbackSocket()

        val ownReflectedAddress = PeerAddress.from(InetAddress.getLoopbackAddress(), 54321)
        val targetKnownAddress = PeerAddress.from(InetAddress.getLoopbackAddress(), targetSocket.localPort)

        var receivedFromId: NodeId? = null
        var receivedClaimedAddress: PeerAddress? = null

        val aTransport = DhtUdpTransport(aSocket, aId, onMessageObserved = {})
        val rTransport = DhtUdpTransport(rSocket, rId, onMessageObserved = {})
        rTransport.onIntroduceRequested = { requestedTargetId ->
            if (requestedTargetId == targetId) {
                Contact(id = targetId, address = targetKnownAddress.encode(), lastSeenAtMs = 0L)
            } else {
                null
            }
        }
        val targetTransport = DhtUdpTransport(targetSocket, targetId, onMessageObserved = {})
        targetTransport.onIntroductionReceived = { fromId, claimedAddress ->
            receivedFromId = fromId
            receivedClaimedAddress = claimedAddress
        }

        aTransport.start()
        rTransport.start()
        targetTransport.start()
        try {
            val result = aTransport.introduce(contactFor(rSocket, rId), targetId, ownReflectedAddress)
            assertEquals(targetKnownAddress, result, "introduce() must return R's found address for the target")

            // R's fire-and-forget INTRODUCTION to the target is sent
            // independently of the INTRODUCE_RESPONSE reaching a -- give the
            // target's receive thread a moment to process it.
            withTimeout(2000) {
                while (receivedFromId == null) {
                    delay(20)
                }
            }

            assertEquals(aId, receivedFromId, "the target's onIntroductionReceived must name A as fromId")
            assertEquals(
                ownReflectedAddress,
                receivedClaimedAddress,
                "the target must receive A's self-reported ownAddress, relayed through by R unmodified",
            )
        } finally {
            aTransport.stop()
            rTransport.stop()
            targetTransport.stop()
        }
    }

    @Test
    fun `introduce returns null for a target R has never seen, and nothing is sent to any third party`() = runBlocking {
        val aId = nodeId(1)
        val rId = nodeId(2)
        val unknownTargetId = nodeId(3)
        val aSocket = loopbackSocket()
        val rSocket = loopbackSocket()
        // A real, listening third-party socket -- proves R never sends it
        // anything when it doesn't know the requested target, not merely
        // that some OTHER address (which might not even exist) was left alone.
        val thirdPartySocket = loopbackSocket()
        val thirdPartyReceived = CopyOnWriteArrayList<ByteArray>()
        val thirdPartyThread = Thread {
            val buffer = ByteArray(2048)
            try {
                while (true) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    thirdPartySocket.receive(packet)
                    thirdPartyReceived.add(packet.data.copyOfRange(packet.offset, packet.offset + packet.length))
                }
            } catch (e: Exception) {
                // socket closed at teardown -- fine.
            }
        }
        thirdPartyThread.start()

        val ownReflectedAddress = PeerAddress.from(InetAddress.getLoopbackAddress(), 54321)
        val aTransport = DhtUdpTransport(aSocket, aId, onMessageObserved = {})
        val rTransport = DhtUdpTransport(rSocket, rId, onMessageObserved = {})
        rTransport.onIntroduceRequested = { null } // R never knows about anyone

        aTransport.start()
        rTransport.start()
        try {
            val result = aTransport.introduce(contactFor(rSocket, rId), unknownTargetId, ownReflectedAddress)
            assertEquals(null, result, "introduce() against an unknown target must return null")

            Thread.sleep(150)
            assertTrue(thirdPartyReceived.isEmpty(), "R must never send anything to any third party when the requested target is not found")
        } finally {
            aTransport.stop()
            rTransport.stop()
            thirdPartySocket.close()
            thirdPartyThread.join(1000)
        }
    }

    @Test
    fun `introduce to a closed, non-listening port times out cleanly, returning null`() = runBlocking {
        val aId = nodeId(1)
        val aSocket = loopbackSocket()
        val aTransport = DhtUdpTransport(aSocket, aId, onMessageObserved = {}, requestTimeoutMs = 200)
        aTransport.start()
        try {
            val deadSocket = loopbackSocket()
            val deadPort = deadSocket.localPort
            deadSocket.close()
            val deadContact = Contact(
                id = nodeId(99),
                address = PeerAddress.from(InetAddress.getLoopbackAddress(), deadPort).encode(),
                lastSeenAtMs = 0L,
            )

            val startedAtMs = System.currentTimeMillis()
            val result = aTransport.introduce(deadContact, nodeId(3), PeerAddress.from(InetAddress.getLoopbackAddress(), 1))
            val elapsedMs = System.currentTimeMillis() - startedAtMs

            assertEquals(null, result, "introduce with no responder must time out to null, not hang or throw")
            assertTrue(
                elapsedMs < DhtUdpTransport.DEFAULT_REQUEST_TIMEOUT_MS,
                "timeout must be bounded by the short injected requestTimeoutMs (200ms), not the production default (${DhtUdpTransport.DEFAULT_REQUEST_TIMEOUT_MS}ms); took ${elapsedMs}ms",
            )
        } finally {
            aTransport.stop()
        }
    }

    @Test
    fun `introduce returns null when the response's type byte doesn't match what was expected`() = runBlocking {
        val aId = nodeId(1)
        val aSocket = loopbackSocket()
        val aTransport = DhtUdpTransport(aSocket, aId, onMessageObserved = {}, requestTimeoutMs = 1000)
        aTransport.start()
        try {
            // An "attacker" socket that answers any inbound INTRODUCE_REQUEST
            // with a bare PONG carrying the same transaction id -- introduce()
            // must not trust this as a real INTRODUCE_RESPONSE.
            val attackerSocket = DatagramSocket(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
            val attackerThread = Thread {
                val buffer = ByteArray(2048)
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    attackerSocket.receive(packet)
                    val request = IntroduceRequestMessage.decode(packet.data.copyOfRange(packet.offset, packet.offset + packet.length))
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
            val result = aTransport.introduce(attackerContact, nodeId(3), PeerAddress.from(InetAddress.getLoopbackAddress(), 1))
            assertEquals(null, result, "a type-mismatched response must be treated as failure, never trusted")

            attackerSocket.close()
            attackerThread.join(1000)
        } finally {
            aTransport.stop()
        }
    }

    // ---- Phase 4 additions: volunteer relay-node discovery ----

    @Test
    fun `announceRelay acks and fires onRelayAnnounceRequested with the announcing relay's id and self-reported address`() = runBlocking {
        val aId = nodeId(1)
        val bId = nodeId(2)
        val aSocket = loopbackSocket()
        val bSocket = loopbackSocket()

        var receivedRelayId: NodeId? = null
        var receivedRelayAddress: PeerAddress? = null

        val aTransport = DhtUdpTransport(aSocket, aId, onMessageObserved = {})
        val bTransport = DhtUdpTransport(bSocket, bId, onMessageObserved = {})
        bTransport.onRelayAnnounceRequested = { relayId, relayAddress ->
            receivedRelayId = relayId
            receivedRelayAddress = relayAddress
        }
        aTransport.start()
        bTransport.start()
        try {
            // A deliberately different address than a's own UDP socket's real
            // source, proving relayAddress is carried through as the
            // genuinely self-reported field it is, never replaced by
            // whatever address b actually observed this packet arriving
            // from -- see RelayAnnounceRequestMessage's own doc.
            val selfReportedBridgeAddress = PeerAddress.from(InetAddress.getLoopbackAddress(), 54321)
            val result = aTransport.announceRelay(contactFor(bSocket, bId), selfReportedBridgeAddress)
            assertTrue(result, "a RELAY_ANNOUNCE to a live, responding peer must ack true")
            assertEquals(aId, receivedRelayId, "the announced relayId must be RelayAnnounceRequestMessage.senderId")
            assertEquals(
                selfReportedBridgeAddress,
                receivedRelayAddress,
                "the announced relayAddress must be exactly what the caller supplied, never the packet's observed source",
            )
        } finally {
            aTransport.stop()
            bTransport.stop()
        }
    }

    @Test
    fun `announceRelay to a closed, non-listening port times out cleanly, returning false`() = runBlocking {
        val aId = nodeId(1)
        val aSocket = loopbackSocket()
        val aTransport = DhtUdpTransport(aSocket, aId, onMessageObserved = {}, requestTimeoutMs = 200)
        aTransport.start()
        try {
            val deadSocket = loopbackSocket()
            val deadPort = deadSocket.localPort
            deadSocket.close()
            val deadContact = Contact(
                id = nodeId(99),
                address = PeerAddress.from(InetAddress.getLoopbackAddress(), deadPort).encode(),
                lastSeenAtMs = 0L,
            )

            val startedAtMs = System.currentTimeMillis()
            val result = aTransport.announceRelay(deadContact, PeerAddress.from(InetAddress.getLoopbackAddress(), 1))
            val elapsedMs = System.currentTimeMillis() - startedAtMs

            assertFalse(result, "announceRelay with no responder must time out to false, not hang or throw")
            assertTrue(
                elapsedMs < DhtUdpTransport.DEFAULT_REQUEST_TIMEOUT_MS,
                "timeout must be bounded by the short injected requestTimeoutMs (200ms), not the production default (${DhtUdpTransport.DEFAULT_REQUEST_TIMEOUT_MS}ms); took ${elapsedMs}ms",
            )
        } finally {
            aTransport.stop()
        }
    }

    @Test
    fun `queryRelays returns the announced relays, and returns empty when nothing was ever announced`() = runBlocking {
        val aId = nodeId(1)
        val bId = nodeId(2)
        val aSocket = loopbackSocket()
        val bSocket = loopbackSocket()

        val relayEntry = Contact(
            id = nodeId(7),
            address = PeerAddress.from(InetAddress.getLoopbackAddress(), 12345).encode(),
            lastSeenAtMs = 0L,
        )

        val aTransport = DhtUdpTransport(aSocket, aId, onMessageObserved = {})
        val bTransport = DhtUdpTransport(bSocket, bId, onMessageObserved = {})
        bTransport.onRelayQueryRequested = { emptyList() }
        aTransport.start()
        bTransport.start()
        try {
            val emptyResult = aTransport.queryRelays(contactFor(bSocket, bId))
            assertTrue(emptyResult.isEmpty(), "querying a responder that knows of no relays must return an empty list, not null or an error")

            bTransport.onRelayQueryRequested = { listOf(relayEntry) }
            val result = aTransport.queryRelays(contactFor(bSocket, bId))
            assertEquals(listOf(relayEntry.id), result.map { it.id })
            assertTrue(relayEntry.address.contentEquals(result[0].address))
        } finally {
            aTransport.stop()
            bTransport.stop()
        }
    }

    @Test
    fun `queryRelays to a closed, non-listening port times out cleanly, returning an empty list`() = runBlocking {
        val aId = nodeId(1)
        val aSocket = loopbackSocket()
        val aTransport = DhtUdpTransport(aSocket, aId, onMessageObserved = {}, requestTimeoutMs = 200)
        aTransport.start()
        try {
            val deadSocket = loopbackSocket()
            val deadPort = deadSocket.localPort
            deadSocket.close()
            val deadContact = Contact(
                id = nodeId(99),
                address = PeerAddress.from(InetAddress.getLoopbackAddress(), deadPort).encode(),
                lastSeenAtMs = 0L,
            )

            val startedAtMs = System.currentTimeMillis()
            val result = aTransport.queryRelays(deadContact)
            val elapsedMs = System.currentTimeMillis() - startedAtMs

            assertTrue(result.isEmpty(), "queryRelays with no responder must time out to an empty list, not hang or throw")
            assertTrue(
                elapsedMs < DhtUdpTransport.DEFAULT_REQUEST_TIMEOUT_MS,
                "timeout must be bounded by the short injected requestTimeoutMs (200ms), not the production default (${DhtUdpTransport.DEFAULT_REQUEST_TIMEOUT_MS}ms); took ${elapsedMs}ms",
            )
        } finally {
            aTransport.stop()
        }
    }

    @Test
    fun `announceRelay returns false when the response's type byte doesn't match what was expected`() = runBlocking {
        val aId = nodeId(1)
        val aSocket = loopbackSocket()
        val aTransport = DhtUdpTransport(aSocket, aId, onMessageObserved = {}, requestTimeoutMs = 1000)
        aTransport.start()
        try {
            val attackerSocket = DatagramSocket(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
            val attackerThread = Thread {
                val buffer = ByteArray(2048)
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    attackerSocket.receive(packet)
                    val request = RelayAnnounceRequestMessage.decode(packet.data.copyOfRange(packet.offset, packet.offset + packet.length))
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
            val result = aTransport.announceRelay(attackerContact, PeerAddress.from(InetAddress.getLoopbackAddress(), 1))
            assertFalse(result, "a type-mismatched response must be treated as failure, never trusted")

            attackerSocket.close()
            attackerThread.join(1000)
        } finally {
            aTransport.stop()
        }
    }

    @Test
    fun `queryRelays returns empty when the response's type byte doesn't match what was expected`() = runBlocking {
        val aId = nodeId(1)
        val aSocket = loopbackSocket()
        val aTransport = DhtUdpTransport(aSocket, aId, onMessageObserved = {}, requestTimeoutMs = 1000)
        aTransport.start()
        try {
            val attackerSocket = DatagramSocket(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
            val attackerThread = Thread {
                val buffer = ByteArray(2048)
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    attackerSocket.receive(packet)
                    val request = RelayQueryRequestMessage.decode(packet.data.copyOfRange(packet.offset, packet.offset + packet.length))
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
            val result = aTransport.queryRelays(attackerContact)
            assertTrue(result.isEmpty(), "a type-mismatched response must be treated as failure, never trusted")

            attackerSocket.close()
            attackerThread.join(1000)
        } finally {
            aTransport.stop()
        }
    }

}
