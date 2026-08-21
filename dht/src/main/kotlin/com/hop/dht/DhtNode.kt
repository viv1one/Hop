package com.hop.dht

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Wires [RoutingTable]'s custody decisions to actual liveness pings over
 * [DhtUdpTransport] -- closing the exact gap [KBucket.InsertResult.PendingReplacement]
 * was built for in Slice 2 ("a later network-transport slice's job"). This
 * was the point of Slice 3: everything else ([DhtUdpTransport], [DhtMessage],
 * [PeerAddress], [TransactionId]) exists to make [observe] possible.
 *
 * Slice 4 adds [findNode] (the iterative lookup, via [IterativeLookup]) and
 * [bootstrapJoin] (populating a routing table from nothing but a single
 * address) -- and, in the `init` block below, fixes a real gap left in the
 * already-shipped Slice 3 code: [DhtUdpTransport.onMessageObserved] was never
 * actually wired to this class's [observe] anywhere in the composition, so a
 * PONG's/FIND_NODE_REQUEST's receipt never reached the routing table at all.
 * [bootstrapJoin] depends entirely on that wiring existing.
 */
class DhtNode(
    val routingTable: RoutingTable,
    private val transport: DhtUdpTransport,
    private val scope: CoroutineScope,
) {
    init {
        // THE REQUIRED FIX: wires transport's two callbacks to this instance.
        // Without this, PONGs/FIND_NODE_REQUESTs never reach the routing
        // table at all -- confirmed absent in the already-shipped Slice 3
        // composition (both DhtUdpTransportTest and DhtNodeTest happened to
        // call observe() themselves in tests, which is why passing tests
        // never caught this), and load-bearing for bootstrapJoin below to do
        // anything.
        transport.onMessageObserved = ::observe
        transport.onFindNodeRequested = { targetId, excludeId ->
            // Over-fetch by one, filter, THEN truncate to k -- not truncate-
            // then-filter. Filtering after truncation would silently cost a
            // legitimately k-th-closest contact its slot whenever the
            // requester itself would have occupied it.
            routingTable.findClosest(targetId, routingTable.k + 1)
                .filter { it.id != excludeId }
                .take(routingTable.k)
        }
    }

    /**
     * Wraps [RoutingTable.insertOrUpdate]. On
     * [InsertResult.PendingReplacement], launches a real
     * [DhtUdpTransport.ping] to [InsertResult.PendingReplacement.evictionCandidate]
     * on [scope] (never blocking the caller) and applies the outcome to the
     * bucket [evictionCandidate] lives in: [KBucket.markAlive] if it answered,
     * [KBucket.removeAndPromoteReplacement] if it didn't.
     */
    fun observe(contact: Contact) {
        when (val result = routingTable.insertOrUpdate(contact)) {
            is InsertResult.PendingReplacement -> {
                val evictionCandidate = result.evictionCandidate
                scope.launch {
                    val alive = transport.ping(evictionCandidate)
                    val bucket = routingTable.bucketFor(evictionCandidate.id)
                    if (alive) {
                        bucket.markAlive(evictionCandidate.id)
                    } else {
                        bucket.removeAndPromoteReplacement(evictionCandidate.id)
                    }
                }
            }
            InsertResult.Inserted, InsertResult.Updated -> Unit
        }
    }

    /**
     * Runs [IterativeLookup] against this device's own routing table as
     * seeds, feeding every contact it discovers back through [observe] AS
     * THE LOOKUP RUNS (inside the [IterativeLookup.queryFn] callback below,
     * not just once at the end on the final returned list) -- routing-table
     * population is at least as much the point of this call as the returned
     * list itself.
     */
    suspend fun findNode(targetId: NodeId): List<Contact> {
        val seeds = routingTable.findClosest(targetId, routingTable.k)
        val lookup = IterativeLookup(
            ownId = routingTable.ownId,
            k = routingTable.k,
            queryFn = { contact, target ->
                val discovered = transport.findNode(contact, target)
                discovered?.forEach { observe(it) }
                discovered
            },
        )
        return lookup.lookup(targetId, seeds)
    }

    /**
     * Pings [bootstrapAddress] via a throwaway placeholder [Contact] bearing
     * a zero [NodeId] -- that placeholder is passed ONLY to
     * [DhtUdpTransport.ping], never to [observe]/[RoutingTable.insertOrUpdate]
     * directly, since it has no real id yet and doing so would poison the
     * table with a fabricated id-to-address mapping. The real, correctly-
     * id'd routing-table entry for the bootstrap node comes exclusively from
     * [DhtUdpTransport.onMessageObserved] firing off the bootstrap's PONG
     * (wired in `init` above -- this only works once that fix is in place).
     *
     * On a successful PONG, runs [findNode] against this device's own id to
     * pull a real contact set from the network. Returns `emptyList()` if the
     * bootstrap never answers.
     */
    suspend fun bootstrapJoin(bootstrapAddress: PeerAddress): List<Contact> {
        val placeholder = Contact(
            id = NodeId(ByteArray(NodeId.SIZE_BYTES)),
            address = bootstrapAddress.encode(),
            lastSeenAtMs = 0L,
        )
        val alive = transport.ping(placeholder)
        if (!alive) return emptyList()
        return findNode(routingTable.ownId)
    }
}
