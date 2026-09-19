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

    /**
     * Every DhtNode in these tests binds to loopback -- this is its
     * ownAddresses (a single-entry list; DhtNode's constructor param is a
     * `List<PeerAddress>` as of Phase 4's IPv6-first/dual-stack slice, to let
     * a dual-stack device announce more than one address -- see
     * `dual-stack self-registration round-trips both addresses through a real
     * FIND_VALUE wire exchange` below for a test that actually exercises two).
     */
    private fun ownAddressFor(socket: DatagramSocket): List<PeerAddress> =
        listOf(PeerAddress.from(InetAddress.getLoopbackAddress(), socket.localPort))

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
            ownAddresses = ownAddressFor(aSocket),
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

    // ---- Phase 4 rendezvous-relayed-introduction additions ----

    /**
     * A real [DhtNode] answers INTRODUCE_REQUEST correctly using its own
     * [RoutingTable] -- not a synthetic insert -- once it has genuinely
     * observed the target via the ordinary PING/PONG mechanism, mirroring
     * [RendezvousNodeTest]'s equivalent proof for [RendezvousNode] over the
     * same shared [DhtUdpTransport]. No new method was added to
     * [RoutingTable]/[KBucket] for this -- [DhtNode]'s `init` block reuses
     * [RoutingTable.findClosest] at `count = 1`, which is guaranteed to
     * return exactly the requested id when it's genuinely a live entry
     * (distance-to-self is the minimum possible, zero).
     */
    @Test
    fun `onIntroduceRequested answers from RoutingTable once the target has been observed via PING`() = runBlocking {
        val rId = chainNodeId(71) // plays the "rendezvous/DHT contact" role
        val aId = chainNodeId(72)
        val targetId = chainNodeId(73)

        val rSocket = loopbackSocket()
        val aSocket = loopbackSocket()
        val targetSocket = loopbackSocket()
        val scope = CoroutineScope(Job() + Dispatchers.Default)

        val rTransport = DhtUdpTransport(rSocket, rId, onMessageObserved = {})
        DhtNode(RoutingTable(rId), rTransport, scope, ownAddressFor(rSocket)) // init block wires rTransport's onIntroduceRequested

        var receivedFromId: NodeId? = null
        val targetTransport = DhtUdpTransport(targetSocket, targetId, onMessageObserved = {})
        // Passed through DhtNode's own onIntroductionReceived constructor
        // parameter (Phase 4's final hole-punching slice), not set on
        // targetTransport directly -- setting it directly here would now be
        // clobbered by DhtNode's own init-block forwarding (see that
        // parameter's own doc), which runs after this line if a caller ever
        // tried the old workaround.
        DhtNode(
            RoutingTable(targetId),
            targetTransport,
            scope,
            ownAddressFor(targetSocket),
            onIntroductionReceived = { fromId, _ -> receivedFromId = fromId },
        )

        val aTransport = DhtUdpTransport(aSocket, aId, onMessageObserved = {})
        DhtNode(RoutingTable(aId), aTransport, scope, ownAddressFor(aSocket))

        val transports = listOf(rTransport, targetTransport, aTransport)
        transports.forEach { it.start() }
        try {
            // R must genuinely observe the target via an ordinary PING before
            // its RoutingTable has a live entry for it -- not a synthetic
            // insert.
            assertTrue(rTransport.ping(socketContact(targetId, targetSocket)))

            val ownReflectedAddress = PeerAddress.from(InetAddress.getLoopbackAddress(), 54321)
            val result = aTransport.introduce(socketContact(rId, rSocket), targetId, ownReflectedAddress)

            requireNotNull(result) { "a real DhtNode must answer found=true for a target it has genuinely observed via PING" }
            assertEquals(
                PeerAddress.from(InetAddress.getLoopbackAddress(), targetSocket.localPort),
                result,
                "the returned address must be the target's genuinely observed address from R's own RoutingTable",
            )

            val deadlineMs = System.currentTimeMillis() + 2000
            while (receivedFromId == null && System.currentTimeMillis() < deadlineMs) {
                Thread.sleep(20)
            }
            assertEquals(aId, receivedFromId, "the target must receive a real INTRODUCTION naming A as fromId")
        } finally {
            transports.forEach { it.stop() }
        }
    }

    @Test
    fun `onIntroduceRequested answers not-found for a target never observed, even when RoutingTable knows an unrelated closer contact`() = runBlocking {
        val rId = chainNodeId(81)
        val aId = chainNodeId(82)
        val unrelatedId = chainNodeId(84) // known to R, but NOT the requested target
        val neverObservedTargetId = chainNodeId(83)

        val rSocket = loopbackSocket()
        val aSocket = loopbackSocket()
        val scope = CoroutineScope(Job() + Dispatchers.Default)

        val rTransport = DhtUdpTransport(rSocket, rId, onMessageObserved = {})
        val rNode = DhtNode(RoutingTable(rId), rTransport, scope, ownAddressFor(rSocket))

        val aTransport = DhtUdpTransport(aSocket, aId, onMessageObserved = {})
        DhtNode(RoutingTable(aId), aTransport, scope, ownAddressFor(aSocket))

        val transports = listOf(rTransport, aTransport)
        transports.forEach { it.start() }
        try {
            // R knows of a DIFFERENT peer -- RoutingTable.findClosest(targetId, 1)
            // will happily return this "closest known" contact even though it
            // isn't the requested target. The `it.id == targetId` filter in
            // DhtNode's init block must reject that mismatch rather than
            // misreporting an unrelated contact as the found target.
            rNode.observe(dummyContact(unrelatedId))

            val ownReflectedAddress = PeerAddress.from(InetAddress.getLoopbackAddress(), 1)
            val result = aTransport.introduce(socketContact(rId, rSocket), neverObservedTargetId, ownReflectedAddress)
            assertEquals(null, result, "a target R's RoutingTable has never observed must answer not-found (null), even when it knows of some other closer contact")
        } finally {
            transports.forEach { it.stop() }
        }
    }

    // ---- Phase 4's final hole-punching slice: onIntroductionReceived forwarding ----

    /**
     * The last piece of the Phase 4 hole-punching thread, at [DhtNode]'s own
     * layer: proves [DhtNode]'s `onIntroductionReceived` constructor
     * parameter is actually forwarded to `transport.onIntroductionReceived`
     * in `init`, rather than being silently left at the transport's own
     * no-op default (the gap this exact test file's own
     * `onIntroduceRequested answers from RoutingTable...` test above worked
     * around by setting `targetTransport.onIntroductionReceived` directly,
     * since [DhtNode] didn't forward it yet at the time that test was
     * written). Same real loopback round trip as that test -- R genuinely
     * observes the target via PING, then A calls a real
     * [DhtUdpTransport.introduce] through R -- but the target's [DhtNode] is
     * now constructed with a real callback lambda passed through the
     * constructor, never touching `targetTransport` directly, so this only
     * passes if [DhtNode]'s forwarding is actually wired.
     */
    @Test
    fun `onIntroductionReceived passed to DhtNode's constructor is forwarded to transport and fires on a real INTRODUCTION`() = runBlocking {
        val rId = chainNodeId(91)
        val aId = chainNodeId(92)
        val targetId = chainNodeId(93)

        val rSocket = loopbackSocket()
        val aSocket = loopbackSocket()
        val targetSocket = loopbackSocket()
        val scope = CoroutineScope(Job() + Dispatchers.Default)

        val rTransport = DhtUdpTransport(rSocket, rId, onMessageObserved = {})
        DhtNode(RoutingTable(rId), rTransport, scope, ownAddressFor(rSocket)) // init block wires rTransport's onIntroduceRequested

        var receivedFromId: NodeId? = null
        var receivedAddress: PeerAddress? = null
        val targetTransport = DhtUdpTransport(targetSocket, targetId, onMessageObserved = {})
        // The callback is threaded through DhtNode's constructor here --
        // NOT set on targetTransport directly -- this is the exact thing
        // under test.
        DhtNode(
            RoutingTable(targetId),
            targetTransport,
            scope,
            ownAddressFor(targetSocket),
            onIntroductionReceived = { fromId, claimedAddress ->
                receivedFromId = fromId
                receivedAddress = claimedAddress
            },
        )

        val aTransport = DhtUdpTransport(aSocket, aId, onMessageObserved = {})
        DhtNode(RoutingTable(aId), aTransport, scope, ownAddressFor(aSocket))

        val transports = listOf(rTransport, targetTransport, aTransport)
        transports.forEach { it.start() }
        try {
            // R must genuinely observe the target via an ordinary PING before
            // its RoutingTable has a live entry for it -- not a synthetic
            // insert.
            assertTrue(rTransport.ping(socketContact(targetId, targetSocket)))

            val ownReflectedAddress = PeerAddress.from(InetAddress.getLoopbackAddress(), 54322)
            val result = aTransport.introduce(socketContact(rId, rSocket), targetId, ownReflectedAddress)
            requireNotNull(result) { "a real DhtNode must answer found=true for a target it has genuinely observed via PING" }

            val deadlineMs = System.currentTimeMillis() + 2000
            while (receivedFromId == null && System.currentTimeMillis() < deadlineMs) {
                Thread.sleep(20)
            }
            assertEquals(aId, receivedFromId, "DhtNode's onIntroductionReceived constructor parameter must fire with the real fromId, proving it was forwarded to transport")
            assertEquals(ownReflectedAddress, receivedAddress, "the forwarded callback must receive A's real self-reported reflected address, unmodified")
        } finally {
            transports.forEach { it.stop() }
        }
    }

    // ---- IPv6-first/dual-stack self-registration slice additions ----

    /**
     * Proves the actual end-to-end path this slice is about -- self-
     * registration -> real wire gossip -> a receiving peer's decode -- not
     * just [PeerAddress.encodeList]/[PeerAddress.decodeList] in isolation.
     *
     * A is dual-stack: [DhtNode.store]'s self-registration is given both a
     * real `::1` IPv6 [PeerAddress] and a real loopback IPv4 [PeerAddress].
     * A genuine dual-stack NIC isn't guaranteed in a CI/test environment, but
     * a real `::1` loopback address costs nothing to construct and proves
     * the mechanism more faithfully than a v4/v4-with-a-different-family-byte
     * fake pair -- neither address needs to be bound or actually dialed here,
     * since nothing in this test ever connects to them; only
     * [PeerAddress.decodeList]'s fidelity through the real wire path is under
     * test.
     *
     * B seeds its routing table with A's real, dialable address (the same
     * [socketContact] helper every other test in this file uses) so that
     * [DhtNode.findValue] on B -- finding nothing in its own local store --
     * queries A directly over a real FIND_VALUE_REQUEST/FIND_VALUE_RESPONSE
     * round trip. This is deliberately a FIND_VALUE round trip, not a
     * FIND_NODE one: [StoreRequestMessage] carries no address field of its
     * own (see that message's own doc, and
     * [DhtUdpTransport.handlePacket]'s STORE_REQUEST case, which always
     * rebuilds a fresh single-address announcer [Contact] from the packet's
     * *observed* source instead), so a self-registered multi-address
     * [Contact] only ever reaches the wire when the self-registering node
     * answers a FIND_VALUE_REQUEST for a key it holds directly -- never via
     * STORE_REQUEST propagation to a third node. B's decoded holder
     * [Contact.address] must round-trip both of A's original addresses, in
     * the order A registered them.
     */
    @Test
    fun `dual-stack self-registration round-trips both addresses through a real FIND_VALUE wire exchange`() = runBlocking {
        val aId = chainNodeId(61)
        val bId = chainNodeId(62)
        val key = chainNodeId(102)

        val aSocket = loopbackSocket()
        val bSocket = loopbackSocket()
        val scope = CoroutineScope(Job() + Dispatchers.Default)

        val ipv6Address = PeerAddress(
            family = PeerAddress.FAMILY_IPV6,
            ip = InetAddress.getByName("::1").address,
            port = 4242,
        )
        val ipv4Address = PeerAddress.from(InetAddress.getLoopbackAddress(), 4243)
        val aOwnAddresses = listOf(ipv6Address, ipv4Address)

        val aTransport = DhtUdpTransport(aSocket, aId, onMessageObserved = {})
        val aNode = DhtNode(RoutingTable(aId), aTransport, scope, aOwnAddresses)

        val bTransport = DhtUdpTransport(bSocket, bId, onMessageObserved = {})
        val bNode = DhtNode(RoutingTable(bId), bTransport, scope, ownAddressFor(bSocket))

        val transports = listOf(aTransport, bTransport)
        transports.forEach { it.start() }
        try {
            // B must know A's real, dialable address up front. A's own
            // routing table stays empty (it never observes B), so store(key)
            // below only self-registers locally and never attempts a
            // STORE_REQUEST to anyone -- findNode(key) against an empty
            // routing table finds nobody to announce to (same setup already
            // proven safe by "findValue discovers a holder through the
            // network..." above, where C's empty routing table plays the
            // same role).
            bNode.observe(socketContact(aId, aSocket))

            aNode.store(key)

            val result = bNode.findValue(key)

            assertTrue(
                result is FindValueResult.Found,
                "B must discover A directly as the holder via a real FIND_VALUE_REQUEST/RESPONSE round trip",
            )
            assertEquals(listOf(aId), result.holders.map { it.id })

            val decodedAddresses = PeerAddress.decodeList(result.holders[0].address)
            assertEquals(
                aOwnAddresses,
                decodedAddresses,
                "the holder Contact's address, decoded via decodeList, must round-trip both of A's original self-registered addresses in order",
            )
        } finally {
            transports.forEach { it.stop() }
        }
    }

    // ---- Phase 4 volunteer-relay-discovery additions ----

    /**
     * Proves a full [DhtNode] answers RELAY_ANNOUNCE/RELAY_QUERY correctly
     * too, not just `rendezvous/`'s `RendezvousNode` (see [DhtNode]'s own
     * `init`-block wiring and [RelayDirectory]'s own class doc for why both
     * consumers share this same class) -- mirrors
     * `RendezvousNodeTest`'s equivalent end-to-end test.
     */
    @Test
    fun `a relay's RELAY_ANNOUNCE against a DhtNode is acked and recorded, and a later RELAY_QUERY returns it`() = runBlocking {
        val hostId = zeroId()
        val hostSocket = loopbackSocket()
        val hostTransport = DhtUdpTransport(hostSocket, hostId, onMessageObserved = {})
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        // Constructed purely for its init-block wiring side effect --
        // never otherwise referenced, same posture as RendezvousNodeTest's
        // own `val rendezvous = RendezvousNode(...)` binding.
        DhtNode(RoutingTable(hostId), hostTransport, scope, ownAddressFor(hostSocket))

        val relaySocket = loopbackSocket()
        val relayId = idInBucketZero(1)
        val relayTransport = DhtUdpTransport(relaySocket, relayId)

        val querierSocket = loopbackSocket()
        val querierTransport = DhtUdpTransport(querierSocket, idInBucketZero(2))

        // Deliberately NOT relaySocket's own UDP address -- the returned
        // entry must be the relay's genuinely self-reported TCP bridge
        // address, never anything derived from this packet's observed
        // source.
        val selfReportedBridgeAddress = PeerAddress.from(InetAddress.getLoopbackAddress(), 54321)

        hostTransport.start()
        relayTransport.start()
        querierTransport.start()
        try {
            val acked = relayTransport.announceRelay(contactAt(hostId, hostSocket), selfReportedBridgeAddress)
            assertTrue(acked, "a RELAY_ANNOUNCE to a running DhtNode must ack true")

            val relays = querierTransport.queryRelays(contactAt(hostId, hostSocket))
            assertEquals(listOf(relayId), relays.map { it.id })
            assertEquals(
                selfReportedBridgeAddress.encode().toList(),
                relays[0].address.toList(),
                "the returned relay's address must be exactly its self-reported relayAddress",
            )
        } finally {
            hostTransport.stop()
            relayTransport.stop()
            querierTransport.stop()
        }
    }

    @Test
    fun `RELAY_QUERY against a DhtNode that has never had a relay announce returns an empty list`() = runBlocking {
        val hostId = zeroId()
        val hostSocket = loopbackSocket()
        val hostTransport = DhtUdpTransport(hostSocket, hostId, onMessageObserved = {})
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        // Constructed purely for its init-block wiring side effect -- see the test above.
        DhtNode(RoutingTable(hostId), hostTransport, scope, ownAddressFor(hostSocket))

        val querierSocket = loopbackSocket()
        val querierTransport = DhtUdpTransport(querierSocket, idInBucketZero(2))

        hostTransport.start()
        querierTransport.start()
        try {
            val relays = querierTransport.queryRelays(contactAt(hostId, hostSocket))
            assertTrue(relays.isEmpty(), "querying a DhtNode with nothing announced must return an empty list")
        } finally {
            hostTransport.stop()
            querierTransport.stop()
        }
    }
}
