package com.hop.dht

import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * [IterativeLookup] exercised with a fake, in-memory [queryFn] over an
 * adjacency map -- no sockets, fast, fully isolated from [DhtUdpTransport].
 */
class IterativeLookupTest {

    private fun nodeId(byteValue: Int): NodeId {
        val bytes = ByteArray(NodeId.SIZE_BYTES)
        bytes[NodeId.SIZE_BYTES - 1] = byteValue.toByte()
        return NodeId(bytes)
    }

    private fun contact(id: NodeId): Contact =
        Contact(id = id, address = PeerAddress.from(InetAddress.getLoopbackAddress(), 1).encode(), lastSeenAtMs = 0L)

    @Test
    fun `empty seeds returns immediately, queryFn never invoked`() = runBlocking {
        val invocationCount = AtomicInteger(0)
        val lookup = IterativeLookup(ownId = nodeId(0), queryFn = { _, _ ->
            invocationCount.incrementAndGet()
            emptyList()
        })

        val result = lookup.lookup(nodeId(99), emptyList())

        assertTrue(result.isEmpty())
        assertEquals(0, invocationCount.get(), "queryFn must never be invoked when seeds is empty")
    }

    @Test
    fun `a small fully-connected topology converges and discovers every node`() = runBlocking {
        // A small, densely-connected adjacency map: everyone knows everyone else.
        val ids = (1..5).map { nodeId(it) }
        val adjacency = ids.associateWith { self -> ids.filter { it != self }.map(::contact) }

        val queryCount = AtomicInteger(0)
        val lookup = IterativeLookup(
            ownId = nodeId(0),
            alpha = 3,
            k = 20,
            queryFn = { contact, _ ->
                queryCount.incrementAndGet()
                adjacency[contact.id]
            },
        )

        val target = nodeId(42)
        val seeds = listOf(contact(ids[0]))
        val result = lookup.lookup(target, seeds)

        assertEquals(ids.toSet(), result.map { it.id }.toSet(), "every node in the fully-connected topology must be discovered")
        // Every node ends up queried exactly once -- termination is driven by
        // "every candidate in the shortlist has been queried", not a fixed
        // round count, but a fully-connected 5-node topology must never need
        // anywhere near maxRounds worth of querying.
        assertEquals(ids.size, queryCount.get(), "each of the 5 discovered nodes must be queried exactly once, no more")
    }

    @Test
    fun `an adversarial queryFn that always returns new, never-closer decoys is capped by maxRounds`() = runBlocking {
        // The target is nodeId(0) (all-zero id): every decoy below is
        // deliberately built to be numerically LARGER (and hence, XORed
        // against an all-zero target, farther away) than the last, so the
        // canonical "closest unqueried" rule alone never naturally
        // terminates -- there's always a fresh, never-queried candidate
        // sitting in the top-k shortlist (k is set larger than the total
        // decoy supply here, so nothing ever gets evicted from consideration).
        val target = NodeId(ByteArray(NodeId.SIZE_BYTES))
        val decoyCounter = AtomicInteger(1000)
        val queryCount = AtomicInteger(0)

        lateinit var queryFn: suspend (Contact, NodeId) -> List<Contact>?
        queryFn = { _, _ ->
            queryCount.incrementAndGet()
            val nextId = decoyCounter.incrementAndGet()
            listOf(contact(nodeId(nextId)))
        }

        val lookup = IterativeLookup(
            ownId = nodeId(0),
            alpha = 1,
            k = 1000, // large enough that no decoy is ever evicted from the shortlist
            maxRounds = 7,
            queryFn = queryFn,
        )

        val seed = contact(nodeId(1))
        val result = lookup.lookup(target, listOf(seed))

        // Without the maxRounds backstop, this would never terminate: proves
        // maxRounds actually bites rather than the lookup looping forever.
        assertEquals(7, queryCount.get(), "maxRounds must cap the number of query rounds at exactly 7 (alpha=1 means one query per round)")
        assertTrue(result.isNotEmpty(), "a forced-terminated lookup must still return whatever shortlist it accumulated, not throw or hang")
    }

    @Test
    fun `own id is never added to the shortlist or queried`() = runBlocking {
        val ownId = nodeId(0)
        val other = nodeId(1)

        val queriedIds = mutableListOf<NodeId>()
        val lookup = IterativeLookup(
            ownId = ownId,
            queryFn = { c, _ ->
                queriedIds.add(c.id)
                listOf(contact(ownId), contact(other)) // a peer's response echoes us back
            },
        )

        val result = lookup.lookup(nodeId(42), listOf(contact(other)))

        assertTrue(result.none { it.id == ownId }, "own id must never appear in the final shortlist")
        assertTrue(queriedIds.none { it == ownId }, "own id must never be queried")
    }
}
