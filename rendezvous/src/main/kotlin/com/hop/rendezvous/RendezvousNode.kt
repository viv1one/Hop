package com.hop.rendezvous

import com.hop.dht.DhtUdpTransport
import com.hop.dht.NodeId
import java.net.DatagramSocket

/**
 * ADR 0002's bootstrap/rendezvous node, made real
 * (`docs/adr/0002-bootstrap-node-carveout.md`).
 *
 * `dht/`'s [com.hop.dht.DhtNode.bootstrapJoin] treats "a bootstrap node" as
 * just an ordinary DHT peer that happens to be well-known: it PINGs a
 * well-known address via the ordinary Kademlia PING/PONG RPC and, once it
 * gets a real PONG, runs a normal `findNode` against the network. That's
 * exactly the problem ADR 0002 identifies -- a full [com.hop.dht.DhtNode]
 * *also* answers STORE_REQUEST/FIND_VALUE_REQUEST via a wired
 * [com.hop.dht.DhtStore], i.e. it can answer "who has this clip/topic." If
 * HOP (or a third party) ran a full [com.hop.dht.DhtNode] as the well-known
 * bootstrap address, that node would be capable of exactly what ADR 0002
 * says a bootstrap node must NEVER be able to do.
 *
 * This class is the right-shaped alternative: it constructs its own
 * [DhtUdpTransport] -- reused byte-for-byte from `dht/`, never forked or
 * reimplemented, so it speaks the identical PING/FIND_NODE wire format any
 * other peer (including a real [com.hop.dht.DhtNode]) already speaks -- and
 * wires ONLY [DhtUdpTransport.onMessageObserved] and
 * [DhtUdpTransport.onFindNodeRequested].
 *
 * **[DhtUdpTransport.onStoreRequested] and
 * [DhtUdpTransport.onFindValueRequested] are DELIBERATELY LEFT UNWIRED.**
 * Both already default to a safe, structurally-incapable-of-revealing-
 * content behavior when left unset:
 * - [DhtUdpTransport.onStoreRequested]'s default (`{ _, _ -> }`) silently
 *   accepts and discards an inbound STORE_REQUEST -- never persists it
 *   anywhere, nothing is left to later retrieve.
 * - [DhtUdpTransport.onFindValueRequested]'s default
 *   (`{ _, _ -> FindValueOutcome.CloserNodes(emptyList()) }`) always answers
 *   `CloserNodes` -- `FindValueOutcome.Holders` is a structurally different
 *   case that default can never construct, regardless of what key is asked
 *   about.
 *
 * This means the ADR 0002 guarantee ("a bootstrap node cannot answer a
 * content or topic query") holds simply because this class never gives
 * [DhtUdpTransport] anything else to call for those two RPCs -- not because
 * of a runtime check here that inspects and rejects/filters a content or
 * topic answer. There is no such check, and there must never be one that
 * takes its place.
 *
 * **DO NOT** "helpfully" wire [DhtUdpTransport.onStoreRequested] or
 * [DhtUdpTransport.onFindValueRequested] in this class, and this class
 * must NEVER import `com.hop.dht.DhtStore` or reference
 * `com.hop.dht.FindValueOutcome.Holders` anywhere. Doing either is the
 * literal ADR 0002 violation this module exists to prevent: "Any change
 * that would let a bootstrap node answer a content or topic query is a
 * design violation of this ADR, not a feature." There must be no code path
 * in this module even capable of constructing an answer that reveals
 * content or topic information -- enforced by this module simply never
 * having the capability to construct one, not by a runtime check that could
 * later be weakened or bypassed.
 *
 * The peer registry backing [onFindNodeRequested]'s answers is a
 * deliberately simpler, flat [RendezvousRegistry] -- NOT `dht/`'s
 * [com.hop.dht.RoutingTable]/[com.hop.dht.KBucket]. A real Kademlia routing
 * table is precision content-routing infrastructure (XOR-distance buckets
 * keyed to a target id); reusing it here would make this class capable of
 * the same closest-to-target routing precision `dht/` uses to answer
 * FIND_VALUE -- exactly the capability ADR 0002 says this module must never
 * have. Accordingly, the requested `targetId` is deliberately ignored below:
 * this answers "here are some peers," never "here are the peers closest to
 * what you're looking for."
 *
 * **Limit, stated plainly (same posture as everywhere else in this codebase
 * that makes a Sybil/abuse-resistance claim):** this only binds a stock
 * client built on this exact wiring. A rendezvous node run on modified code
 * that wires those two callbacks anyway would violate ADR 0002 -- nothing
 * about the wire protocol prevents a dishonest bootstrap operator from doing
 * that. What this class provides is "the reference implementation cannot do
 * this," not "no implementation could ever do this."
 */
class RendezvousNode(
    socket: DatagramSocket,
    ownId: NodeId,
    private val registry: RendezvousRegistry = RendezvousRegistry(),
    private val responseCap: Int = DEFAULT_RESPONSE_CAP,
) {
    private val transport = DhtUdpTransport(socket, ownId)

    init {
        transport.onMessageObserved = registry::observe
        transport.onFindNodeRequested = { _, excludeId ->
            registry.liveContacts()
                .filter { it.id != excludeId }
                .shuffled()
                .take(responseCap)
        }
        // transport.onStoreRequested and transport.onFindValueRequested are
        // DELIBERATELY left at DhtUdpTransport's own safe no-op defaults --
        // see this class's doc above. Do not wire them here.
    }

    /** Delegates to [DhtUdpTransport.start]. Safe to call once; a second call is a no-op. */
    fun start() = transport.start()

    /** Delegates to [DhtUdpTransport.stop]. */
    fun stop() = transport.stop()

    companion object {
        /**
         * Bounds how many contacts a single FIND_NODE_RESPONSE hands out.
         * Matches classic Kademlia's k (see `KBucket.DEFAULT_K`) in value
         * only -- replicated here as a plain literal rather than importing
         * `KBucket`, to keep this module's independence from `dht/`'s
         * routing-table types visible in the code, not just in prose.
         * Unmeasured placeholder, same posture as `KBucket.DEFAULT_K` /
         * `DhtUdpTransport.DEFAULT_REQUEST_TIMEOUT_MS`. Comfortably under
         * `FindNodeResponseMessage.MAX_CONTACTS` (24), so a response at this
         * cap never risks that wire-format ceiling.
         */
        const val DEFAULT_RESPONSE_CAP = 20
    }
}
