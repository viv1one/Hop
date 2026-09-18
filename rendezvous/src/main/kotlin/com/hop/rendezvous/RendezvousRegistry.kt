package com.hop.rendezvous

import com.hop.dht.Contact
import com.hop.dht.NodeId

/**
 * Bounded, TTL-expiring in-memory registry of observed peer addresses --
 * [RendezvousNode]'s entire "who do I know about" state.
 *
 * Deliberately NOT `dht/`'s [com.hop.dht.RoutingTable]/[com.hop.dht.KBucket]:
 * this is a single flat map, not XOR-distance-bucketed, and has no notion of
 * "closest to a target." See [RendezvousNode]'s class doc for why keeping
 * this registry structurally simpler than a real Kademlia routing table is
 * deliberate, not a shortcut -- it's part of what keeps this module visibly,
 * obviously incapable of the content-routing precision `dht/`'s
 * [com.hop.dht.RoutingTable] provides.
 *
 * Bounded by two independent mechanisms, mirroring `dht/`'s
 * [com.hop.dht.DhtStore]'s established "read-time expiry + bounded eviction,
 * no background sweep" conventions for stylistic consistency -- even though
 * this is a fresh, independent structure that shares no code with it:
 * - **Read-time TTL expiry**: an entry older than [entryTtlMs] is excluded
 *   from [liveContacts] and deleted as a side effect of that same read.
 * - **Capacity cap** ([capacity]): oldest-observed-or-refreshed-first evicted
 *   once the registry holds more than [capacity] distinct peers, mirroring
 *   [com.hop.dht.KBucket]'s own LRU-eviction-on-overflow precedent.
 *
 * Every method is `@Synchronized`: [RendezvousNode] wires [observe] directly
 * to [com.hop.dht.DhtUdpTransport.onMessageObserved], which fires from that
 * transport's single receive thread, while [liveContacts] can be called
 * concurrently from whatever thread answers an inbound FIND_NODE_REQUEST --
 * matching [com.hop.dht.DhtStore]'s own synchronization posture.
 */
class RendezvousRegistry(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val entryTtlMs: Long = DEFAULT_ENTRY_TTL_MS,
    /** Injectable for TTL testability, same posture as [com.hop.dht.DhtStore]'s injectable `nowMs`. */
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private class Entry(val contact: Contact, val expiresAtMs: Long)

    // Single flat map, ordered oldest-observed-or-refreshed (LRU, first) ->
    // most-recent (last) -- deliberately NOT nested per-key like DhtStore:
    // there's no "key" here, only "peers this node has heard from."
    private val entries = LinkedHashMap<NodeId, Entry>()

    /**
     * Records or refreshes [contact] as most-recently-observed. If this
     * pushes the registry over [capacity], evicts the oldest entry (by
     * observation/refresh order, not last-seen wall-clock time) until back
     * at or under it.
     */
    @Synchronized
    fun observe(contact: Contact) {
        entries.remove(contact.id)
        entries[contact.id] = Entry(contact = contact, expiresAtMs = nowMs() + entryTtlMs)
        while (entries.size > capacity) {
            val oldestId = entries.keys.first()
            entries.remove(oldestId)
        }
    }

    /**
     * Every non-expired observed contact, oldest-observed-or-refreshed first.
     * Expiry is enforced here, not by a background sweep: reading past
     * expiry deletes the entry as a side effect of this same call -- same
     * posture as [com.hop.dht.DhtStore.get].
     */
    @Synchronized
    fun liveContacts(): List<Contact> {
        val now = nowMs()
        val alive = mutableListOf<Contact>()
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next().value
            if (entry.expiresAtMs <= now) {
                iterator.remove()
            } else {
                alive.add(entry.contact)
            }
        }
        return alive
    }

    /**
     * The live (non-expired) contact for [id], or `null` if this registry
     * has never observed it or its entry has expired -- same read-time
     * expiry semantics as [liveContacts]: an entry found to be past
     * [entryTtlMs] is pruned as a side effect of THIS call, not left for a
     * later sweep. Backs Phase 4's rendezvous-relayed-introduction primitive
     * (see `DhtUdpTransport.onIntroduceRequested`, wired to this method in
     * [RendezvousNode]'s own `init` block) -- unlike [liveContacts]
     * (a bounded random subset for cold-start peer exchange, ADR 0002),
     * this answers "do I know this exact id," the one lookup shape
     * [liveContacts] alone can't answer without a linear scan at every call
     * site.
     */
    @Synchronized
    fun lookup(id: NodeId): Contact? {
        val entry = entries[id] ?: return null
        if (entry.expiresAtMs <= nowMs()) {
            entries.remove(id)
            return null
        }
        return entry.contact
    }

    /** Raw entry count, including entries not yet checked for expiry -- exposed for tests only. */
    val size: Int
        get() = entries.size

    companion object {
        /**
         * Unmeasured placeholder, deliberately smaller than
         * [com.hop.dht.DhtStore.DEFAULT_MAX_KEYS] (4096): a rendezvous node
         * only needs enough entries to hand a cold-starting peer a bounded
         * random subset of currently-reachable addresses (ADR 0002), not to
         * maintain full network topology the way a real DHT routing table
         * does.
         */
        const val DEFAULT_CAPACITY = 1024

        /**
         * 30min -- same directionally-short, unmeasured-placeholder posture
         * as [com.hop.dht.DhtStore.DEFAULT_ENTRY_TTL_MS]. An entry here only
         * means "this peer was reachable recently enough to hand its address
         * to a newcomer," not persistent identity -- stale entries should
         * fall out quickly.
         */
        const val DEFAULT_ENTRY_TTL_MS = 30 * 60 * 1000L
    }
}
