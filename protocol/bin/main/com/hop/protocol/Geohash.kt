package com.hop.protocol

/**
 * Pure, hand-rolled geohash encode/decode/neighbor math. Zero external
 * dependencies -- deliberately, not a corner cut. The only real candidate
 * library on Maven Central (`ch.hsr:geohash` 1.4.0) has an unfixed bug in its
 * neighbor computation: it mod-masks latitude bits with no pole
 * special-casing, silently wrapping a pole-touching cell's "northern" or
 * "southern" neighbor into the wrong hemisphere. Geohash bit-interleaving is
 * fully specified in well under 100 lines with public known-answer test
 * vectors, so hand-rolling here (matching `Frame.kt`/`WireEnvelope.kt`'s
 * existing zero-dependency posture in `protocol/`) is the safer choice, not
 * a simplification -- see the Phase 4 Slice 1 plan's Context section.
 */
object Geohash {

    private const val BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz"

    /**
     * A decoded geohash cell's lat/lon bounding box, plus its center point.
     * `internal` (not part of this object's public encode/neighbors
     * contract that other `protocol/` code depends on) -- exposed only so
     * this module's own tests can assert on decoded coordinates directly
     * (e.g. the antimeridian-wrap case, where the resulting geohash string
     * itself isn't hand-verifiable by inspection).
     */
    internal data class BoundingBox(
        val latMin: Double,
        val latMax: Double,
        val lonMin: Double,
        val lonMax: Double,
    ) {
        val latCenter: Double get() = (latMin + latMax) / 2.0
        val lonCenter: Double get() = (lonMin + lonMax) / 2.0
    }

    /**
     * Encodes ([latitude], [longitude]) as a base32 geohash string of
     * [precision] characters, using the standard interleaved-bit-halving
     * algorithm (longitude bit first, then latitude, alternating).
     */
    fun encode(latitude: Double, longitude: Double, precision: Int): String {
        require(precision > 0) { "precision must be positive, was $precision" }
        require(latitude in -90.0..90.0) { "latitude must be in [-90, 90], was $latitude" }
        require(longitude in -180.0..180.0) { "longitude must be in [-180, 180], was $longitude" }

        var latMin = -90.0
        var latMax = 90.0
        var lonMin = -180.0
        var lonMax = 180.0
        var isEvenBit = true // longitude bit first, per standard geohash bit order
        var bitsInChar = 0
        var charBits = 0
        val result = StringBuilder(precision)

        while (result.length < precision) {
            if (isEvenBit) {
                val mid = (lonMin + lonMax) / 2.0
                if (longitude >= mid) {
                    charBits = (charBits shl 1) or 1
                    lonMin = mid
                } else {
                    charBits = charBits shl 1
                    lonMax = mid
                }
            } else {
                val mid = (latMin + latMax) / 2.0
                if (latitude >= mid) {
                    charBits = (charBits shl 1) or 1
                    latMin = mid
                } else {
                    charBits = charBits shl 1
                    latMax = mid
                }
            }
            isEvenBit = !isEvenBit
            bitsInChar++
            if (bitsInChar == 5) {
                result.append(BASE32[charBits])
                bitsInChar = 0
                charBits = 0
            }
        }
        return result.toString()
    }

    /** Decodes [geohash] back to its lat/lon bounding box via the inverse of [encode]'s bit-halving. */
    internal fun decode(geohash: String): BoundingBox {
        require(geohash.isNotEmpty()) { "geohash must not be empty" }
        var latMin = -90.0
        var latMax = 90.0
        var lonMin = -180.0
        var lonMax = 180.0
        var isEvenBit = true

        for (c in geohash) {
            val charBits = BASE32.indexOf(c)
            require(charBits >= 0) { "Invalid geohash character: '$c' in \"$geohash\"" }
            for (bitPosition in 4 downTo 0) {
                val bit = (charBits shr bitPosition) and 1
                if (isEvenBit) {
                    val mid = (lonMin + lonMax) / 2.0
                    if (bit == 1) lonMin = mid else lonMax = mid
                } else {
                    val mid = (latMin + latMax) / 2.0
                    if (bit == 1) latMin = mid else latMax = mid
                }
                isEvenBit = !isEvenBit
            }
        }
        return BoundingBox(latMin, latMax, lonMin, lonMax)
    }

    /**
     * Up to 8 same-precision adjacent cell prefixes for [geohash]. Correctly
     * wraps at +/-180 degrees longitude (the antimeridian; genuinely cyclic,
     * so wrapping is correct there). Deliberately omits, never wraps, any
     * direction that would cross +/-90 degrees latitude (a cell touching a
     * pole has no real northern/southern neighbor to wrap to) -- this is a
     * documented, deliberate clamp, not accidental bit-masking behavior. The
     * two are different fixes for two different edge cases, not the same
     * fix applied twice.
     */
    fun neighbors(geohash: String): List<String> {
        val precision = geohash.length
        val box = decode(geohash)
        val latStep = box.latMax - box.latMin
        val lonStep = box.lonMax - box.lonMin

        val result = mutableListOf<String>()
        for (dLat in -1..1) {
            for (dLon in -1..1) {
                if (dLat == 0 && dLon == 0) continue

                val neighborLat = box.latCenter + dLat * latStep
                // Deliberate clamp: a cell touching a pole has no real
                // northern/southern neighbor to wrap to. Omit, don't wrap.
                if (neighborLat < -90.0 || neighborLat > 90.0) continue

                var neighborLon = box.lonCenter + dLon * lonStep
                // The antimeridian IS genuinely cyclic (unlike the poles),
                // so wrapping here is correct, not a bug being papered over.
                if (neighborLon > 180.0) neighborLon -= 360.0
                if (neighborLon < -180.0) neighborLon += 360.0

                result.add(encode(neighborLat, neighborLon, precision))
            }
        }
        return result.distinct()
    }
}
