package com.hop.dht

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
