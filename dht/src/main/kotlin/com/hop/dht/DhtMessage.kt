package com.hop.dht

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Thrown when a byte array cannot be decoded as a valid [DhtMessage]: wrong
 * length, unknown/future version, or unknown type. Decoding must fail loudly
 * rather than silently misparse -- mirrors `Frame.decode`'s exact posture
 * (`/protocol/WIRE_FORMAT.md`'s hard versioning convention, applied here even
 * though this wire format lives in `dht/`, not `protocol/`).
 */
class DhtMessageDecodeException(message: String) : Exception(message)

/**
 * The RPC types across every message kind in this module -- shared because
 * every wire format here (this file's [DhtMessage] and, as of Slice 4,
 * `FindNodeMessage.kt`'s independent `FindNodeRequestMessage`/
 * `FindNodeResponseMessage`, as of Slice 5, `StoreMessage.kt`'s
 * `StoreRequestMessage` and `FindValueMessage.kt`'s independent
 * `FindValueRequestMessage`/`FindValueResponseMessage`, as of Phase 4's NAT
 * hole-punching address-self-discovery slice, `AddressReflectionMessage.kt`'s
 * independent `AddressReflectionRequestMessage`/`AddressReflectionResponseMessage`,
 * and as of Phase 4's rendezvous-relayed-introduction slice,
 * `IntroductionMessage.kt`'s independent `IntroduceRequestMessage`/
 * `IntroduceResponseMessage`/`IntroductionMessage`) puts its type byte at
 * the same fixed offset 1 (`[1B version][1B type]...`), letting a receiver
 * dispatch on this one byte before choosing which type's decoder to invoke.
 *
 * FIND_NODE_REQUEST/FIND_NODE_RESPONSE/STORE_REQUEST/FIND_VALUE_REQUEST/
 * FIND_VALUE_RESPONSE/ADDRESS_REFLECTION_REQUEST/ADDRESS_REFLECTION_RESPONSE/
 * INTRODUCE_REQUEST/INTRODUCE_RESPONSE/INTRODUCTION are wire-value constants
 * only -- [DhtMessage] itself (this class's `encode`/`decode`, its fixed
 * 42-byte [WIRE_SIZE]) is never constructed with those ten type values;
 * their actual encode/decode logic lives entirely in each request/response
 * type's own file, deliberately not a retrofit of [DhtMessage] into a sealed
 * hierarchy.
 *
 * STORE_RESPONSE is one exception: it *is* constructed as a plain
 * [DhtMessage] (the same bare-ack shape as PONG) -- a STORE ack carries
 * nothing beyond "acknowledged," unlike FIND_VALUE_RESPONSE, which must
 * distinguish holders-found from closer-routing-candidates and so needs its
 * own type. See `StoreRequestMessage`'s own doc for that contrast.
 * RELAY_ANNOUNCE_ACK (wire value 14) is the other exception, same reasoning:
 * see `RelayDirectoryMessage.kt`'s own file doc.
 * ADDRESS_REFLECTION_REQUEST's payload shape is *also* bare (identical to
 * PING's), but it still gets its own dedicated
 * `AddressReflectionRequestMessage` type rather than reusing [DhtMessage]
 * directly -- see that class's own doc for why a distinct type byte (and
 * therefore a distinct class, per this enum's own convention) is still
 * required even though the wire bytes carry nothing beyond
 * transactionId/senderId.
 *
 * INTRODUCE_REQUEST/INTRODUCE_RESPONSE/INTRODUCTION (wire values 10-12) are
 * Phase 4's rendezvous-relayed-introduction primitive -- see
 * `IntroductionMessage.kt`'s own file doc for the design decision this
 * implements (rendezvous-relayed introduction, not simultaneous-open TCP
 * hole punching) and its scope (wire-level relay mechanism only; nothing
 * here attempts an actual connection on receipt of an INTRODUCTION).
 *
 * RELAY_ANNOUNCE/RELAY_ANNOUNCE_ACK/RELAY_QUERY/RELAY_QUERY_RESPONSE (wire
 * values 13-16) are Phase 4's volunteer relay-node discovery primitive --
 * see `RelayDirectoryMessage.kt`'s own file doc for the design decision this
 * implements (why this can't reuse STORE_REQUEST/FIND_VALUE_REQUEST) and its
 * scope (wire-level discovery only; the client-side "fall back to a
 * discovered relay" orchestration is separate, later work). RELAY_ANNOUNCE_ACK
 * follows STORE_RESPONSE's own precedent: it IS constructed as a plain
 * [DhtMessage] (a bare ack carries nothing beyond "registered"), unlike the
 * other three, whose actual encode/decode logic lives in their own file, same
 * as every other request/response type in this module.
 */
