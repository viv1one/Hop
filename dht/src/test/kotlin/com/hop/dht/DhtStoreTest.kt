package com.hop.dht

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DhtStoreTest {

    private fun nodeId(byteValue: Int): NodeId {
        val bytes = ByteArray(NodeId.SIZE_BYTES)
        bytes[NodeId.SIZE_BYTES - 1] = byteValue.toByte()
        return NodeId(bytes)
    }

    private fun contact(id: NodeId, port: Int = 1): Contact =
        Contact(id = id, address = PeerAddress.from(InetAddress.getLoopbackAddress(), port).encode(), lastSeenAtMs = 0L)

    @Test
    fun `get returns an empty list for a key that was never stored`() {
        val store = DhtStore()
        assertEquals(emptyList(), store.get(nodeId(1)))
    }

    @Test
    fun `recordAnnouncer then get returns the announcer while still within TTL`() {
        var now = 1_000L
        val store = DhtStore(entryTtlMs = 10_000L, nowMs = { now })
        val key = nodeId(1)
        val announcer = contact(nodeId(2))

        store.recordAnnouncer(key, announcer)
        now += 5_000L // still within the 10s TTL

        assertEquals(listOf(announcer.id), store.get(key).map { it.id })
    }

    @Test
    fun `an expired announcer is excluded from get and pruned as a side effect`() {
        var now = 1_000L
        val store = DhtStore(entryTtlMs = 10_000L, nowMs = { now })
        val key = nodeId(1)
        val announcer = contact(nodeId(2))

        store.recordAnnouncer(key, announcer)
        now += 10_000L // exactly at expiry -- expiresAtMs <= now must be treated as expired

        assertEquals(emptyList(), store.get(key), "an expired announcer must never be returned")

        // Prove the expiry deleted the entry as a side effect, not merely
        // filtered it for this one read: re-querying (with time held constant,
        // still "expired") must still return empty, and a fresh non-expired
        // announcer recorded afterward must be the ONLY thing get() returns --
        // proving the old entry is really gone, not just hidden.
        val secondAnnouncer = contact(nodeId(3))
        store.recordAnnouncer(key, secondAnnouncer)
        assertEquals(
            listOf(secondAnnouncer.id),
            store.get(key).map { it.id },
            "the expired entry must have been pruned, leaving only the freshly-recorded announcer",
        )
    }

    @Test
    fun `per-key announcer cap evicts oldest-first once over capacity`() {
        val store = DhtStore(maxAnnouncersPerKey = 3)
        val key = nodeId(1)
        val announcers = (1..4).map { contact(nodeId(it + 10)) }

        announcers.forEach { store.recordAnnouncer(key, it) }

        val remaining = store.get(key).map { it.id }.toSet()
        assertEquals(3, remaining.size, "announcer set must be capped at maxAnnouncersPerKey")
        assertTrue(
            announcers[0].id !in remaining,
            "the oldest announcer must be the one evicted once the cap is exceeded",
        )
        assertTrue(
            remaining.containsAll(announcers.drop(1).map { it.id }),
            "every announcer added after the oldest must survive",
        )
    }

    @Test
    fun `global key cap rejects a brand-new key once at capacity, without evicting an existing key`() {
        val store = DhtStore(maxKeys = 2)
        val existingKey1 = nodeId(1)
        val existingKey2 = nodeId(2)
        val newKey = nodeId(3)

        store.recordAnnouncer(existingKey1, contact(nodeId(101)))
        store.recordAnnouncer(existingKey2, contact(nodeId(102)))

        // Store is now at maxKeys=2 distinct keys -- a brand-new third key
        // must be silently rejected, not evict either existing key.
        store.recordAnnouncer(newKey, contact(nodeId(103)))

        assertEquals(emptyList(), store.get(newKey), "a brand-new key at global capacity must be rejected, not stored")
        assertEquals(listOf(nodeId(101)), store.get(existingKey1).map { it.id }, "an existing key's announcer set must survive the rejected new-key attempt")
        assertEquals(listOf(nodeId(102)), store.get(existingKey2).map { it.id }, "an existing key's announcer set must survive the rejected new-key attempt")
    }

    @Test
    fun `global key cap does not reject an additional announcer for an already-present key`() {
        val store = DhtStore(maxKeys = 1)
        val key = nodeId(1)
        store.recordAnnouncer(key, contact(nodeId(101)))
        // Same key, not a new one -- must not be treated as hitting the global cap.
        store.recordAnnouncer(key, contact(nodeId(102)))

        assertEquals(
            setOf(nodeId(101), nodeId(102)),
            store.get(key).map { it.id }.toSet(),
            "a second announcer for an ALREADY-PRESENT key must not be rejected by the global distinct-key cap",
        )
    }

    @Test
    fun `a self-registered entry survives announcer-cap eviction pressure that would otherwise evict it`() {
        val store = DhtStore(maxAnnouncersPerKey = 2)
        val key = nodeId(1)
        val self = contact(nodeId(999))

        // Self-registers FIRST, so it's the oldest entry -- the exact position
        // a naive oldest-evicted-first policy would pick to evict first.
        store.registerSelf(key, self)

        // Flood the key with far more external announcers than the cap allows.
        (1..10).forEach { store.recordAnnouncer(key, contact(nodeId(it + 10))) }

        val remaining = store.get(key).map { it.id }
        assertTrue(self.id in remaining, "a Sybil STORE-flood must never evict this device's own self-announcement")
        assertEquals(
            2,
            remaining.size,
            "the announcer set (including the protected self entry) must still respect the per-key cap overall",
        )
    }

    @Test
    fun `registerSelf bypasses the global key cap unconditionally`() {
        val store = DhtStore(maxKeys = 1)
        val existingKey = nodeId(1)
        val selfKey = nodeId(2)
        store.recordAnnouncer(existingKey, contact(nodeId(101)))

        val self = contact(nodeId(999))
        store.registerSelf(selfKey, self)

        assertEquals(
            listOf(self.id),
            store.get(selfKey).map { it.id },
            "registerSelf must always succeed, even for a brand-new key once the store is already at maxKeys",
        )
        assertEquals(
            listOf(nodeId(101)),
            store.get(existingKey).map { it.id },
            "an existing key's announcer set must be unaffected by a subsequent registerSelf on a different key",
        )
    }

    @Test
    fun `registerSelf on an existing key is idempotent, not a duplicate entry`() {
        val store = DhtStore()
        val key = nodeId(1)
        val self = contact(nodeId(999))

        store.registerSelf(key, self)
        store.registerSelf(key, self)

        assertEquals(listOf(self.id), store.get(key).map { it.id })
    }
}
