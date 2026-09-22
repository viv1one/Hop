package com.hop.dht

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KBucketTest {

    private fun contact(byteValue: Int, lastSeenAtMs: Long = byteValue.toLong()): Contact {
        val idBytes = ByteArray(NodeId.SIZE_BYTES).also { it[NodeId.SIZE_BYTES - 1] = byteValue.toByte() }
        return Contact(id = NodeId(idBytes), address = byteArrayOf(byteValue.toByte()), lastSeenAtMs = lastSeenAtMs)
    }

    @Test
    fun `insert into non-full bucket returns Inserted`() {
        val bucket = KBucket(k = 3)
        val c1 = contact(1)
        assertEquals(InsertResult.Inserted, bucket.insertOrUpdate(c1))
        assertEquals(1, bucket.size)
        assertEquals(listOf(c1), bucket.contacts())
    }

    @Test
    fun `re-insert known id returns Updated and moves to MRU`() {
        val bucket = KBucket(k = 3)
        val c1 = contact(1)
        val c2 = contact(2)
        bucket.insertOrUpdate(c1)
        bucket.insertOrUpdate(c2)
        assertEquals(listOf(c1, c2), bucket.contacts())

        val c1Refreshed = contact(1, lastSeenAtMs = 999)
        assertEquals(InsertResult.Updated, bucket.insertOrUpdate(c1Refreshed))
        assertEquals(2, bucket.size, "update must not change bucket size")
        assertEquals(listOf(c2, c1Refreshed), bucket.contacts(), "updated contact must move to MRU (last) position")
    }

    @Test
    fun `full lifecycle - fill, PendingReplacement, markAlive, removeAndPromoteReplacement, cache eviction`() {
        val bucket = KBucket(k = 3, replacementCacheSize = 2)
        val c1 = contact(1)
        val c2 = contact(2)
        val c3 = contact(3)
        assertEquals(InsertResult.Inserted, bucket.insertOrUpdate(c1))
        assertEquals(InsertResult.Inserted, bucket.insertOrUpdate(c2))
        assertEquals(InsertResult.Inserted, bucket.insertOrUpdate(c3))
        assertEquals(3, bucket.size)
        assertEquals(listOf(c1, c2, c3), bucket.contacts(), "c1 is LRU: inserted first, never touched again")

        // Bucket now full (k=3). A brand-new contact must yield PendingReplacement
        // naming the true LRU contact (c1) as the eviction candidate.
        val c4 = contact(4)
        val result1 = bucket.insertOrUpdate(c4)
        assertTrue(result1 is InsertResult.PendingReplacement)
        assertEquals(c1, result1.evictionCandidate)
        assertEquals(c4, result1.replacementCandidate)
        assertEquals(listOf(c4), bucket.replacementCandidates())
        assertEquals(3, bucket.size, "PendingReplacement must not change live bucket size")

        // A second new contact: still evicts c1 (still LRU, untouched), and grows
        // the replacement cache.
        val c5 = contact(5)
        val result2 = bucket.insertOrUpdate(c5)
        assertTrue(result2 is InsertResult.PendingReplacement)
        assertEquals(c1, result2.evictionCandidate)
        assertEquals(c5, result2.replacementCandidate)
        assertEquals(listOf(c4, c5), bucket.replacementCandidates())

        // A third new contact overflows replacementCacheSize=2: the cache evicts
        // its own oldest entry (c4) to make room for c6.
        val c6 = contact(6)
        val result3 = bucket.insertOrUpdate(c6)
        assertTrue(result3 is InsertResult.PendingReplacement)
        assertEquals(c1, result3.evictionCandidate)
        assertEquals(listOf(c5, c6), bucket.replacementCandidates(), "replacement cache must evict its own oldest entry (c4) once full")

        // markAlive on the named eviction candidate (c1) bumps it to MRU with no
        // eviction -- this is the "ping succeeded" path.
        assertTrue(bucket.markAlive(c1.id))
        assertEquals(3, bucket.size, "markAlive must not evict")
        assertEquals(listOf(c2, c3, c1), bucket.contacts(), "c1 must move to MRU (last) position")

        // markAlive for an id that isn't in the bucket returns false.
        assertFalse(bucket.markAlive(contact(42).id))

        // Now c2 is LRU. removeAndPromoteReplacement (the "ping failed" path)
        // evicts it and promotes the MRU replacement-cache entry (c6) into its
        // place.
        val evicted = bucket.removeAndPromoteReplacement(c2.id)
        assertEquals(c2, evicted)
        assertEquals(3, bucket.size, "promoted replacement must keep the bucket at k")
        assertEquals(listOf(c3, c1, c6), bucket.contacts(), "promoted contact (c6) lands at MRU")
        assertEquals(listOf(c5), bucket.replacementCandidates(), "promoted contact must leave the replacement cache")
    }

    @Test
    fun `removeAndPromoteReplacement with an empty replacement cache leaves the bucket short`() {
        val bucket = KBucket(k = 2, replacementCacheSize = 2)
        val c1 = contact(1)
        val c2 = contact(2)
        bucket.insertOrUpdate(c1)
        bucket.insertOrUpdate(c2)
        assertEquals(2, bucket.size)

        val evicted = bucket.removeAndPromoteReplacement(c1.id)
        assertEquals(c1, evicted)
        assertEquals(1, bucket.size, "with no replacement candidates, the bucket is left short (k-1)")
        assertEquals(listOf(c2), bucket.contacts())
    }

    @Test
    fun `removeAndPromoteReplacement returns null for an unknown id`() {
        val bucket = KBucket(k = 2)
        bucket.insertOrUpdate(contact(1))
        assertNull(bucket.removeAndPromoteReplacement(contact(99).id))
    }
}