enum class DhtMessageType(val wireValue: Int) {
    PING(0),
    PONG(1),
    FIND_NODE_REQUEST(2),
    FIND_NODE_RESPONSE(3),
    STORE_REQUEST(4),
    STORE_RESPONSE(5),
    FIND_VALUE_REQUEST(6),
    FIND_VALUE_RESPONSE(7),
    ADDRESS_REFLECTION_REQUEST(8),
    ADDRESS_REFLECTION_RESPONSE(9),
    INTRODUCE_REQUEST(10),
    INTRODUCE_RESPONSE(11),
    INTRODUCTION(12),
    RELAY_ANNOUNCE(13),
    RELAY_ANNOUNCE_ACK(14),
    RELAY_QUERY(15),
    RELAY_QUERY_RESPONSE(16),
    ;

    companion object {
        fun fromWireValue(value: Int): DhtMessageType =
            values().find { it.wireValue == value }
                ?: throw DhtMessageDecodeException("Unknown DhtMessage type: $value")
    }
}

/**
 * The DHT liveness-RPC wire message: a fixed-size 42-byte PING or PONG.
 *
 * Both message types are the same fixed size on purpose -- a PONG is never
 * larger than the PING that provoked it, so this format carries no UDP
 * amplification/reflection concern (a common pitfall for UDP-based services);
 * no mitigation is needed this slice.
 *
 * Deliberately carries no address field: a contact's stored address must
 * always come from the UDP packet's *observed* source address
 * (`DatagramPacket.address`/`.port`), never a self-reported field inside the
 * message -- see [DhtUdpTransport]'s own doc for where that rule is enforced.
 */
data class DhtMessage(
    val type: DhtMessageType,
    val transactionId: TransactionId,
    val senderId: NodeId,
) {
    /** `[1B version][1B type][8B transactionId][32B senderId]`. */
    fun encode(): ByteArray {
        val buffer = ByteBuffer.allocate(WIRE_SIZE).order(ByteOrder.BIG_ENDIAN)
        buffer.put(CURRENT_VERSION.toByte())
        buffer.put(type.wireValue.toByte())
        buffer.put(transactionId.bytes)
        buffer.put(senderId.bytes)
        return buffer.array()
    }

    companion object {
        const val CURRENT_VERSION = 1
        const val WIRE_SIZE = 1 + 1 + TransactionId.SIZE_BYTES + NodeId.SIZE_BYTES

        /**
         * Rejects wrong length, unknown version, unknown type -- mirrors
         * `Frame.decode`'s exact posture (`/protocol/WIRE_FORMAT.md`'s hard
         * versioning convention, applied here even though this format lives
         * in `dht/`, not `protocol/`).
         */
        fun decode(bytes: ByteArray): DhtMessage {
            if (bytes.size != WIRE_SIZE) {
                throw DhtMessageDecodeException("DhtMessage must be $WIRE_SIZE bytes, was ${bytes.size}")
            }

            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)

            val type = DhtMessageHeader.readVersionAndType(buffer, messageTypeName = "DhtMessage")
            val transactionId = TransactionId(ByteArray(TransactionId.SIZE_BYTES).also { buffer.get(it) })
            val senderId = NodeId(ByteArray(NodeId.SIZE_BYTES).also { buffer.get(it) })

            return DhtMessage(type = type, transactionId = transactionId, senderId = senderId)
        }
    }
}

/**
 * Shared wire-format helpers every message kind in this module's own
 * `decode()` otherwise hand-rolled an independent copy of -- factored out
 * here (`DhtMessage.kt`, the canonical home for the shared
 * `[1B version][1B type]` header convention every message in this module
 * agrees on, per [DhtMessageType]'s own doc) so a future message kind can't
 * silently drift out of sync with how every existing one validates its
 * header or rewraps a malformed trailing [PeerAddress].
 *
 * Additive-only refactor: no caller's accepted/rejected input, wire byte
 * layout, or error message text changes -- only where the logic that
 * produces that text lives. Each message kind's own *length* invariant
 * (fixed [WIRE_SIZE] vs. "at least `HEADER_SIZE`") is still that class's own
 * responsibility, checked before either helper below is ever called; only
 * the version/type-byte parsing that follows, and the address-decode rewrap
 * that some message kinds need afterward, is now shared.
 */
internal object DhtMessageHeader {
    /**
     * Reads the leading `[1B version][1B type]` fields from [buffer]
     * (already positioned at offset 0) and returns the decoded
     * [DhtMessageType] -- rejecting an unsupported version exactly as every
     * caller's own prior inline check did: `"Unsupported $messageTypeName
     * version: $version (this decoder only understands version
     * ${DhtMessage.CURRENT_VERSION})"`.
     *
     * Performs no type-match check of its own -- [DhtMessage.decode] is the
     * one caller that genuinely needs the decoded type back without
     * asserting it's any one specific value (a [DhtMessage] can legitimately
     * be PING, PONG, or STORE_RESPONSE). Every other message kind in this
     * module calls [requireVersionAndType] instead, which adds that
     * additional check.
     */
    fun readVersionAndType(buffer: ByteBuffer, messageTypeName: String): DhtMessageType {
        val version = buffer.get().toInt() and 0xFF
        if (version != DhtMessage.CURRENT_VERSION) {
            throw DhtMessageDecodeException(
                "Unsupported $messageTypeName version: $version (this decoder only understands version ${DhtMessage.CURRENT_VERSION})"
            )
        }
        return DhtMessageType.fromWireValue(buffer.get().toInt() and 0xFF)
    }

