package com.hop.dht

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Encode/decode coverage for [IntroduceRequestMessage]/[IntroduceResponseMessage]/
 * [IntroductionMessage] -- matching [AddressReflectionMessageTest]'s style
 * (round trip, truncated header, unknown version, wrong type byte), plus the
 * found/not-found explicit-flag coverage [IntroduceResponseMessage] shares
 * with [FindValueResponseMessage] (see that message's own test for the
 * precedent).
 */
class IntroductionMessageTest {

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

    // ---- IntroduceRequestMessage ----

    @Test
    fun `request round trip preserves all fields with an IPv4 ownAddress`() {
        val original = IntroduceRequestMessage(
            transactionId = TransactionId.random(),
            senderId = nodeId(1),
            targetId = nodeId(2),
            ownAddress = ipv4Address(5),
        )
        val decoded = IntroduceRequestMessage.decode(original.encode())
        assertEquals(original, decoded)
    }

    @Test
    fun `request round trip preserves all fields with an IPv6 ownAddress`() {
        val original = IntroduceRequestMessage(
            transactionId = TransactionId.random(),
            senderId = nodeId(1),
            targetId = nodeId(2),
            ownAddress = ipv6Address(9),
        )
        val decoded = IntroduceRequestMessage.decode(original.encode())
        assertEquals(original, decoded)
    }

    @Test
    fun `request encodes to 81 bytes for an IPv4 ownAddress and 93 bytes for an IPv6 ownAddress`() {
        val ipv4Request = IntroduceRequestMessage(TransactionId.random(), nodeId(1), nodeId(2), ipv4Address(5))
        val ipv6Request = IntroduceRequestMessage(TransactionId.random(), nodeId(1), nodeId(2), ipv6Address(9))
        assertEquals(81, ipv4Request.encode().size)
        assertEquals(93, ipv6Request.encode().size)
    }

    @Test
    fun `request decode rejects a truncated header`() {
        val full = IntroduceRequestMessage(TransactionId.random(), nodeId(1), nodeId(2), ipv4Address(5)).encode()
        // HEADER_SIZE = 1+1+8+32+32 = 74. Chop below that entirely, not just the trailing address.
        assertFailsWith<DhtMessageDecodeException> {
            IntroduceRequestMessage.decode(full.copyOfRange(0, 73))
        }
    }

    @Test
    fun `request decode rejects a truncated trailing ownAddress`() {
        val full = IntroduceRequestMessage(TransactionId.random(), nodeId(1), nodeId(2), ipv4Address(5)).encode()
        assertFailsWith<DhtMessageDecodeException> {
            IntroduceRequestMessage.decode(full.copyOfRange(0, full.size - 1))
        }
    }

    @Test
    fun `request decode rejects unknown version`() {
        val bytes = IntroduceRequestMessage(TransactionId.random(), nodeId(1), nodeId(2), ipv4Address(5)).encode()
        bytes[0] = 99
        assertFailsWith<DhtMessageDecodeException> { IntroduceRequestMessage.decode(bytes) }
    }

    @Test
    fun `request decode rejects a non-INTRODUCE_REQUEST type byte`() {
        val bytes = IntroduceRequestMessage(TransactionId.random(), nodeId(1), nodeId(2), ipv4Address(5)).encode()
        bytes[1] = DhtMessageType.PING.wireValue.toByte()
        assertFailsWith<DhtMessageDecodeException> { IntroduceRequestMessage.decode(bytes) }
    }

    // ---- IntroduceResponseMessage ----

    @Test
    fun `response found() round trips with an IPv4 address`() {
        val original = IntroduceResponseMessage.found(
            transactionId = TransactionId.random(),
            senderId = nodeId(1),
            address = ipv4Address(5),
        )
        val decoded = IntroduceResponseMessage.decode(original.encode())
        assertEquals(original, decoded)
        assertEquals(true, decoded.found)
        assertEquals(false, decoded.relayFound)
    }

