package com.hop.dht

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PeerAddressTest {

    @Test
    fun `IPv4 round trip preserves family, ip, and port`() {
        val original = PeerAddress(PeerAddress.FAMILY_IPV4, byteArrayOf(127, 0, 0, 1), 4222)
        val decoded = PeerAddress.decode(original.encode())
        assertEquals(original, decoded)
        assertEquals(PeerAddress.FAMILY_IPV4, decoded.family)
        assertEquals(4222, decoded.port)
    }

    @Test
    fun `IPv6 round trip preserves family, ip, and port`() {
        val ip = ByteArray(16) { it.toByte() }
        val original = PeerAddress(PeerAddress.FAMILY_IPV6, ip, 51820)
        val decoded = PeerAddress.decode(original.encode())
        assertEquals(original, decoded)
        assertEquals(PeerAddress.FAMILY_IPV6, decoded.family)
        assertEquals(51820, decoded.port)
    }

    @Test
    fun `from() and toInetSocketAddress() round trip through a real InetAddress`() {
        val loopback = InetAddress.getLoopbackAddress()
        val original = PeerAddress.from(loopback, 9999)
        val socketAddress = original.toInetSocketAddress()
        assertEquals(9999, socketAddress.port)
        assertEquals(loopback, socketAddress.address)
    }

    @Test
    fun `constructor rejects unknown family`() {
        assertFailsWith<IllegalArgumentException> { PeerAddress(5, byteArrayOf(1, 2, 3, 4), 1) }
    }

    @Test
    fun `constructor rejects ip size mismatched with family`() {
        assertFailsWith<IllegalArgumentException> { PeerAddress(PeerAddress.FAMILY_IPV4, ByteArray(16), 1) }
        assertFailsWith<IllegalArgumentException> { PeerAddress(PeerAddress.FAMILY_IPV6, ByteArray(4), 1) }
    }

    @Test
    fun `constructor rejects out-of-range port`() {
        assertFailsWith<IllegalArgumentException> { PeerAddress(PeerAddress.FAMILY_IPV4, byteArrayOf(1, 2, 3, 4), -1) }
        assertFailsWith<IllegalArgumentException> { PeerAddress(PeerAddress.FAMILY_IPV4, byteArrayOf(1, 2, 3, 4), 65536) }
    }

    @Test
    fun `decode rejects truncated input`() {
        assertFailsWith<PeerAddressDecodeException> { PeerAddress.decode(ByteArray(3)) }
    }

    @Test
    fun `decode rejects unknown family byte`() {
        val bytes = PeerAddress(PeerAddress.FAMILY_IPV4, byteArrayOf(1, 2, 3, 4), 80).encode()
        bytes[0] = 9 // corrupt the family byte
        assertFailsWith<PeerAddressDecodeException> { PeerAddress.decode(bytes) }
    }

    @Test
    fun `decode rejects a length that does not match the declared family`() {
        // Valid IPv4 family byte (4) but with an IPv6-sized (or otherwise wrong) body.
        val malformed = byteArrayOf(4) + ByteArray(16) + byteArrayOf(0, 80)
        assertFailsWith<PeerAddressDecodeException> { PeerAddress.decode(malformed) }
    }

    // ---- encodeList/decodeList: dual-stack self-announcement ----

    @Test
    fun `encodeList of an empty list decodes back to an empty list`() {
        assertEquals(emptyList(), PeerAddress.decodeList(PeerAddress.encodeList(emptyList())))
        assertEquals(emptyList(), PeerAddress.decodeList(ByteArray(0)))
    }

    @Test
    fun `encodeList-decodeList round trips a single address exactly like encode-decode`() {
        val original = PeerAddress.from(InetAddress.getLoopbackAddress(), 4222)
        val decoded = PeerAddress.decodeList(PeerAddress.encodeList(listOf(original)))
        assertEquals(listOf(original), decoded)
    }

    @Test
    fun `encodeList-decodeList round trips a mixed IPv6-then-IPv4 list in order`() {
        val ipv6 = PeerAddress(PeerAddress.FAMILY_IPV6, ByteArray(16) { it.toByte() }, 51820)
        val ipv4 = PeerAddress(PeerAddress.FAMILY_IPV4, byteArrayOf(10, 0, 0, 1), 4222)
        val original = listOf(ipv6, ipv4)

        val encoded = PeerAddress.encodeList(original)
        // Self-delimiting entries, no extra per-entry length prefix: total
        // size is exactly the sum of each entry's own encode() size.
        assertEquals(ipv6.encode().size + ipv4.encode().size, encoded.size)

        assertEquals(original, PeerAddress.decodeList(encoded))
    }

    @Test
    fun `encodeList-decodeList round trips an IPv4-then-IPv6 list in order`() {
        val ipv4 = PeerAddress(PeerAddress.FAMILY_IPV4, byteArrayOf(10, 0, 0, 1), 4222)
        val ipv6 = PeerAddress(PeerAddress.FAMILY_IPV6, ByteArray(16) { it.toByte() }, 51820)
        val original = listOf(ipv4, ipv6)

        assertEquals(original, PeerAddress.decodeList(PeerAddress.encodeList(original)))
    }

    @Test
    fun `decodeList rejects an unknown family byte mid-list`() {
        val ipv4 = PeerAddress(PeerAddress.FAMILY_IPV4, byteArrayOf(10, 0, 0, 1), 4222)
        val bytes = PeerAddress.encodeList(listOf(ipv4)) + byteArrayOf(9) // a second, corrupt entry
        assertFailsWith<PeerAddressDecodeException> { PeerAddress.decodeList(bytes) }
    }

    @Test
    fun `decodeList rejects a truncated trailing entry`() {
        val ipv6 = PeerAddress(PeerAddress.FAMILY_IPV6, ByteArray(16) { it.toByte() }, 51820)
        val ipv4 = PeerAddress(PeerAddress.FAMILY_IPV4, byteArrayOf(10, 0, 0, 1), 4222)
        val encoded = PeerAddress.encodeList(listOf(ipv6, ipv4))
        // Chop off the last 2 bytes of the second (IPv4) entry -- a
        // truncated trailing entry, not just a truncated whole-input case.
        val truncated = encoded.copyOfRange(0, encoded.size - 2)
        assertFailsWith<PeerAddressDecodeException> { PeerAddress.decodeList(truncated) }
    }
}
