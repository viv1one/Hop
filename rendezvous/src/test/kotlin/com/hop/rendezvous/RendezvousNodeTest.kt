package com.hop.rendezvous

import com.hop.dht.Contact
import com.hop.dht.DhtUdpTransport
import com.hop.dht.FindValueOutcome
import com.hop.dht.NodeId
import com.hop.dht.PeerAddress
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Drives a real [RendezvousNode] over real loopback UDP sockets from
 * separate "peer" [DhtUdpTransport] instances -- matching this codebase's
 * established "drive real production classes over real sockets" convention
 * (see `DhtUdpTransportTest`/`DhtNodeTest`).
 *
 * The critical negative test below (STORE_REQUEST/FIND_VALUE_REQUEST) is
 * this module's whole reason to exist per ADR 0002 -- see hop-dev's "extra
 * scrutiny areas" note on `rendezvous/`.
 */
class RendezvousNodeTest {

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
    fun `PING is answered alive, same as any DHT node`() = runBlocking {
        val rendezvousSocket = loopbackSocket()
        val rendezvousId = nodeId(1)
        val rendezvous = RendezvousNode(rendezvousSocket, rendezvousId)

        val peerSocket = loopbackSocket()
        val peerTransport = DhtUdpTransport(peerSocket, nodeId(2))

        rendezvous.start()
        peerTransport.start()
        try {
            val alive = peerTransport.ping(contactFor(rendezvousSocket, rendezvousId))
            assertTrue(alive, "a running RendezvousNode must answer PING like any ordinary DHT peer")
        } finally {
            rendezvous.stop()
            peerTransport.stop()
        }
    }

    @Test
    fun `FIND_NODE returns a bounded, correct subset of previously-observed contacts, excluding the requester's own id`() = runBlocking {
        val rendezvousSocket = loopbackSocket()
        val rendezvousId = nodeId(1)
        // Cap deliberately smaller than the number of peers observed below, to
        // actually exercise the "bounded" part of the guarantee, not just
        // happen to return everything because nothing exceeded the cap.
        val rendezvous = RendezvousNode(rendezvousSocket, rendezvousId, responseCap = 3)

        val peerSockets = (1..5).map { loopbackSocket() }
        val peerIds = (1..5).map { nodeId(it + 10) }
        val peerTransports = peerSockets.zip(peerIds).map { (socket, id) -> DhtUdpTransport(socket, id) }

        val requesterSocket = loopbackSocket()
        val requesterId = nodeId(99)
        val requesterTransport = DhtUdpTransport(requesterSocket, requesterId)

        rendezvous.start()
        peerTransports.forEach { it.start() }
        requesterTransport.start()
        try {
            // Every peer, AND the requester itself, gets observed by the
            // rendezvous node via an ordinary PING.
            peerTransports.forEach { assertTrue(it.ping(contactFor(rendezvousSocket, rendezvousId))) }
            assertTrue(requesterTransport.ping(contactFor(rendezvousSocket, rendezvousId)))

            val response = requesterTransport.findNode(contactFor(rendezvousSocket, rendezvousId), targetId = nodeId(255))
            requireNotNull(response)

            assertEquals(3, response.size, "the response must be bounded by responseCap")
            assertFalse(response.any { it.id == requesterId }, "the requester's own id must never appear in its own FIND_NODE response")
            assertTrue(
                response.all { it.id in peerIds },
                "every returned contact must be drawn from the set of previously-observed peers",
            )
        } finally {
            rendezvous.stop()
            peerTransports.forEach { it.stop() }
            requesterTransport.stop()
        }
    }

    // ---- The critical negative test: the whole point of this module (ADR 0002) ----

    @Test
    fun `STORE_REQUEST is silently discarded and FIND_VALUE_REQUEST always answers CloserNodes, never Holders, for any key`() = runBlocking {
        val rendezvousSocket = loopbackSocket()
        val rendezvousId = nodeId(1)
        val rendezvous = RendezvousNode(rendezvousSocket, rendezvousId)

        val announcerSocket = loopbackSocket()
        val announcerTransport = DhtUdpTransport(announcerSocket, nodeId(2))

        val querierSocket = loopbackSocket()
        val querierTransport = DhtUdpTransport(querierSocket, nodeId(3))

        val key = nodeId(42)

        rendezvous.start()
        announcerTransport.start()
        querierTransport.start()
        try {
            // A peer announces itself as a holder of `key` via an ordinary
            // STORE_REQUEST. DhtUdpTransport always sends a STORE_RESPONSE ack
            // unconditionally (see DhtUdpTransport.handlePacket) -- that ack
            // firing is NOT evidence the rendezvous node persisted anything;
            // it's what the transport's own unwired onStoreRequested default
            // ({ _, _ -> }, a pure no-op) does regardless.
            val storeAcked = announcerTransport.store(contactFor(rendezvousSocket, rendezvousId), key)
            assertTrue(storeAcked, "the wire-level STORE_RESPONSE ack still fires -- that's the transport's own unconditional behavior, independent of whether anything was actually persisted")

            // The real assertion: immediately afterward, a FIND_VALUE_REQUEST
            // for that EXACT SAME key must never come back as Holders -- proving
            // the STORE_REQUEST above was never actually recorded anywhere this
            // node could later answer from, and that this node structurally
            // cannot construct a Holders answer at all.
            val outcome = querierTransport.findValue(contactFor(rendezvousSocket, rendezvousId), key)
            assertTrue(
                outcome is FindValueOutcome.CloserNodes,
                "a rendezvous node must NEVER answer FIND_VALUE_REQUEST with Holders, even for a key it just received a STORE_REQUEST for -- got $outcome",
            )

            // Same must hold for a key nobody ever announced, and for a
            // completely different arbitrary key -- FIND_VALUE_REQUEST always
            // answers CloserNodes, unconditionally, regardless of the key.
            val neverStoredOutcome = querierTransport.findValue(contactFor(rendezvousSocket, rendezvousId), nodeId(123))
            assertTrue(
                neverStoredOutcome is FindValueOutcome.CloserNodes,
                "FIND_VALUE_REQUEST for a key nobody ever announced must also answer CloserNodes -- got $neverStoredOutcome",
            )
        } finally {
            rendezvous.stop()
            announcerTransport.stop()
            querierTransport.stop()
        }
    }
}
