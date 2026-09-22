package com.hop.relaynode

import com.hop.dht.Contact
import com.hop.dht.DhtUdpTransport
import com.hop.dht.PeerAddress
import kotlinx.coroutines.runBlocking

/**
 * Optional companion to [RelayNode]: lets a running relay node announce its
 * own real TCP bridge address to a rendezvous/DHT contact, so a client can
 * later discover it via [DhtUdpTransport.queryRelays] -- the discovery half
 * of BUILD_PLAN.md's Phase 4 volunteer relay-node fallback (see `dht/`'s
 * `RelayAnnounceRequestMessage.kt`'s own file doc for the wire mechanism and
 * why it can't reuse a STORE_REQUEST-based approach).
 *
 * Deliberately a small, separate class, not folded into [RelayNode]'s own
 * accept/bridging loop -- [RelayNode]'s core bridging logic stays untouched
 * by this slice (this file is the entirety of the change; `RelayNode.kt`
 * itself has zero knowledge this class exists). Adding a [DhtUdpTransport]
 * dependency here for pure address-announcement purposes does not violate
 * this module's "zero dependency on `protocol/`" discipline (see
 * [RelayNode]'s own class doc) -- [DhtUdpTransport]/[com.hop.dht.NodeId]/
 * [PeerAddress] are routing/addressing primitives, not content types, and
 * this module already depends on `dht/` for [com.hop.dht.NodeId]. This class
 * never touches, and has no way to reach, anything content-shaped.
 *
 * **Caller owns [transport]'s lifecycle** ([DhtUdpTransport.start]/`.stop`)
 * -- this class only ever calls [DhtUdpTransport.announceRelay] on it, never
 * starts or stops it. That keeps this class a thin, easily-testable wrapper
 * rather than a second place a socket lifecycle needs managing.
 *
 * **Announces once per [announceOnce] call, not on an internal repeating
 * timer -- deliberate, not an oversight.** A relay node's own reachable TCP
 * address doesn't change while it's running, so there's no strong reason for
 * this class to own scheduling. A longer-lived production deployment that
 * wants to re-announce periodically (e.g. to refresh a `RelayDirectory`
 * entry before its TTL expires, or to announce to more than one bootstrap
 * contact) can call [announceOnce] again itself, on whatever cadence it
 * chooses -- background scheduling is left to the caller, not built into
 * this class this slice. If a real deployment later needs this built in,
 * that's a small, separate follow-up, not a reason to over-build this one.
 *
 * [bootstrapContact] (the rendezvous/DHT node to announce to) and
 * [ownBridgeAddress] (this relay's own reachable TCP bridge address -- i.e.
 * the address a client would actually connect [RelayNode]'s `serverSocket`
 * at) are both caller-supplied at construction, never hardcoded.
 */
class RelayAnnouncer(
    private val transport: DhtUdpTransport,
    private val bootstrapContact: Contact,
    private val ownBridgeAddress: PeerAddress,
) {
    /**
     * Sends one RELAY_ANNOUNCE to [bootstrapContact] and blocks (via
     * [runBlocking] -- this module has no [kotlinx.coroutines.CoroutineScope]
     * threaded through it anywhere else, matching [RelayNode]'s own
     * plain-thread posture, so a bounded blocking call here rather than a
     * suspend function is the better fit) until it's acked or times out.
     * Returns whether the announcement was acked -- never throws for a
     * timeout or an unreachable bootstrap contact, matching
     * [DhtUdpTransport.announceRelay]'s own never-throw posture.
     */
    fun announceOnce(): Boolean = runBlocking {
        transport.announceRelay(bootstrapContact, ownBridgeAddress)
    }
}
