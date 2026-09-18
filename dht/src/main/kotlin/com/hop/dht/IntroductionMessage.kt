package com.hop.dht

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * INTRODUCE_REQUEST/INTRODUCE_RESPONSE/INTRODUCTION wire messages: Phase 4's
 * second NAT hole-punching building block, following
 * [AddressReflectionRequestMessage]/[AddressReflectionResponseMessage]'s
 * step (1) "learn your own observed address."
 *
 * **The design decision this file implements** (see BUILD_PLAN.md's Phase 4
 * NAT hole-punching note): true simultaneous-open TCP hole punching -- both
 * NATed peers dialing each other at precisely the same instant so each
 * side's own outbound SYN opens the pinhole the other side's inbound SYN
 * passes through -- needs timing coordination this codebase has no
 * primitive for, and is a bigger, riskier design decision than this slice
 * makes. Instead: **rendezvous-relayed introduction**. A device (A) asks a
 * rendezvous/DHT contact (R) it already talks to, to introduce it to a
 * target (B) it knows of but can't yet reach directly. R tells A whatever
 * address it has on file for B (if any -- [IntroduceResponseMessage]), AND
 * separately, unprompted, tells B about A's address
 * ([IntroductionMessage]). Both A and B then independently attempt an
 * entirely ordinary dial via `p2p/`'s existing `PeerDialer`/
 * `InternetPeerConnection` -- no new dial logic, no timing coordination.
 * Whichever direction a NAT happens to allow through succeeds. This does
 * NOT solve the case where both sides are strictly symmetric NAT -- that
 * remains volunteer relay-node fallback's job, separate and later.
 *
 * **This file builds ONLY the wire-level introduction-relay mechanism.**
 * Nothing here reacts to a received [IntroductionMessage] by attempting an
 * actual connection -- [DhtUdpTransport.onIntroductionReceived] fires a
 * callback and stops there, mirroring this codebase's own established
 * pattern of shipping a wire primitive before its consuming logic (e.g.
 * `PreKeyBundleEnvelope` before its transport wiring, `TierKeyRequestEnvelope`
 * before its automatic requester).
 *
 * Three independent top-level types, same reasoning as every other RPC pair
 * in this module: [INTRODUCE_REQUEST]/[INTRODUCE_RESPONSE]/[INTRODUCTION]
 * (new [DhtMessageType] wire values 10, 11, 12, next free after
 * [DhtMessageType.ADDRESS_REFLECTION_RESPONSE]) are wire-value constants
 * only -- [DhtMessage] itself is never constructed with any of them.
 */

/**
 * `[1B version][1B type=INTRODUCE_REQUEST][8B transactionId][32B senderId]
 * [32B targetId][ownAddress: PeerAddress.encode()]` = 81 bytes (IPv4
 * [ownAddress]) or 93 bytes (IPv6 [ownAddress]).
 *
 * Sent A -> R: "introduce me to [targetId]; here's my own address in case
 * you need to hand it to them." [senderId] is A's id (per this module's
 * standard sender-id convention, used by the receiver's [observe] call).
 * [targetId] is the [NodeId] A wants introduced to -- the peer A already
 * knows *of* (e.g. via `topics/` browse) but can't yet reach directly.
 *
 * **[ownAddress] is genuinely self-reported -- the one deliberate exception
 * to this module's "never self-reported, always observed" hard rule.**
 * Every other address field in this module ([AddressReflectionResponseMessage.reflectedAddress],
 * every [Contact] built from an inbound packet) comes from the packet's own
 * observed UDP source, never from message content. That doesn't work here:
 * R has no independent way to learn A's real internet-facing *reflected*
 * address from this packet alone -- R only observes A's *local* source
 * address on this UDP datagram, which may differ from what A actually
 * learned via [DhtUdpTransport.reflectOwnAddress] if there's yet another NAT
 * layer between A and R (e.g. A is itself behind a second NAT relative to
 * R, or A and R are on the same LAN so R only sees a private address). A
 * must supply its own previously-reflected address explicitly for R to have
 * anything correct to relay to B. **This is a real, accepted limitation, not
 * papered over**: [ownAddress] is exactly as trustworthy as A itself --
 * a dishonest or buggy A could claim any address here, and neither R nor B
 * can verify it from this message alone. R relays it through unmodified
 * ([IntroductionMessage.claimedAddress]), never re-observing or
 * re-validating it, so B inherits the same trust level. This is the same
 * trust extended to every RPC target in this module (a hostile contact can
 * always lie about what it tells you) -- just applied to a field that, for
 * the first time in this module, has no honest alternative to self-report.
 */
