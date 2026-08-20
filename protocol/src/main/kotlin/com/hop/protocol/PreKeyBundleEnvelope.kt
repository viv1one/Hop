package com.hop.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Thrown when a byte array cannot be decoded as a valid [PreKeyBundleEnvelope]:
 * truncated `peerId` length/bytes, truncated `hopCount`/`originatedAtMs`, or a
 * truncated header generally. Decoding must fail loudly rather than silently
 * misparse — see /protocol/WIRE_FORMAT.md.
 */
class PreKeyBundleEnvelopeDecodeException(message: String) : Exception(message)

/**
 * The payload carried inside a [WirePayloadType.PREKEY_BUNDLE]-typed
 * [WireEnvelope]: identifies which peer is announcing a Double Ratchet
 * prekey bundle, plus the bundle bytes themselves.
 *
 * [bundleBytes] is treated as **fully opaque** by this class and by all of
 * `protocol/` — it is a serialized libsignal-client `PreKeyBundle`, but this
 * module never imports libsignal-client types or attempts to deserialize it.
 * That opacity is deliberate: it preserves ADR 0001's one-way dependency
 * rule (`protocol/` may depend on `crypto/`, never the reverse) without
 * adding any *new* dependency on libsignal-client specifically. The caller
 * in `crypto/`/app code is responsible for serializing/deserializing the
 * actual `PreKeyBundle`.
 *
 * [peerId] is likewise treated as an opaque identifying string by this
 * module — it happens to be the hex-encoded `senderDeviceId` per the
 * messaging design, but [WireEnvelope]/`protocol/` doesn't need to know
 * that semantic.
 *
 * [hopCount]/[originatedAtMs] were added for prekey-bundle mesh flood relay
 * (the "prekey-bundle relay/discovery" follow-up to Phase 2's four original
 * slices) — a breaking change to this envelope's previous shape, accepted
 * under the same "no real users yet" justification that covered
 * [MessageCiphertextEnvelope]'s own `hopCount`/`originatedAtMs` addition
 * (see that class's own doc) and [WireEnvelope]'s introduction as a breaking
 * change to the socket framing (see /protocol/WIRE_FORMAT.md). They mirror
 * [MessageCiphertextEnvelope.hopCount]/[MessageCiphertextEnvelope.originatedAtMs]
 * exactly (same uint8/int64 encoding and semantics: `hopCount` starts at `0`
 * at a genuine direct announce and increments by one per relay hop;
 * `originatedAtMs` is epoch millis at first announce), so a bundle
 * relay-custody row can be evaluated against the same
 * [RelayPolicy.isEligibleForRelay]/[RelayPolicy.isExpired] already used for
 * posts and messages, unmodified.
 *
 * **`hopCount == 0` is the load-bearing distinction a receiver must respect**:
 * it is what tells `com.hop.transport.EnvelopeDispatcher` whether the
 * connection this envelope arrived on genuinely belongs to [peerId] (a
 * direct announce, safe to tag `remotePeerId` with) or merely carried
 * [peerId]'s bundle on their behalf (a relayed copy, `hopCount >= 1` by
 * construction — every relay path re-encodes at `hopCount + 1`, mirroring
 * `com.hop.repository.PendingMessageRepository.buildOutgoingBacklog`'s own
 * re-encode step). Getting this gating wrong would let a mere carrier's
 * connection be mistaken for a direct line to [peerId] — see
 * `com.hop.transport.EnvelopeDispatcher`'s own doc for the message-
 * misdirection bug this specifically closes.
 */
