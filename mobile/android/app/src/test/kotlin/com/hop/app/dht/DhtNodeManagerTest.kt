package com.hop.app.dht

import com.hop.protocol.ReachTier
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Plain JVM tests -- `registerWithProcessLifecycle = false` throughout (see
 * [DhtNodeManager]'s own doc for why [androidx.lifecycle.ProcessLifecycleOwner]
 * can't run outside a real Android process). Exercises real
 * [java.net.DatagramSocket]s and a real two-node bootstrap/publish/browse
 * round trip over loopback -- the same "real code, not a mock" posture as
 * `dht/`'s own `DhtNodeTest`.
 */
class DhtNodeManagerTest {

    private fun newManager(
        seed: ByteArray,
        bootstrapHost: String = "",
        bootstrapPort: Int = 0,
    ): DhtNodeManager = DhtNodeManager(
        getOwnNodeIdSeed = { seed },
        bootstrapHost = bootstrapHost,
        bootstrapPort = bootstrapPort,
        registerWithProcessLifecycle = false,
    )

    @Test
    fun `start with no bootstrap configured still produces a working standalone TopicSubscription`() = runBlocking {
        val manager = newManager(seed = ByteArray(16) { 1 })
        try {
            manager.start()
            val subscription = manager.awaitTopicSubscription()
            assertNotNull(subscription, "a DHT node with no bootstrap address must still come up standalone")

            // Publish then browse from the SAME node: DhtNode.store's
            // unconditional self-registration (see that method's own doc)
            // means a lone node always finds its own published cell.
            subscription.publish(latitude = 40.7128, longitude = -74.0060, tier = ReachTier.CITY)
            val holders = subscription.browse(latitude = 40.7128, longitude = -74.0060, tier = ReachTier.CITY)
            assertTrue(holders.isNotEmpty(), "self-registered publish must be findable by this same node's own browse")
        } finally {
            manager.stop()
        }
    }

    @Test
    fun `an unreachable bootstrap address does not prevent the node from starting`() = runBlocking {
        // Port 1 is a real, currently-unbound port on loopback in virtually
        // every test environment -- nothing answers, so bootstrapJoin's PING
        // times out. This is the low-density/near-broken-chain case: no peer
        // to join through, must not crash or hang start().
        val manager = newManager(seed = ByteArray(16) { 2 }, bootstrapHost = "127.0.0.1", bootstrapPort = 1)
        try {
            manager.start()
            val subscription = manager.awaitTopicSubscription(timeoutMs = 5_000)
            // kotlin.test's assertNotNull returns the asserted non-null value
            // (not Unit) -- as the trailing expression of this try block it
            // would make this @Test method's inferred return type non-void,
            // which JUnit4 rejects ("should be void"). The explicit `Unit`
            // below keeps the assertion while keeping the function void.
            assertNotNull(subscription, "an unreachable bootstrap address must not prevent standalone startup")
            Unit
        } finally {
            manager.stop()
        }
    }

    @Test
    fun `two nodes bootstrap off each other and can publish and browse across the pair`() = runBlocking {
        val nodeA = newManager(seed = ByteArray(16) { 3 })
        try {
            nodeA.start()
            assertNotNull(nodeA.awaitTopicSubscription())
            val nodeAAddress = nodeA.ownAddress
            assertNotNull(nodeAAddress, "nodeA must have a bound address to bootstrap through")

            val nodeB = newManager(
                seed = ByteArray(16) { 4 },
                bootstrapHost = "127.0.0.1",
                bootstrapPort = nodeAAddress.port,
            )
            try {
                nodeB.start()
                // bootstrapJoin runs before nodeB's TopicSubscription is
                // considered ready (see DhtNodeManager.start's own doc), so
                // by the time this returns non-null, nodeB's routing table
                // already reflects the join against nodeA -- no extra delay
                // needed here.
                val subscriptionB = nodeB.awaitTopicSubscription(timeoutMs = 5_000)
                assertNotNull(subscriptionB, "nodeB must bootstrap successfully against nodeA, reachable on loopback")

                // DhtNode.store awaits the real STORE_REQUEST/STORE_RESPONSE
                // round trip before returning -- by the time publish()
                // suspends-and-returns, nodeA has already recorded nodeB as
                // an announcer, so no extra synchronization is needed before
                // browsing from nodeA below either.
                subscriptionB.publish(latitude = 51.5074, longitude = -0.1278, tier = ReachTier.TOWN)

                val subscriptionA = nodeA.awaitTopicSubscription()
                assertNotNull(subscriptionA)
                val holders = subscriptionA.browse(latitude = 51.5074, longitude = -0.1278, tier = ReachTier.TOWN)
                assertTrue(holders.isNotEmpty(), "nodeA must discover what nodeB published, via the DHT they bootstrapped together")
            } finally {
                nodeB.stop()
            }
        } finally {
            nodeA.stop()
        }
    }

    @Test
    fun `stop tears down the node so awaitTopicSubscription returns null afterward`() = runBlocking {
        val manager = newManager(seed = ByteArray(16) { 5 })
        manager.start()
        assertNotNull(manager.awaitTopicSubscription())

        manager.stop()

        val subscriptionAfterStop = manager.awaitTopicSubscription(timeoutMs = 200)
        assertNull(subscriptionAfterStop, "stop() must tear down the DHT node, not leave a stale TopicSubscription reachable")
    }
}
