package com.hop.dht

import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoutingTableTest {

    private fun zeroId(): NodeId = NodeId(ByteArray(NodeId.SIZE_BYTES))

    private fun idWithByte(index: Int, value: Int): NodeId {
        val bytes = ByteArray(NodeId.SIZE_BYTES)
        bytes[index] = value.toByte()
        return NodeId(bytes)
    }

    private fun contact(id: NodeId, tag: Int): Contact =
        Contact(id = id, address = byteArrayOf(tag.toByte()), lastSeenAtMs = tag.toLong())

    /** Independent reference computation of unsigned XOR distance, deliberately
     * not reusing NodeId.distanceTo/Distance.compareTo, so this test doesn't
     * validate itself using the same code it's checking. */
    private fun referenceDistance(a: NodeId, b: NodeId): BigInteger {
        val xor = ByteArray(NodeId.SIZE_BYTES) { i -> (a.bytes[i].toInt() xor b.bytes[i].toInt()).toByte() }
        return BigInteger(1, xor)
    }

    @Test
    fun `insertOrUpdate of ownId is a no-op and never appears in any bucket`() {
        val own = zeroId()
        val table = RoutingTable(ownId = own)
        table.insertOrUpdate(contact(own, tag = 1))
        // Ask for every possible contact, including own -- own must never appear.
        val everything = table.findClosest(own, count = NodeId.SIZE_BITS)
        assertTrue(everything.isEmpty(), "ownId must never be stored as a contact in its own routing table")
    }

    @Test
    fun `a contact routes to the bucket matching its exact sharedPrefixLength with ownId`() {
        val own = zeroId()
        val table = RoutingTable(ownId = own)

        val c0 = contact(idWithByte(0, 0x80), tag = 1) // sharedPrefixLength(own) == 0
        val c50 = contact(idWithByte(6, 0x20), tag = 2) // bit 50 set -> sharedPrefixLength == 50
        val c255 = contact(idWithByte(31, 0x01), tag = 3) // last bit -> sharedPrefixLength == 255

        assertEquals(0, c0.id.sharedPrefixLength(own))
        assertEquals(50, c50.id.sharedPrefixLength(own))
        assertEquals(255, c255.id.sharedPrefixLength(own))

        table.insertOrUpdate(c0)
        table.insertOrUpdate(c50)
        table.insertOrUpdate(c255)

        assertEquals(listOf(c0), table.bucketFor(c0.id).contacts())
        assertEquals(listOf(c50), table.bucketFor(c50.id).contacts())
        assertEquals(listOf(c255), table.bucketFor(c255.id).contacts())
        assertTrue(table.bucketFor(c0.id) !== table.bucketFor(c50.id))
        assertTrue(table.bucketFor(c50.id) !== table.bucketFor(c255.id))
    }

    /**
     * findClosest must be a full-scan-across-all-buckets-then-sort. This test
     * deliberately builds a table where the true-closest contacts to [target]
     * live in buckets FAR (by bucket index) from the bucket nearest to the
     * target's own shared-prefix-length with ownId -- and that nearest bucket is
     * left empty. An implementation that searches outward from the near bucket
     * (e.g. bucket 50, then 49, then 51, then 48...) and stops once it has
     * `count` results without a final full sort would wrongly prefer the
     * index-adjacent-but-farther contacts (bucket 48/49) over the true closest
     * ones sitting in distant buckets 100/200.
     */
    @Test
    fun `findClosest full-scans all buckets, not just those near the target's own bucket`() {
        val own = zeroId()
        val table = RoutingTable(ownId = own)

        // target: matches ownId (all-zero) through bit 49, differs at bit 50.
        // -> sharedPrefixLength(target, own) == 50: the "nearest bucket" a naive
        // outward-search implementation would start from.
        val target = idWithByte(6, 0x20)
        assertEquals(50, target.sharedPrefixLength(own))

        // Bucket 50 itself (nearest to target's own shared-prefix-length) is left
        // deliberately EMPTY.
        assertEquals(0, table.bucketFor(target).size)

        // Two contacts index-adjacent to bucket 50 (48 and 49) -- per Kademlia's
        // XOR-distance structure these are PROVABLY farther from target than any
        // contact tied at prefix-length 50, since they diverge from target one or
        // two bits earlier. A naive "expand outward from 50" walk would encounter
        // these first.
        val cBucket48 = contact(idWithByte(6, 0x80), tag = 48) // bit 48 set only
        val cBucket49 = contact(idWithByte(6, 0x40), tag = 49) // bit 49 set only
        assertEquals(48, cBucket48.id.sharedPrefixLength(own))
        assertEquals(49, cBucket49.id.sharedPrefixLength(own))

        // Two contacts in FAR buckets (100 and 200) that are, despite the bucket
        // distance, the two truly closest contacts to target overall.
        val cBucket100 = contact(idWithByte(12, 0x08), tag = 100) // bit 100 set only
        val cBucket200 = contact(idWithByte(25, 0x80), tag = 200) // bit 200 set only
        assertEquals(100, cBucket100.id.sharedPrefixLength(own))
        assertEquals(200, cBucket200.id.sharedPrefixLength(own))

        val allContacts = listOf(cBucket48, cBucket49, cBucket100, cBucket200)
        allContacts.forEach { table.insertOrUpdate(it) }

        // Independently confirm, via a completely separate BigInteger-based
        // distance computation, what the true closest-2 order actually is.
        val expectedOrder = allContacts.sortedBy { referenceDistance(it.id, target) }
        assertEquals(listOf(cBucket200, cBucket100), expectedOrder.take(2), "sanity check on the hand-built scenario itself")

        val result = table.findClosest(target, count = 2)
        assertEquals(listOf(cBucket200, cBucket100), result, "must return the true closest 2, from buckets 200 and 100, not the index-adjacent-but-farther 48/49")
    }
}
