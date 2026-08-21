package com.hop.topics

import com.hop.dht.NodeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class GeohashTopicKeyTest {

    @Test
    fun `toNodeId is deterministic and matches NodeId fromKeyMaterial directly`() {
        assertEquals(NodeId.fromKeyMaterial("ezs42"), GeohashTopicKey.toNodeId("ezs42"))
    }

    @Test
    fun `different geohash prefixes map to different NodeIds`() {
        assertNotEquals(GeohashTopicKey.toNodeId("ezs42"), GeohashTopicKey.toNodeId("ezs43"))
    }

    @Test
    fun `a Town-tier prefix and the City-tier prefix it is nested under map to unrelated NodeIds`() {
        // "ezs42" (5 chars, Town) is nested directly under "ezs4" (4 chars,
        // City) -- confirming this class does NOT invent a tier-aware
        // hierarchical relationship between the two derived keys, per its
        // own doc.
        val town = GeohashTopicKey.toNodeId("ezs42")
        val city = GeohashTopicKey.toNodeId("ezs4")
        assertNotEquals(town, city)
    }
}
