package com.hop.app.feed

import com.hop.protocol.ReachTier

/**
 * Plain-language labels for the feed's post overlay.
 *
 * Pure functions with an injected `nowMs` rather than reading the clock
 * themselves, so they get real JVM test coverage without a Compose harness --
 * the same "extract the decision, test the decision" shape
 * [dontRelayActionEnabled] already uses in this package.
 *
 * Every string here is user-facing, so none of them may leak protocol
 * vocabulary (hop-dev invariant #5, PRD §5): no "tier", "geohash", "relay",
 * "TTL", "decay" or "key". "Fades" is the user-facing word for what ADR
 * 0003's key expiry does; "Town"/"City" name a place, not a reach tier.
 */

/** Plain place-name for [PostEntity.reachTier]'s enum name, or `null` if unrecognized. */
internal fun reachLabel(reachTierName: String): String? =
    when (reachTierName) {
        ReachTier.LOCALITY.name -> "Around here"
        ReachTier.TOWN.name -> "Town"
        ReachTier.CITY.name -> "City"
        ReachTier.COUNTRY.name -> "Country"
        // Defensive: reachTier is always written from ReachTier.name, so an
        // unknown value means a schema change this UI hasn't caught up with.
        // Showing nothing beats showing a raw enum name to a user.
        else -> null
    }

/** How long ago the post was created, e.g. "Just now", "12m", "3h", "2d". */
internal fun postAgeLabel(originatedAtMs: Long, nowMs: Long): String {
    val elapsed = nowMs - originatedAtMs
    // A post whose origin clock ran ahead of this device's is not an error
    // worth surfacing -- peers have no shared time source (no server), so
    // small skew is expected. Clamp rather than render a negative age.
    if (elapsed < MINUTE_MS) return "Just now"
    return when {
        elapsed < HOUR_MS -> "${elapsed / MINUTE_MS}m"
        elapsed < DAY_MS -> "${elapsed / HOUR_MS}h"
        else -> "${elapsed / DAY_MS}d"
    }
}

/**
 * How long until this post stops being readable, e.g. "Fades in 40m".
 *
 * `null` once the window has passed -- at that point the post either already
 * renders as [DecayedPostPlaceholder] or is about to, and a "Fades in 0m"
 * badge next to it would just be noise.
 *
 * This makes HOP's signature mechanic visible instead of leaving it as an
 * invisible surprise: ephemerality is the product, so the feed says so.
 */
internal fun fadeLabel(originatedAtMs: Long, ttlSeconds: Long, nowMs: Long): String? {
    val remaining = (originatedAtMs + ttlSeconds * 1_000L) - nowMs
    if (remaining <= 0L) return null
    return when {
        remaining < HOUR_MS -> "Fades in ${(remaining / MINUTE_MS).coerceAtLeast(1)}m"
        remaining < DAY_MS -> "Fades in ${remaining / HOUR_MS}h"
        else -> "Fades in ${remaining / DAY_MS}d"
    }
}

private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 60 * MINUTE_MS
private const val DAY_MS = 24 * HOUR_MS
