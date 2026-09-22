package com.hop.dht

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * The iterative Kademlia lookup algorithm, algorithm-pure: no
 * [RoutingTable]/[DhtUdpTransport] dependency, everything network-shaped goes
 * through the injected [queryFn]. Matches this module's established
 * granularity -- every existing type here is small and single-purpose.
 *
 * [ownId] exists purely to keep this device's own id out of the shortlist/
 * query set (a contact bearing our own id can turn up in a peer's response,
 * e.g. if that peer's routing table happens to include us -- never a
 * meaningful thing for this device to "query").
 */
class IterativeLookup(
    private val ownId: NodeId,
    private val alpha: Int = DEFAULT_ALPHA,
    private val k: Int = KBucket.DEFAULT_K,
    private val maxRounds: Int = DEFAULT_MAX_ROUNDS,
    private val queryFn: suspend (Contact, NodeId) -> List<Contact>?,
) {
    /**
     * Canonical Kademlia termination: repeatedly query up to [alpha] of the
     * closest *unqueried* candidates in the current top-[k] shortlist
     * (sorted by distance to [targetId]); terminate once every candidate in
     * the current shortlist has been queried (self-terminating -- the
     * shortlist naturally stops changing once nothing closer is being
     * discovered, no fuzzy "did this round improve" comparison needed).
     * [maxRounds] is a defensive backstop against a genuinely adversarial
     * [queryFn] that keeps injecting new-but-never-closer decoy contacts,
     * which the canonical rule alone doesn't fully rule out. A [queryFn]
     * timeout/failure (`null`) marks that candidate queried and contributes
     * nothing, without aborting the lookup.
     *
     * Empty [seeds] returns immediately without ever invoking [queryFn].
     */
    suspend fun lookup(targetId: NodeId, seeds: List<Contact>): List<Contact> {
        if (seeds.isEmpty()) return emptyList()

        val queried = mutableSetOf<NodeId>()
        // Keyed by NodeId to dedupe repeated sightings of the same contact
        // across multiple responses; re-sorted by distance on every read.
        val shortlist = LinkedHashMap<NodeId, Contact>()
        seeds.forEach { if (it.id != ownId) shortlist[it.id] = it }

        var round = 0
        while (round < maxRounds) {
            val currentTopK = shortlist.values.sortedBy { it.id.distanceTo(targetId) }.take(k)
            val toQuery = currentTopK.filter { it.id !in queried }.take(alpha)

            // Canonical termination: nothing left in the current top-k shortlist
            // that hasn't already been queried.
            if (toQuery.isEmpty()) break

            // Mark queried up front (not after a successful response) so a
            // queryFn timeout/failure still counts this candidate as attempted
            // and it is never re-queried in a later round.
            toQuery.forEach { queried.add(it.id) }

            val results = coroutineScope {
                toQuery.map { contact -> async { queryFn(contact, targetId) } }.awaitAll()
            }

            for (discoveredList in results) {
                discoveredList?.forEach { discovered ->
                    if (discovered.id != ownId) {
                        shortlist[discovered.id] = discovered
                    }
                }
            }

            round++
        }

        return shortlist.values.sortedBy { it.id.distanceTo(targetId) }.take(k)
    }

    companion object {
        /** Classic Kademlia value, unmeasured for HOP, same posture as [KBucket.DEFAULT_K]. */
        const val DEFAULT_ALPHA = 3

        /** Unmeasured placeholder. */
        const val DEFAULT_MAX_ROUNDS = 20
    }
}