data class IntroduceRequestMessage(
    val transactionId: TransactionId,
    val senderId: NodeId,
    val targetId: NodeId,
    val ownAddress: PeerAddress,
) {
    fun encode(): ByteArray {
        val addressBytes = ownAddress.encode()
        val buffer = ByteBuffer.allocate(HEADER_SIZE + addressBytes.size).order(ByteOrder.BIG_ENDIAN)
        buffer.put(DhtMessage.CURRENT_VERSION.toByte())
        buffer.put(DhtMessageType.INTRODUCE_REQUEST.wireValue.toByte())
        buffer.put(transactionId.bytes)
        buffer.put(senderId.bytes)
        buffer.put(targetId.bytes)
        buffer.put(addressBytes)
        return buffer.array()
    }

    companion object {
        private const val HEADER_SIZE = 1 + 1 + TransactionId.SIZE_BYTES + NodeId.SIZE_BYTES + NodeId.SIZE_BYTES

        /**
         * Rejects a truncated header, an unknown version, a type byte other
         * than INTRODUCE_REQUEST, or a malformed/truncated trailing
         * [ownAddress] (converted from [PeerAddressDecodeException] into
         * [DhtMessageDecodeException], same posture as
         * [AddressReflectionResponseMessage.decode]).
         */
        fun decode(bytes: ByteArray): IntroduceRequestMessage {
            if (bytes.size < HEADER_SIZE) {
                throw DhtMessageDecodeException(
                    "Truncated IntroduceRequestMessage: got ${bytes.size} bytes, need at least $HEADER_SIZE for the header"
                )
            }

            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)

            val version = buffer.get().toInt() and 0xFF
            if (version != DhtMessage.CURRENT_VERSION) {
                throw DhtMessageDecodeException(
                    "Unsupported IntroduceRequestMessage version: $version (this decoder only understands version ${DhtMessage.CURRENT_VERSION})"
                )
            }

            val type = DhtMessageType.fromWireValue(buffer.get().toInt() and 0xFF)
            if (type != DhtMessageType.INTRODUCE_REQUEST) {
                throw DhtMessageDecodeException("Expected INTRODUCE_REQUEST type byte, got $type")
            }

            val transactionId = TransactionId(ByteArray(TransactionId.SIZE_BYTES).also { buffer.get(it) })
            val senderId = NodeId(ByteArray(NodeId.SIZE_BYTES).also { buffer.get(it) })
            val targetId = NodeId(ByteArray(NodeId.SIZE_BYTES).also { buffer.get(it) })
            val addressBytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
            val ownAddress = try {
                PeerAddress.decode(addressBytes)
            } catch (e: PeerAddressDecodeException) {
                throw DhtMessageDecodeException("Malformed ownAddress in IntroduceRequestMessage: ${e.message}")
            }

            return IntroduceRequestMessage(transactionId = transactionId, senderId = senderId, targetId = targetId, ownAddress = ownAddress)
        }
    }
}

/**
 * `[1B version][1B type=INTRODUCE_RESPONSE][8B transactionId][32B senderId]
 * [1B found][address: PeerAddress.encode(), present only if found]` = 43
 * bytes (not found), 50 bytes (found, IPv4 address), or 62 bytes (found,
 * IPv6 address).
 *
 * Sent R -> A, the reply [DhtUdpTransport.introduce] awaits. Mirrors
 * [FindValueResponseMessage]'s found/not-found duality: [found]`=true` means
 * R's registry/routing table has a live entry for the requested
 * [IntroduceRequestMessage.targetId] and [address] carries it; [found]`=false`
 * means R doesn't know that target and [address] is absent.
 *
 * [found] is an explicit flag, not inferred from whether trailing address
 * bytes are present -- same reasoning as [TierKeyResponseEnvelope.granted]:
 * an explicit flag byte is this codebase's established convention for
 * exactly this "don't infer state from an adjacent field's shape" case.
 * [address] must be `null` when [found] is `false` -- a not-found answer
 * must never carry address bytes, even stale/leftover ones.
 */
