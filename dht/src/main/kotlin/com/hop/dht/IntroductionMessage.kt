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

            DhtMessageHeader.requireVersionAndType(
                buffer,
                messageTypeName = "IntroduceRequestMessage",
                expectedType = DhtMessageType.INTRODUCE_REQUEST,
            )

            val transactionId = TransactionId(ByteArray(TransactionId.SIZE_BYTES).also { buffer.get(it) })
            val senderId = NodeId(ByteArray(NodeId.SIZE_BYTES).also { buffer.get(it) })
            val targetId = NodeId(ByteArray(NodeId.SIZE_BYTES).also { buffer.get(it) })
            val addressBytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
            val ownAddress = DhtMessageHeader.decodePeerAddress(
                addressBytes,
                fieldName = "ownAddress",
                messageTypeName = "IntroduceRequestMessage",
            )

            return IntroduceRequestMessage(transactionId = transactionId, senderId = senderId, targetId = targetId, ownAddress = ownAddress)
        }
    }
}

/**
 * `[1B version][1B type=INTRODUCE_RESPONSE][8B transactionId][32B senderId]
 * [1B found][address: PeerAddress.encode(), present only if found]
 * [1B relayFound][32B relayId][relayAddress: PeerAddress.encode(), the
 * latter two present only if relayFound]` = 44 bytes (not found, no relay),
 * up to 63 bytes (found IPv6 address + relayFound IPv6 relayAddress) minimum
 * 44 bytes, see [IntroductionMessageTest] for the exact byte counts for
 * every found/relayFound x IPv4/IPv6 combination.
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
 *
 * **[relayFound]/[relayId]/[relayAddress] -- Phase 4's relay-fallback-
 * coordination addition.** The real gap volunteer-relay-node discovery
 * (`RelayDirectoryMessage.kt`) left open on its own: two peers each
 * independently querying RELAY_QUERY for "some relay" can land on two
 * *different* relays and silently never converge, with no way to even
 * detect the mismatch. The fix: R -- the same node relaying this
 * introduction -- also picks ONE relay from its own `RelayDirectory` (see
 * [DhtUdpTransport.handlePacket]'s `INTRODUCE_REQUEST` case) and hands the
 * IDENTICAL choice to both A (here, in this response) and B (in the
 * [IntroductionMessage] sent alongside it) -- guaranteeing agreement because
 * it's the same decision, made once, by R, not two independent ones. Same
 * explicit-flag convention as [found]/[address]: [relayFound]`=false` means
 * R's `RelayDirectory` had no live relay to suggest (or this response is
 * itself `found=false`, in which case there is no B to coordinate a relay
 * choice with in the first place -- see [DhtUdpTransport.handlePacket]'s own
 * doc), never inferred from [relayId]/[relayAddress] being absent.
 * [relayId] and [relayAddress] are exactly as trustworthy as whichever relay
 * originally announced them to R -- see `RelayAnnounceRequestMessage`'s own
 * doc for that limitation, unchanged by this message carrying them through.
 */
