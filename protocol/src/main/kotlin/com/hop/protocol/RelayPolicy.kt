package com.hop.protocol

import java.time.Clock

/**
 * Pure relay-eligibility policy for a [Frame] being considered for further
 * store-and-forward propagation (Phase 2 Slice 1 -- app-layer relay for
 * posts). Zero `crypto/` dependency (ADR 0001's one-way rule: `protocol/`
 * may depend on `crypto/`, never the reverse -- this class needs neither
 * encryption nor key material to decide whether a frame is relay-eligible)
 * and zero I/O, so it's fully unit-testable without Room/Android.
 *
 * This class only decides "should this device keep relaying this frame" --
 * it never decrypts, never mutates a [Frame], and knows nothing about how a
 * frame got here (mesh peer, own composer) or where it's going next. Callers
 * (`mobile/android/`'s `RelayRepository`) own persistence and hop-count
 * bumping on top of this.
 *
 * [hopCount]/[dontRelay]/`ttlSeconds` are plaintext [Frame] header fields
 * with no MAC (see [Frame]'s own wire-format doc) -- a modified client can
 * self-report `hopCount = 0` on every hop it forwards, or clear `dontRelay`
 * before re-sending. [maxHops] and [isEligibleForRelay] are therefore a
 * deterrent against the honest-client population (the stock app never
 * relays past the bound, and never relays a frame its own holder marked
 * `dontRelay`), not an enforced cap against a hostile custom client -- state
 * this plainly anywhere this policy is described, per ADR 0003/0004's own
 * "Limits" sections.
 */
class RelayPolicy(
    private val maxHops: Int = DEFAULT_MAX_HOPS,
    private val clock: Clock = Clock.systemUTC(),
) {

    /** The absolute wall-clock instant (epoch ms) at which a frame originated at [originatedAtMs] with [ttlSeconds] decays. */
    fun expiresAtMs(originatedAtMs: Long, ttlSeconds: Long): Long =
        originatedAtMs + ttlSeconds * 1000

    /**
     * Whether a frame originated at [originatedAtMs] with [ttlSeconds] has
     * already decayed as of [nowMs]. Uses `>=` (not `>`) at the boundary --
     * matches [com.hop.crypto.DecayKeyStore.retrieve]'s existing
     * `!clock.instant().isBefore(entry.expiresAt)` convention exactly, so a
     * frame is never relay-eligible at the exact instant its decay key is
     * already gone (and never considered "still live" one instant after the
     * key store would already call it expired).
     */
    fun isExpired(originatedAtMs: Long, ttlSeconds: Long, nowMs: Long = clock.millis()): Boolean =
        nowMs >= expiresAtMs(originatedAtMs, ttlSeconds)

    /**
     * Whether a frame this device is holding at [storedHopCount] (the hop
     * count baked into the copy this device took custody of -- see
     * `RelayQueueEntity.hopCount`'s doc for why that's never mutated after
     * insert) should still be offered onward to other peers: it hasn't hit
     * [maxHops] yet, its holder hasn't marked it `dontRelay`, and it hasn't
     * already decayed as of [nowMs].
     */
    fun isEligibleForRelay(
        storedHopCount: Int,
        dontRelay: Boolean,
        originatedAtMs: Long,
        ttlSeconds: Long,
        nowMs: Long = clock.millis(),
    ): Boolean =
        storedHopCount < maxHops && !dontRelay && !isExpired(originatedAtMs, ttlSeconds, nowMs)

    companion object {
        /**
         * Unmeasured placeholder, matching how `WifiDirectTransport
         * .CONNECT_COOLDOWN_MS` documents its own unmeasured constant -- not
         * tuned against real multi-hop mesh density/reach data. Revisit once
         * real-world hop-distance numbers exist.
         */
        const val DEFAULT_MAX_HOPS = 6
    }
}
