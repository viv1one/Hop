package com.hop.dht

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SharedPrefixLengthTest {

    private fun zeroId(): NodeId = NodeId(ByteArray(NodeId.SIZE_BYTES))

    private fun idWithBitSet(bitIndex: Int): NodeId {
        val bytes = ByteArray(NodeId.SIZE_BYTES)
        val byteIndex = bitIndex / 8
        val bitInByte = bitIndex % 8
        bytes[byteIndex] = (0x80 ushr bitInByte).toByte()
        return NodeId(bytes)
    }

    @Test
    fun `identical ids share the full 256-bit prefix`() {
        val a = idWithBitSet(3)
        val b = NodeId(a.bytes.copyOf())
        assertEquals(NodeId.SIZE_BITS, a.sharedPrefixLength(b))
    }

    @Test
    fun `256 is guarded, never used as a raw bucket array index`() {
        // RoutingTable.bucketFor is the only place sharedPrefixLength feeds a
        // bucket-array index; it must refuse an id identical to ownId rather than
        // indexing buckets[256] (out of bounds by construction, array size 256
        // indexed 0..255).
        val table = RoutingTable(ownId = zeroId())
        assertFailsWith<IllegalArgumentException> { table.bucketFor(zeroId()) }
    }

    @Test
    fun `differ only at first MSB bit gives shared prefix length 0`() {
        val a = zeroId()
        val b = idWithBitSet(0)
        assertEquals(0, a.sharedPrefixLength(b))
    }

    @Test
    fun `differ only at last LSB bit gives shared prefix length 255`() {
        val a = zeroId()
        val b = idWithBitSet(255)
        assertEquals(255, a.sharedPrefixLength(b))
    }

    @Test
    fun `differ starting at bit 128 gives shared prefix length 128`() {
        val a = zeroId()
        val b = idWithBitSet(128)
        assertEquals(128, a.sharedPrefixLength(b))
    }
}