data class IntroduceResponseMessage(
    val transactionId: TransactionId,
    val senderId: NodeId,
    val found: Boolean,
    val address: PeerAddress?,
) {
    init {
        require(found == (address != null)) {
            "address must be non-null iff found=true (found=$found, address=$address)"
        }
    }

    fun encode(): ByteArray {
        val addressBytes = address?.encode() ?: ByteArray(0)
        val buffer = ByteBuffer.allocate(HEADER_SIZE + addressBytes.size).order(ByteOrder.BIG_ENDIAN)
        buffer.put(DhtMessage.CURRENT_VERSION.toByte())
        buffer.put(DhtMessageType.INTRODUCE_RESPONSE.wireValue.toByte())
        buffer.put(transactionId.bytes)
        buffer.put(senderId.bytes)
        buffer.put(if (found) 1.toByte() else 0.toByte())
        buffer.put(addressBytes)
        return buffer.array()
    }

    companion object {
        private const val HEADER_SIZE = 1 + 1 + TransactionId.SIZE_BYTES + NodeId.SIZE_BYTES + 1

        /** Convenience: a found response carrying B's known [address]. */
        fun found(transactionId: TransactionId, senderId: NodeId, address: PeerAddress): IntroduceResponseMessage =
            IntroduceResponseMessage(transactionId = transactionId, senderId = senderId, found = true, address = address)

        /** Convenience: a not-found response, carrying no address. */
        fun notFound(transactionId: TransactionId, senderId: NodeId): IntroduceResponseMessage =
            IntroduceResponseMessage(transactionId = transactionId, senderId = senderId, found = false, address = null)

        /**
         * Rejects a truncated header, an unknown version, a type byte other
         * than INTRODUCE_RESPONSE, an invalid `found` byte, a malformed/
         * truncated trailing address when `found=true`, or -- mirroring
         * [TierKeyResponseEnvelope.decode]'s rejection of a mismatched
         * granted/wrappedCek combination -- trailing bytes present when
         * `found=false` (a not-found answer must never carry address bytes).
         */
        fun decode(bytes: ByteArray): IntroduceResponseMessage {
            if (bytes.size < HEADER_SIZE) {
                throw DhtMessageDecodeException(
                    "Truncated IntroduceResponseMessage: got ${bytes.size} bytes, need at least $HEADER_SIZE for the header"
                )
            }

            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)

            val version = buffer.get().toInt() and 0xFF
            if (version != DhtMessage.CURRENT_VERSION) {
                throw DhtMessageDecodeException(
                    "Unsupported IntroduceResponseMessage version: $version (this decoder only understands version ${DhtMessage.CURRENT_VERSION})"
                )
            }

            val type = DhtMessageType.fromWireValue(buffer.get().toInt() and 0xFF)
            if (type != DhtMessageType.INTRODUCE_RESPONSE) {
                throw DhtMessageDecodeException("Expected INTRODUCE_RESPONSE type byte, got $type")
            }

            val transactionId = TransactionId(ByteArray(TransactionId.SIZE_BYTES).also { buffer.get(it) })
            val senderId = NodeId(ByteArray(NodeId.SIZE_BYTES).also { buffer.get(it) })

            val foundByte = buffer.get().toInt() and 0xFF
            val found = when (foundByte) {
                0 -> false
                1 -> true
                else -> throw DhtMessageDecodeException("Invalid found byte in IntroduceResponseMessage: $foundByte (expected 0 or 1)")
            }

            val addressBytes = ByteArray(buffer.remaining()).also { buffer.get(it) }

            if (!found) {
                if (addressBytes.isNotEmpty()) {
                    throw DhtMessageDecodeException(
                        "Malformed IntroduceResponseMessage: found=false but ${addressBytes.size} trailing address bytes are present"
                    )
                }
                return IntroduceResponseMessage(transactionId = transactionId, senderId = senderId, found = false, address = null)
            }

            val address = try {
                PeerAddress.decode(addressBytes)
            } catch (e: PeerAddressDecodeException) {
                throw DhtMessageDecodeException("Malformed address in IntroduceResponseMessage: ${e.message}")
            }
            return IntroduceResponseMessage(transactionId = transactionId, senderId = senderId, found = true, address = address)
        }
    }
}

