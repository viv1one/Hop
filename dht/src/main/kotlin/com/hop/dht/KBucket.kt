package com.hop.dht

/**
 * Result of [KBucket.insertOrUpdate].
 */
sealed class InsertResult {
    /** [Contact] was new and the bucket had room -- inserted at the MRU end. */
    data object Inserted : InsertResult()

    /** [Contact] was already present -- refreshed to the MRU end, no eviction. */
    data object Updated : InsertResult()

    /**
     * Bucket is full and the inserted contact is unknown -- a later network slice
     * must ping [evictionCandidate] (the actual least-recently-seen entry in the
     * bucket) before deciding whether to make room for [replacementCandidate].
     *
     * Ping succeeds -> call [KBucket.markAlive] with [evictionCandidate]'s id.
     * Ping fails -> call [KBucket.removeAndPromoteReplacement] with
     * [evictionCandidate]'s id.
     *
     * This slice implements the bookkeeping and the correct signal; it implements
     * zero policy about when/whether to actually ping. This ping-then-evict shape
     * (rather than unconditionally evicting the least-recently-seen contact) is
     * what makes the routing table resistant to an eclipse attack where an
     * adversary floods it with short-lived Sybil contacts to evict established,
     * honest ones -- long-lived contacts are preferred by default.
     */
    data class PendingReplacement(
        val evictionCandidate: Contact,
        val replacementCandidate: Contact,
    ) : InsertResult()
}

/**
 * A single Kademlia k-bucket: up to [k] live contacts plus a small bounded
 * replacement cache, ordered least-recently-seen (LRU, index 0) to
 * most-recently-seen (MRU, last index). Pure data-only bookkeeping -- no network
 * I/O, no policy about when to ping a contact. That policy belongs to a later
 * network-transport slice; this type only gives it the correct shape to call into.
 */
class KBucket(
    val k: Int = DEFAULT_K,
    val replacementCacheSize: Int = DEFAULT_REPLACEMENT_CACHE_SIZE,
) {
    // Both ordered LRU (index 0) -> MRU (last index).
    private val liveContacts = LinkedHashMap<NodeId, Contact>()
    private val replacementCache = LinkedHashMap<NodeId, Contact>()

    val size: Int
        get() = liveContacts.size

    fun insertOrUpdate(contact: Contact): InsertResult {
        val existing = liveContacts.remove(contact.id)
        if (existing != null) {
            liveContacts[contact.id] = contact
            return InsertResult.Updated
        }

        if (liveContacts.size < k) {
            liveContacts[contact.id] = contact
            return InsertResult.Inserted
        }

        // Bucket is full: stash the new contact in the replacement cache (bumping
        // it to MRU if already present there) and surface the current LRU live
        // contact as the eviction candidate for a later slice to ping.
        replacementCache.remove(contact.id)
        replacementCache[contact.id] = contact
        while (replacementCache.size > replacementCacheSize) {
            val oldestKey = replacementCache.keys.first()
            replacementCache.remove(oldestKey)
        }

        val evictionCandidate = liveContacts.values.first()
        return InsertResult.PendingReplacement(
            evictionCandidate = evictionCandidate,
            replacementCandidate = contact,
        )
    }

    /** Ordered least-recently-seen -> most-recently-seen. */
    fun contacts(): List<Contact> = liveContacts.values.toList()

    /** Ordered least-recently-seen -> most-recently-seen within the cache. */
    fun replacementCandidates(): List<Contact> = replacementCache.values.toList()

    /**
     * Bumps an existing contact to most-recently-seen without eviction -- called
     * when a later slice's liveness ping to it succeeds. Returns false if
     * [nodeId] isn't in this bucket.
     */
    fun markAlive(nodeId: NodeId): Boolean {
        val existing = liveContacts.remove(nodeId) ?: return false
        liveContacts[nodeId] = existing
        return true
    }

    /**
     * Evicts [nodeId] and promotes the most-recently-seen replacement-cache entry
     * into its place (or leaves the bucket short if the cache is empty) -- called
     * when a later slice's liveness ping fails. Returns the evicted contact, or
     * null if [nodeId] wasn't present.
     */
    fun removeAndPromoteReplacement(nodeId: NodeId): Contact? {
        val evicted = liveContacts.remove(nodeId) ?: return null

        if (replacementCache.isNotEmpty()) {
            val newestKey = replacementCache.keys.last()
            val promoted = replacementCache.remove(newestKey)
            if (promoted != null) {
                liveContacts[promoted.id] = promoted
            }
        }

        return evicted
    }

    companion object {
        /** Classic Kademlia value. Unmeasured for HOP's actual network density -- revisit once real data exists, same posture as RelayPolicy.DEFAULT_MAX_HOPS. */
        const val DEFAULT_K = 20

        /** Unmeasured placeholder, same posture. */
        const val DEFAULT_REPLACEMENT_CACHE_SIZE = 5
    }
}
