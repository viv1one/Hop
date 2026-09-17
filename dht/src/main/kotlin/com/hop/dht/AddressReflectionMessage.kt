package com.hop.dht

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ADDRESS_REFLECTION request/response wire messages: "what address did you
 * observe this packet arriving from?" -- the classic STUN-style
 * self-address-discovery primitive, and Phase 4's first NAT hole-punching
 * building block (see BUILD_PLAN.md's Phase 4 sequencing note on
 * [DhtUdpTransport]'s own class doc).
 *
 * This is deliberately step (1) only -- "a device learns its OWN
 * public-facing address as observed from outside its NAT" -- never the
 * harder, timing-sensitive peer-introduction/simultaneous-connect signaling
 * step. That's separate, later work; nothing here assumes it exists yet.
 *
 * Two independent top-level types, same reasoning as [FindNodeRequestMessage]/
 * [FindNodeResponseMessage] and [StoreRequestMessage]/[FindValueRequestMessage]/
 * [FindValueResponseMessage]: [ADDRESS_REFLECTION_REQUEST]/
 * [ADDRESS_REFLECTION_RESPONSE] (new [DhtMessageType] wire values 8 and 9,
 * next free after [DhtMessageType.FIND_VALUE_RESPONSE]) are wire-value
 * constants only -- [DhtMessage] itself is never constructed with either
 * value, matching every other non-bare-ack RPC pair in this module.
 *
 * **Why the request isn't just a reused bare [DhtMessage] (like PING), even
 * though its payload shape is identical:** the responder side needs a
 * distinct type byte to dispatch on in [DhtUdpTransport.handlePacket] (a PING
 * must still get a PONG, not an address-reflection response) -- so a distinct
 * [DhtMessageType] value is required regardless, and once that's true, giving
 * it its own named class (mirroring every other request type in this module)
 * is clearer than overloading [DhtMessage]'s generic constructor with a type
 * value its own doc says is reserved for PING/PONG/STORE_RESPONSE.
 */

/**
 * `[1B version][1B type=ADDRESS_REFLECTION_REQUEST][8B transactionId][32B senderId]`
 * = 42 bytes fixed -- the identical bare-ack shape as [DhtMessage]'s PING,
 * carrying nothing beyond "who's asking," since the responder needs nothing
 * else: it answers unconditionally with the packet's own observed source
 * address (see [DhtUdpTransport.handlePacket]'s `ADDRESS_REFLECTION_REQUEST`
 * case), never anything the requester supplies.
 */
data class AddressReflectionRequestMessage(
    val transactionId: TransactionId,
    val senderId: NodeId,
) {
    fun encode(): ByteArray {
        val buffer = ByteBuffer.allocate(WIRE_SIZE).order(ByteOrder.BIG_ENDIAN)
        buffer.put(DhtMessage.CURRENT_VERSION.toByte())
        buffer.put(DhtMessageType.ADDRESS_REFLECTION_REQUEST.wireValue.toByte())
        buffer.put(transactionId.bytes)
        buffer.put(senderId.bytes)
        return buffer.array()
    }

    companion object {
        const val WIRE_SIZE = 1 + 1 + TransactionId.SIZE_BYTES + NodeId.SIZE_BYTES

        /** Rejects wrong length, unknown version, or a type byte other than ADDRESS_REFLECTION_REQUEST. */
        fun decode(bytes: ByteArray): AddressReflectionRequestMessage {
            if (bytes.size != WIRE_SIZE) {
                throw DhtMessageDecodeException("AddressReflectionRequestMessage must be $WIRE_SIZE bytes, was ${bytes.size}")
            }

            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)

            val version = buffer.get().toInt() and 0xFF
            if (version != DhtMessage.CURRENT_VERSION) {
                throw DhtMessageDecodeException(
                    "Unsupported AddressReflectionRequestMessage version: $version (this decoder only understands version ${DhtMessage.CURRENT_VERSION})"
                )
            }

            val type = DhtMessageType.fromWireValue(buffer.get().toInt() and 0xFF)
            if (type != DhtMessageType.ADDRESS_REFLECTION_REQUEST) {
                throw DhtMessageDecodeException("Expected ADDRESS_REFLECTION_REQUEST type byte, got $type")
            }

            val transactionId = TransactionId(ByteArray(TransactionId.SIZE_BYTES).also { buffer.get(it) })
            val senderId = NodeId(ByteArray(NodeId.SIZE_BYTES).also { buffer.get(it) })

            return AddressReflectionRequestMessage(transactionId = transactionId, senderId = senderId)
        }
    }
}

