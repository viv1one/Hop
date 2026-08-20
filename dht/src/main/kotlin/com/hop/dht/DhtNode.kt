package com.hop.dht

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Wires [RoutingTable]'s custody decisions to actual liveness pings over
 * [DhtUdpTransport] -- closing the exact gap [KBucket.InsertResult.PendingReplacement]
 * was built for in Slice 2 ("a later network-transport slice's job"). This is
 * the point of this slice: everything else ([DhtUdpTransport], [DhtMessage],
 * [PeerAddress], [TransactionId]) exists to make [observe] possible.
 *
 * No FIND_NODE/FIND_VALUE/STORE, no bootstrap-join flow -- this facade only
 * ever pings a contact this device already learned about some other way
 * (currently: [DhtUdpTransport.onMessageObserved] firing on an inbound
 * PING/PONG). Populating a routing table from nothing (a real network join)
 * needs FIND_NODE at minimum and is explicitly a later slice's job.
 */
class DhtNode(
    val routingTable: RoutingTable,
    private val transport: DhtUdpTransport,
    private val scope: CoroutineScope,
) {
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
}
