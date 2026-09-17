package com.hop.dht

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AddressReflectionMessageTest {

    private fun nodeId(byteValue: Int): NodeId {
        val bytes = ByteArray(NodeId.SIZE_BYTES)
        bytes[NodeId.SIZE_BYTES - 1] = byteValue.toByte()
        return NodeId(bytes)
    }

    private fun ipv4Address(lastOctet: Int, port: Int = 9000): PeerAddress =
        PeerAddress(PeerAddress.FAMILY_IPV4, byteArrayOf(127, 0, 0, lastOctet.toByte()), port)

    private fun ipv6Address(lastByte: Int, port: Int = 9000): PeerAddress {
        val ip = ByteArray(PeerAddress.IPV6_SIZE)
        ip[PeerAddress.IPV6_SIZE - 1] = lastByte.toByte()
        return PeerAddress(PeerAddress.FAMILY_IPV6, ip, port)
    }

    // ---- AddressReflectionRequestMessage ----

    @Test
    fun `request round trip preserves all fields`() {
        val original = AddressReflectionRequestMessage(
            transactionId = TransactionId.random(),
            senderId = nodeId(1),
        )
        val decoded = AddressReflectionRequestMessage.decode(original.encode())
        assertEquals(original, decoded)
    }

    @Test
    fun `request encodes to the fixed 42-byte size, identical to DhtMessage's bare shape`() {
        val message = AddressReflectionRequestMessage(TransactionId.random(), nodeId(1))
        assertEquals(DhtMessage.WIRE_SIZE, message.encode().size)
        assertEquals(AddressReflectionRequestMessage.WIRE_SIZE, message.encode().size)
    }

    @Test
    fun `request decode rejects wrong length`() {
        assertFailsWith<DhtMessageDecodeException> {
            AddressReflectionRequestMessage.decode(ByteArray(AddressReflectionRequestMessage.WIRE_SIZE - 1))
        }
        assertFailsWith<DhtMessageDecodeException> {
            AddressReflectionRequestMessage.decode(ByteArray(AddressReflectionRequestMessage.WIRE_SIZE + 1))
        }
    }

    @Test
    fun `request decode rejects unknown version`() {
        val bytes = AddressReflectionRequestMessage(TransactionId.random(), nodeId(1)).encode()
        bytes[0] = 99
        assertFailsWith<DhtMessageDecodeException> { AddressReflectionRequestMessage.decode(bytes) }
    }

    @Test
    fun `request decode rejects a non-ADDRESS_REFLECTION_REQUEST type byte`() {
        val bytes = AddressReflectionRequestMessage(TransactionId.random(), nodeId(1)).encode()
        bytes[1] = DhtMessageType.PING.wireValue.toByte()
        assertFailsWith<DhtMessageDecodeException> { AddressReflectionRequestMessage.decode(bytes) }
    }

    // ---- AddressReflectionResponseMessage ----

    @Test
    fun `response round trip with an IPv4 reflected address`() {
        val original = AddressReflectionResponseMessage(
            transactionId = TransactionId.random(),
            senderId = nodeId(1),
            reflectedAddress = ipv4Address(lastOctet = 5, port = 54321),
        )
        val decoded = AddressReflectionResponseMessage.decode(original.encode())
        assertEquals(original, decoded)
    }

    @Test
    fun `response round trip with an IPv6 reflected address`() {
        val original = AddressReflectionResponseMessage(
            transactionId = TransactionId.random(),
            senderId = nodeId(1),
            reflectedAddress = ipv6Address(lastByte = 9, port = 54321),
        )
        val decoded = AddressReflectionResponseMessage.decode(original.encode())
        assertEquals(original, decoded)
    }

    @Test
    fun `response encodes to 49 bytes for an IPv4 address and 61 bytes for an IPv6 address`() {
        val ipv4Response = AddressReflectionResponseMessage(TransactionId.random(), nodeId(1), ipv4Address(5))
        val ipv6Response = AddressReflectionResponseMessage(TransactionId.random(), nodeId(1), ipv6Address(9))
        assertEquals(49, ipv4Response.encode().size)
        assertEquals(61, ipv6Response.encode().size)
    }

    @Test
    fun `response decode rejects a truncated header`() {
        val full = AddressReflectionResponseMessage(TransactionId.random(), nodeId(1), ipv4Address(5)).encode()
        assertFailsWith<DhtMessageDecodeException> {
            // Chop below HEADER_SIZE (42 bytes) entirely, not just the trailing address.
            AddressReflectionResponseMessage.decode(full.copyOfRange(0, 41))
        }
    }

    @Test
    fun `response decode rejects a truncated reflected address`() {
        val full = AddressReflectionResponseMessage(TransactionId.random(), nodeId(1), ipv4Address(5)).encode()
        // Header (42 bytes) is intact, but the trailing PeerAddress is missing its
        // last byte -- PeerAddress.decode must reject this, and this decoder must
        // surface that as a DhtMessageDecodeException, not let a
        // PeerAddressDecodeException leak through uncaught.
        assertFailsWith<DhtMessageDecodeException> {
            AddressReflectionResponseMessage.decode(full.copyOfRange(0, full.size - 1))
        }
    }

    @Test
    fun `response decode rejects an unknown family byte in the reflected address`() {
        val full = AddressReflectionResponseMessage(TransactionId.random(), nodeId(1), ipv4Address(5)).encode()
        // The reflected address's family byte is the first byte after the header.
        full[42] = 123
        assertFailsWith<DhtMessageDecodeException> { AddressReflectionResponseMessage.decode(full) }
    }

    @Test
    fun `response decode rejects unknown version`() {
        val bytes = AddressReflectionResponseMessage(TransactionId.random(), nodeId(1), ipv4Address(5)).encode()
        bytes[0] = 99
        assertFailsWith<DhtMessageDecodeException> { AddressReflectionResponseMessage.decode(bytes) }
    }

    @Test
    fun `response decode rejects a non-ADDRESS_REFLECTION_RESPONSE type byte`() {
        val bytes = AddressReflectionResponseMessage(TransactionId.random(), nodeId(1), ipv4Address(5)).encode()
        bytes[1] = DhtMessageType.PONG.wireValue.toByte()
        assertFailsWith<DhtMessageDecodeException> { AddressReflectionResponseMessage.decode(bytes) }
    }
}
