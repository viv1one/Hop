package com.hop.dht

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FindValueMessageTest {

    private fun nodeId(byteValue: Int): NodeId {
        val bytes = ByteArray(NodeId.SIZE_BYTES)
        bytes[NodeId.SIZE_BYTES - 1] = byteValue.toByte()
        return NodeId(bytes)
    }

    private fun ipv4Contact(id: NodeId, lastOctet: Int, port: Int = 9000): Contact =
        Contact(
            id = id,
            address = PeerAddress(PeerAddress.FAMILY_IPV4, byteArrayOf(127, 0, 0, lastOctet.toByte()), port).encode(),
            lastSeenAtMs = 0L,
        )

    private fun ipv6Contact(id: NodeId, lastByte: Int, port: Int = 9000): Contact {
        val ip = ByteArray(PeerAddress.IPV6_SIZE)
        ip[PeerAddress.IPV6_SIZE - 1] = lastByte.toByte()
        return Contact(
            id = id,
            address = PeerAddress(PeerAddress.FAMILY_IPV6, ip, port).encode(),
            lastSeenAtMs = 0L,
        )
    }

    // ---- FindValueRequestMessage ----

    @Test
    fun `request round trip preserves all fields`() {
        val original = FindValueRequestMessage(
            transactionId = TransactionId.random(),
            senderId = nodeId(1),
            key = nodeId(2),
        )
        val decoded = FindValueRequestMessage.decode(original.encode())
        assertEquals(original, decoded)
    }

    @Test
    fun `request encodes to the fixed 74-byte size, same layout as FindNodeRequestMessage`() {
        val message = FindValueRequestMessage(TransactionId.random(), nodeId(1), nodeId(2))
        assertEquals(74, message.encode().size)
        assertEquals(FindValueRequestMessage.WIRE_SIZE, message.encode().size)
    }

    @Test
    fun `request decode rejects wrong length`() {
        assertFailsWith<DhtMessageDecodeException> {
            FindValueRequestMessage.decode(ByteArray(FindValueRequestMessage.WIRE_SIZE - 1))
        }
        assertFailsWith<DhtMessageDecodeException> {
            FindValueRequestMessage.decode(ByteArray(FindValueRequestMessage.WIRE_SIZE + 1))
        }
    }

    @Test
    fun `request decode rejects unknown version`() {
        val bytes = FindValueRequestMessage(TransactionId.random(), nodeId(1), nodeId(2)).encode()
        bytes[0] = 99
        assertFailsWith<DhtMessageDecodeException> { FindValueRequestMessage.decode(bytes) }
    }

    @Test
    fun `request decode rejects a non-FIND_VALUE_REQUEST type byte`() {
        val bytes = FindValueRequestMessage(TransactionId.random(), nodeId(1), nodeId(2)).encode()
        bytes[1] = DhtMessageType.FIND_NODE_REQUEST.wireValue.toByte()
        assertFailsWith<DhtMessageDecodeException> { FindValueRequestMessage.decode(bytes) }
    }

    @Test
    fun `request decode rejects a FindNodeRequestMessage's own bytes despite the identical layout`() {
        // Same byte layout as FindNodeRequestMessage, but FIND_VALUE and
        // FIND_NODE are deliberately independent wire types -- a
        // FindNodeRequestMessage's encoded bytes must never decode as a
        // FindValueRequestMessage.
        val findNodeBytes = FindNodeRequestMessage(TransactionId.random(), nodeId(1), nodeId(2)).encode()
        assertFailsWith<DhtMessageDecodeException> { FindValueRequestMessage.decode(findNodeBytes) }
    }

    // ---- FindValueResponseMessage ----

    @Test
    fun `response round trip with found=true and holder contacts`() {
        val holder = ipv4Contact(nodeId(2), lastOctet = 5)
        val original = FindValueResponseMessage(TransactionId.random(), nodeId(1), found = true, contacts = listOf(holder))
        val decoded = FindValueResponseMessage.decode(original.encode())
        assertTrue(decoded.found)
        assertEquals(1, decoded.contacts.size)
        assertEquals(holder.id, decoded.contacts[0].id)
        assertTrue(holder.address.contentEquals(decoded.contacts[0].address))
    }

    @Test
    fun `response round trip with found=false and closer-routing-candidate contacts`() {
        val closer = ipv6Contact(nodeId(3), lastByte = 9)
        val original = FindValueResponseMessage(TransactionId.random(), nodeId(1), found = false, contacts = listOf(closer))
        val decoded = FindValueResponseMessage.decode(original.encode())
        assertFalse(decoded.found)
        assertEquals(1, decoded.contacts.size)
        assertEquals(closer.id, decoded.contacts[0].id)
        assertTrue(closer.address.contentEquals(decoded.contacts[0].address))
    }

    @Test
    fun `response round trip with found=true and zero contacts`() {
        val original = FindValueResponseMessage(TransactionId.random(), nodeId(1), found = true, contacts = emptyList())
        val decoded = FindValueResponseMessage.decode(original.encode())
        assertTrue(decoded.found)
        assertTrue(decoded.contacts.isEmpty())
    }

    @Test
    fun `response round trip with a mix of IPv4 and IPv6 contacts`() {
        val contacts = listOf(
            ipv4Contact(nodeId(2), lastOctet = 5),
            ipv6Contact(nodeId(3), lastByte = 9),
        )
        val original = FindValueResponseMessage(TransactionId.random(), nodeId(1), found = false, contacts = contacts)
        val decoded = FindValueResponseMessage.decode(original.encode())
        assertEquals(contacts.map { it.id }, decoded.contacts.map { it.id })
        contacts.zip(decoded.contacts).forEach { (expected, actual) ->
            assertTrue(expected.address.contentEquals(actual.address))
        }
    }

    @Test
    fun `response encode rejects more than MAX_CONTACTS`() {
        val contacts = (0 until FindValueResponseMessage.MAX_CONTACTS + 1).map { index ->
            ipv6Contact(nodeId(index + 10), lastByte = index)
        }
        val message = FindValueResponseMessage(TransactionId.random(), nodeId(1), found = true, contacts = contacts)
        assertFailsWith<IllegalArgumentException> { message.encode() }
    }

    @Test
    fun `response decode rejects a truncated header`() {
        val full = FindValueResponseMessage(TransactionId.random(), nodeId(1), found = false, contacts = emptyList()).encode()
        assertFailsWith<DhtMessageDecodeException> {
            FindValueResponseMessage.decode(full.copyOfRange(0, full.size - 1))
        }
    }

    @Test
    fun `response decode rejects a truncated per-contact address`() {
        val contact = ipv4Contact(nodeId(2), lastOctet = 5)
        val full = FindValueResponseMessage(TransactionId.random(), nodeId(1), found = true, contacts = listOf(contact)).encode()
        assertFailsWith<DhtMessageDecodeException> {
            FindValueResponseMessage.decode(full.copyOfRange(0, full.size - 1))
        }
    }

    @Test
    fun `response decode rejects a declared contactCount exceeding remaining bytes`() {
        val full = FindValueResponseMessage(TransactionId.random(), nodeId(1), found = false, contacts = emptyList()).encode()
        // contactCount is the very last byte of the (contact-less) header --
        // bump it to claim a contact that isn't actually present.
        full[full.size - 1] = 1
        assertFailsWith<DhtMessageDecodeException> { FindValueResponseMessage.decode(full) }
    }

    @Test
    fun `response decode rejects a declared addressLength exceeding remaining bytes`() {
        val contact = ipv4Contact(nodeId(2), lastOctet = 5)
        val bytes = FindValueResponseMessage(TransactionId.random(), nodeId(1), found = true, contacts = listOf(contact)).encode()
        // Header is 44 bytes (version+type+transactionId+senderId+found+contactCount),
        // the addressLength byte sits right after the 32-byte contact id.
        val addressLengthIndex = 44 + NodeId.SIZE_BYTES
        bytes[addressLengthIndex] = 100
        assertFailsWith<DhtMessageDecodeException> { FindValueResponseMessage.decode(bytes) }
    }

    @Test
    fun `response decode rejects unknown version`() {
        val bytes = FindValueResponseMessage(TransactionId.random(), nodeId(1), found = false, contacts = emptyList()).encode()
        bytes[0] = 99
        assertFailsWith<DhtMessageDecodeException> { FindValueResponseMessage.decode(bytes) }
    }

    @Test
    fun `response decode rejects a non-FIND_VALUE_RESPONSE type byte`() {
        val bytes = FindValueResponseMessage(TransactionId.random(), nodeId(1), found = false, contacts = emptyList()).encode()
        bytes[1] = DhtMessageType.FIND_NODE_RESPONSE.wireValue.toByte()
        assertFailsWith<DhtMessageDecodeException> { FindValueResponseMessage.decode(bytes) }
    }
}