    @Test
    fun `response found() round trips with an IPv6 address`() {
        val original = IntroduceResponseMessage.found(
            transactionId = TransactionId.random(),
            senderId = nodeId(1),
            address = ipv6Address(9),
        )
        val decoded = IntroduceResponseMessage.decode(original.encode())
        assertEquals(original, decoded)
        assertEquals(true, decoded.found)
    }

    @Test
    fun `response notFound() round trips with no address`() {
        val original = IntroduceResponseMessage.notFound(
            transactionId = TransactionId.random(),
            senderId = nodeId(1),
        )
        val decoded = IntroduceResponseMessage.decode(original.encode())
        assertEquals(original, decoded)
        assertEquals(false, decoded.found)
        assertEquals(null, decoded.address)
        assertEquals(false, decoded.relayFound)
    }

    @Test
    fun `response found() round trips with an IPv4 address AND a relay suggestion`() {
        val original = IntroduceResponseMessage.found(
            transactionId = TransactionId.random(),
            senderId = nodeId(1),
            address = ipv4Address(5),
            relayId = nodeId(7),
            relayAddress = ipv4Address(8),
        )
        val decoded = IntroduceResponseMessage.decode(original.encode())
        assertEquals(original, decoded)
        assertEquals(true, decoded.relayFound)
        assertEquals(nodeId(7), decoded.relayId)
        assertEquals(ipv4Address(8), decoded.relayAddress)
    }

    @Test
    fun `response found() round trips with an IPv6 address AND an IPv6 relay suggestion`() {
        val original = IntroduceResponseMessage.found(
            transactionId = TransactionId.random(),
            senderId = nodeId(1),
            address = ipv6Address(9),
            relayId = nodeId(7),
            relayAddress = ipv6Address(11),
        )
        val decoded = IntroduceResponseMessage.decode(original.encode())
        assertEquals(original, decoded)
        assertEquals(true, decoded.relayFound)
    }

    @Test
    fun `response constructor rejects found=true with a null address`() {
        assertFailsWith<IllegalArgumentException> {
            IntroduceResponseMessage(transactionId = TransactionId.random(), senderId = nodeId(1), found = true, address = null)
        }
    }

    @Test
    fun `response constructor rejects found=false with a non-null address`() {
        assertFailsWith<IllegalArgumentException> {
            IntroduceResponseMessage(transactionId = TransactionId.random(), senderId = nodeId(1), found = false, address = ipv4Address(5))
        }
    }

