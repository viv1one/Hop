package com.hop.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Thrown when a byte array cannot be decoded as a valid [DontRelayFlagEnvelope]:
 * truncated `clipHash`, truncated `attestedDeviceKey` length/bytes, or a
 * truncated fixed-size tail. Decoding must fail loudly rather than silently
 * misparse — see /protocol/WIRE_FORMAT.md.
 */
class DontRelayFlagEnvelopeDecodeException(message: String) : Exception(message)

/**
 * The payload carried inside a [WirePayloadType.DONT_RELAY_FLAG]-typed
 * [WireEnvelope]: one attested device's "don't relay" signal for [clipHash]
 * (Phase 2 Slice 2, PRD §4.6, ADR 0004).
 *
 * **Explicit non-goal**: nothing that consumes this envelope ever rewrites a
 * [Frame]'s own on-wire `dontRelay` bit. Only a local, per-device
 * `RelayQueueEntity` row's `dontRelay` column changes, as each device
 * independently crosses its own locally-observed distinct-flagger threshold
 * -- see `com.hop.repository.DontRelayRepository`'s own doc.
 *
 * [attestedDeviceKey] is length-prefixed rather than fixed-size like
 * [Frame.senderDeviceId] -- a real Play Integrity/App Attest key will differ
 * in size from [com.hop.crypto.StubAttestationProvider]'s nonce-sized stand-in,
 * and length-prefixing now avoids a future wire-format bump once real
 * attestation replaces the stub.
 *
 * [originatedAtMs]/[ttlSeconds] are denormalized off the post this flag
 * refers to, not looked up from a locally-held post: a device can receive a
 * flag for a clipHash before it ever receives the post itself (mesh delivery
 * order isn't guaranteed), so a [DontRelayFlagEnvelope] must carry everything
 * it needs to bound its own propagation/expiry on its own. The flagging
 * device already has these values in hand (it just proved local receipt via
 * its own `PostEntity` before it was allowed to flag at all), so carrying
 * them costs nothing -- and it lets flag expiry reuse
 * [RelayPolicy.isExpired]/[RelayPolicy.expiresAtMs] exactly as-is, symmetric
 * with how posts already expire. No separate hop-count field is added here --
 * flag propagation is bounded by TTL plus distinct-key dedup alone; a second
 * independent bound would be over-engineering for a flat-threshold v1.
 */
data class DontRelayFlagEnvelope(
    /** 32 bytes, matches [Frame.CLIP_HASH_SIZE]. */
    val clipHash: ByteArray,
    /** Length-prefixed on the wire -- see class doc for why this isn't fixed-size. */
    val attestedDeviceKey: ByteArray,
    val flaggedAtMs: Long,
    val originatedAtMs: Long,
    val ttlSeconds: Long,
) {
    init {
        require(clipHash.size == Frame.CLIP_HASH_SIZE) {
            "clipHash must be ${Frame.CLIP_HASH_SIZE} bytes, was ${clipHash.size}"
        }
    }

    /**
     * Encodes as `[32B clipHash][4B keyLen][keyBytes][8B flaggedAtMs][8B originatedAtMs][4B ttlSeconds]`,
     * per /protocol/WIRE_FORMAT.md.
     */
    fun encode(): ByteArray {
        val buffer = ByteBuffer.allocate(
            Frame.CLIP_HASH_SIZE + 4 + attestedDeviceKey.size + 8 + 8 + 4,
        )
        buffer.order(ByteOrder.BIG_ENDIAN)
        buffer.put(clipHash)
        buffer.putInt(attestedDeviceKey.size)
        buffer.put(attestedDeviceKey)
        buffer.putLong(flaggedAtMs)
        buffer.putLong(originatedAtMs)
        buffer.putInt(ttlSeconds.toInt())
        return buffer.array()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DontRelayFlagEnvelope) return false
        return clipHash.contentEquals(other.clipHash) &&
            attestedDeviceKey.contentEquals(other.attestedDeviceKey) &&
            flaggedAtMs == other.flaggedAtMs &&
            originatedAtMs == other.originatedAtMs &&
            ttlSeconds == other.ttlSeconds
    }

    override fun hashCode(): Int {
        var result = clipHash.contentHashCode()
        result = 31 * result + attestedDeviceKey.contentHashCode()
        result = 31 * result + flaggedAtMs.hashCode()
        result = 31 * result + originatedAtMs.hashCode()
        result = 31 * result + ttlSeconds.hashCode()
        return result
    }

    companion object {
        /** Fixed portion of the header: `clipHash` + `keyLen` prefix + `flaggedAtMs` + `originatedAtMs` + `ttlSeconds`. */
        private const val FIXED_TAIL_SIZE = 8 + 8 + 4

        /**
         * Decodes [bytes] into a [DontRelayFlagEnvelope].
         *
         * Throws [DontRelayFlagEnvelopeDecodeException] on:
         * - fewer than [Frame.CLIP_HASH_SIZE] + 4 bytes (truncated `clipHash`
         *   or `attestedDeviceKey` length prefix),
         * - a declared `attestedDeviceKey` byte length longer than the bytes
         *   actually available,
         * - fewer than [FIXED_TAIL_SIZE] bytes remaining after
         *   `attestedDeviceKey` (truncated `flaggedAtMs`/`originatedAtMs`/`ttlSeconds`).
         */
        fun decode(bytes: ByteArray): DontRelayFlagEnvelope {
            if (bytes.size < Frame.CLIP_HASH_SIZE + 4) {
                throw DontRelayFlagEnvelopeDecodeException(
                    "Truncated don't-relay flag envelope: got ${bytes.size} bytes, need at least " +
                        "${Frame.CLIP_HASH_SIZE + 4} for clipHash + attestedDeviceKey length prefix"
                )
            }

            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val clipHash = ByteArray(Frame.CLIP_HASH_SIZE).also { buffer.get(it) }

            val keyLength = buffer.int.toLong() and 0xFFFFFFFFL
            val remainingAfterKeyLength = buffer.remaining().toLong()
            if (keyLength > remainingAfterKeyLength) {
                throw DontRelayFlagEnvelopeDecodeException(
                    "Truncated don't-relay flag envelope: declared attestedDeviceKey length=$keyLength " +
                        "but only $remainingAfterKeyLength bytes remain"
                )
            }
            val attestedDeviceKey = ByteArray(keyLength.toInt()).also { buffer.get(it) }

            if (buffer.remaining() < FIXED_TAIL_SIZE) {
                throw DontRelayFlagEnvelopeDecodeException(
                    "Truncated don't-relay flag envelope: need $FIXED_TAIL_SIZE more bytes for " +
                        "flaggedAtMs/originatedAtMs/ttlSeconds, only ${buffer.remaining()} remain"
                )
            }
            val flaggedAtMs = buffer.long
            val originatedAtMs = buffer.long
            val ttlSeconds = buffer.int.toLong() and 0xFFFFFFFFL

            return DontRelayFlagEnvelope(
                clipHash = clipHash,
                attestedDeviceKey = attestedDeviceKey,
                flaggedAtMs = flaggedAtMs,
                originatedAtMs = originatedAtMs,
                ttlSeconds = ttlSeconds,
            )
        }
    }
}