data class IntroduceResponseMessage(
    val transactionId: TransactionId,
    val senderId: NodeId,
    val found: Boolean,
    val address: PeerAddress?,
    val relayFound: Boolean = false,
    val relayId: NodeId? = null,
    val relayAddress: PeerAddress? = null,
) {
    init {
        require(found == (address != null)) {
            "address must be non-null iff found=true (found=$found, address=$address)"
        }
        require(relayFound == (relayId != null)) {
            "relayId must be non-null iff relayFound=true (relayFound=$relayFound, relayId=$relayId)"
        }
        require(relayFound == (relayAddress != null)) {
            "relayAddress must be non-null iff relayFound=true (relayFound=$relayFound, relayAddress=$relayAddress)"
        }
    }

    fun encode(): ByteArray {
        val addressBytes = address?.encode() ?: ByteArray(0)
        val relayIdBytes = if (relayFound) relayId!!.bytes else ByteArray(0)
        val relayAddressBytes = relayAddress?.encode() ?: ByteArray(0)
        val size = HEADER_SIZE + addressBytes.size + 1 + relayIdBytes.size + relayAddressBytes.size
        val buffer = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        buffer.put(DhtMessage.CURRENT_VERSION.toByte())
        buffer.put(DhtMessageType.INTRODUCE_RESPONSE.wireValue.toByte())
        buffer.put(transactionId.bytes)
        buffer.put(senderId.bytes)
        buffer.put(if (found) 1.toByte() else 0.toByte())
        buffer.put(addressBytes)
        buffer.put(if (relayFound) 1.toByte() else 0.toByte())
        buffer.put(relayIdBytes)
        buffer.put(relayAddressBytes)
        return buffer.array()
    }

    companion object {
        private const val HEADER_SIZE = 1 + 1 + TransactionId.SIZE_BYTES + NodeId.SIZE_BYTES + 1

        /** Convenience: a found response carrying B's known [address], and optionally a relay suggestion (both [relayId]/[relayAddress] non-null, or both null). */
        fun found(
            transactionId: TransactionId,
            senderId: NodeId,
            address: PeerAddress,
            relayId: NodeId? = null,
            relayAddress: PeerAddress? = null,
        ): IntroduceResponseMessage =
            IntroduceResponseMessage(
                transactionId = transactionId,
                senderId = senderId,
                found = true,
                address = address,
                relayFound = relayId != null,
                relayId = relayId,
                relayAddress = relayAddress,
            )

        /** Convenience: a not-found response, carrying no address (and, in practice, never a relay suggestion either -- see this class's own doc on why [DhtUdpTransport.handlePacket] never computes one for a not-found answer). */
        fun notFound(
            transactionId: TransactionId,
            senderId: NodeId,
            relayId: NodeId? = null,
            relayAddress: PeerAddress? = null,
        ): IntroduceResponseMessage =
            IntroduceResponseMessage(
                transactionId = transactionId,
                senderId = senderId,
                found = false,
                address = null,
                relayFound = relayId != null,
                relayId = relayId,
                relayAddress = relayAddress,
            )

        /**
         * Rejects a truncated header, an unknown version, a type byte other
         * than INTRODUCE_RESPONSE, an invalid `found`/`relayFound` byte, a
         * malformed/truncated trailing address (when `found=true`) or
         * relayId/relayAddress (when `relayFound=true`), or any unexpected
         * byte left over once every field has been read (the generalized
         * form of the old "found=false must never carry trailing address
         * bytes" check, now covering every field combination since address
         * is no longer necessarily the last one in this message).
         */
        fun decode(bytes: ByteArray): IntroduceResponseMessage {
            if (bytes.size < HEADER_SIZE) {
                throw DhtMessageDecodeException(
                    "Truncated IntroduceResponseMessage: got ${bytes.size} bytes, need at least $HEADER_SIZE for the header"
                )
            }

            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)

            DhtMessageHeader.requireVersionAndType(
                buffer,
                messageTypeName = "IntroduceResponseMessage",
                expectedType = DhtMessageType.INTRODUCE_RESPONSE,
            )

            val transactionId = TransactionId(ByteArray(TransactionId.SIZE_BYTES).also { buffer.get(it) })
            val senderId = NodeId(ByteArray(NodeId.SIZE_BYTES).also { buffer.get(it) })

            val foundByte = buffer.get().toInt() and 0xFF
            val found = when (foundByte) {
                0 -> false
                1 -> true
                else -> throw DhtMessageDecodeException("Invalid found byte in IntroduceResponseMessage: $foundByte (expected 0 or 1)")
            }
            val address = if (found) {
                DhtMessageHeader.decodePeerAddressFromBuffer(buffer, fieldName = "address", messageTypeName = "IntroduceResponseMessage")
            } else {
                null
            }

            if (!buffer.hasRemaining()) {
                throw DhtMessageDecodeException("Truncated IntroduceResponseMessage: missing relayFound byte")
            }
            val relayFoundByte = buffer.get().toInt() and 0xFF
            val relayFound = when (relayFoundByte) {
                0 -> false
                1 -> true
                else -> throw DhtMessageDecodeException("Invalid relayFound byte in IntroduceResponseMessage: $relayFoundByte (expected 0 or 1)")
            }
            val relayId: NodeId?
            val relayAddress: PeerAddress?
            if (relayFound) {
                if (buffer.remaining() < NodeId.SIZE_BYTES) {
                    throw DhtMessageDecodeException(
                        "Truncated IntroduceResponseMessage: relayFound=true but only ${buffer.remaining()} byte(s) remain for a ${NodeId.SIZE_BYTES}-byte relayId"
                    )
                }
                relayId = NodeId(ByteArray(NodeId.SIZE_BYTES).also { buffer.get(it) })
                relayAddress = DhtMessageHeader.decodePeerAddressFromBuffer(
                    buffer,
                    fieldName = "relayAddress",
                    messageTypeName = "IntroduceResponseMessage",
                )
            } else {
                relayId = null
                relayAddress = null
            }

            if (buffer.hasRemaining()) {
                throw DhtMessageDecodeException(
                    "Malformed IntroduceResponseMessage: ${buffer.remaining()} unexpected trailing byte(s)"
                )
            }

            return IntroduceResponseMessage(
                transactionId = transactionId,
                senderId = senderId,
                found = found,
                address = address,
                relayFound = relayFound,
                relayId = relayId,
                relayAddress = relayAddress,
            )
        }
    }
}

/**
 * `[1B version][1B type=INTRODUCTION][8B transactionId][32B senderId]
 * [32B fromId][claimedAddress: PeerAddress.encode()][1B relayFound]
 * [32B relayId][relayAddress: PeerAddress.encode(), the latter two present
 * only if relayFound]` = 82 bytes (IPv4 [claimedAddress], no relay) up to
 * 113 bytes (IPv6 [claimedAddress] + relayFound IPv6 relayAddress) -- see
 * [IntroductionMessageTest] for the exact byte counts for every
 * IPv4/IPv6 x relayFound combination.
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
 *
 * **[relayFound]/[relayId]/[relayAddress] -- carries the exact same relay
 * suggestion R also hands A in the paired [IntroduceResponseMessage] for
 * this same INTRODUCE_REQUEST** -- see that class's own doc for the full
 * relay-fallback-coordination reasoning (the whole point: A and B converge
 * on the same relay because it's the one choice R made once, not two
 * independent ones). Same explicit-flag convention, same trust limitation
 * (exactly as trustworthy as whichever relay announced it to R).
 */
