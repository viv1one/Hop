package com.hop.dht

/**
 * A flat, fully-preallocated Kademlia routing table: one [KBucket] per possible
 * [NodeId.sharedPrefixLength] value (0..255), indexed by that value relative to
 * [ownId]. A flat array (rather than classic lazy bucket-splitting) is
 * behaviorally identical once a bucket exists -- splitting only changes when
 * bucket objects get instantiated, not lookup correctness -- and at HOP's
 * realistic scale (a proximity mesh plus a volunteer relay/bootstrap network, not
 * millions of concurrent peers on server infrastructure) 256 empty buckets cost a
 * few KB.
 */
class RoutingTable(val ownId: NodeId, val k: Int = KBucket.DEFAULT_K) {

    private val buckets: Array<KBucket> = Array(NodeId.SIZE_BITS) { KBucket(k) }

    /**
     * No-ops/rejects if `contact.id == ownId` -- a routing table never stores an
     * entry for itself. Returns [InsertResult.Updated] as an inert sentinel in
     * that case (there is nothing to insert and nothing pending).
     */
    fun insertOrUpdate(contact: Contact): InsertResult {
        if (contact.id == ownId) return InsertResult.Updated
        return bucketFor(contact.id).insertOrUpdate(contact)
    }

    /**
     * Collects contacts across ALL buckets, sorts by `distanceTo(targetId)`, takes
     * [count]. Deliberately full-scan-and-sort, not a bucket-index-expansion
     * search outward from the target's own nearest bucket -- correctness-first at
     * this scale: the true-closest contacts to an arbitrary target are not
     * guaranteed to all sit in the bucket nearest that target's own
     * shared-prefix-length with [ownId].
     */
    fun findClosest(targetId: NodeId, count: Int): List<Contact> {
        return buckets
            .asSequence()
            .flatMap { it.contacts().asSequence() }
            .sortedBy { it.id.distanceTo(targetId) }
            .take(count)
            .toList()
    }

    /**
     * The bucket [id] would live in, indexed by `id.sharedPrefixLength(ownId)`.
     * Never called with `id == ownId` in practice (that would be index 256, out of
     * bounds by construction) -- [insertOrUpdate] guards against storing an entry
     * for [ownId] before this is ever reached for it.
     */
    fun bucketFor(id: NodeId): KBucket {
        val index = id.sharedPrefixLength(ownId)
        require(index < NodeId.SIZE_BITS) {
            "bucketFor() called with an id identical to ownId (sharedPrefixLength == ${NodeId.SIZE_BITS}); a routing table never buckets itself"
        }
        return buckets[index]
    }
}
