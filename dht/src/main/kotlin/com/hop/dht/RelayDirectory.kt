package com.hop.dht

/**
 * Bounded, TTL-expiring in-memory registry of announced volunteer relay
 * nodes (`tools/relay-node/`'s `RelayNode`) -- backs
 * [DhtUdpTransport.onRelayAnnounceRequested]/[DhtUdpTransport.onRelayQueryRequested]
 * for both [DhtNode] (this module) and `rendezvous/`'s `RendezvousNode`: both
 * sit on the same [DhtUdpTransport], so both can equally well answer "what
 * relay nodes do you know about" -- see [RelayAnnounceRequestMessage]'s own
 * file doc for the wire mechanism this backs.
 *
 * **Why this lives in `dht/`, not `rendezvous/`, even though its shape
 * mirrors `rendezvous/`'s own `RendezvousRegistry` closely:** `rendezvous/`
 * already depends on `dht/` (for [DhtUdpTransport]/[Contact]/[NodeId]/
 * [PeerAddress]), and that dependency is strictly one-way -- `dht/` must
 * never depend back on `rendezvous/`, the same layering discipline
 * `protocol/`/`crypto/` already enforce for their own one-way relationship.
 * [DhtNode] (this module) needs a [RelayDirectory] instance just as much as
 * `RendezvousNode` does, so a type only `rendezvous/` could see would make
 * that impossible without inverting the dependency into an actual Gradle
 * project cycle (`dht` -> `rendezvous` -> `dht`), which this build would
 * reject outright. Living here instead lets both consumers use the exact
 * same class with zero new inter-module dependency in either direction.
 *
 * This placement does NOT weaken ADR 0002's carve-out
 * (`docs/adr/0002-bootstrap-node-carveout.md`) for `RendezvousNode`: an entry
 * here is exactly "a peer's id + address" -- the same address-only shape
 * `RendezvousRegistry` already stores for ordinary FIND_NODE peer exchange,
 * just for a peer role (relay) instead of an arbitrary DHT contact. This
 * class has no method that can construct or answer a content-hash or
 * topic-key query, so `RendezvousNode` wiring [DhtUdpTransport.onRelayAnnounceRequested]/
 * [DhtUdpTransport.onRelayQueryRequested] against an instance of this class
 * stays structurally incapable of an ADR 0002 violation, the same
 * "incapable by construction, not by a runtime check" posture that class's
 * own doc insists on for [DhtUdpTransport.onStoreRequested]/
 * [DhtUdpTransport.onFindValueRequested] staying unwired there.
 *
 * Mirrors `RendezvousRegistry`'s exact shape (bounded capacity, read-time TTL
 * expiry, no background sweep) as a fresh, independent structure -- same
 * stylistic-consistency-without-shared-code posture `RendezvousRegistry`
 * itself already took relative to this module's own [DhtStore].
 *
 * **Trust limitation, stated plainly (same posture as [RelayAnnounceRequestMessage]'s
 * own doc):** an entry recorded here is exactly as trustworthy as whichever
 * relay announced it -- this class has no way to independently verify a
 * relay's claimed address is real, reachable, or actually running a real
 * `RelayNode`. Real abuse-resistance for who can announce as a relay (anyone
 * can announce a bogus address today, at zero cost) is explicitly NOT solved
 * here -- future work, not a regression introduced by this class.
 */
class RelayDirectory(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val entryTtlMs: Long = DEFAULT_ENTRY_TTL_MS,
    /** Injectable for TTL testability, same posture as [DhtStore]'s injectable `nowMs`. */
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private class Entry(val contact: Contact, val expiresAtMs: Long)

    // Single flat map, ordered oldest-observed-or-refreshed (LRU, first) ->
    // most-recent (last) -- same shape as RendezvousRegistry's own `entries`.
    private val entries = LinkedHashMap<NodeId, Entry>()

    /**
     * Records or refreshes [relayId]/[relayAddress] as most-recently-announced.
     * If this pushes the registry over [capacity], evicts the oldest entry
     * (by announcement/refresh order, not last-seen wall-clock time) until
     * back at or under it. [relayAddress] is stored exactly as given --
     * genuinely self-reported by the relay (see [RelayAnnounceRequestMessage]'s
     * own doc), never independently verified here.
     */
    @Synchronized
    fun announce(relayId: NodeId, relayAddress: PeerAddress) {
        val contact = Contact(id = relayId, address = relayAddress.encode(), lastSeenAtMs = nowMs())
        entries.remove(relayId)
        entries[relayId] = Entry(contact = contact, expiresAtMs = nowMs() + entryTtlMs)
        while (entries.size > capacity) {
            val oldestId = entries.keys.first()
            entries.remove(oldestId)
        }
    }

    /**
     * Every non-expired announced relay, oldest-announced-or-refreshed first.
     * Expiry is enforced here, not by a background sweep: reading past
     * expiry deletes the entry as a side effect of this same call -- same
     * posture as `rendezvous/`'s `RendezvousRegistry.liveContacts`/
     * [DhtStore.get].
     */
    @Synchronized
    fun liveRelays(): List<Contact> {
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

    /** Raw entry count, including entries not yet checked for expiry -- exposed for tests only. */
    val size: Int
        get() = entries.size

    companion object {
        /**
         * Unmeasured placeholder, same posture as `RendezvousRegistry.DEFAULT_CAPACITY`
         * -- a relay directory only needs enough entries to hand a querying
         * client a bounded random subset of currently-announced relays, not
         * to maintain full network topology.
         */
        const val DEFAULT_CAPACITY = 256

        /**
         * 30min -- same directionally-short, unmeasured-placeholder posture
         * as `RendezvousRegistry.DEFAULT_ENTRY_TTL_MS`/[DhtStore.DEFAULT_ENTRY_TTL_MS].
         * An entry here only means "this relay announced itself reachable
         * recently enough to hand its address to a querier," not persistent
         * identity -- stale entries should fall out quickly, especially
         * given this class's stated trust limitation.
         */
        const val DEFAULT_ENTRY_TTL_MS = 30 * 60 * 1000L

        /**
         * Bounds how many relay entries a single RELAY_QUERY_RESPONSE hands
         * out -- same value and reasoning as
         * `RendezvousNode.DEFAULT_RESPONSE_CAP`: comfortably under
         * [RelayQueryResponseMessage.MAX_RELAYS] (24), so a response at this
         * cap never risks that wire-format ceiling. Applied by each caller
         * ([DhtNode]/`RendezvousNode`) at the [liveRelays] call site (e.g.
         * `.shuffled().take(DEFAULT_RESPONSE_CAP)`), not inside this class --
         * same "capping is the wiring layer's job" convention
         * `RendezvousNode.onFindNodeRequested` already established for
         * `RendezvousRegistry.liveContacts()`.
         */
        const val DEFAULT_RESPONSE_CAP = 20
    }
}
