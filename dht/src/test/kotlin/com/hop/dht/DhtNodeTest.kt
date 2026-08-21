package com.hop.dht

import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    /** Every DhtNode in these tests binds to loopback -- this is its ownAddress, Slice 5's new required constructor param. */
    private fun ownAddressFor(socket: DatagramSocket): PeerAddress =
        PeerAddress.from(InetAddress.getLoopbackAddress(), socket.localPort)

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
            val node = DhtNode(routingTable, ownTransport, scope, ownAddressFor(ownSocket))

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
            //
            // Note: as of Slice 4's required DhtNode.observe wiring fix,
            // ownTransport.onMessageObserved is no longer the no-op passed at
            // construction -- DhtNode's init block overwrites it to the real
            // node.observe. That means the eviction candidate's own PONG
            // (received here as a side effect of the liveness ping this test
            // triggers) now legitimately re-observes it and refreshes
            // lastSeenAtMs -- correct behavior, not a regression -- so this
            // assertion checks id/address identity rather than exact Contact
            // equality (which would also compare the now-updated timestamp).
            val keptContacts = routingTable.bucketFor(evictionCandidateId).contacts()
            assertEquals(1, keptContacts.size, "a reachable eviction candidate must be kept (markAlive), not evicted")
            assertEquals(evictionCandidateId, keptContacts[0].id)
            assertTrue(evictionCandidate.address.contentEquals(keptContacts[0].address))
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
            val node = DhtNode(routingTable, ownTransport, scope, ownAddressFor(ownSocket))

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

    // ---- Slice 4 additions ----

    private fun chainNodeId(byteValue: Int): NodeId {
        val bytes = ByteArray(NodeId.SIZE_BYTES)
        bytes[NodeId.SIZE_BYTES - 1] = byteValue.toByte()
        return NodeId(bytes)
    }

    /**
     * All-zero bytes except a single bit set at [bitPosition] (0-indexed,
     * MSB-first) -- gives an id whose [NodeId.sharedPrefixLength] against an
     * all-zero id is exactly [bitPosition], and whose magnitude (hence its
     * XOR-distance to an all-zero target) decreases as [bitPosition]
     * increases. Lets a test pick an exact, easy-to-reason-about
     * closest-to-target ranking among several ids without depending on
     * SecureRandom.
     */
    private fun idWithBitSet(bitPosition: Int): NodeId {
        val bytes = ByteArray(NodeId.SIZE_BYTES)
        val byteIndex = bitPosition / 8
        val bitInByte = bitPosition % 8
        bytes[byteIndex] = (0x80 ushr bitInByte).toByte()
        return NodeId(bytes)
    }

    private fun dummyContact(id: NodeId): Contact =
        Contact(id = id, address = PeerAddress.from(InetAddress.getLoopbackAddress(), 1).encode(), lastSeenAtMs = 0L)

    private fun socketContact(id: NodeId, socket: DatagramSocket): Contact =
        Contact(
            id = id,
            address = PeerAddress.from(InetAddress.getLoopbackAddress(), socket.localPort).encode(),
            lastSeenAtMs = 0L,
        )

    /**
     * The Q5/design-point-3 off-by-one regression test: filtering the
     * requester's own contact out of a FIND_NODE response must happen
     * *before* truncating to k (over-fetch k+1, filter, then take k) -- not
     * truncate-to-k-then-filter, which would silently cost a real,
     * legitimately k-th-closest contact its slot whenever the requester
     * itself would have occupied it.
     *
     * Target is the all-zero id, so XOR-distance-to-target is just each
     * contact's raw id value -- [idWithBitSet] with a higher bit position
     * gives a smaller (closer) id. Ranking, closest first: c1, requester,
     * c3, c4, farAway. With k=3: a naive truncate-then-filter would compute
     * top-3 = [c1, requester, c3], filter out requester, and wrongly return
     * only [c1, c3] -- silently dropping c4, which should have taken
     * requester's slot. The correct over-fetch-then-filter-then-take
     * behavior returns [c1, c3, c4].
     */
    @Test
    fun `onFindNodeRequested over-fetches before filtering -- doesn't drop a legitimately k-th-closest contact`() {
        val ownId = zeroId()
        val target = zeroId()
        val k = 3

        val requesterId = idWithBitSet(200)
        val c1 = dummyContact(idWithBitSet(210)) // closest
        val c3 = dummyContact(idWithBitSet(190))
        val c4 = dummyContact(idWithBitSet(180)) // legitimately 4th-closest -- must survive the exclusion
        val farAway = dummyContact(idWithBitSet(50)) // 5th-closest -- must never appear in a k=3 response regardless

        val routingTable = RoutingTable(ownId = ownId, k = k)
        listOf(c1, dummyContact(requesterId), c3, c4, farAway).forEach { routingTable.insertOrUpdate(it) }

        val ownSocket = loopbackSocket()
        val transport = DhtUdpTransport(ownSocket, ownId, onMessageObserved = {})
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        DhtNode(routingTable, transport, scope, ownAddressFor(ownSocket)) // init block wires transport.onFindNodeRequested

        val response = transport.onFindNodeRequested(target, requesterId)

        assertFalse(response.any { it.id == requesterId }, "the requester's own contact must never appear in its own FIND_NODE response")
        assertEquals(
            listOf(c1.id, c3.id, c4.id),
            response.map { it.id },
            "c4 (legitimately k-th-closest once the requester is excluded) must not be dropped, and farAway must not appear",
        )
    }

    /**
     * Real loopback UDP sockets across four [DhtUdpTransport]/[RoutingTable]/
     * [DhtNode] instances in a deliberate chain topology (A knows only B, B
     * knows only C, C knows only D, D knows nobody) -- the low-density,
     * near-broken-chain case this codebase's conventions call out as the one
     * that actually breaks a mesh/relay design in the field, not the dense
     * fully-connected happy path.
     */
    @Test
    fun `findNode discovers peers along a chain topology and populates the routing table as it runs`() = runBlocking {
        val aId = chainNodeId(1)
        val bId = chainNodeId(2)
        val cId = chainNodeId(3)
        val dId = chainNodeId(4)

        val aSocket = loopbackSocket()
        val bSocket = loopbackSocket()
        val cSocket = loopbackSocket()
        val dSocket = loopbackSocket()

        val scope = CoroutineScope(Job() + Dispatchers.Default)

        val aTable = RoutingTable(aId)
        val aTransport = DhtUdpTransport(aSocket, aId, onMessageObserved = {})
        val aNode = DhtNode(aTable, aTransport, scope, ownAddressFor(aSocket))

        val bTransport = DhtUdpTransport(bSocket, bId, onMessageObserved = {})
        val bNode = DhtNode(RoutingTable(bId), bTransport, scope, ownAddressFor(bSocket))

        val cTransport = DhtUdpTransport(cSocket, cId, onMessageObserved = {})
        val cNode = DhtNode(RoutingTable(cId), cTransport, scope, ownAddressFor(cSocket))

        val dTransport = DhtUdpTransport(dSocket, dId, onMessageObserved = {})
        DhtNode(RoutingTable(dId), dTransport, scope, ownAddressFor(dSocket)) // init block wires dTransport's callbacks; D itself knows nobody

        val transports = listOf(aTransport, bTransport, cTransport, dTransport)
        transports.forEach { it.start() }
        try {
            aNode.observe(socketContact(bId, bSocket))
            bNode.observe(socketContact(cId, cSocket))
            cNode.observe(socketContact(dId, dSocket))

            val discovered = aNode.findNode(dId)

            assertTrue(discovered.any { it.id == dId }, "findNode from A must discover D through the B->C->D chain")
            assertTrue(discovered.any { it.id == cId }, "findNode from A must discover the intermediate hop C")

            // The point of findNode isn't just the return value -- every contact
            // discovered along the way must have been fed back through observe()
            // as the lookup ran, not just collected into the final list.
            val aKnownIds = aTable.findClosest(dId, 10).map { it.id }.toSet()
            assertTrue(
                aKnownIds.containsAll(listOf(bId, cId, dId)),
                "A's routing table must end up populated with B, C, and D, not just have them in the returned list",
            )
        } finally {
            transports.forEach { it.stop() }
        }
    }

    /**
     * Bootstrap-join needs only an address, never a [NodeId], up front: a
     * fresh [DhtNode] with an empty routing table, given only the bootstrap
     * node's [PeerAddress], ends up with populated routing-table entries for
     * every other node in a small pre-seeded real-socket topology --
     * confirming the real, correctly-id'd bootstrap entry comes exclusively
     * from [DhtUdpTransport.onMessageObserved] firing off the bootstrap's
     * PONG (this only works because of Slice 4's required DhtNode.observe
     * wiring fix).
     */
    @Test
    fun `bootstrapJoin populates a fresh routing table from just an address`() = runBlocking {
        val aId = chainNodeId(11)
        val bId = chainNodeId(12) // the bootstrap node
        val cId = chainNodeId(13)
        val dId = chainNodeId(14)

        val aSocket = loopbackSocket()
        val bSocket = loopbackSocket()
        val cSocket = loopbackSocket()
        val dSocket = loopbackSocket()

        val scope = CoroutineScope(Job() + Dispatchers.Default)

        val aTable = RoutingTable(aId)
        val aTransport = DhtUdpTransport(aSocket, aId, onMessageObserved = {})
        val aNode = DhtNode(aTable, aTransport, scope, ownAddressFor(aSocket))

        val bTransport = DhtUdpTransport(bSocket, bId, onMessageObserved = {})
        val bNode = DhtNode(RoutingTable(bId), bTransport, scope, ownAddressFor(bSocket))

        val cTransport = DhtUdpTransport(cSocket, cId, onMessageObserved = {})
        val cNode = DhtNode(RoutingTable(cId), cTransport, scope, ownAddressFor(cSocket))

        val dTransport = DhtUdpTransport(dSocket, dId, onMessageObserved = {})
        DhtNode(RoutingTable(dId), dTransport, scope, ownAddressFor(dSocket))

        val transports = listOf(aTransport, bTransport, cTransport, dTransport)
        transports.forEach { it.start() }
        try {
            // Pre-seeded topology B -> C -> D. A starts knowing nobody -- not
            // even bootstrap B's NodeId, only its address.
            bNode.observe(socketContact(cId, cSocket))
            cNode.observe(socketContact(dId, dSocket))

            val bootstrapAddress = PeerAddress.from(InetAddress.getLoopbackAddress(), bSocket.localPort)
            val discovered = aNode.bootstrapJoin(bootstrapAddress)

            assertTrue(discovered.any { it.id == bId }, "bootstrapJoin must discover the bootstrap node B itself")
            assertTrue(discovered.any { it.id == cId }, "bootstrapJoin must discover C via B")
            assertTrue(discovered.any { it.id == dId }, "bootstrapJoin must discover D via C")

            val aKnownIds = aTable.findClosest(aId, 10).map { it.id }.toSet()
            assertTrue(
                aKnownIds.containsAll(listOf(bId, cId, dId)),
                "A's routing table must end up populated with B, C, and D after joining through only B's address",
            )
        } finally {
            transports.forEach { it.stop() }
        }
    }

    @Test
    fun `bootstrapJoin returns emptyList when the bootstrap node never answers`() = runBlocking {
        val aId = chainNodeId(21)
        val aSocket = loopbackSocket()
        val aTransport = DhtUdpTransport(aSocket, aId, onMessageObserved = {}, requestTimeoutMs = 300)
        val aNode = DhtNode(
            RoutingTable(aId),
            aTransport,
            scope = CoroutineScope(Job() + Dispatchers.Default),
            ownAddress = ownAddressFor(aSocket),
        )
        aTransport.start()
        try {
            // Bind and immediately close a socket to obtain a real port guaranteed
            // to have nothing listening on it.
            val deadSocket = loopbackSocket()
            val deadPort = deadSocket.localPort
            deadSocket.close()

            val result = aNode.bootstrapJoin(PeerAddress.from(InetAddress.getLoopbackAddress(), deadPort))
            assertEquals(emptyList(), result, "an unanswered bootstrap ping must yield emptyList(), not hang or throw")
        } finally {
            aTransport.stop()
        }
    }

    // ---- Slice 5 additions ----

    /**
     * Two real nodes, A knowing only B: [DhtNode.store] must do BOTH of its
     * documented things -- unconditional local self-registration (A can
     * answer its own [DhtNode.findValue] for the key immediately, no network
     * round trip) AND a real STORE_REQUEST to the externally-known-closest
     * peer (B, the only candidate [DhtNode.findNode] can find from A's
     * single-entry routing table) that B must actually record.
     */
    @Test
    fun `store registers self locally and announces to the closest known peer over the network`() = runBlocking {
        val aId = chainNodeId(31)
        val bId = chainNodeId(32)
        val key = chainNodeId(99)

        val aSocket = loopbackSocket()
        val bSocket = loopbackSocket()
        val scope = CoroutineScope(Job() + Dispatchers.Default)

        val aTransport = DhtUdpTransport(aSocket, aId, onMessageObserved = {})
        val aNode = DhtNode(RoutingTable(aId), aTransport, scope, ownAddressFor(aSocket))

        val bTransport = DhtUdpTransport(bSocket, bId, onMessageObserved = {})
        val bNode = DhtNode(RoutingTable(bId), bTransport, scope, ownAddressFor(bSocket))

        val transports = listOf(aTransport, bTransport)
        transports.forEach { it.start() }
        try {
            aNode.observe(socketContact(bId, bSocket))

            aNode.store(key)

            val localResult = aNode.findValue(key)
            assertTrue(localResult is FindValueResult.Found, "A must be able to answer its own findValue for a key it just stored, with no network round trip")
            assertEquals(listOf(aId), localResult.holders.map { it.id })

            val remoteResult = bNode.findValue(key)
            assertTrue(remoteResult is FindValueResult.Found, "B must have recorded A as a holder via a real STORE_REQUEST")
            assertEquals(listOf(aId), remoteResult.holders.map { it.id })
        } finally {
            transports.forEach { it.stop() }
        }
    }

    /**
     * The early-termination race itself, over real sockets in a chain
     * topology (A knows only B, B knows only C): C is the true holder (via
     * its own local [DhtNode.store] self-registration only -- C's routing
     * table knows nobody, so no STORE ever reaches the network). A's local
     * store is empty, so [DhtNode.findValue] must fall through to
     * [IterativeLookup], get a not-found/closer-nodes answer from B pointing
     * at C, then query C and terminate EARLY on C's "found" answer -- proven
     * by the fact this returns C as a holder at all, since a full lookup
     * with no early-termination would still (eventually) reach the same
     * answer, but the point of this slice's race-from-outside design is that
     * it doesn't need to exhaust the lookup to get there.
     */
    @Test
    fun `findValue discovers a holder through the network and early-terminates on the found answer`() = runBlocking {
        val aId = chainNodeId(41)
        val bId = chainNodeId(42)
        val cId = chainNodeId(43)
        val key = chainNodeId(100)

        val aSocket = loopbackSocket()
        val bSocket = loopbackSocket()
        val cSocket = loopbackSocket()
        val scope = CoroutineScope(Job() + Dispatchers.Default)

        val aTransport = DhtUdpTransport(aSocket, aId, onMessageObserved = {})
        val aNode = DhtNode(RoutingTable(aId), aTransport, scope, ownAddressFor(aSocket))

        val bTransport = DhtUdpTransport(bSocket, bId, onMessageObserved = {})
        val bNode = DhtNode(RoutingTable(bId), bTransport, scope, ownAddressFor(bSocket))

        val cTransport = DhtUdpTransport(cSocket, cId, onMessageObserved = {})
        val cNode = DhtNode(RoutingTable(cId), cTransport, scope, ownAddressFor(cSocket))

        val transports = listOf(aTransport, bTransport, cTransport)
        transports.forEach { it.start() }
        try {
            aNode.observe(socketContact(bId, bSocket))
            bNode.observe(socketContact(cId, cSocket))

            // C's routing table knows nobody, so this only self-registers
            // locally -- it never reaches the network.
            cNode.store(key)

            val result = aNode.findValue(key)

            assertTrue(result is FindValueResult.Found, "A must discover C as the holder through the B -> C chain")
            assertEquals(listOf(cId), result.holders.map { it.id })
        } finally {
            transports.forEach { it.stop() }
        }
    }

    /**
     * Nobody in the network holds [key]: [DhtNode.findValue] must return
     * [FindValueResult.NotFound] carrying the closest-known contacts (same
     * "closest known" answer [DhtNode.findNode] would have returned), not
     * hang, throw, or fabricate a holder.
     */
    @Test
    fun `findValue returns NotFound with the closest known contacts when nobody holds the key`() = runBlocking {
        val aId = chainNodeId(51)
        val bId = chainNodeId(52)
        val key = chainNodeId(101)

        val aSocket = loopbackSocket()
        val bSocket = loopbackSocket()
        val scope = CoroutineScope(Job() + Dispatchers.Default)

        val aTransport = DhtUdpTransport(aSocket, aId, onMessageObserved = {})
        val aNode = DhtNode(RoutingTable(aId), aTransport, scope, ownAddressFor(aSocket))

        val bTransport = DhtUdpTransport(bSocket, bId, onMessageObserved = {})
        DhtNode(RoutingTable(bId), bTransport, scope, ownAddressFor(bSocket)) // nobody ever stores `key`

        val transports = listOf(aTransport, bTransport)
        transports.forEach { it.start() }
        try {
            aNode.observe(socketContact(bId, bSocket))

            val result = aNode.findValue(key)

            assertTrue(result is FindValueResult.NotFound, "no holder anywhere must yield NotFound, not hang, throw, or fabricate a holder")
            assertEquals(listOf(bId), result.closestKnown.map { it.id })
        } finally {
            transports.forEach { it.stop() }
        }
    }
}
