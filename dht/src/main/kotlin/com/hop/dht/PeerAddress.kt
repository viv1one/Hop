package com.hop.dht

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Thrown when a byte array cannot be decoded as a valid [PeerAddress]:
 * truncated input, an unknown family byte, or a family/IP-length mismatch.
 */
class PeerAddressDecodeException(message: String) : Exception(message)

/**
 * Resolves [Contact.address]'s opaque `ByteArray` placeholder from Slice 2
 * into a concrete type: an IPv4 or IPv6 address plus port, per Phase 4's
 * stated IPv6-first goal.
 *
 * Tagged binary format, deliberately **not** a `"host:port"` string --
 * IPv6 address strings contain colons, which would reintroduce the exact
 * string-parsing ambiguity this codebase deliberately avoids elsewhere (see
 * `Frame.contentType`'s "never inferred, explicit wire field" posture in
 * `/protocol/WIRE_FORMAT.md`). Wire layout: `[1B family][4 or 16B ip][2B
 * port big-endian]`.
 */
class PeerAddress(val family: Int, val ip: ByteArray, val port: Int) {

    init {
        require(family == FAMILY_IPV4 || family == FAMILY_IPV6) { "family must be $FAMILY_IPV4 or $FAMILY_IPV6, was $family" }
        val expectedIpSize = if (family == FAMILY_IPV4) IPV4_SIZE else IPV6_SIZE
        require(ip.size == expectedIpSize) { "ip size must be $expectedIpSize for family $family, was ${ip.size}" }
        require(port in 0..65535) { "port out of range: $port" }
    }

    /** `[1B family][4 or 16B ip][2B port big-endian]`. */
    fun encode(): ByteArray {
        val buffer = ByteBuffer.allocate(1 + ip.size + 2).order(ByteOrder.BIG_ENDIAN)
        buffer.put(family.toByte())
        buffer.put(ip)
        buffer.putShort(port.toShort())
        return buffer.array()
    }

    fun toInetSocketAddress(): InetSocketAddress = InetSocketAddress(InetAddress.getByAddress(ip), port)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PeerAddress) return false
        return family == other.family && ip.contentEquals(other.ip) && port == other.port
    }

    override fun hashCode(): Int {
        var result = family
        result = 31 * result + ip.contentHashCode()
        result = 31 * result + port
        return result
    }

    override fun toString(): String =
        "PeerAddress(family=$family, ip=${InetAddress.getByAddress(ip).hostAddress}, port=$port)"

    companion object {
        const val FAMILY_IPV4 = 4
        const val FAMILY_IPV6 = 6
        const val IPV4_SIZE = 4
        const val IPV6_SIZE = 16

        /** Smallest valid encoding: IPv4 family, `1 + 4 + 2`. */
        const val MIN_WIRE_SIZE = 1 + IPV4_SIZE + 2

        fun from(address: InetAddress, port: Int): PeerAddress = when (address) {
            is Inet4Address -> PeerAddress(FAMILY_IPV4, address.address, port)
            is Inet6Address -> PeerAddress(FAMILY_IPV6, address.address, port)
            else -> throw PeerAddressDecodeException("Unsupported InetAddress subtype: ${address.javaClass}")
        }

        /**
         * Rejects a truncated input, an unknown family byte, or a length that
         * doesn't match the declared family -- mirrors [DhtMessage.decode]'s
         * versioning/rejection posture.
         */
        fun decode(bytes: ByteArray): PeerAddress {
            if (bytes.size < MIN_WIRE_SIZE) {
                throw PeerAddressDecodeException("Truncated PeerAddress: got ${bytes.size} bytes, need at least $MIN_WIRE_SIZE")
            }
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val family = buffer.get().toInt() and 0xFF
            val ipSize = when (family) {
                FAMILY_IPV4 -> IPV4_SIZE
                FAMILY_IPV6 -> IPV6_SIZE
                else -> throw PeerAddressDecodeException("Unknown PeerAddress family: $family")
            }
            val expectedTotalSize = 1 + ipSize + 2
            if (bytes.size != expectedTotalSize) {
                throw PeerAddressDecodeException(
                    "PeerAddress size mismatch for family $family: got ${bytes.size} bytes, expected $expectedTotalSize"
                )
            }
            val ip = ByteArray(ipSize).also { buffer.get(it) }
            val port = buffer.short.toInt() and 0xFFFF
            return PeerAddress(family, ip, port)
        }
    }
}