    /**
     * [readVersionAndType] plus the "the type byte must be exactly this one"
     * check every message kind besides [DhtMessage] itself also performs --
     * rejecting a mismatch exactly as each caller's own prior inline check
     * did: `"Expected $expectedType type byte, got $type"`.
     */
    fun requireVersionAndType(buffer: ByteBuffer, messageTypeName: String, expectedType: DhtMessageType) {
        val type = readVersionAndType(buffer, messageTypeName)
        if (type != expectedType) {
            throw DhtMessageDecodeException("Expected $expectedType type byte, got $type")
        }
    }

    /**
     * Decodes [addressBytes] as a [PeerAddress], rewrapping a
     * [PeerAddressDecodeException] into a [DhtMessageDecodeException] -- the
     * same "malformed trailing address" idiom
     * [AddressReflectionResponseMessage], [IntroduceRequestMessage],
     * [IntroduceResponseMessage], and [IntroductionMessage] each otherwise
     * hand-rolled independently. [fieldName] and [messageTypeName] are folded
     * into the rewrapped exception's message exactly as each caller's own
     * prior inline text: `"Malformed $fieldName in $messageTypeName:
     * ${e.message}"`.
     */
    fun decodePeerAddress(addressBytes: ByteArray, fieldName: String, messageTypeName: String): PeerAddress =
        try {
            PeerAddress.decode(addressBytes)
        } catch (e: PeerAddressDecodeException) {
            throw DhtMessageDecodeException("Malformed $fieldName in $messageTypeName: ${e.message}")
        }

    /**
     * Reads exactly ONE [PeerAddress] entry starting at [buffer]'s current
     * position, advancing the buffer past that entry only -- unlike
     * [decodePeerAddress], which consumes an entire caller-supplied
     * [ByteArray] and requires every byte in it to belong to that one
     * address (only correct when the address is the last field in a
     * message). Needed as of Phase 4's relay-fallback-coordination slice:
     * [IntroduceResponseMessage.address]/[IntroductionMessage.claimedAddress]
     * are no longer necessarily the last field once an optional trailing
     * relay suggestion follows them, so "consume everything left in the
     * buffer" is no longer a valid way to bound the address's own bytes.
     *
     * Relies on [PeerAddress]'s own self-delimiting wire shape (its leading
     * family byte alone determines whether the whole entry is
     * [PeerAddress.MIN_WIRE_SIZE] (7, IPv4) or `1 + PeerAddress.IPV6_SIZE + 2`
     * (19, IPv6) bytes -- the same fact [PeerAddress.decodeList] already
     * relies on to walk a concatenated list of entries): peeks that one
     * leading byte (via [ByteBuffer.get(index)][java.nio.ByteBuffer.get],
     * which does NOT advance the buffer's position), decides the entry's
     * total length from it, then reads exactly that many bytes and decodes
     * them via [decodePeerAddress].
     *
     * Throws [DhtMessageDecodeException] if [buffer] has no bytes remaining
     * for even the leading family byte, an unrecognized family byte, or
     * fewer bytes remaining than the family byte's declared entry length
     * requires -- same rejection posture as every other malformed-input case
     * in this module, rewrapped from [PeerAddressDecodeException] exactly
     * like [decodePeerAddress] already does.
     */
    fun decodePeerAddressFromBuffer(buffer: ByteBuffer, fieldName: String, messageTypeName: String): PeerAddress {
        if (!buffer.hasRemaining()) {
            throw DhtMessageDecodeException("Truncated $fieldName in $messageTypeName: no bytes remaining for the leading family byte")
        }
        val family = buffer.get(buffer.position()).toInt() and 0xFF
        val ipSize = when (family) {
            PeerAddress.FAMILY_IPV4 -> PeerAddress.IPV4_SIZE
            PeerAddress.FAMILY_IPV6 -> PeerAddress.IPV6_SIZE
            else -> throw DhtMessageDecodeException("Unknown PeerAddress family in $fieldName of $messageTypeName: $family")
        }
        val entrySize = 1 + ipSize + 2
        if (buffer.remaining() < entrySize) {
            throw DhtMessageDecodeException(
                "Truncated $fieldName in $messageTypeName: family $family needs $entrySize bytes, only ${buffer.remaining()} remain"
            )
        }
        val entryBytes = ByteArray(entrySize).also { buffer.get(it) }
        return decodePeerAddress(entryBytes, fieldName = fieldName, messageTypeName = messageTypeName)
    }
}