data class IntroductionMessage(
    val transactionId: TransactionId,
    val senderId: NodeId,
    val fromId: NodeId,
    val claimedAddress: PeerAddress,
    val relayFound: Boolean = false,
    val relayId: NodeId? = null,
    val relayAddress: PeerAddress? = null,
) {
    init {
        require(relayFound == (relayId != null)) {
            "relayId must be non-null iff relayFound=true (relayFound=$relayFound, relayId=$relayId)"
        }
        require(relayFound == (relayAddress != null)) {
            "relayAddress must be non-null iff relayFound=true (relayFound=$relayFound, relayAddress=$relayAddress)"
        }
    }

    fun encode(): ByteArray {
        val addressBytes = claimedAddress.encode()
        val relayIdBytes = if (relayFound) relayId!!.bytes else ByteArray(0)
        val relayAddressBytes = relayAddress?.encode() ?: ByteArray(0)
        val size = HEADER_SIZE + addressBytes.size + 1 + relayIdBytes.size + relayAddressBytes.size
        val buffer = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        buffer.put(DhtMessage.CURRENT_VERSION.toByte())
        buffer.put(DhtMessageType.INTRODUCTION.wireValue.toByte())
        buffer.put(transactionId.bytes)
        buffer.put(senderId.bytes)
        buffer.put(fromId.bytes)
        buffer.put(addressBytes)
        buffer.put(if (relayFound) 1.toByte() else 0.toByte())
        buffer.put(relayIdBytes)
        buffer.put(relayAddressBytes)
        return buffer.array()
    }

    companion object {
        private const val HEADER_SIZE = 1 + 1 + TransactionId.SIZE_BYTES + NodeId.SIZE_BYTES + NodeId.SIZE_BYTES

        /**
         * Rejects a truncated header, an unknown version, a type byte other
         * than INTRODUCTION, an invalid `relayFound` byte, a malformed/
         * truncated trailing [claimedAddress] or relayId/relayAddress (when
         * `relayFound=true`), or any unexpected byte left over once every
         * field has been read.
         */
        fun decode(bytes: ByteArray): IntroductionMessage {
            if (bytes.size < HEADER_SIZE) {
                throw DhtMessageDecodeException(
                    "Truncated IntroductionMessage: got ${bytes.size} bytes, need at least $HEADER_SIZE for the header"
                )
            }

            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)

            DhtMessageHeader.requireVersionAndType(
                buffer,
                messageTypeName = "IntroductionMessage",
                expectedType = DhtMessageType.INTRODUCTION,
            )

            val transactionId = TransactionId(ByteArray(TransactionId.SIZE_BYTES).also { buffer.get(it) })
            val senderId = NodeId(ByteArray(NodeId.SIZE_BYTES).also { buffer.get(it) })
            val fromId = NodeId(ByteArray(NodeId.SIZE_BYTES).also { buffer.get(it) })
            val claimedAddress = DhtMessageHeader.decodePeerAddressFromBuffer(
                buffer,
                fieldName = "claimedAddress",
                messageTypeName = "IntroductionMessage",
            )

            if (!buffer.hasRemaining()) {
                throw DhtMessageDecodeException("Truncated IntroductionMessage: missing relayFound byte")
            }
            val relayFoundByte = buffer.get().toInt() and 0xFF
            val relayFound = when (relayFoundByte) {
                0 -> false
                1 -> true
                else -> throw DhtMessageDecodeException("Invalid relayFound byte in IntroductionMessage: $relayFoundByte (expected 0 or 1)")
            }
            val relayId: NodeId?
            val relayAddress: PeerAddress?
            if (relayFound) {
                if (buffer.remaining() < NodeId.SIZE_BYTES) {
                    throw DhtMessageDecodeException(
                        "Truncated IntroductionMessage: relayFound=true but only ${buffer.remaining()} byte(s) remain for a ${NodeId.SIZE_BYTES}-byte relayId"
                    )
                }
                relayId = NodeId(ByteArray(NodeId.SIZE_BYTES).also { buffer.get(it) })
                relayAddress = DhtMessageHeader.decodePeerAddressFromBuffer(
                    buffer,
                    fieldName = "relayAddress",
                    messageTypeName = "IntroductionMessage",
                )
            } else {
                relayId = null
                relayAddress = null
            }

            if (buffer.hasRemaining()) {
                throw DhtMessageDecodeException("Malformed IntroductionMessage: ${buffer.remaining()} unexpected trailing byte(s)")
            }

            return IntroductionMessage(
                transactionId = transactionId,
                senderId = senderId,
                fromId = fromId,
                claimedAddress = claimedAddress,
                relayFound = relayFound,
                relayId = relayId,
                relayAddress = relayAddress,
            )
        }
    }
}
