package com.hop.transport

import com.hop.protocol.ReachTier
import com.hop.protocol.ReachTierKeyDistribution
import java.time.Clock
import java.time.Instant

/**
 * Correlates an outgoing [com.hop.protocol.TierKeyRequestEnvelope] this
 * device actually broadcast with an incoming
 * [com.hop.protocol.TierKeyResponseEnvelope] -- closing a real gap in
 * [EnvelopeDispatcher.dispatch]'s `TIER_KEY_RESPONSE` branch, whose own
 * former doc comment incorrectly assumed "a response always arrives on the
 * connection its matching request went out on, so a device only ever sees a
 * response to its own request." That reasoning doesn't hold, on two counts:
 * (1) [TransportManager.broadcastTierKeyRequest] fans the *same* request out
 * to *every* connected peer, on both transports, so more than one peer can
 * legitimately respond; and (2) nothing about a live connection stops an
 * already-connected peer from sending an unsolicited, well-formed
 * `TIER_KEY_RESPONSE` for a `contentId` this device never actually asked
 * about at all -- e.g. `granted=true` for a post this device already holds a
 * real, previously-granted key for, silently poisoning or denying a working
 * decrypt.
 *
 * A single shared instance (composed once in `com.hop.app.AppContainer`) is
 * threaded into [TransportManager.broadcastTierKeyRequest] (which calls
 * [markPending] the moment a request actually goes out, over both
 * transports at once) and into both [WifiDirectTransport]'s and
 * [InternetPeerConnection]'s [EnvelopeDispatcher] construction (both call
 * [consumeIfPending] before honoring an incoming `TIER_KEY_RESPONSE`) --
 * this cannot live inside either transport individually, since a single
 * request goes out over *both* and a legitimate response can arrive on
 * *either*.
 *
 * Tracking key is composed via
 * [ReachTierKeyDistribution.decayKeyStorageKey] -- the exact same
 * `(contentId, tier)` composition the tiered [com.hop.crypto.DecayKeyStore]
 * entry itself uses -- reused rather than reinvented, so a pending-request
 * entry and its eventual [com.hop.crypto.DecayKeyStore] entry always agree on
 * what "the same request" means.
 *
 * [markPending]/[consumeIfPending] are a deliberate single-use,
 * consume-on-first-match pair: [consumeIfPending] removes the entry the
 * moment it finds a live match, so a second (or later-replayed) response for
 * the same `(contentId, tier)` -- honest or not -- is no longer correlated to
 * anything and gets ignored. That's the correct fail-safe posture once a
 * grant has already been accepted, not a bug: this device has no use for a
 * second answer to a question it already got a real answer to.
 *
 * [maxAgeSeconds] is an unmeasured placeholder, matching
 * [ReachTierKeyDistribution.DEFAULT_CLAIM_MAX_AGE_SECONDS]/
 * `FeedViewModel.TIER_KEY_REQUEST_COOLDOWN_MS`'s own "not tuned against real
 * mesh/internet-mode round-trip data" posture -- revisit once real latency
 * numbers exist. Deliberately short: a pending entry should not meaningfully
 * outlive the realistic window in which a genuine response could still
 * arrive.
 *
 * **Limit (state plainly, per ADR 0003 / the hop-dev "extra scrutiny"
 * posture):** this closes the "any connected peer can send an unsolicited
 * grant/denial for content this device never asked about" hole, for the
 * stock client. It does not, and cannot, verify that a peer this device
 * *did* genuinely request from is telling the truth in its response (wrong
 * key, garbage bytes) -- that's a different problem, out of scope here.
 */
class PendingTierKeyRequests(
    private val clock: Clock = Clock.systemUTC(),
    private val maxAgeSeconds: Long = DEFAULT_MAX_AGE_SECONDS,
) {
    private val pendingExpiresAt = mutableMapOf<String, Instant>()

    /**
     * Records that this device just broadcast a `TIER_KEY_REQUEST` for
     * `(contentId, tier)`, starting a fresh [maxAgeSeconds]-bounded window in
     * which a matching response will be accepted. Overwrites any existing
     * (e.g. stale, already-expired-but-not-yet-consumed) entry for the same
     * key with a fresh expiry -- a device may legitimately re-request the
     * same content after its first attempt's window lapses with no response.
     */
    @Synchronized
    fun markPending(contentId: String, tier: ReachTier) {
        val key = ReachTierKeyDistribution.decayKeyStorageKey(contentId, tier)
        pendingExpiresAt[key] = clock.instant().plusSeconds(maxAgeSeconds)
    }

    /**
     * Returns `true` and removes the entry for `(contentId, tier)` exactly
     * once, iff a still-live (not yet expired) pending request exists for it
     * -- `false` in every other case (never marked pending, already consumed
     * by an earlier response, or expired), also opportunistically evicting
     * an expired entry it happens to find along the way. Callers must only
     * honor an incoming `TIER_KEY_RESPONSE` when this returns `true`.
     */
    @Synchronized
    fun consumeIfPending(contentId: String, tier: ReachTier): Boolean {
        val key = ReachTierKeyDistribution.decayKeyStorageKey(contentId, tier)
        val expiresAt = pendingExpiresAt.remove(key) ?: return false
        return clock.instant().isBefore(expiresAt)
    }

    companion object {
        /** See this class's own doc for why this is an unmeasured placeholder. */
        const val DEFAULT_MAX_AGE_SECONDS: Long = 60L
    }
}
