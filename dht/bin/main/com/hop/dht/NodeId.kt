package com.hop.dht

import java.security.MessageDigest

/**
 * A 256-bit identifier in the Kademlia XOR-distance ID space: both DHT node
 * identities and content keys live in this space, so [distanceTo] is meaningful
 * across both once a later slice wires content-addressed DHT-store against this
 * routing table.
 */
class NodeId(val bytes: ByteArray) {

    init {
        require(bytes.size == SIZE_BYTES) { "NodeId must be $SIZE_BYTES bytes, was ${bytes.size}" }
    }

    infix fun distanceTo(other: NodeId): Distance {
        val result = ByteArray(SIZE_BYTES)
        for (i in 0 until SIZE_BYTES) {
            result[i] = (bytes[i].toInt() xor other.bytes[i].toInt()).toByte()
        }
        return Distance(result)
    }

    /**
     * Length, in bits, of the shared MSB-first prefix between this ID and [other].
     * Range is 0..256; 256 only when the two IDs are bit-for-bit identical (e.g.
     * comparing this device's own ID against itself) -- callers must guard against
     * using 256 as a raw bucket-array index (see [RoutingTable], which never
     * inserts an entry for [RoutingTable.ownId] itself, precisely to avoid this).
     */
    fun sharedPrefixLength(other: NodeId): Int {
        for (byteIndex in 0 until SIZE_BYTES) {
            val a = bytes[byteIndex].toInt() and 0xFF
            val b = other.bytes[byteIndex].toInt() and 0xFF
            val xor = a xor b
            if (xor != 0) {
                // Number of leading zero bits in this differing byte (as an 8-bit
                // quantity), plus the bits already matched in prior whole bytes.
                var leadingZeros = 0
                var mask = 0x80
                while (mask != 0 && (xor and mask) == 0) {
                    leadingZeros++
                    mask = mask ushr 1
                }
                return byteIndex * 8 + leadingZeros
            }
        }
        return SIZE_BITS
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NodeId) return false
        return bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = bytes.contentHashCode()

    companion object {
        // 32 bytes, matching Frame.CLIP_HASH_SIZE's SHA-256 convention BY DESIGN,
        // not by module dependency -- dht/ has zero dependency on protocol/ this
        // slice. Content keys and node IDs must live in the same ID space for
        // Kademlia's XOR-distance lookups to be meaningful once a later slice
        // wires content-addressed DHT-store against this routing table.
        const val SIZE_BYTES = 32
        const val SIZE_BITS = SIZE_BYTES * 8

        /**
         * Deterministically maps arbitrary [keyMaterial] bytes onto this DHT's
         * 256-bit id space via SHA-256 (whose 32-byte digest happens to equal
         * [SIZE_BYTES] exactly, so no truncation/padding decision is needed).
         * This is the ONE function `dht/` exposes for turning an external key
         * space into a [NodeId] usable with [DhtNode.store]/[DhtNode.findValue]
         * -- and it is deliberately domain-agnostic: this file's own class-level
         * doc already explains why `dht/` has zero dependency on `protocol/`
         * this slice, so this function knows nothing about content hashes,
         * geohashes, or reach tiers. [DhtStore]'s own doc describes the two
         * use cases this generalizes to ("content-hash -> holder now,
         * geohash-prefix -> topic-subscriber later") -- both funnel through
         * this same function, with the actual "what does this byte string
         * mean" semantics owned entirely by the caller (e.g. a higher module
         * that depends on both `dht/` and `protocol/`, since neither of those
         * two may depend on the other).
         *
         * Content-addressing note: for the content-hash use case, callers that
         * already have a 32-byte SHA-256 clip hash (`Frame.CLIP_HASH_SIZE`)
         * should NOT double-hash it through this function -- a raw
         * `NodeId(clipHash)` construction is more direct and preserves the
         * "content key == its own hash" property BitTorrent Mainline DHT
         * relies on. This function exists for keys that are NOT already a
         * 32-byte digest in this space, e.g. an arbitrary-length geohash-prefix
         * string.
         */
        fun fromKeyMaterial(keyMaterial: ByteArray): NodeId {
            val digest = MessageDigest.getInstance("SHA-256").digest(keyMaterial)
            return NodeId(digest)
        }

        /** [fromKeyMaterial] over [keyMaterial]'s UTF-8 bytes -- convenience for string-shaped keys (e.g. a geohash prefix). */
        fun fromKeyMaterial(keyMaterial: String): NodeId = fromKeyMaterial(keyMaterial.toByteArray(Charsets.UTF_8))
    }
}

/**
 * XOR distance between two [NodeId]s, as an unsigned big-endian quantity.
 */
class Distance(val bytes: ByteArray) : Comparable<Distance> {

    /**
     * Unsigned big-endian lexicographic comparison -- each byte is masked
     * (`toInt() and 0xFF`) before comparing, since Kotlin's `Byte` is signed and a
     * naive signed comparison silently inverts ordering for any byte >= 0x80.
     */
    override fun compareTo(other: Distance): Int {
        for (i in bytes.indices) {
            val a = bytes[i].toInt() and 0xFF
            val b = other.bytes[i].toInt() and 0xFF
            if (a != b) return a - b
        }
        return 0
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Distance) return false
        return bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = bytes.contentHashCode()
}
