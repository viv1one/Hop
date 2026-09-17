package com.hop.rendezvous

import com.hop.dht.Contact
import com.hop.dht.NodeId
import com.hop.dht.PeerAddress
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Mirrors `dht/`'s `DhtStoreTest`'s shapes for style consistency (see RendezvousRegistry's own doc). */
class RendezvousRegistryTest {

    private fun nodeId(byteValue: Int): NodeId {
        val bytes = ByteArray(NodeId.SIZE_BYTES)
        bytes[NodeId.SIZE_BYTES - 1] = byteValue.toByte()
        return NodeId(bytes)
    }

    private fun contact(id: NodeId, port: Int = 1): Contact =
        Contact(id = id, address = PeerAddress.from(InetAddress.getLoopbackAddress(), port).encode(), lastSeenAtMs = 0L)

    @Test
    fun `liveContacts returns an empty list when nothing has been observed`() {
        val registry = RendezvousRegistry()
        assertEquals(emptyList(), registry.liveContacts())
    }

    @Test
    fun `observe then liveContacts returns the contact while still within TTL`() {
        var now = 1_000L
        val registry = RendezvousRegistry(entryTtlMs = 10_000L, nowMs = { now })
        val peer = contact(nodeId(1))

        registry.observe(peer)
        now += 5_000L // still within the 10s TTL

        assertEquals(listOf(peer.id), registry.liveContacts().map { it.id })
    }

    @Test
    fun `an expired entry is excluded from liveContacts and pruned as a side effect`() {
        var now = 1_000L
        val registry = RendezvousRegistry(entryTtlMs = 10_000L, nowMs = { now })
        val peer = contact(nodeId(1))

        registry.observe(peer)
        now += 10_000L // exactly at expiry -- expiresAtMs <= now must be treated as expired

        assertEquals(emptyList(), registry.liveContacts(), "an expired entry must never be returned")

        // Prove the expiry deleted the entry as a side effect, not merely
        // filtered it for this one read: a fresh non-expired peer observed
        // afterward must be the ONLY thing liveContacts() returns.
        val freshPeer = contact(nodeId(2))
        registry.observe(freshPeer)
        assertEquals(
            listOf(freshPeer.id),
            registry.liveContacts().map { it.id },
            "the expired entry must have been pruned, leaving only the freshly-observed peer",
        )
    }

    @Test
    fun `capacity cap evicts the oldest entry once over capacity`() {
        val registry = RendezvousRegistry(capacity = 3)
        val peers = (1..4).map { contact(nodeId(it)) }

        peers.forEach { registry.observe(it) }

        val remaining = registry.liveContacts().map { it.id }.toSet()
        assertEquals(3, remaining.size, "registry must be capped at capacity")
        assertTrue(
            peers[0].id !in remaining,
            "the oldest-observed entry must be the one evicted once the cap is exceeded",
        )
        assertTrue(
            remaining.containsAll(peers.drop(1).map { it.id }),
            "every peer observed after the oldest must survive",
        )
    }

    @Test
    fun `observing an already-known contact refreshes it rather than duplicating it, and resets its TTL`() {
        var now = 1_000L
        val registry = RendezvousRegistry(entryTtlMs = 10_000L, nowMs = { now })
        val peer = contact(nodeId(1))

        registry.observe(peer)
        now += 9_000L // just under the original TTL
        registry.observe(peer) // refresh
        now += 9_000L // past the ORIGINAL expiry, but within the REFRESHED one

        assertEquals(1, registry.size, "re-observing a known contact must not create a second entry")
        assertEquals(
            listOf(peer.id),
            registry.liveContacts().map { it.id },
            "a refreshed entry's TTL must be measured from the most recent observation, not the first",
        )
    }

    @Test
    fun `re-observing a known contact bumps it to most-recently-seen, protecting it from capacity eviction`() {
        val registry = RendezvousRegistry(capacity = 2)
        val oldest = contact(nodeId(1))
        val middle = contact(nodeId(2))

        registry.observe(oldest)
        registry.observe(middle)
        registry.observe(oldest) // refresh -- oldest is now the most-recently-seen of the two

        // A third, brand-new peer would normally evict whichever entry is
        // currently oldest -- that must now be `middle`, not `oldest`.
        val newcomer = contact(nodeId(3))
        registry.observe(newcomer)

        val remaining = registry.liveContacts().map { it.id }.toSet()
        assertTrue(oldest.id in remaining, "a refreshed entry must survive eviction pressure that would otherwise target the original insertion order")
        assertTrue(newcomer.id in remaining)
        assertTrue(middle.id !in remaining, "the entry that was NOT refreshed must be the one evicted")
    }
}
