package com.hop.dht

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FindNodeMessageTest {

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

    // ---- FindNodeRequestMessage ----

    @Test
    fun `request round trip preserves all fields`() {
        val original = FindNodeRequestMessage(
            transactionId = TransactionId.random(),
            senderId = nodeId(1),
            targetId = nodeId(2),
        )
        val decoded = FindNodeRequestMessage.decode(original.encode())
        assertEquals(original, decoded)
    }

    @Test
    fun `request encodes to the fixed 74-byte size`() {
        val message = FindNodeRequestMessage(TransactionId.random(), nodeId(1), nodeId(2))
        assertEquals(74, message.encode().size)
        assertEquals(FindNodeRequestMessage.WIRE_SIZE, message.encode().size)
    }

    @Test
    fun `request decode rejects wrong length`() {
        assertFailsWith<DhtMessageDecodeException> {
            FindNodeRequestMessage.decode(ByteArray(FindNodeRequestMessage.WIRE_SIZE - 1))
        }
        assertFailsWith<DhtMessageDecodeException> {
            FindNodeRequestMessage.decode(ByteArray(FindNodeRequestMessage.WIRE_SIZE + 1))
        }
    }

    @Test
    fun `request decode rejects unknown version`() {
        val bytes = FindNodeRequestMessage(TransactionId.random(), nodeId(1), nodeId(2)).encode()
        bytes[0] = 99
        assertFailsWith<DhtMessageDecodeException> { FindNodeRequestMessage.decode(bytes) }
    }

    @Test
    fun `request decode rejects a non-FIND_NODE_REQUEST type byte`() {
        val bytes = FindNodeRequestMessage(TransactionId.random(), nodeId(1), nodeId(2)).encode()
        bytes[1] = DhtMessageType.PING.wireValue.toByte()
        assertFailsWith<DhtMessageDecodeException> { FindNodeRequestMessage.decode(bytes) }
    }

    // ---- FindNodeResponseMessage ----

    @Test
    fun `response round trip with zero contacts`() {
        val original = FindNodeResponseMessage(TransactionId.random(), nodeId(1), emptyList())
        val decoded = FindNodeResponseMessage.decode(original.encode())
        assertEquals(original.transactionId, decoded.transactionId)
        assertEquals(original.senderId, decoded.senderId)
        assertTrue(decoded.contacts.isEmpty())
    }

    @Test
    fun `response round trip with a single IPv4 contact`() {
        val contact = ipv4Contact(nodeId(2), lastOctet = 5)
        val original = FindNodeResponseMessage(TransactionId.random(), nodeId(1), listOf(contact))
        val decoded = FindNodeResponseMessage.decode(original.encode())
        assertEquals(1, decoded.contacts.size)
        assertEquals(contact.id, decoded.contacts[0].id)
        assertTrue(contact.address.contentEquals(decoded.contacts[0].address))
    }

    @Test
    fun `response round trip with a single IPv6 contact`() {
        val contact = ipv6Contact(nodeId(2), lastByte = 7)
        val original = FindNodeResponseMessage(TransactionId.random(), nodeId(1), listOf(contact))
        val decoded = FindNodeResponseMessage.decode(original.encode())
        assertEquals(1, decoded.contacts.size)
        assertEquals(contact.id, decoded.contacts[0].id)
        assertTrue(contact.address.contentEquals(decoded.contacts[0].address))
    }

    @Test
    fun `response round trip with a mix of IPv4 and IPv6 contacts`() {
        val contacts = listOf(
            ipv4Contact(nodeId(2), lastOctet = 5),
            ipv6Contact(nodeId(3), lastByte = 9),
            ipv4Contact(nodeId(4), lastOctet = 11),
        )
        val original = FindNodeResponseMessage(TransactionId.random(), nodeId(1), contacts)
        val decoded = FindNodeResponseMessage.decode(original.encode())
        assertEquals(contacts.map { it.id }, decoded.contacts.map { it.id })
        contacts.zip(decoded.contacts).forEach { (expected, actual) ->
            assertTrue(expected.address.contentEquals(actual.address))
        }
    }

    @Test
    fun `response round trip at exactly MAX_CONTACTS`() {
        val contacts = (0 until FindNodeResponseMessage.MAX_CONTACTS).map { index ->
            ipv6Contact(nodeId(index + 10), lastByte = index)
        }
        val original = FindNodeResponseMessage(TransactionId.random(), nodeId(1), contacts)
        val decoded = FindNodeResponseMessage.decode(original.encode())
        assertEquals(FindNodeResponseMessage.MAX_CONTACTS, decoded.contacts.size)
        assertEquals(contacts.map { it.id }, decoded.contacts.map { it.id })
    }

    @Test
    fun `response encode rejects more than MAX_CONTACTS`() {
        val contacts = (0 until FindNodeResponseMessage.MAX_CONTACTS + 1).map { index ->
            ipv6Contact(nodeId(index + 10), lastByte = index)
        }
        val message = FindNodeResponseMessage(TransactionId.random(), nodeId(1), contacts)
        assertFailsWith<IllegalArgumentException> { message.encode() }
    }

    @Test
    fun `response decode rejects a truncated header`() {
        val full = FindNodeResponseMessage(TransactionId.random(), nodeId(1), emptyList()).encode()
        assertFailsWith<DhtMessageDecodeException> {
            FindNodeResponseMessage.decode(full.copyOfRange(0, full.size - 1))
        }
    }

    @Test
    fun `response decode rejects a truncated per-contact address`() {
        val contact = ipv4Contact(nodeId(2), lastOctet = 5)
        val full = FindNodeResponseMessage(TransactionId.random(), nodeId(1), listOf(contact)).encode()
        // Chop off the last byte of the (fully-present) contact's address bytes.
        assertFailsWith<DhtMessageDecodeException> {
            FindNodeResponseMessage.decode(full.copyOfRange(0, full.size - 1))
        }
    }

    @Test
    fun `response decode rejects a declared contactCount exceeding remaining bytes`() {
        val full = FindNodeResponseMessage(TransactionId.random(), nodeId(1), emptyList()).encode()
        // contactCount byte is the last byte of the (contact-less) header -- bump
        // it to claim a contact that isn't actually present.
        full[full.size - 1] = 1
        assertFailsWith<DhtMessageDecodeException> { FindNodeResponseMessage.decode(full) }
    }

    @Test
    fun `response decode rejects a declared addressLength exceeding remaining bytes`() {
        val contact = ipv4Contact(nodeId(2), lastOctet = 5)
        val bytes = FindNodeResponseMessage(TransactionId.random(), nodeId(1), listOf(contact)).encode()
        // addressLength byte sits immediately after the 32-byte contact id, right
        // after the header. Header is 43 bytes, so the addressLength byte is at
        // index 43 + 32 = 75.
        val addressLengthIndex = 43 + NodeId.SIZE_BYTES
        bytes[addressLengthIndex] = 100 // claim far more address bytes than actually follow
        assertFailsWith<DhtMessageDecodeException> { FindNodeResponseMessage.decode(bytes) }
    }

    @Test
    fun `response decode rejects unknown version`() {
        val bytes = FindNodeResponseMessage(TransactionId.random(), nodeId(1), emptyList()).encode()
        bytes[0] = 99
        assertFailsWith<DhtMessageDecodeException> { FindNodeResponseMessage.decode(bytes) }
    }

    @Test
    fun `response decode rejects a non-FIND_NODE_RESPONSE type byte`() {
        val bytes = FindNodeResponseMessage(TransactionId.random(), nodeId(1), emptyList()).encode()
        bytes[1] = DhtMessageType.PONG.wireValue.toByte()
        assertFailsWith<DhtMessageDecodeException> { FindNodeResponseMessage.decode(bytes) }
    }
}
