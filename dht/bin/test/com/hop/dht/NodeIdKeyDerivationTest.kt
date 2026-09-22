package com.hop.dht

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * [NodeId.fromKeyMaterial] is the one bridge `dht/` exposes for mapping an
 * external key space (content hash, geohash-prefix topic, ...) onto this
 * DHT's id space -- see that function's own doc for why it's deliberately
 * domain-agnostic. These tests only exercise the SHA-256 mapping itself, not
 * any geohash/content semantics (that belongs to whatever higher module
 * calls this -- `dht/` stays zero-dependency on `protocol/`).
 */
class NodeIdKeyDerivationTest {

    @Test
    fun `fromKeyMaterial is deterministic -- same input always yields the same NodeId`() {
        val a = NodeId.fromKeyMaterial("town-tier-topic:9q8yy".toByteArray())
        val b = NodeId.fromKeyMaterial("town-tier-topic:9q8yy".toByteArray())
        assertEquals(a, b)
    }

    @Test
    fun `fromKeyMaterial produces a real 32-byte SHA-256 digest, not a truncated or padded value`() {
        val material = "9q8yyk".toByteArray()
        val expected = MessageDigest.getInstance("SHA-256").digest(material)
        val id = NodeId.fromKeyMaterial(material)
        assertEquals(NodeId(expected), id)
        assertEquals(NodeId.SIZE_BYTES, id.bytes.size)
    }

    @Test
    fun `fromKeyMaterial gives different inputs different NodeIds`() {
        val a = NodeId.fromKeyMaterial("9q8yy")
        val b = NodeId.fromKeyMaterial("9q8yz")
        assertNotEquals(a, b)
    }

    @Test
    fun `String overload matches the UTF-8 ByteArray overload`() {
        val fromString = NodeId.fromKeyMaterial("ezs42")
        val fromBytes = NodeId.fromKeyMaterial("ezs42".toByteArray(Charsets.UTF_8))
        assertEquals(fromBytes, fromString)
    }
}