/**
 * `[1B version][1B type=INTRODUCTION][8B transactionId][32B senderId]
 * [32B fromId][claimedAddress: PeerAddress.encode()]` = 81 bytes (IPv4
 * [claimedAddress]) or 93 bytes (IPv6 [claimedAddress]).
 *
 * Sent R -> B via [DhtUdpTransport.sendUnsolicited] -- fire-and-forget, no
 * response expected or awaited. [senderId] is R's own id, per this module's
 * standard sender-id convention (used by the receiver's ordinary [observe]
 * call for R itself, since this packet genuinely, observably arrived from
 * R). [fromId] and [claimedAddress] describe A: [fromId] is A's [NodeId]
 * (echoed from [IntroduceRequestMessage.senderId]) and [claimedAddress] is
 * A's self-reported reflected address, relayed through from
 * [IntroduceRequestMessage.ownAddress] **unmodified, not re-observed** -- R
 * has no way to independently verify it either (see that field's own doc for
 * why). B must treat [claimedAddress] with exactly that trust level: this is
 * A telling R (relayed via R) "try me here," not a verified fact.
 */
data class IntroductionMessage(
    val transactionId: TransactionId,
    val senderId: NodeId,
    val fromId: NodeId,
    val claimedAddress: PeerAddress,
) {
    fun encode(): ByteArray {
        val addressBytes = claimedAddress.encode()
        val buffer = ByteBuffer.allocate(HEADER_SIZE + addressBytes.size).order(ByteOrder.BIG_ENDIAN)
        buffer.put(DhtMessage.CURRENT_VERSION.toByte())
        buffer.put(DhtMessageType.INTRODUCTION.wireValue.toByte())
        buffer.put(transactionId.bytes)
        buffer.put(senderId.bytes)
        buffer.put(fromId.bytes)
        buffer.put(addressBytes)
        return buffer.array()
    }

    companion object {
        private const val HEADER_SIZE = 1 + 1 + TransactionId.SIZE_BYTES + NodeId.SIZE_BYTES + NodeId.SIZE_BYTES

        /**
         * Rejects a truncated header, an unknown version, a type byte other
         * than INTRODUCTION, or a malformed/truncated trailing
         * [claimedAddress].
         */
        fun decode(bytes: ByteArray): IntroductionMessage {
            if (bytes.size < HEADER_SIZE) {
                throw DhtMessageDecodeException(
                    "Truncated IntroductionMessage: got ${bytes.size} bytes, need at least $HEADER_SIZE for the header"
                )
            }

            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)

            val version = buffer.get().toInt() and 0xFF
            if (version != DhtMessage.CURRENT_VERSION) {
                throw DhtMessageDecodeException(
                    "Unsupported IntroductionMessage version: $version (this decoder only understands version ${DhtMessage.CURRENT_VERSION})"
                )
            }

            val type = DhtMessageType.fromWireValue(buffer.get().toInt() and 0xFF)
            if (type != DhtMessageType.INTRODUCTION) {
                throw DhtMessageDecodeException("Expected INTRODUCTION type byte, got $type")
            }

            val transactionId = TransactionId(ByteArray(TransactionId.SIZE_BYTES).also { buffer.get(it) })
            val senderId = NodeId(ByteArray(NodeId.SIZE_BYTES).also { buffer.get(it) })
            val fromId = NodeId(ByteArray(NodeId.SIZE_BYTES).also { buffer.get(it) })
            val addressBytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
            val claimedAddress = try {
                PeerAddress.decode(addressBytes)
            } catch (e: PeerAddressDecodeException) {
                throw DhtMessageDecodeException("Malformed claimedAddress in IntroductionMessage: ${e.message}")
            }

            return IntroductionMessage(transactionId = transactionId, senderId = senderId, fromId = fromId, claimedAddress = claimedAddress)
        }
    }
}