data class PreKeyBundleEnvelope(
    val peerId: String,
    /**
     * Number of relay hops so far. `0` at a genuine direct announce
     * (`com.hop.transport.WifiDirectTransport.announceOwnPreKeyBundle`
     * always constructs one this way); incremented by one on each carrier's
     * outgoing re-offer (see `com.hop.repository.BundleRepository
     * .buildOutgoingBacklog`). Fits a uint8 on the wire (0..255), same bound
     * [MessageCiphertextEnvelope.hopCount]/`Frame.hopCount` enforce.
     */
    val hopCount: Int,
    /** Epoch millis this bundle was originally announced, mirroring [MessageCiphertextEnvelope.originatedAtMs]'s own semantics. Input to [RelayPolicy.isExpired]/[RelayPolicy.expiresAtMs] against [DEFAULT_TTL_SECONDS]. */
    val originatedAtMs: Long,
    val bundleBytes: ByteArray,
) {
    init {
        require(hopCount in 0..0xFF) { "hopCount must fit in a uint8 (0..255), was $hopCount" }
    }

    /**
     * Encodes as `[4-byte peerId-UTF8-byte-length][peerId UTF-8 bytes]
     * [1-byte hopCount][8-byte originatedAtMs][bundleBytes]`, mirroring how
     * [MessageCiphertextEnvelope] length-prefixes its own peer id fields and
     * appends `hopCount`/`originatedAtMs` before its opaque payload.
     */
    fun encode(): ByteArray {
        val peerIdBytes = peerId.toByteArray(StandardCharsets.UTF_8)
        val buffer = ByteBuffer.allocate(4 + peerIdBytes.size + 1 + 8 + bundleBytes.size)
        buffer.order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(peerIdBytes.size)
        buffer.put(peerIdBytes)
        buffer.put(hopCount.toByte())
        buffer.putLong(originatedAtMs)
        buffer.put(bundleBytes)
        return buffer.array()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PreKeyBundleEnvelope) return false
        return peerId == other.peerId &&
            hopCount == other.hopCount &&
            originatedAtMs == other.originatedAtMs &&
            bundleBytes.contentEquals(other.bundleBytes)
    }

    override fun hashCode(): Int {
        var result = peerId.hashCode()
        result = 31 * result + hopCount
        result = 31 * result + originatedAtMs.hashCode()
        result = 31 * result + bundleBytes.contentHashCode()
        return result
    }

    companion object {
        /**
         * Unmeasured placeholder, matching [MessageCiphertextEnvelope.DEFAULT_TTL_SECONDS]'s
         * own "not tuned against real data" posture — propagation-hygiene
         * knob, not a safety control: staleness here fails safe already
         * (see `com.hop.repository.BundleRepository`'s own "Limits" doc for
         * the traced libsignal-client call chain establishing that a stale
         * bundle fails cleanly at the *owner's* device on first decrypt,
         * never silently). 6 hours is comfortably inside the ~96h worst-case
         * signed/Kyber prekey validity window (48h rotation + 48h grace, per
         * `com.hop.data.PreKeyRotationManager`), long enough to cover "met a
         * few hours ago, haven't reconnected" without keeping stale bundles
         * in flood circulation for days.
         */
        const val DEFAULT_TTL_SECONDS: Long = 6L * 60 * 60

        /**
         * Decodes [bytes] into a [PreKeyBundleEnvelope].
         *
         * Throws [PreKeyBundleEnvelopeDecodeException] on:
         * - fewer than 4 bytes (truncated `peerId` length prefix),
         * - a declared `peerId` byte length longer than the bytes actually
         *   available (truncated `peerId`),
         * - fewer than 9 bytes remaining after `peerId` (truncated
         *   `hopCount`/`originatedAtMs`).
         *
         * Everything after `hopCount`/`originatedAtMs` is [bundleBytes], with
         * no further length prefix or validation — this module treats it as
         * fully opaque.
         */
        fun decode(bytes: ByteArray): PreKeyBundleEnvelope {
            if (bytes.size < 4) {
                throw PreKeyBundleEnvelopeDecodeException(
                    "Truncated prekey bundle envelope: got ${bytes.size} bytes, need at least 4 for the peerId length prefix"
                )
            }

            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val peerIdLength = buffer.int.toLong() and 0xFFFFFFFFL
            val remainingAfterLength = buffer.remaining().toLong()
            if (peerIdLength > remainingAfterLength) {
                throw PreKeyBundleEnvelopeDecodeException(
                    "Truncated prekey bundle envelope: declared peerId length=$peerIdLength but only $remainingAfterLength bytes remain"
                )
            }

            val peerIdBytes = ByteArray(peerIdLength.toInt()).also { buffer.get(it) }

            if (buffer.remaining() < 9) {
                throw PreKeyBundleEnvelopeDecodeException(
                    "Truncated prekey bundle envelope: only ${buffer.remaining()} bytes remain, need at least 9 for hopCount+originatedAtMs"
                )
            }
            val hopCount = buffer.get().toInt() and 0xFF
            val originatedAtMs = buffer.long

            val bundleBytes = ByteArray(buffer.remaining()).also { buffer.get(it) }

            return PreKeyBundleEnvelope(
                peerId = String(peerIdBytes, StandardCharsets.UTF_8),
                hopCount = hopCount,
                originatedAtMs = originatedAtMs,
                bundleBytes = bundleBytes,
            )
        }
    }
}
