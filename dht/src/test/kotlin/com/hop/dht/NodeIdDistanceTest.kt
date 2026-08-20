package com.hop.dht

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NodeIdDistanceTest {

    private fun idOf(vararg leadingBytes: Int): NodeId {
        val bytes = ByteArray(NodeId.SIZE_BYTES)
        for (i in leadingBytes.indices) bytes[i] = leadingBytes[i].toByte()
        return NodeId(bytes)
    }

    private fun idFilled(value: Int): NodeId = NodeId(ByteArray(NodeId.SIZE_BYTES) { value.toByte() })

    @Test
    fun `distance to self is all zero`() {
        val id = idOf(0x01, 0x02, 0xAB, 0xFF)
        val distance = id distanceTo id
        assertTrue(distance.bytes.all { it == 0.toByte() }, "distance-to-self must be all-zero bytes")
    }

    @Test
    fun `distance is symmetric`() {
        val a = idOf(0x12, 0x34, 0xAB)
        val b = idOf(0x56, 0x78, 0xCD, 0xEF)
        assertEquals(a distanceTo b, b distanceTo a)
    }

    @Test
    fun `all-zero vs all-0xFF is maximum distance`() {
        val zero = idFilled(0x00)
        val max = idFilled(0xFF)
        val distance = zero distanceTo max
        assertTrue(distance.bytes.all { it == 0xFF.toByte() }, "XOR of all-0x00 and all-0xFF must be all-0xFF")

        // And it must compare as strictly greater than any non-maximal distance.
        val smaller = zero distanceTo idOf(0x01)
        assertTrue(distance > smaller)
    }

    @Test
    fun `distance comparison masks bytes as unsigned, not signed`() {
        // Signed Byte pitfall: 0x7F (127 signed) vs 0x80 (-128 signed). A naive
        // signed comparison would say 0x7F > 0x80, inverting true unsigned
        // ordering (0x7F = 127 < 0x80 = 128). Every other byte held identical so
        // the first byte alone determines the comparison outcome.
        val smallerDistanceBytes = ByteArray(NodeId.SIZE_BYTES).also { it[0] = 0x7F.toByte() }
        val largerDistanceBytes = ByteArray(NodeId.SIZE_BYTES).also { it[0] = 0x80.toByte() }

        val smaller = Distance(smallerDistanceBytes)
        val larger = Distance(largerDistanceBytes)

        assertTrue(smaller < larger, "0x7F-prefixed distance must compare less than 0x80-prefixed distance under unsigned comparison")
        assertTrue(larger > smaller)
        assertEquals(-1, smaller.compareTo(larger).let { if (it < 0) -1 else if (it > 0) 1 else 0 })

        // A second pair entirely in the high range (both >= 0x80) to make sure
        // it's not just the 0x7F/0x80 boundary that's handled correctly.
        val highA = Distance(ByteArray(NodeId.SIZE_BYTES).also { it[0] = 0xA0.toByte() })
        val highB = Distance(ByteArray(NodeId.SIZE_BYTES).also { it[0] = 0xF0.toByte() })
        assertTrue(highA < highB, "0xA0-prefixed distance must compare less than 0xF0-prefixed distance under unsigned comparison")
    }
}
