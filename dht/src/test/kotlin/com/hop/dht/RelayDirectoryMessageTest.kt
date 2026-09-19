package com.hop.dht

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Encode/decode coverage for [RelayAnnounceRequestMessage]/
 * [RelayQueryRequestMessage]/[RelayQueryResponseMessage] -- matching
 * [IntroductionMessageTest]'s/[FindNodeMessageTest]'s style (round trip,
 * truncated header, unknown version, wrong type byte, and, for the
 * multi-entry response, the same [ContactListCodec] truncation coverage
 * [FindNodeMessageTest] already established).
 */
class RelayDirectoryMessageTest {

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

    private fun ipv4Contact(id: NodeId, lastOctet: Int, port: Int = 9000): Contact =
        Contact(id = id, address = ipv4Address(lastOctet, port).encode(), lastSeenAtMs = 0L)

    // ---- RelayAnnounceRequestMessage ----

    @Test
    fun `announce round trip preserves all fields with an IPv4 relayAddress`() {
        val original = RelayAnnounceRequestMessage(
            transactionId = TransactionId.random(),
            senderId = nodeId(1),
            relayAddress = ipv4Address(5),
        )
        val decoded = RelayAnnounceRequestMessage.decode(original.encode())
        assertEquals(original, decoded)
    }

    @Test
    fun `announce round trip preserves all fields with an IPv6 relayAddress`() {
        val original = RelayAnnounceRequestMessage(
            transactionId = TransactionId.random(),
            senderId = nodeId(1),
            relayAddress = ipv6Address(9),
        )
        val decoded = RelayAnnounceRequestMessage.decode(original.encode())
        assertEquals(original, decoded)
    }

    @Test
    fun `announce encodes to 49 bytes for an IPv4 relayAddress and 61 bytes for an IPv6 relayAddress`() {
        // HEADER_SIZE (version+type+transactionId+senderId) = 1+1+8+32 = 42,
        // plus relayAddress's own encoding (7 IPv4, 19 IPv6) -- same total
        // shape as AddressReflectionResponseMessage, since both carry
        // exactly one trailing PeerAddress after an identical header.
        val ipv4Announce = RelayAnnounceRequestMessage(TransactionId.random(), nodeId(1), ipv4Address(5))
        val ipv6Announce = RelayAnnounceRequestMessage(TransactionId.random(), nodeId(1), ipv6Address(9))
        assertEquals(49, ipv4Announce.encode().size)
        assertEquals(61, ipv6Announce.encode().size)
    }

    @Test
    fun `announce decode rejects a truncated header`() {
        val full = RelayAnnounceRequestMessage(TransactionId.random(), nodeId(1), ipv4Address(5)).encode()
        // HEADER_SIZE = 1+1+8+32 = 42. Chop below that entirely, not just the trailing address.
        assertFailsWith<DhtMessageDecodeException> {
            RelayAnnounceRequestMessage.decode(full.copyOfRange(0, 41))
        }
    }

    @Test
    fun `announce decode rejects a truncated trailing relayAddress`() {
        val full = RelayAnnounceRequestMessage(TransactionId.random(), nodeId(1), ipv4Address(5)).encode()
        assertFailsWith<DhtMessageDecodeException> {
            RelayAnnounceRequestMessage.decode(full.copyOfRange(0, full.size - 1))
        }
    }

    @Test
    fun `announce decode rejects unknown version`() {
        val bytes = RelayAnnounceRequestMessage(TransactionId.random(), nodeId(1), ipv4Address(5)).encode()
        bytes[0] = 99
        assertFailsWith<DhtMessageDecodeException> { RelayAnnounceRequestMessage.decode(bytes) }
    }

    @Test
    fun `announce decode rejects a non-RELAY_ANNOUNCE type byte`() {
        val bytes = RelayAnnounceRequestMessage(TransactionId.random(), nodeId(1), ipv4Address(5)).encode()
        bytes[1] = DhtMessageType.PING.wireValue.toByte()
        assertFailsWith<DhtMessageDecodeException> { RelayAnnounceRequestMessage.decode(bytes) }
    }

    // ---- RELAY_ANNOUNCE_ACK (bare DhtMessage, mirroring STORE_RESPONSE) ----

    @Test
    fun `a bare DhtMessage round trips as a RELAY_ANNOUNCE_ACK`() {
        val original = DhtMessage(type = DhtMessageType.RELAY_ANNOUNCE_ACK, transactionId = TransactionId.random(), senderId = nodeId(1))
        val decoded = DhtMessage.decode(original.encode())
        assertEquals(original, decoded)
        assertEquals(DhtMessageType.RELAY_ANNOUNCE_ACK, decoded.type)
    }

    // ---- RelayQueryRequestMessage ----

    @Test
    fun `query round trip preserves all fields`() {
        val original = RelayQueryRequestMessage(transactionId = TransactionId.random(), senderId = nodeId(1))
        val decoded = RelayQueryRequestMessage.decode(original.encode())
        assertEquals(original, decoded)
    }

    @Test
    fun `query encodes to the fixed 42-byte size`() {
        val message = RelayQueryRequestMessage(TransactionId.random(), nodeId(1))
        assertEquals(42, message.encode().size)
        assertEquals(RelayQueryRequestMessage.WIRE_SIZE, message.encode().size)
    }

