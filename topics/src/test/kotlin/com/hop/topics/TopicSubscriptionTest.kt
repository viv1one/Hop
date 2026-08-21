package com.hop.topics

import com.hop.dht.Contact
import com.hop.dht.DhtNode
import com.hop.dht.DhtUdpTransport
import com.hop.dht.FindValueResult
import com.hop.dht.NodeId
import com.hop.dht.PeerAddress
import com.hop.dht.RoutingTable
import com.hop.protocol.Geohash
import com.hop.protocol.ReachTier
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking

/**
 * Real loopback UDP sockets across multiple [DhtUdpTransport]/[RoutingTable]/
 * [DhtNode] instances, per this codebase's established convention for
 * DHT-network-shaped tests (see `dht/`'s own `DhtNodeTest`). Every scenario
 * here uses a deliberately sparse star topology -- a publisher and a browser
 * that know only a shared relay, never each other directly -- rather than a
 * dense fully-connected graph: the low-density, near-broken-mesh case this
 * codebase's conventions call out as the one that actually breaks a
 * mesh/relay design in the field.
 */
class TopicSubscriptionTest {

    private fun loopbackSocket(): DatagramSocket = DatagramSocket(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))

    private fun nodeId(byteValue: Int): NodeId {
        val bytes = ByteArray(NodeId.SIZE_BYTES)
        bytes[NodeId.SIZE_BYTES - 1] = byteValue.toByte()
        return NodeId(bytes)
    }

    private fun ownAddressFor(socket: DatagramSocket): PeerAddress =
        PeerAddress.from(InetAddress.getLoopbackAddress(), socket.localPort)

    private fun contactFor(id: NodeId, socket: DatagramSocket): Contact =
        Contact(id = id, address = ownAddressFor(socket).encode(), lastSeenAtMs = 0L)

    /**
     * Starting from ([lat], [lon])'s own [precision]-length geohash cell,
     * walks eastward in small steps (public [Geohash.encode]/[Geohash.neighbors]
     * only -- `Geohash.decode` is `internal` to `protocol/`, not visible from
     * this module) until landing in the immediate next cell, and returns a
     * lat/lon inside it. Deterministic: the first step that produces a
     * different geohash string IS the immediate neighbor cell (cells are
     * contiguous along a fixed latitude line at a fixed precision), verified
     * directly against [Geohash.neighbors] rather than assumed.
     */
    private fun eastNeighborLatLon(lat: Double, lon: Double, precision: Int): Pair<Double, Double> {
        val origin = Geohash.encode(lat, lon, precision)
        val neighbors = Geohash.neighbors(origin)
        var offset = 0.0005
        while (offset < 1.0) {
            val candidateLon = lon + offset
            val candidate = Geohash.encode(lat, candidateLon, precision)
            if (candidate != origin) {
                check(candidate in neighbors) {
                    "expected the first eastward-differing cell from $origin to be an immediate neighbor, got $candidate (neighbors=$neighbors)"
                }
                return lat to candidateLon
            }
            offset += 0.0005
        }
        error("did not find a neighboring cell for ($lat, $lon) within the search window")
    }

    @Test
    fun `publish-then-browse round trip finds the publisher via the target cell's own key`() = runBlocking {
        // Known Wikipedia geohash vector (also used in protocol's GeohashTest):
        // lat 42.6, lon -5.6 -> "ezs42" at precision 5 (Town tier).
        val lat = 42.6
        val lon = -5.6

        val publisherId = nodeId(1)
        val relayId = nodeId(2)
        val browserId = nodeId(3)

        val publisherSocket = loopbackSocket()
        val relaySocket = loopbackSocket()
        val browserSocket = loopbackSocket()
        val scope = CoroutineScope(Job() + Dispatchers.Default)

        val publisherTransport = DhtUdpTransport(publisherSocket, publisherId, onMessageObserved = {})
        val publisherNode = DhtNode(RoutingTable(publisherId), publisherTransport, scope, ownAddressFor(publisherSocket))

        val relayTransport = DhtUdpTransport(relaySocket, relayId, onMessageObserved = {})
        DhtNode(RoutingTable(relayId), relayTransport, scope, ownAddressFor(relaySocket)) // init block wires relay's callbacks

        val browserTransport = DhtUdpTransport(browserSocket, browserId, onMessageObserved = {})
        val browserNode = DhtNode(RoutingTable(browserId), browserTransport, scope, ownAddressFor(browserSocket))

        val transports = listOf(publisherTransport, relayTransport, browserTransport)
        transports.forEach { it.start() }
        try {
            // Sparse star: publisher and browser each know only the relay,
            // never each other.
            publisherNode.observe(contactFor(relayId, relaySocket))
            browserNode.observe(contactFor(relayId, relaySocket))

            TopicSubscription(publisherNode).publish(lat, lon, ReachTier.TOWN)

            val holders = TopicSubscription(browserNode).browse(lat, lon, ReachTier.TOWN)

            assertEquals(
                listOf(publisherId),
                holders.map { it.id },
                "browsing the exact same location/tier as the publisher must find it via the target cell's own key",
            )
        } finally {
            transports.forEach { it.stop() }
        }
    }

    @Test
    fun `browse finds a publisher in a neighboring cell via neighbor-cell resolution`() = runBlocking {
        val publisherLat = 42.6
        val publisherLon = -5.6 // "ezs42" at precision 5
        val (browserLat, browserLon) = eastNeighborLatLon(publisherLat, publisherLon, precision = 5)

        // Confirm the two locations genuinely land in different Town-tier
        // cells before asserting anything about browse's behavior -- this is
        // the point of the whole scenario, not an assumption to leave silent.
        val publisherCell = Geohash.encode(publisherLat, publisherLon, precision = 5)
        val browserCell = Geohash.encode(browserLat, browserLon, precision = 5)
        assertTrue(publisherCell != browserCell, "test setup bug: expected different Town-tier cells")
        assertTrue(publisherCell in Geohash.neighbors(browserCell), "test setup bug: expected the two cells to be neighbors")

        val publisherId = nodeId(11)
        val relayId = nodeId(12)
        val browserId = nodeId(13)

        val publisherSocket = loopbackSocket()
        val relaySocket = loopbackSocket()
        val browserSocket = loopbackSocket()
        val scope = CoroutineScope(Job() + Dispatchers.Default)

        val publisherTransport = DhtUdpTransport(publisherSocket, publisherId, onMessageObserved = {})
        val publisherNode = DhtNode(RoutingTable(publisherId), publisherTransport, scope, ownAddressFor(publisherSocket))

        val relayTransport = DhtUdpTransport(relaySocket, relayId, onMessageObserved = {})
        DhtNode(RoutingTable(relayId), relayTransport, scope, ownAddressFor(relaySocket))

        val browserTransport = DhtUdpTransport(browserSocket, browserId, onMessageObserved = {})
        val browserNode = DhtNode(RoutingTable(browserId), browserTransport, scope, ownAddressFor(browserSocket))

        val transports = listOf(publisherTransport, relayTransport, browserTransport)
        transports.forEach { it.start() }
        try {
            publisherNode.observe(contactFor(relayId, relaySocket))
            browserNode.observe(contactFor(relayId, relaySocket))

            // Publisher announces only its OWN cell -- never the neighbor cells.
            TopicSubscription(publisherNode).publish(publisherLat, publisherLon, ReachTier.TOWN)

            val holders = TopicSubscription(browserNode).browse(browserLat, browserLon, ReachTier.TOWN)

            assertEquals(
                listOf(publisherId),
                holders.map { it.id },
                "browsing a neighboring cell must still find a publisher via neighbor-cell resolution (PRD §6)",
            )
        } finally {
            transports.forEach { it.stop() }
        }
    }

    @Test
    fun `browse finds nobody when the publisher's cell is not the target cell or a neighbor`() = runBlocking {
        val publisherLat = 42.6
        val publisherLon = -5.6 // "ezs42"
        val browserLat = 35.6762 // Tokyo -- thousands of km away, not remotely adjacent at Town precision
        val browserLon = 139.6503

        val publisherId = nodeId(21)
        val relayId = nodeId(22)
        val browserId = nodeId(23)

        val publisherSocket = loopbackSocket()
        val relaySocket = loopbackSocket()
        val browserSocket = loopbackSocket()
        val scope = CoroutineScope(Job() + Dispatchers.Default)

        val publisherTransport = DhtUdpTransport(publisherSocket, publisherId, onMessageObserved = {})
        val publisherNode = DhtNode(RoutingTable(publisherId), publisherTransport, scope, ownAddressFor(publisherSocket))

        val relayTransport = DhtUdpTransport(relaySocket, relayId, onMessageObserved = {})
        DhtNode(RoutingTable(relayId), relayTransport, scope, ownAddressFor(relaySocket))

        val browserTransport = DhtUdpTransport(browserSocket, browserId, onMessageObserved = {})
        val browserNode = DhtNode(RoutingTable(browserId), browserTransport, scope, ownAddressFor(browserSocket))

        val transports = listOf(publisherTransport, relayTransport, browserTransport)
        transports.forEach { it.start() }
        try {
            publisherNode.observe(contactFor(relayId, relaySocket))
            browserNode.observe(contactFor(relayId, relaySocket))

            TopicSubscription(publisherNode).publish(publisherLat, publisherLon, ReachTier.TOWN)

            val holders = TopicSubscription(browserNode).browse(browserLat, browserLon, ReachTier.TOWN)

            assertEquals(
                emptyList(),
                holders,
                "a publisher whose cell is neither the target cell nor a neighbor must never show up in browse",
            )
        } finally {
            transports.forEach { it.stop() }
        }
    }

    @Test
    fun `publish announces only the target cell, never the neighbor cells`() = runBlocking {
        val lat = 42.6
        val lon = -5.6 // "ezs42" at precision 5

        val publisherId = nodeId(31)
        val relayId = nodeId(32)

        val publisherSocket = loopbackSocket()
        val relaySocket = loopbackSocket()
        val scope = CoroutineScope(Job() + Dispatchers.Default)

        val publisherTransport = DhtUdpTransport(publisherSocket, publisherId, onMessageObserved = {})
        val publisherNode = DhtNode(RoutingTable(publisherId), publisherTransport, scope, ownAddressFor(publisherSocket))

        val relayTransport = DhtUdpTransport(relaySocket, relayId, onMessageObserved = {})
        val relayNode = DhtNode(RoutingTable(relayId), relayTransport, scope, ownAddressFor(relaySocket))

        val transports = listOf(publisherTransport, relayTransport)
        transports.forEach { it.start() }
        try {
            publisherNode.observe(contactFor(relayId, relaySocket))

            TopicSubscription(publisherNode).publish(lat, lon, ReachTier.TOWN)

            val targetCell = Geohash.encode(lat, lon, precision = 5)
            val neighborCells = Geohash.neighbors(targetCell)

            // The relay directly received the STORE -- assert on its raw DhtStore
            // state (via findValue) for the target key and every neighbor key.
            val targetResult = relayNode.findValue(GeohashTopicKey.toNodeId(targetCell))
            assertTrue(targetResult is FindValueResult.Found, "the relay must have recorded the publisher against the target cell's own key")

            neighborCells.forEach { neighborCell ->
                val neighborResult = relayNode.findValue(GeohashTopicKey.toNodeId(neighborCell))
                assertTrue(
                    neighborResult is FindValueResult.NotFound,
                    "publish must never announce into a neighbor cell's key ($neighborCell) -- only the target cell",
                )
            }
        } finally {
            transports.forEach { it.stop() }
        }
    }
}
