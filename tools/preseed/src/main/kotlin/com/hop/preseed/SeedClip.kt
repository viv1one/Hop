package com.hop.preseed

import com.hop.protocol.ReachTier

/**
 * One pre-seeded clip, already packaged into the exact wire shape the app's
 * real posting path produces (see [ClipPackager.packageClip]) -- ready to
 * offer to a real discovering peer as connect-time backlog, and to answer a
 * later [com.hop.protocol.TierKeyRequestEnvelope] against, once this class's
 * [contentId]/[reachTier]/[originGeohashPrefix] are known to [PreseedContentServer].
 *
 * [encodedFrame] is [com.hop.protocol.Frame.encode]'s own output -- the exact
 * bytes [PreseedContentServer] wraps in a [com.hop.protocol.WireEnvelope]
 * ([com.hop.protocol.WirePayloadType.POST_FRAME]) and offers verbatim, the
 * same "payload is opaque, pre-encoded bytes" posture every other backlog in
 * this codebase already uses (see `InternetPeerConnectionManager.sendBacklog`'s
 * own doc). The real content-encryption key for this clip is NOT carried
 * here -- per ADR 0003, for every non-Locality tier the real key lives only
 * in the [com.hop.crypto.DecayKeyStore] this clip was packaged against
 * (keyed via [com.hop.protocol.ReachTierKeyDistribution.decayKeyStorageKey]),
 * never inline on the wire (`Frame.keyIncluded = false`) -- see
 * [ClipPackager]'s own doc.
 *
 * [latitude]/[longitude] are kept here (not just [originGeohashPrefix])
 * purely so [PreseedNode.publishSeededContent] has what it needs to call
 * [com.hop.topics.TopicSubscription.publish] for this clip's target cell --
 * they never touch the wire (see [com.hop.protocol.Frame.originGeohashPrefix]'s
 * own "raw lat/lon never hits the wire" invariant, which this class also
 * respects: [encodedFrame] only ever carries the already-derived geohash
 * prefix, never these raw coordinates).
 */
data class SeedClip(
    /** Hex-encoded [com.hop.protocol.Frame.clipHash] -- the content-addressed id a [com.hop.protocol.TierKeyRequestEnvelope] names. */
    val contentId: String,
    val reachTier: ReachTier,
    /** Empty only if this clip was packaged without a location fix -- see [ClipPackager]'s own doc for why that makes it permanently key-less to everyone, same limitation [com.hop.app.composer.PostComposerViewModel] already documents for the real app. */
    val originGeohashPrefix: String,
    val latitude: Double,
    val longitude: Double,
    val encodedFrame: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SeedClip) return false
        return contentId == other.contentId &&
            reachTier == other.reachTier &&
            originGeohashPrefix == other.originGeohashPrefix &&
            latitude == other.latitude &&
            longitude == other.longitude &&
            encodedFrame.contentEquals(other.encodedFrame)
    }

    override fun hashCode(): Int {
        var result = contentId.hashCode()
        result = 31 * result + reachTier.hashCode()
        result = 31 * result + originGeohashPrefix.hashCode()
        result = 31 * result + latitude.hashCode()
        result = 31 * result + longitude.hashCode()
        result = 31 * result + encodedFrame.contentHashCode()
        return result
    }
}
