package com.hop.relaynode

import com.hop.dht.Contact
import com.hop.dht.DhtUdpTransport
import com.hop.dht.NodeId
import com.hop.dht.PeerAddress
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Real-loopback-UDP-socket test, matching `dht/`'s `DhtUdpTransportTest`
 * style: [RelayAnnouncer] on one side, a plain [DhtUdpTransport] playing the
 * role of the bootstrap rendezvous/DHT contact on the other, proving
 * [RelayAnnouncer.announceOnce] sends a real RELAY_ANNOUNCE and gets acked.
 */
class RelayAnnouncerTest {

    private fun loopbackSocket(): DatagramSocket = DatagramSocket(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))

    private fun nodeId(byteValue: Int): NodeId {
        val bytes = ByteArray(NodeId.SIZE_BYTES)
        bytes[NodeId.SIZE_BYTES - 1] = byteValue.toByte()
        return NodeId(bytes)
    }

    private fun contactFor(socket: DatagramSocket, id: NodeId): Contact =
        Contact(id = id, address = PeerAddress.from(InetAddress.getLoopbackAddress(), socket.localPort).encode(), lastSeenAtMs = 0L)

    @Test
    fun `announceOnce sends a real RELAY_ANNOUNCE and returns true once acked`() {
        val relaySocket = loopbackSocket()
        val relayId = nodeId(1)
        val relayTransport = DhtUdpTransport(relaySocket, relayId)

        val bootstrapSocket = loopbackSocket()
        val bootstrapId = nodeId(2)
        var receivedRelayId: NodeId? = null
        var receivedRelayAddress: PeerAddress? = null
        val bootstrapTransport = DhtUdpTransport(bootstrapSocket, bootstrapId)
        bootstrapTransport.onRelayAnnounceRequested = { announcedRelayId, announcedRelayAddress ->
            receivedRelayId = announcedRelayId
            receivedRelayAddress = announcedRelayAddress
        }

        // The relay's real TCP bridge address -- deliberately NOT
        // relaySocket's own UDP address, proving this is what actually gets
        // announced, not anything derived from this transport's own socket.
        val ownBridgeAddress = PeerAddress.from(InetAddress.getLoopbackAddress(), 54321)

        relayTransport.start()
        bootstrapTransport.start()
        try {
            val announcer = RelayAnnouncer(
                transport = relayTransport,
                bootstrapContact = contactFor(bootstrapSocket, bootstrapId),
                ownBridgeAddress = ownBridgeAddress,
            )

            val acked = announcer.announceOnce()
            assertTrue(acked, "announceOnce against a live, responding bootstrap contact must return true")
            assertEquals(relayId, receivedRelayId, "the bootstrap contact must observe the relay's own NodeId as the announcer")
            assertEquals(ownBridgeAddress, receivedRelayAddress, "the bootstrap contact must receive exactly the relay's self-reported bridge address")
        } finally {
            relayTransport.stop()
            bootstrapTransport.stop()
        }
    }

    @Test
    fun `announceOnce against an unreachable bootstrap contact returns false, not throwing`() {
        val relaySocket = loopbackSocket()
        val relayTransport = DhtUdpTransport(relaySocket, nodeId(1), requestTimeoutMs = 200)

        val deadSocket = loopbackSocket()
        val deadPort = deadSocket.localPort
        deadSocket.close()
        val deadContact = Contact(
            id = nodeId(99),
            address = PeerAddress.from(InetAddress.getLoopbackAddress(), deadPort).encode(),
            lastSeenAtMs = 0L,
        )

        relayTransport.start()
        try {
            val announcer = RelayAnnouncer(
                transport = relayTransport,
                bootstrapContact = deadContact,
                ownBridgeAddress = PeerAddress.from(InetAddress.getLoopbackAddress(), 54321),
            )
            assertEquals(false, announcer.announceOnce(), "announceOnce with no responder must return false, not hang or throw")
        } finally {
            relayTransport.stop()
        }
    }
}
