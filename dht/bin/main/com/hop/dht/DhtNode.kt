package com.hop.dht

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

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
 *
 * Slice 5 adds [store] and [findValue] -- the announce/get-peers primitive
 * [DhtStore] backs. [findValue]'s early termination on "found" is achieved by
 * racing [IterativeLookup] from *outside* it (a [CompletableDeferred]
 * completed by the injected `queryFn`, then explicitly cancelling the
 * lookup's own coroutine) rather than by changing [IterativeLookup] itself --
 * see that class's own doc and [findValue]'s doc below for why. This slice
 * also introduces [ownAddresses]: nothing before it needed to describe this
 * device's own reachable address, since [observe] always derives a contact's
 * address from the *observed* UDP source, never self-reported -- [store]'s
 * unconditional self-registration (see [DhtStore.registerSelf]) is the first
 * thing that needs to build a [Contact] bearing this device's own id.
 *
 * Phase 4's IPv6-first/dual-stack slice widens this from a single [PeerAddress]
 * to a list: a dual-stack device reachable at both an IPv6 and an IPv4 address
 * now has a real way to announce both, via [PeerAddress.encodeList] --
 * [PeerAddress.decodeList] recovers them on the receiving side. See [Contact]'s
 * own class doc for why this needed zero changes to any wire-framing class.
 *
 * Phase 4's final hole-punching slice closes the last gap left open by the
 * rendezvous-relayed-introduction slice (see `onIntroductionReceived`'s own
 * doc below): this class previously left `transport.onIntroductionReceived`
 * entirely unset, meaning a received INTRODUCTION reached nobody. Now
 * forwarded straight through from a new constructor parameter, same shape as
 * every other transport callback wired in `init` -- this class still decides
 * nothing about *what* to do with an introduction, only makes sure the
 * caller-supplied decision actually reaches [transport].
 */
class DhtNode(
    val routingTable: RoutingTable,
    private val transport: DhtUdpTransport,
    private val scope: CoroutineScope,
    /**
     * This device's own reachable address(es), used only to build the
     * self-holder [Contact] for [store]'s unconditional self-registration.
     * One entry for a single-stack device, two for a dual-stack device
     * announcing both an IPv6 and an IPv4 address. Explicitly NOT a
     * NAT/external-address-discovery solution -- just wiring the field
     * through so [store] has something to construct a self [Contact] from.
     * Real internet-mode NAT traversal is later, unbuilt work; until then
     * these are whatever address(es) this device is actually reachable at on
     * its local network (loopback + bound port in every test here).
     */
    private val ownAddresses: List<PeerAddress>,
    private val store: DhtStore = DhtStore(),
    /**
     * Fired when [transport] receives an unsolicited INTRODUCTION naming
     * another peer and its self-reported [PeerAddress] -- the rendezvous-
     * relayed NAT-hole-punching primitive from [IntroductionMessage.kt]'s own
     * file doc. Defaults to a no-op, same as [DhtUdpTransport]'s own default,
     * for every construction site that has no use for it (e.g. a bare
     * `rendezvous/` node -- see `com.hop.rendezvous.RendezvousNode`'s own
     * doc for why it's correct for that class to never act on this). The one
     * real consumer today is `com.hop.app.dht.DhtNodeManager`, which forwards
     * this into `com.hop.transport.InternetPeerConnectionManager` to actually
     * attempt a connection to the introduced peer.
     *
     * [relayId]/[relayAddress] are Phase 4's relay-fallback-coordination
     * addition (see [IntroduceResponseMessage]'s own doc): the same relay
     * suggestion R also handed to A in the paired INTRODUCE_RESPONSE, both
     * `null` together if R had none to suggest.
     */
    onIntroductionReceived: (fromId: NodeId, claimedAddress: PeerAddress, relayId: NodeId?, relayAddress: PeerAddress?) -> Unit =
        { _, _, _, _ -> },
    /**
     * Backs [transport]'s [DhtUdpTransport.onRelayAnnounceRequested]/
     * [DhtUdpTransport.onRelayQueryRequested] -- Phase 4's volunteer-relay-
     * discovery primitive (see [RelayAnnounceRequestMessage]'s own file doc).
     * A full [DhtNode] can just as well answer "what relay nodes do you know
     * about" as `rendezvous/`'s `RendezvousNode` can -- both sit on the same
     * [DhtUdpTransport] -- so this is wired unconditionally here, not left
     * rendezvous-only. See [RelayDirectory]'s own class doc for why it lives
     * in this module rather than `rendezvous/` despite mirroring that
     * module's `RendezvousRegistry` shape.
     */
    private val relayDirectory: RelayDirectory = RelayDirectory(),
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
        transport.onStoreRequested = { key, announcer -> store.recordAnnouncer(key, announcer) }
        transport.onFindValueRequested = { key, excludeId ->
            val holders = store.get(key)
            if (holders.isNotEmpty()) {
                FindValueOutcome.Holders(holders)
            } else {
                FindValueOutcome.CloserNodes(
                    routingTable.findClosest(key, routingTable.k + 1).filter { it.id != excludeId }.take(routingTable.k)
                )
            }
        }
        // Phase 4's rendezvous-relayed-introduction primitive (see
        // IntroductionMessage.kt's own file doc): answers "do I know this
        // exact peer's address," using RoutingTable itself rather than a
        // dedicated lookup-by-id method. RoutingTable.findClosest(targetId, 1)
        // sorts every known contact by XOR distance to targetId and takes the
        // single closest one -- if targetId is genuinely a live entry, its
        // distance to itself is zero, the minimum possible, so it's always
        // selected at count = 1. The explicit `it.id == targetId` filter
        // guards the "not actually known" case, where findClosest(targetId, 1)
        // would otherwise just return whatever contact happens to be closest,
        // silently misreporting an unknown target as found.
        // Forwards this constructor's onIntroductionReceived (default no-op)
        // straight through to transport -- the last piece of the Phase 4
        // hole-punching thread: reacting to a received INTRODUCTION by
        // actually attempting a connection is the caller's job (see this
        // param's own doc above), not something DhtNode itself decides.
        transport.onIntroductionReceived = onIntroductionReceived
        transport.onIntroduceRequested = { targetId ->
            routingTable.findClosest(targetId, 1).firstOrNull { it.id == targetId }
        }
        // Phase 4's volunteer-relay-discovery primitive (see
        // RelayAnnounceRequestMessage.kt's own file doc): recording an
        // announcement and answering a query are both address-only, the
        // same shape onFindNodeRequested/onIntroduceRequested already
        // exercise -- relayDirectory is structurally incapable of answering
        // anything beyond "here are some relay id+address pairs."
        // .shuffled().take(...) here mirrors RendezvousNode.onFindNodeRequested's
        // own bounding convention -- RelayDirectory.liveRelays() itself
        // returns every live entry, uncapped.
        transport.onRelayAnnounceRequested = { relayId, relayAddress -> relayDirectory.announce(relayId, relayAddress) }
        transport.onRelayQueryRequested = { relayDirectory.liveRelays().shuffled().take(RelayDirectory.DEFAULT_RESPONSE_CAP) }
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

    /**
     * Announces this device as a holder of [key]. Two things ALWAYS happen,
     * independent of each other:
     *
     * 1. Unconditional self-registration into this device's own local
     *    [DhtStore], via [DhtStore.registerSelf] -- this is the fix for a
     *    real correctness gap, not an optimization: [RoutingTable]/
     *    [IterativeLookup] both deliberately exclude this device's own id
     *    from every result, so [findNode] run against [key] can never itself
     *    reveal that this device might be one of the true closest nodes to
     *    it. Without this unconditional local self-registration, a device
     *    that happens to be genuinely closest to [key] would have no way to
     *    ever learn that and would never answer its own (or a third party's)
     *    [findValue] for a key it's actually supposed to hold. Some devices
     *    will over-eagerly self-register for keys they aren't the true
     *    global closest for -- harmless: bounded local cost, no network
     *    amplification, since third parties only ever route toward what
     *    *their own* tables resolve as closest.
     * 2. [findNode] against [key] to locate the externally-known-closest
     *    peers, then a real [DhtUdpTransport.store] STORE_REQUEST to each.
     */
    suspend fun store(key: NodeId) {
        val self = Contact(id = routingTable.ownId, address = PeerAddress.encodeList(ownAddresses), lastSeenAtMs = System.currentTimeMillis())
        store.registerSelf(key, self)

        val closestKnown = findNode(key)
        closestKnown.forEach { contact -> transport.store(contact, key) }
    }

    /**
     * Finds holders of [key]: this device's own local [DhtStore] first
     * (already TTL-pruned via [DhtStore.get]); if empty, races
     * [IterativeLookup] from OUTSIDE it rather than modifying that class at
     * all (deliberate -- see this class's own doc, and the design note
     * below).
     *
     * **Why racing from outside, not a "terminal result" case inside
     * [IterativeLookup]:** [IterativeLookup] is reused byte-for-byte from
     * Slice 4, already tested via [IterativeLookupTest] and already correct
     * for [findNode]'s "keep going until nothing closer" termination rule --
     * generalizing its return type or forking a second copy of its
     * round/shortlist/queried-set loop just for "found" early-termination
     * would re-litigate the canonical-termination-vs-"nothing closer"
     * distinction Slice 4 already got right once, for no gain. Instead: a
     * [CompletableDeferred] is completed by the injected `queryFn` the
     * moment ANY query in ANY round comes back with
     * [FindValueOutcome.Holders], and the lookup's own coroutine
     * ([lookupJob], launched lazily so [select] below can safely reference
     * it from within its own `queryFn` closure without a
     * construction-order race) is explicitly cancelled at that point. Because
     * [IterativeLookup.lookup] queries each round's candidates as sibling
     * [kotlinx.coroutines.async] children inside its own `coroutineScope`,
     * cancelling [lookupJob] genuinely cancels every other still-in-flight
     * sibling query in that same round too -- real structured-concurrency
     * cancellation, not merely "stop caring about the result."
     */
    suspend fun findValue(key: NodeId): FindValueResult {
        val localHolders = store.get(key)
        if (localHolders.isNotEmpty()) return FindValueResult.Found(localHolders)

        val seeds = routingTable.findClosest(key, routingTable.k)
        if (seeds.isEmpty()) return FindValueResult.NotFound(emptyList())

        return coroutineScope {
            val foundDeferred = CompletableDeferred<List<Contact>>()

            val lookupJob: Deferred<List<Contact>> = async(start = CoroutineStart.LAZY) {
                val lookup = IterativeLookup(
                    ownId = routingTable.ownId,
                    k = routingTable.k,
                    queryFn = { contact, target ->
                        when (val outcome = transport.findValue(contact, target)) {
                            null -> null
                            is FindValueOutcome.Holders -> {
                                outcome.contacts.forEach { observe(it) }
                                foundDeferred.complete(outcome.contacts)
                                // Nothing to feed into the shortlist -- a
                                // "found" outcome means the lookup is about
                                // to be cancelled by the select{} below, not
                                // continued.
                                null
                            }
                            is FindValueOutcome.CloserNodes -> {
                                outcome.contacts.forEach { observe(it) }
                                outcome.contacts
                            }
                        }
                    },
                )
                lookup.lookup(key, seeds)
            }
            lookupJob.start()

            select<FindValueResult> {
                foundDeferred.onAwait { holders ->
                    lookupJob.cancel()
                    FindValueResult.Found(holders)
                }
                lookupJob.onAwait { closestKnown ->
                    FindValueResult.NotFound(closestKnown)
                }
            }
        }
    }
}

/**
 * Result of [DhtNode.findValue]: either known holders of the requested key
 * ([Found] -- the querier should stop searching and try these directly), or
 * the closest-known routing candidates if no holder was found anywhere in
 * the lookup ([NotFound]).
 */
sealed class FindValueResult {
    data class Found(val holders: List<Contact>) : FindValueResult()
    data class NotFound(val closestKnown: List<Contact>) : FindValueResult()
}
