package com.hop.dht

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Mirrors `rendezvous/`'s `RendezvousRegistryTest`'s shapes for style consistency (see [RelayDirectory]'s own doc). */
class RelayDirectoryTest {

    private fun nodeId(byteValue: Int): NodeId {
        val bytes = ByteArray(NodeId.SIZE_BYTES)
        bytes[NodeId.SIZE_BYTES - 1] = byteValue.toByte()
        return NodeId(bytes)
    }

    private fun address(port: Int = 1): PeerAddress = PeerAddress.from(InetAddress.getLoopbackAddress(), port)

    @Test
    fun `liveRelays returns an empty list when nothing has been announced`() {
        val directory = RelayDirectory()
        assertEquals(emptyList(), directory.liveRelays())
    }

    @Test
    fun `announce then liveRelays returns the relay with its self-reported address while still within TTL`() {
        var now = 1_000L
        val directory = RelayDirectory(entryTtlMs = 10_000L, nowMs = { now })
        val relayId = nodeId(1)
        val relayAddress = address(port = 12345)

        directory.announce(relayId, relayAddress)
        now += 5_000L // still within the 10s TTL

        val live = directory.liveRelays()
        assertEquals(listOf(relayId), live.map { it.id })
        assertTrue(relayAddress.encode().contentEquals(live[0].address), "the stored address must be exactly the relay's self-reported relayAddress")
    }

    @Test
    fun `an expired entry is excluded from liveRelays and pruned as a side effect`() {
        var now = 1_000L
        val directory = RelayDirectory(entryTtlMs = 10_000L, nowMs = { now })
        val relayId = nodeId(1)

        directory.announce(relayId, address())
        now += 10_000L // exactly at expiry -- expiresAtMs <= now must be treated as expired

        assertEquals(emptyList(), directory.liveRelays(), "an expired entry must never be returned")

        // Prove the expiry deleted the entry as a side effect, not merely
        // filtered it for this one read: a fresh non-expired relay announced
        // afterward must be the ONLY thing liveRelays() returns.
        val freshRelayId = nodeId(2)
        directory.announce(freshRelayId, address())
        assertEquals(
            listOf(freshRelayId),
            directory.liveRelays().map { it.id },
            "the expired entry must have been pruned, leaving only the freshly-announced relay",
        )
    }

    @Test
    fun `capacity cap evicts the oldest entry once over capacity`() {
        val directory = RelayDirectory(capacity = 3)
        val relayIds = (1..4).map { nodeId(it) }

        relayIds.forEach { directory.announce(it, address()) }

        val remaining = directory.liveRelays().map { it.id }.toSet()
        assertEquals(3, remaining.size, "directory must be capped at capacity")
        assertTrue(relayIds[0] !in remaining, "the oldest-announced entry must be the one evicted once the cap is exceeded")
        assertTrue(remaining.containsAll(relayIds.drop(1)), "every relay announced after the oldest must survive")
    }

    @Test
    fun `re-announcing a known relay refreshes it rather than duplicating it, and resets its TTL and address`() {
        var now = 1_000L
        val directory = RelayDirectory(entryTtlMs = 10_000L, nowMs = { now })
        val relayId = nodeId(1)

        directory.announce(relayId, address(port = 1111))
        now += 9_000L // just under the original TTL
        directory.announce(relayId, address(port = 2222)) // refresh, with a new address
        now += 9_000L // past the ORIGINAL expiry, but within the REFRESHED one

        assertEquals(1, directory.size, "re-announcing a known relay must not create a second entry")
        val live = directory.liveRelays()
        assertEquals(listOf(relayId), live.map { it.id }, "a refreshed entry's TTL must be measured from the most recent announcement, not the first")
        assertTrue(address(port = 2222).encode().contentEquals(live[0].address), "a refreshed entry's address must be the most recently announced one")
    }
}