    @Test
    fun `response constructor rejects a relayId without a relayAddress, and vice versa`() {
        assertFailsWith<IllegalArgumentException> {
            IntroduceResponseMessage(
                transactionId = TransactionId.random(), senderId = nodeId(1), found = false, address = null,
                relayFound = true, relayId = nodeId(7), relayAddress = null,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            IntroduceResponseMessage(
                transactionId = TransactionId.random(), senderId = nodeId(1), found = false, address = null,
                relayFound = true, relayId = null, relayAddress = ipv4Address(8),
            )
        }
    }

    @Test
    fun `response encodes to 44 bytes not-found, 51 bytes found-IPv4, 63 bytes found-IPv6, plus 33 or 45 more with an IPv4 or IPv6 relay suggestion`() {
        val notFound = IntroduceResponseMessage.notFound(TransactionId.random(), nodeId(1))
        val foundV4 = IntroduceResponseMessage.found(TransactionId.random(), nodeId(1), ipv4Address(5))
        val foundV6 = IntroduceResponseMessage.found(TransactionId.random(), nodeId(1), ipv6Address(9))
        val foundV4WithV4Relay = IntroduceResponseMessage.found(TransactionId.random(), nodeId(1), ipv4Address(5), nodeId(7), ipv4Address(8))
        val foundV4WithV6Relay = IntroduceResponseMessage.found(TransactionId.random(), nodeId(1), ipv4Address(5), nodeId(7), ipv6Address(11))
        assertEquals(44, notFound.encode().size)
        assertEquals(51, foundV4.encode().size)
        assertEquals(63, foundV6.encode().size)
        // foundV4's own 51 bytes already include the relayFound=0 byte;
        // relayFound=true adds 32 relayId bytes + 7 (IPv4) or 19 (IPv6)
        // relayAddress bytes on top of that (the flag byte itself doesn't
        // grow -- it just flips from 0 to 1).
        assertEquals(51 + 32 + 7, foundV4WithV4Relay.encode().size)
        assertEquals(51 + 32 + 19, foundV4WithV6Relay.encode().size)
    }

    @Test
    fun `response decode rejects a truncated header`() {
        val full = IntroduceResponseMessage.found(TransactionId.random(), nodeId(1), ipv4Address(5)).encode()
        // HEADER_SIZE = 1+1+8+32+1 = 43. Chop below that entirely.
        assertFailsWith<DhtMessageDecodeException> {
            IntroduceResponseMessage.decode(full.copyOfRange(0, 42))
        }
    }

    @Test
    fun `response decode rejects an invalid found byte`() {
        val bytes = IntroduceResponseMessage.notFound(TransactionId.random(), nodeId(1)).encode()
        // The found byte sits right after version+type+transactionId+senderId = 42.
        bytes[42] = 2
        assertFailsWith<DhtMessageDecodeException> { IntroduceResponseMessage.decode(bytes) }
    }

    @Test
    fun `response decode rejects found=false carrying trailing address bytes`() {
        // Craft a not-found header (found byte at offset 42 = 0) but with
        // extra address-shaped bytes spliced in right after it, before the
        // real trailing relayFound=0 byte -- must be rejected, not silently
        // ignored (mirrors TierKeyResponseEnvelope.decode's rejection of a
        // mismatched granted/wrappedCek combination). In practice this
        // fails because the spliced-in bytes get misread as an invalid
        // relayFound byte rather than as "trailing content" per se -- either
        // way, decode() must throw, never silently accept it.
        val notFoundBytes = IntroduceResponseMessage.notFound(TransactionId.random(), nodeId(1)).encode()
        val addressBytes = ipv4Address(5).encode()
        val tampered = notFoundBytes.copyOfRange(0, 43) + addressBytes + notFoundBytes.copyOfRange(43, notFoundBytes.size)
        assertFailsWith<DhtMessageDecodeException> { IntroduceResponseMessage.decode(tampered) }
    }

    @Test
    fun `response decode rejects a truncated trailing address when found=true`() {
        val full = IntroduceResponseMessage.found(TransactionId.random(), nodeId(1), ipv4Address(5)).encode()
        assertFailsWith<DhtMessageDecodeException> {
            // Chop off the trailing relayFound byte AND the last address byte,
            // so the address itself is left truncated.
            IntroduceResponseMessage.decode(full.copyOfRange(0, full.size - 2))
        }
    }

    @Test
    fun `response decode rejects an invalid relayFound byte`() {
        val bytes = IntroduceResponseMessage.notFound(TransactionId.random(), nodeId(1)).encode()
        // relayFound sits right after the not-found header (43) + zero address bytes = offset 43.
        bytes[43] = 2
        assertFailsWith<DhtMessageDecodeException> { IntroduceResponseMessage.decode(bytes) }
    }

    @Test
    fun `response decode rejects relayFound=true with a truncated relayId`() {
        val full = IntroduceResponseMessage.found(
            TransactionId.random(), nodeId(1), ipv4Address(5), nodeId(7), ipv4Address(8),
        ).encode()
        assertFailsWith<DhtMessageDecodeException> {
            IntroduceResponseMessage.decode(full.copyOfRange(0, full.size - 10))
        }
    }

    @Test
    fun `response decode rejects relayFound=false carrying trailing relay bytes`() {
        val found = IntroduceResponseMessage.found(TransactionId.random(), nodeId(1), ipv4Address(5)).encode()
        val tampered = found + nodeId(7).bytes + ipv4Address(8).encode()
        assertFailsWith<DhtMessageDecodeException> { IntroduceResponseMessage.decode(tampered) }
    }

    @Test
    fun `response decode rejects unknown version`() {
        val bytes = IntroduceResponseMessage.found(TransactionId.random(), nodeId(1), ipv4Address(5)).encode()
        bytes[0] = 99
        assertFailsWith<DhtMessageDecodeException> { IntroduceResponseMessage.decode(bytes) }
    }

    @Test
    fun `response decode rejects a non-INTRODUCE_RESPONSE type byte`() {
        val bytes = IntroduceResponseMessage.found(TransactionId.random(), nodeId(1), ipv4Address(5)).encode()
        bytes[1] = DhtMessageType.PONG.wireValue.toByte()
        assertFailsWith<DhtMessageDecodeException> { IntroduceResponseMessage.decode(bytes) }
    }

    // ---- IntroductionMessage ----

    @Test
    fun `introduction round trip preserves all fields with an IPv4 claimedAddress`() {
        val original = IntroductionMessage(
            transactionId = TransactionId.random(),
            senderId = nodeId(1),
            fromId = nodeId(2),
            claimedAddress = ipv4Address(5),
        )
        val decoded = IntroductionMessage.decode(original.encode())
        assertEquals(original, decoded)
    }

    @Test
    fun `introduction round trip preserves all fields with an IPv6 claimedAddress`() {
        val original = IntroductionMessage(
            transactionId = TransactionId.random(),
            senderId = nodeId(1),
            fromId = nodeId(2),
            claimedAddress = ipv6Address(9),
        )
        val decoded = IntroductionMessage.decode(original.encode())
        assertEquals(original, decoded)
    }

    @Test
    fun `introduction round trip preserves all fields with an IPv4 claimedAddress AND a relay suggestion`() {
        val original = IntroductionMessage(
            transactionId = TransactionId.random(),
            senderId = nodeId(1),
            fromId = nodeId(2),
            claimedAddress = ipv4Address(5),
            relayFound = true,
            relayId = nodeId(7),
            relayAddress = ipv4Address(8),
        )
        val decoded = IntroductionMessage.decode(original.encode())
        assertEquals(original, decoded)
        assertEquals(true, decoded.relayFound)
        assertEquals(nodeId(7), decoded.relayId)
        assertEquals(ipv4Address(8), decoded.relayAddress)
    }

    @Test
    fun `introduction round trip preserves all fields with an IPv6 claimedAddress AND an IPv6 relay suggestion`() {
        val original = IntroductionMessage(
            transactionId = TransactionId.random(),
            senderId = nodeId(1),
            fromId = nodeId(2),
            claimedAddress = ipv6Address(9),
            relayFound = true,
            relayId = nodeId(7),
            relayAddress = ipv6Address(11),
        )
        val decoded = IntroductionMessage.decode(original.encode())
        assertEquals(original, decoded)
    }

    @Test
    fun `introduction constructor rejects a relayId without a relayAddress, and vice versa`() {
        assertFailsWith<IllegalArgumentException> {
            IntroductionMessage(
                transactionId = TransactionId.random(), senderId = nodeId(1), fromId = nodeId(2), claimedAddress = ipv4Address(5),
                relayFound = true, relayId = nodeId(7), relayAddress = null,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            IntroductionMessage(
                transactionId = TransactionId.random(), senderId = nodeId(1), fromId = nodeId(2), claimedAddress = ipv4Address(5),
                relayFound = true, relayId = null, relayAddress = ipv4Address(8),
            )
        }
    }

    @Test
    fun `introduction encodes to 82 bytes for an IPv4 claimedAddress and 94 bytes for an IPv6 claimedAddress, plus 33 or 45 more with a relay suggestion`() {
        val ipv4Introduction = IntroductionMessage(TransactionId.random(), nodeId(1), nodeId(2), ipv4Address(5))
        val ipv6Introduction = IntroductionMessage(TransactionId.random(), nodeId(1), nodeId(2), ipv6Address(9))
        val ipv4WithV4Relay = IntroductionMessage(
            TransactionId.random(), nodeId(1), nodeId(2), ipv4Address(5), relayFound = true, relayId = nodeId(7), relayAddress = ipv4Address(8),
        )
        val ipv4WithV6Relay = IntroductionMessage(
            TransactionId.random(), nodeId(1), nodeId(2), ipv4Address(5), relayFound = true, relayId = nodeId(7), relayAddress = ipv6Address(11),
        )
        assertEquals(82, ipv4Introduction.encode().size)
        assertEquals(94, ipv6Introduction.encode().size)
        // ipv4Introduction's own 82 bytes already include the relayFound=0
        // byte; relayFound=true adds 32 relayId bytes + 7 (IPv4) or 19
        // (IPv6) relayAddress bytes on top of that.
        assertEquals(82 + 32 + 7, ipv4WithV4Relay.encode().size)
        assertEquals(82 + 32 + 19, ipv4WithV6Relay.encode().size)
    }

    @Test
    fun `introduction decode rejects a truncated header`() {
        val full = IntroductionMessage(TransactionId.random(), nodeId(1), nodeId(2), ipv4Address(5)).encode()
        // HEADER_SIZE = 1+1+8+32+32 = 74. Chop below that entirely.
        assertFailsWith<DhtMessageDecodeException> {
            IntroductionMessage.decode(full.copyOfRange(0, 73))
        }
    }

    @Test
    fun `introduction decode rejects a truncated trailing claimedAddress`() {
        val full = IntroductionMessage(TransactionId.random(), nodeId(1), nodeId(2), ipv4Address(5)).encode()
        assertFailsWith<DhtMessageDecodeException> {
            // Chop off the trailing relayFound byte AND the last claimedAddress
            // byte, so the address itself is left genuinely truncated (chopping
            // only the very last byte would just remove the mandatory
            // relayFound byte instead, a different failure this test isn't
            // about -- see the dedicated relayFound-byte test below for that).
            IntroductionMessage.decode(full.copyOfRange(0, full.size - 2))
        }
    }

    @Test
    fun `introduction decode rejects a missing or invalid relayFound byte`() {
        val truncated = IntroductionMessage(TransactionId.random(), nodeId(1), nodeId(2), ipv4Address(5)).encode()
        assertFailsWith<DhtMessageDecodeException> {
            // Chop off exactly the trailing relayFound byte -- claimedAddress
            // itself stays intact.
            IntroductionMessage.decode(truncated.copyOfRange(0, truncated.size - 1))
        }

        val tampered = IntroductionMessage(TransactionId.random(), nodeId(1), nodeId(2), ipv4Address(5)).encode()
        tampered[tampered.size - 1] = 2
        assertFailsWith<DhtMessageDecodeException> { IntroductionMessage.decode(tampered) }
    }

    @Test
    fun `introduction decode rejects relayFound=true with a truncated relayId or relayAddress`() {
        val full = IntroductionMessage(
            TransactionId.random(), nodeId(1), nodeId(2), ipv4Address(5), relayFound = true, relayId = nodeId(7), relayAddress = ipv4Address(8),
        ).encode()
        assertFailsWith<DhtMessageDecodeException> {
            IntroductionMessage.decode(full.copyOfRange(0, full.size - 10))
        }
        assertFailsWith<DhtMessageDecodeException> {
            IntroductionMessage.decode(full.copyOfRange(0, full.size - 1))
        }
    }

    @Test
    fun `introduction decode rejects relayFound=false carrying trailing relay bytes`() {
        val noRelay = IntroductionMessage(TransactionId.random(), nodeId(1), nodeId(2), ipv4Address(5)).encode()
        val tampered = noRelay + nodeId(7).bytes + ipv4Address(8).encode()
        assertFailsWith<DhtMessageDecodeException> { IntroductionMessage.decode(tampered) }
    }

    @Test
    fun `introduction decode rejects unknown version`() {
        val bytes = IntroductionMessage(TransactionId.random(), nodeId(1), nodeId(2), ipv4Address(5)).encode()
        bytes[0] = 99
        assertFailsWith<DhtMessageDecodeException> { IntroductionMessage.decode(bytes) }
    }

    @Test
    fun `introduction decode rejects a non-INTRODUCTION type byte`() {
        val bytes = IntroductionMessage(TransactionId.random(), nodeId(1), nodeId(2), ipv4Address(5)).encode()
        bytes[1] = DhtMessageType.PONG.wireValue.toByte()
        assertFailsWith<DhtMessageDecodeException> { IntroductionMessage.decode(bytes) }
    }
}