/**
 * `[1B version][1B type=ADDRESS_REFLECTION_RESPONSE][8B transactionId][32B senderId][reflectedAddress: PeerAddress.encode()]`
 * = 49 bytes (IPv4 reflected address) or 61 bytes (IPv6 reflected address).
 *
 * [reflectedAddress] is the address the *responder* observed the *request*
 * arriving from -- i.e. exactly [DhtUdpTransport.handlePacket]'s
 * `observedAddress` local, echoed back. This is the entire mechanism: the
 * requester never supplies its own address anywhere in
 * [AddressReflectionRequestMessage] for the responder to merely parrot back,
 * so [reflectedAddress] is genuinely observed, never self-reported --
 * [DhtUdpTransport]'s hard rule, extended to this RPC pair's payload as well
 * as its `onMessageObserved` bookkeeping.
 *
 * No explicit length prefix for [reflectedAddress] is needed -- unlike
 * [FindNodeResponseMessage]'s per-contact `addressLength` byte (necessary
 * there because a contact list can hold a variable *number* of variable-
 * length entries), this message carries exactly one [PeerAddress], and
 * [PeerAddress.encode]'s own leading family byte already makes it
 * self-delimiting: whatever bytes remain after the fixed header are handed
 * directly to [PeerAddress.decode], which itself enforces an exact
 * length match for the family it declares.
 */
data class AddressReflectionResponseMessage(
    val transactionId: TransactionId,
    val senderId: NodeId,
    val reflectedAddress: PeerAddress,
) {
    fun encode(): ByteArray {
        val addressBytes = reflectedAddress.encode()
        val buffer = ByteBuffer.allocate(HEADER_SIZE + addressBytes.size).order(ByteOrder.BIG_ENDIAN)
        buffer.put(DhtMessage.CURRENT_VERSION.toByte())
        buffer.put(DhtMessageType.ADDRESS_REFLECTION_RESPONSE.wireValue.toByte())
        buffer.put(transactionId.bytes)
        buffer.put(senderId.bytes)
        buffer.put(addressBytes)
        return buffer.array()
    }

    companion object {
        private const val HEADER_SIZE = 1 + 1 + TransactionId.SIZE_BYTES + NodeId.SIZE_BYTES

        /**
         * Rejects a truncated header, an unknown version, a type byte other
         * than ADDRESS_REFLECTION_RESPONSE, or a malformed/truncated trailing
         * [PeerAddress] (converted from [PeerAddressDecodeException] into
         * [DhtMessageDecodeException] so every caller of this decoder --
         * including [DhtUdpTransport.reflectOwnAddress] -- can keep catching
         * one exception type for "not a trustworthy response," matching this
         * module's other four RPCs' own catch-and-return-null posture).
         */
        fun decode(bytes: ByteArray): AddressReflectionResponseMessage {
            if (bytes.size < HEADER_SIZE) {
                throw DhtMessageDecodeException(
                    "Truncated AddressReflectionResponseMessage: got ${bytes.size} bytes, need at least $HEADER_SIZE for the header"
                )
            }

            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)

            val version = buffer.get().toInt() and 0xFF
            if (version != DhtMessage.CURRENT_VERSION) {
                throw DhtMessageDecodeException(
                    "Unsupported AddressReflectionResponseMessage version: $version (this decoder only understands version ${DhtMessage.CURRENT_VERSION})"
                )
            }

            val type = DhtMessageType.fromWireValue(buffer.get().toInt() and 0xFF)
            if (type != DhtMessageType.ADDRESS_REFLECTION_RESPONSE) {
                throw DhtMessageDecodeException("Expected ADDRESS_REFLECTION_RESPONSE type byte, got $type")
            }

            val transactionId = TransactionId(ByteArray(TransactionId.SIZE_BYTES).also { buffer.get(it) })
            val senderId = NodeId(ByteArray(NodeId.SIZE_BYTES).also { buffer.get(it) })
            val addressBytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
            val reflectedAddress = try {
                PeerAddress.decode(addressBytes)
            } catch (e: PeerAddressDecodeException) {
                throw DhtMessageDecodeException("Malformed reflectedAddress in AddressReflectionResponseMessage: ${e.message}")
            }

            return AddressReflectionResponseMessage(
                transactionId = transactionId,
                senderId = senderId,
                reflectedAddress = reflectedAddress,
            )
        }
    }
}