    @Test
    fun `query decode rejects wrong length`() {
        assertFailsWith<DhtMessageDecodeException> {
            RelayQueryRequestMessage.decode(ByteArray(RelayQueryRequestMessage.WIRE_SIZE - 1))
        }
        assertFailsWith<DhtMessageDecodeException> {
            RelayQueryRequestMessage.decode(ByteArray(RelayQueryRequestMessage.WIRE_SIZE + 1))
        }
    }

    @Test
    fun `query decode rejects unknown version`() {
        val bytes = RelayQueryRequestMessage(TransactionId.random(), nodeId(1)).encode()
        bytes[0] = 99
        assertFailsWith<DhtMessageDecodeException> { RelayQueryRequestMessage.decode(bytes) }
    }

    @Test
    fun `query decode rejects a non-RELAY_QUERY type byte`() {
        val bytes = RelayQueryRequestMessage(TransactionId.random(), nodeId(1)).encode()
        bytes[1] = DhtMessageType.PING.wireValue.toByte()
        assertFailsWith<DhtMessageDecodeException> { RelayQueryRequestMessage.decode(bytes) }
    }

    // ---- RelayQueryResponseMessage ----

    @Test
    fun `response round trip with zero relays`() {
        val original = RelayQueryResponseMessage(TransactionId.random(), nodeId(1), emptyList())
        val decoded = RelayQueryResponseMessage.decode(original.encode())
        assertEquals(original.transactionId, decoded.transactionId)
        assertEquals(original.senderId, decoded.senderId)
        assertTrue(decoded.relays.isEmpty())
    }

    @Test
    fun `response round trip with a single relay`() {
        val relay = ipv4Contact(nodeId(2), lastOctet = 5)
        val original = RelayQueryResponseMessage(TransactionId.random(), nodeId(1), listOf(relay))
        val decoded = RelayQueryResponseMessage.decode(original.encode())
        assertEquals(1, decoded.relays.size)
        assertEquals(relay.id, decoded.relays[0].id)
        assertTrue(relay.address.contentEquals(decoded.relays[0].address))
    }

    @Test
    fun `response round trip with multiple relays`() {
        val relays = listOf(
            ipv4Contact(nodeId(2), lastOctet = 5),
            ipv4Contact(nodeId(3), lastOctet = 6),
            ipv4Contact(nodeId(4), lastOctet = 7),
        )
        val original = RelayQueryResponseMessage(TransactionId.random(), nodeId(1), relays)
        val decoded = RelayQueryResponseMessage.decode(original.encode())
        assertEquals(relays.map { it.id }, decoded.relays.map { it.id })
    }

    @Test
    fun `response round trip at exactly MAX_RELAYS`() {
        val relays = (0 until RelayQueryResponseMessage.MAX_RELAYS).map { index ->
            ipv4Contact(nodeId(index + 10), lastOctet = index % 250)
        }
        val original = RelayQueryResponseMessage(TransactionId.random(), nodeId(1), relays)
        val decoded = RelayQueryResponseMessage.decode(original.encode())
        assertEquals(RelayQueryResponseMessage.MAX_RELAYS, decoded.relays.size)
        assertEquals(relays.map { it.id }, decoded.relays.map { it.id })
    }

    @Test
    fun `response encode rejects more than MAX_RELAYS`() {
        val relays = (0 until RelayQueryResponseMessage.MAX_RELAYS + 1).map { index ->
            ipv4Contact(nodeId(index + 10), lastOctet = index % 250)
        }
        val message = RelayQueryResponseMessage(TransactionId.random(), nodeId(1), relays)
        assertFailsWith<IllegalArgumentException> { message.encode() }
    }

    @Test
    fun `response decode rejects a truncated header`() {
        val full = RelayQueryResponseMessage(TransactionId.random(), nodeId(1), emptyList()).encode()
        assertFailsWith<DhtMessageDecodeException> {
            RelayQueryResponseMessage.decode(full.copyOfRange(0, full.size - 1))
        }
    }

    @Test
    fun `response decode rejects a declared relayCount exceeding remaining bytes`() {
        val full = RelayQueryResponseMessage(TransactionId.random(), nodeId(1), emptyList()).encode()
        // relayCount byte is the last byte of the (relay-less) header -- bump
        // it to claim a relay that isn't actually present.
        full[full.size - 1] = 1
        assertFailsWith<DhtMessageDecodeException> { RelayQueryResponseMessage.decode(full) }
    }

    @Test
    fun `response decode rejects unknown version`() {
        val bytes = RelayQueryResponseMessage(TransactionId.random(), nodeId(1), emptyList()).encode()
        bytes[0] = 99
        assertFailsWith<DhtMessageDecodeException> { RelayQueryResponseMessage.decode(bytes) }
    }

    @Test
    fun `response decode rejects a non-RELAY_QUERY_RESPONSE type byte`() {
        val bytes = RelayQueryResponseMessage(TransactionId.random(), nodeId(1), emptyList()).encode()
        bytes[1] = DhtMessageType.PONG.wireValue.toByte()
        assertFailsWith<DhtMessageDecodeException> { RelayQueryResponseMessage.decode(bytes) }
    }
}
