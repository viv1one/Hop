package com.hop.dht

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
 * Full lifecycle test: fill a [KBucket] to capacity via [RoutingTable], insert
 * one more contact into the same bucket, and confirm [DhtNode.observe] turns
 * the resulting [InsertResult.PendingReplacement] into a real ping over two
 * live [DhtUdpTransport] instances -- [KBucket.markAlive] when the eviction
 * candidate answers, [KBucket.removeAndPromoteReplacement] when it doesn't
 * (simulated by never starting that transport's receive loop -- a live,
 * bound socket that simply never answers).
 */
class DhtNodeTest {

    private fun loopbackSocket(): DatagramSocket = DatagramSocket(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))

    private fun zeroId(): NodeId = NodeId(ByteArray(NodeId.SIZE_BYTES))

    /** Both share sharedPrefixLength(zeroId()) == 0 -- same bucket -- while being distinct ids. */
    private fun idInBucketZero(secondByte: Int): NodeId {
        val bytes = ByteArray(NodeId.SIZE_BYTES)
        bytes[0] = 0x80.toByte() // first bit set -> sharedPrefixLength 0 vs. an all-zero ownId
        bytes[1] = secondByte.toByte() // distinguishes the two ids from each other
        return NodeId(bytes)
    }

    private fun contactAt(id: NodeId, socket: DatagramSocket): Contact =
        Contact(
            id = id,
            address = PeerAddress.from(InetAddress.getLoopbackAddress(), socket.localPort).encode(),
            lastSeenAtMs = 0L,
        )

    /**
     * [DhtNode.observe] launches the ping-then-evict outcome as a fire-and-
     * forget child coroutine on [scope] -- this joins every child of [scope]'s
     * own [Job] so the test can deterministically observe the outcome before
     * asserting on [RoutingTable]/[KBucket] state.
     */
    private fun awaitPendingWork(scope: CoroutineScope) = runBlocking {
        scope.coroutineContext[Job]!!.children.toList().forEach { it.join() }
    }

    @Test
    fun `PendingReplacement with a reachable eviction candidate calls markAlive`() {
        val ownId = zeroId()
        val evictionCandidateId = idInBucketZero(1)
        val replacementCandidateId = idInBucketZero(2)

        val ownSocket = loopbackSocket()
        val evictionCandidateSocket = loopbackSocket()

        val ownTransport = DhtUdpTransport(ownSocket, ownId, onMessageObserved = {}, requestTimeoutMs = 1000)
        // The eviction candidate's own transport: started, so it answers pings.
        val evictionCandidateTransport = DhtUdpTransport(evictionCandidateSocket, evictionCandidateId, onMessageObserved = {})
        ownTransport.start()
        evictionCandidateTransport.start()

        try {
            val routingTable = RoutingTable(ownId = ownId, k = 1)
            val scope = CoroutineScope(Job() + Dispatchers.Default)
            val node = DhtNode(routingTable, ownTransport, scope)

            val evictionCandidate = contactAt(evictionCandidateId, evictionCandidateSocket)
            val replacementCandidate = contactAt(replacementCandidateId, ownSocket) // address irrelevant, never pinged

            // Fill the k=1 bucket to capacity.
            node.observe(evictionCandidate)
            assertEquals(listOf(evictionCandidate), routingTable.bucketFor(evictionCandidateId).contacts())

            // One more contact into the same (now-full) bucket triggers
            // PendingReplacement, which DhtNode.observe must turn into a real ping.
            node.observe(replacementCandidate)
            awaitPendingWork(scope)

            // Reachable -> markAlive: the eviction candidate stays, the
            // replacement candidate is never promoted into the live bucket.
            assertEquals(
                listOf(evictionCandidate),
                routingTable.bucketFor(evictionCandidateId).contacts(),
                "a reachable eviction candidate must be kept (markAlive), not evicted",
            )
            assertEquals(listOf(replacementCandidate), routingTable.bucketFor(evictionCandidateId).replacementCandidates())
        } finally {
            ownTransport.stop()
            evictionCandidateTransport.stop()
        }
    }

    @Test
    fun `PendingReplacement with an unreachable eviction candidate calls removeAndPromoteReplacement`() {
        val ownId = zeroId()
        val evictionCandidateId = idInBucketZero(1)
        val replacementCandidateId = idInBucketZero(2)

        val ownSocket = loopbackSocket()
        // Bound but its DhtUdpTransport is deliberately never started below --
        // a live socket that simply never answers, simulating an unreachable peer.
        val evictionCandidateSocket = loopbackSocket()

        val ownTransport = DhtUdpTransport(ownSocket, ownId, onMessageObserved = {}, requestTimeoutMs = 300)
        ownTransport.start()

        try {
            val routingTable = RoutingTable(ownId = ownId, k = 1)
            val scope = CoroutineScope(Job() + Dispatchers.Default)
            val node = DhtNode(routingTable, ownTransport, scope)

            val evictionCandidate = contactAt(evictionCandidateId, evictionCandidateSocket)
            val replacementCandidate = contactAt(replacementCandidateId, ownSocket) // address irrelevant, never pinged

            node.observe(evictionCandidate)
            assertEquals(listOf(evictionCandidate), routingTable.bucketFor(evictionCandidateId).contacts())

            node.observe(replacementCandidate)
            awaitPendingWork(scope)

            // Unreachable -> removeAndPromoteReplacement: the eviction candidate is
            // gone, and the replacement candidate is promoted into the live bucket.
            assertEquals(
                listOf(replacementCandidate),
                routingTable.bucketFor(evictionCandidateId).contacts(),
                "an unreachable eviction candidate must be evicted and replaced by the promoted replacement candidate",
            )
            assertTrue(
                routingTable.bucketFor(evictionCandidateId).replacementCandidates().isEmpty(),
                "the promoted replacement candidate must leave the replacement cache",
            )
        } finally {
            ownTransport.stop()
            evictionCandidateSocket.close()
        }
    }
}
