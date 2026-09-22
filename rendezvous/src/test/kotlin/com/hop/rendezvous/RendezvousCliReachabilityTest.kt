package com.hop.rendezvous

import com.hop.dht.DhtNode
import com.hop.dht.DhtUdpTransport
import com.hop.dht.NodeId
import com.hop.dht.PeerAddress
import com.hop.dht.RoutingTable
import java.io.File
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/**
 * Proves `RendezvousCli`'s own construction path -- [loadOrCreateNodeIdSeed]
 * + [NodeId.fromKeyMaterial] + a bound [DatagramSocket] + `RendezvousNode(socket,
 * ownId)`, in that exact order -- produces a genuinely reachable ADR 0002
 * bootstrap node, not just "main() doesn't throw." A real [DhtNode]
 * bootstrap-joins through it and looks up another real peer, matching
 * `DhtNodeManagerTest`'s own real two-node round-trip style (that class
 * being Android-only and unavailable to this plain-JVM module).
 *
 * [main][RendezvousCli]'s own args-parsing and blocking shutdown-latch wait
 * are deliberately NOT exercised here -- this test drives the exact
 * construction steps that precede them, which is the part with real
 * behavior to verify.
 */
class RendezvousCliReachabilityTest {

    private fun loopbackSocket(): DatagramSocket = DatagramSocket(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))

    private fun ownAddressFor(socket: DatagramSocket): List<PeerAddress> =
        listOf(PeerAddress.from(InetAddress.getLoopbackAddress(), socket.localPort))

    @Test
    fun `loadOrCreateNodeIdSeed persists a stable seed across repeated calls, same as across a real process restart`() {
        val seedFile = File.createTempFile("rendezvous-cli-test-seed", ".bin")
        seedFile.delete() // start from "file does not exist yet" -- a genuine first run
        try {
            val first = loadOrCreateNodeIdSeed(seedFile)
            assertTrue(seedFile.isFile, "a fresh seed must be persisted to disk on first run")

            val second = loadOrCreateNodeIdSeed(seedFile)
            assertEquals(
                first.toList(),
                second.toList(),
                "a second call against the same seed file (simulating a process restart) must reuse the identical seed, never generate a fresh one -- see RendezvousCli's own \"Stable identity across restarts\" doc for why a churning id would break every peer's cached routing-table entry for this node",
            )
        } finally {
            seedFile.delete()
        }
    }

    @Test
    fun `a RendezvousNode built via the CLI's own construction path is a real bootstrap-and-lookup target for a DhtNode`() = runBlocking {
        val seedFile = File.createTempFile("rendezvous-cli-test-seed", ".bin")
        seedFile.delete()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            // Exactly RendezvousCli.main's own construction steps, short of
            // CLI-arg parsing and the blocking shutdown-latch wait.
            val seed = loadOrCreateNodeIdSeed(seedFile)
            val ownId = NodeId.fromKeyMaterial(seed)
            val rendezvousSocket = loopbackSocket()
            val rendezvous = RendezvousNode(rendezvousSocket, ownId)
            rendezvous.start()

            val bootstrapAddress = PeerAddress.from(InetAddress.getLoopbackAddress(), rendezvousSocket.localPort)

            val aSocket = loopbackSocket()
            val aId = NodeId.fromKeyMaterial("rendezvous-cli-test-peer-a")
            val aTransport = DhtUdpTransport(aSocket, aId)
            val aNode = DhtNode(RoutingTable(aId), aTransport, scope, ownAddressFor(aSocket))
            aTransport.start()

            val bSocket = loopbackSocket()
            val bId = NodeId.fromKeyMaterial("rendezvous-cli-test-peer-b")
            val bTransport = DhtUdpTransport(bSocket, bId)
            val bNode = DhtNode(RoutingTable(bId), bTransport, scope, ownAddressFor(bSocket))
            bTransport.start()

            try {
                val aDiscovered = aNode.bootstrapJoin(bootstrapAddress)
                assertTrue(aDiscovered.any { it.id == ownId }, "A's bootstrapJoin must discover the CLI-constructed rendezvous node itself")

                val bDiscovered = bNode.bootstrapJoin(bootstrapAddress)
                assertTrue(bDiscovered.any { it.id == ownId }, "B's bootstrapJoin must discover the CLI-constructed rendezvous node itself")
                assertTrue(bDiscovered.any { it.id == aId }, "B's bootstrapJoin must discover A via the rendezvous node's FIND_NODE peer exchange, since A already registered itself there")

                val lookup = aNode.findNode(bId)
                assertTrue(lookup.any { it.id == bId }, "A must be able to look up B by id end-to-end, having learned of B only via the CLI-constructed rendezvous node")
            } finally {
                rendezvous.stop()
                aTransport.stop()
                bTransport.stop()
            }
        } finally {
            scope.cancel()
            seedFile.delete()
        }
    }
}
