package com.hop.dht

/**
 * Bounded in-memory key -> announcer-set storage backing STORE/FIND_VALUE:
 * "who has announced holding NodeId-space key K" (BitTorrent Mainline DHT's
 * announce/get-peers shape -- BEP 5), never a generic arbitrary-value
 * key-value store. This class never holds content or a decryption key --
 * only [NodeId]s mapped to the [Contact]s that announced holding them. See
 * `FindValueRequestMessage`'s own doc for the product-doc grounding (memo.md/
 * PRD §6) of this exact model.
 *
 * Bounded by three independent mechanisms, none of them a background sweep:
 * - **Read-time TTL expiry**: an entry older than [entryTtlMs] is excluded
 *   from [get] and deleted as a side effect of that same read -- same
 *   posture as `DecayKeyStore.retrieve`/`RelayRepository` (no scheduled
 *   cleanup thread). [DEFAULT_ENTRY_TTL_MS] is a directionally-shorter,
 *   unmeasured placeholder vs. classic Kademlia's ~24h, since HOP content is
 *   inherently short-lived by product design (ADR 0003), not "persist until
 *   explicitly removed."
 * - **Per-key announcer cap** ([maxAnnouncersPerKey]): oldest-evicted-first
 *   once a key's announcer set is full, mirroring [KBucket]'s own
 *   LRU-eviction-on-overflow precedent -- except a self-registered entry
 *   (see [registerSelf]) is NEVER a candidate for this eviction, so a Sybil
 *   STORE-flood of a key this device has itself announced can never evict
 *   its own self-announcement.
 * - **Global distinct-key cap** ([maxKeys]): a brand-new key arriving via
 *   [recordAnnouncer] once the store already holds [maxKeys] distinct keys
 *   is silently rejected (a no-op) rather than evicting some existing key's
 *   announcer set wholesale -- this slice has no data to decide a sane
 *   cross-key eviction priority, so it declines to guess. [registerSelf] is
 *   exempt from this cap too: unlike [recordAnnouncer] (driven entirely by
 *   inbound, attacker-influenceable network input), [registerSelf] is driven
 *   entirely by this device's own [DhtNode.store] calls -- bounded local
 *   cost, no network amplification.
 *
 * **Limit (state plainly):** [recordAnnouncer] accepts a STORE announcement
 * with zero proof the announcer actually holds the content behind its key --
 * a hostile/Sybil peer can announce itself as a holder of content it doesn't
 * have, at zero cost, wasting a legitimate querier's later fetch attempt.
 * See `StoreRequestMessage`'s own doc for the same limit stated at the
 * wire-message level. A real mitigation (proof-of-possession challenge,
 * attestation-gated rate limiting per ADR 0004's faucet-cap pattern) is
 * future work, not built this slice.
 */
class DhtStore(
    private val entryTtlMs: Long = DEFAULT_ENTRY_TTL_MS,
    private val maxAnnouncersPerKey: Int = DEFAULT_MAX_ANNOUNCERS_PER_KEY,
    private val maxKeys: Int = DEFAULT_MAX_KEYS,
    /** Injectable for TTL testability, same posture as [DhtUdpTransport]'s injectable `requestTimeoutMs`. */
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private class Entry(val contact: Contact, val expiresAtMs: Long, val isSelf: Boolean)

    // Outer map keyed by content/topic key; inner map keyed by announcer NodeId,
    // ordered oldest-inserted-or-refreshed (LRU, first) -> most-recent (last) --
    // same ordering convention KBucket.liveContacts already established.
    private val entriesByKey = LinkedHashMap<NodeId, LinkedHashMap<NodeId, Entry>>()

    /**
     * Unconditional self-registration -- see the self-storage gap this fixes
     * (a device running `findNode(key)` and STORE-ing to the externally-
     * discovered closest peers can never learn, via its own lookup, that it
     * might itself be one of the true closest nodes, since [RoutingTable]/
     * [IterativeLookup] both deliberately exclude a device's own id from
     * every result). Bypasses both [maxAnnouncersPerKey] eviction pressure
     * and the [maxKeys] global cap -- see this class's own doc for why that's
     * safe here specifically (locally-driven, not attacker-driven).
     */
    @Synchronized
    fun registerSelf(key: NodeId, self: Contact) {
        val announcers = entriesByKey.getOrPut(key) { LinkedHashMap() }
        announcers.remove(self.id)
        announcers[self.id] = Entry(contact = self, expiresAtMs = nowMs() + entryTtlMs, isSelf = true)
        evictOverflow(announcers)
    }

    /**
     * From an inbound STORE request: adds [announcer] to [key]'s announcer
     * set, oldest-evicted-first if over [maxAnnouncersPerKey] (self entries
     * exempt -- see [registerSelf]); rejects a brand-new key if the store is
     * already at [maxKeys] distinct keys (silent no-op, not an exception --
     * matches [DhtUdpTransport]'s "never throw on hostile/malformed input"
     * posture).
     */
    @Synchronized
    fun recordAnnouncer(key: NodeId, announcer: Contact) {
        val existing = entriesByKey[key]
        if (existing == null) {
            if (entriesByKey.size >= maxKeys) return // global cap: reject a brand-new key
            val created = LinkedHashMap<NodeId, Entry>()
            created[announcer.id] = Entry(contact = announcer, expiresAtMs = nowMs() + entryTtlMs, isSelf = false)
            entriesByKey[key] = created
            return
        }
        existing.remove(announcer.id)
        existing[announcer.id] = Entry(contact = announcer, expiresAtMs = nowMs() + entryTtlMs, isSelf = false)
        evictOverflow(existing)
    }

    /**
     * Returns every non-expired announcer of [key]. Expiry is enforced here,
     * not by a background sweep: reading past expiry deletes the entry as a
     * side effect (this deletion *is* the decay/bounded-storage enforcement
     * mechanism, not a courtesy check on top of some other cleanup process --
     * same posture as `DecayKeyStore.retrieve`).
     */
    @Synchronized
    fun get(key: NodeId): List<Contact> {
        val announcers = entriesByKey[key] ?: return emptyList()
        val now = nowMs()
        val alive = mutableListOf<Contact>()
        val iterator = announcers.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next().value
            if (entry.expiresAtMs <= now) {
                iterator.remove()
            } else {
                alive.add(entry.contact)
            }
        }
        if (announcers.isEmpty()) entriesByKey.remove(key)
        return alive
    }

    /** Evicts oldest-first, skipping self-registered entries, until [announcers] is back at or under [maxAnnouncersPerKey] (or every remaining entry is self-registered). */
    private fun evictOverflow(announcers: LinkedHashMap<NodeId, Entry>) {
        val iterator = announcers.entries.iterator()
        while (announcers.size > maxAnnouncersPerKey && iterator.hasNext()) {
            val entry = iterator.next().value
            if (!entry.isSelf) iterator.remove()
        }
    }

    companion object {
        /** 30min -- unmeasured placeholder, directionally shorter than classic Kademlia's ~24h since HOP content is inherently short-lived by product design (ADR 0003), not "persist until removed." */
        const val DEFAULT_ENTRY_TTL_MS = 30 * 60 * 1000L
        const val DEFAULT_MAX_ANNOUNCERS_PER_KEY = 8
        const val DEFAULT_MAX_KEYS = 4096
    }
}
