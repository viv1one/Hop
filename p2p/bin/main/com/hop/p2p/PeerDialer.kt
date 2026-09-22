package com.hop.p2p

import com.hop.dht.PeerAddress
import java.io.IOException
import java.net.Socket

/**
 * Thrown by [PeerDialer.dial] when every candidate it tried failed (or
 * [PeerDialer.dial] was given an empty candidate list to begin with).
 */
class PeerDialException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Opens a direct TCP connection to one logical peer, given every
 * [PeerAddress] candidate known for that peer, using the "Happy Eyeballs"
 * IPv6-first pattern (RFC 8305): try an IPv6 candidate first, and only fall
 * back to an IPv4 candidate if the IPv6 attempt doesn't connect within a
 * short window.
 *
 * **Why this module exists, and why it needs BOTH `:dht` and `:protocol`.**
 * [dial] needs [PeerAddress] ([com.hop.dht.PeerAddress] — deliberately a
 * general "how do you encode an IP+port" type, not DHT-routing-specific, per
 * that class's own doc comment) to know *where* to connect, and this
 * module's other half, [PeerChannel], needs `com.hop.protocol.WireEnvelope`/
 * `com.hop.protocol.WirePayloadType` to know *what* is being exchanged once
 * connected. `:dht` and `:protocol` must never depend on each other in
 * either direction (`dht/`'s own `NodeId.kt` doc; `protocol/`'s
 * `ReachTierGeohash` doc) — `com.hop.topics.TopicSubscription`'s own class
 * doc states the identical problem shape for its own DHT-topic bridge
 * ("this class needs BOTH `dht/` and `protocol/`, and `dht/`/`protocol/`
 * must never depend on each other, so something has to own the bridge") and
 * is the precedent this module follows: a small standalone bridge module is
 * the right shape here too, not a mistake to avoid. This module cannot live
 * in `:dht` (which forbids depending on `:protocol`) or `:protocol`
 * (wire-format-only, no socket I/O, and must not gain a dependency on
 * `:dht`'s `PeerAddress`).
 *
 * **Known, deliberately out-of-scope gap this module inherits, does not
 * solve.** Today's DHT wire format only lets a `Contact` carry a single
 * `PeerAddress` (`Contact.address: ByteArray` decodes to exactly one
 * `PeerAddress`), so no real peer discovered via `:topics`/`:dht` can
 * currently announce both an IPv6 and an IPv4 address at once — there is,
 * today, no real caller able to hand [dial] a genuine two-family candidate
 * list. Extending `Contact`/the FIND_NODE wire format to carry multiple
 * addresses per peer is separate, real scope (a `:dht` wire-format change) —
 * deliberately not attempted here. [dial] is written so that eventual fix
 * needs no change on this side: it already accepts and races a
 * `List<PeerAddress>`, and this class's own tests exercise exactly that
 * two-candidate shape over loopback, using two independently-constructed
 * [PeerAddress] values rather than anything that had to come from a real
 * `Contact`.
 *
 * **What this class deliberately does NOT do — separate, later slices:**
 * - NAT hole-punching (peer-assisted signaling so two NATed peers can
 *   connect) — needs this direct-dial foundation to exist first.
 * - Volunteer relay-node fallback for symmetric NAT (`tools/relay-node/`) —
 *   a volunteer relay cannot be assumed honest (hop-dev's own "extra
 *   scrutiny" list for NAT traversal / relay-node trust logic), and
 *   deserves its own dedicated design pass, not a rushed add-on here.
 * - Picking *which* candidates to try, or discovering them in the first
 *   place — that's `:topics`'/`:dht`'s job upstream of this class; [dial]
 *   only ever races the candidates it's handed.
 * - Deciding what to do with the connected [Socket] once returned (framing
 *   dispatch, `EnvelopeDispatcher`/`TransportManager` wiring) — see
 *   [PeerChannel]'s own doc for that boundary.
 *
 * **Sequential, not concurrently-raced, by design.** RFC 8305's canonical
 * Happy Eyeballs starts a delayed IPv4 attempt *while the IPv6 attempt is
 * still outstanding*, taking whichever completes first. This implementation
 * is the simpler sequential variant this slice's own spec describes ("try
 * IPv6 first... if it doesn't connect within that window, fall back to
 * IPv4") — still tries IPv6 first, still bounds total wait time, still falls
 * back on IPv6 failure, but easier to reason about and test deterministically
 * over loopback without a second concurrent attempt racing the first. Noted
 * here as a deliberate simplification, not an oversight — a later slice
 * could switch to a true concurrent race if measured latency ever justifies
 * the added complexity.
 *
 * Only the *first* candidate of each family in the given list is tried —
 * this races *families* (IPv6 vs. IPv4), not every candidate within a
 * family. A peer with more than one candidate of the same family is out of
 * scope for this slice (no real caller produces that shape today, per this
 * class's own "known gap" note above).
 */
object PeerDialer {

    /**
     * How long to wait for the IPv6 candidate to connect before giving up on
     * it and moving on to the IPv4 candidate — only applies when an IPv4
     * candidate actually exists to fall back to (see [dial]; a lone IPv6
     * candidate gets the longer [FALLBACK_CONNECT_TIMEOUT_MS] instead, since
     * there's nothing to bail out to).
     *
     * Unmeasured placeholder, same posture as
     * [com.hop.dht.DhtUdpTransport.DEFAULT_REQUEST_TIMEOUT_MS] /
     * `RendezvousNode`'s own constants: RFC 8305 §8 recommends 250ms as its
     * default "Connection Attempt Delay" for general Internet traffic, but
     * this codebase's actual traffic shape (proximity-mesh peers reached
     * over a real network, not a browser tab racing a CDN) hasn't been
     * measured against that number yet. Revisit once field data exists.
     */
    const val IPV6_FIRST_ATTEMPT_TIMEOUT_MS: Int = 250

    /**
     * The timeout for a connect attempt that has no fallback left to bail
     * out to — either the IPv4 attempt after an IPv6 failure, or the sole
     * attempt when only one address family is available at all. Unmeasured
     * placeholder, same posture as [IPV6_FIRST_ATTEMPT_TIMEOUT_MS] above;
     * deliberately longer than that one, since there's nothing left to fall
     * back to if this attempt also fails.
     */
    const val FALLBACK_CONNECT_TIMEOUT_MS: Int = 2_000

    /**
     * Connects to one peer given every [PeerAddress] candidate known for it.
     *
     * Tries the first IPv6 candidate in [candidates] (if any) first,
     * bounding that attempt to [IPV6_FIRST_ATTEMPT_TIMEOUT_MS] when an IPv4
     * candidate also exists to fall back to (or [FALLBACK_CONNECT_TIMEOUT_MS]
     * if it's the only candidate at all). Falls back to the first IPv4
     * candidate (if any) on any IPv6 failure — timeout, connection refused,
     * unreachable, or simply no IPv6 candidate present in [candidates] at
     * all.
     *
     * Returns the connected [Socket] on success. Throws [PeerDialException]
     * if every attempted candidate failed, or if [candidates] was empty to
     * begin with. Never hangs indefinitely — every attempt is bounded by one
     * of the explicit timeouts above, so total worst-case wait is bounded by
     * their sum, not unbounded.
     */
    fun dial(candidates: List<PeerAddress>): Socket {
        if (candidates.isEmpty()) {
            throw PeerDialException("No candidates given to dial")
        }

        val ipv6Candidate = candidates.firstOrNull { it.family == PeerAddress.FAMILY_IPV6 }
        val ipv4Candidate = candidates.firstOrNull { it.family == PeerAddress.FAMILY_IPV4 }

        var lastError: Exception? = null

        if (ipv6Candidate != null) {
            val timeoutMs = if (ipv4Candidate != null) IPV6_FIRST_ATTEMPT_TIMEOUT_MS else FALLBACK_CONNECT_TIMEOUT_MS
            try {
                return connectTo(ipv6Candidate, timeoutMs)
            } catch (e: Exception) {
                lastError = e
            }
        }

        if (ipv4Candidate != null) {
            try {
                return connectTo(ipv4Candidate, FALLBACK_CONNECT_TIMEOUT_MS)
            } catch (e: Exception) {
                lastError = e
            }
        }

        throw PeerDialException("Could not connect to any candidate for this peer", lastError)
    }

    /** One bounded connect attempt. Closes the socket before rethrowing on any failure -- never leaks a half-open socket. */
    private fun connectTo(address: PeerAddress, timeoutMs: Int): Socket {
        val socket = Socket()
        try {
            socket.connect(address.toInetSocketAddress(), timeoutMs)
            return socket
        } catch (e: Exception) {
            try {
                socket.close()
            } catch (closeError: Exception) {
                // Best-effort cleanup; the original connect failure is what matters.
            }
            throw e
        }
    }
}
