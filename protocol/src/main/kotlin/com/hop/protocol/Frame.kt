package com.hop.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Thrown when a byte array cannot be decoded as a valid [Frame]: unknown/future
 * version byte, truncated input, or a field value outside its defined range.
 * Decoding must fail loudly rather than silently misparse — see
 * /protocol/WIRE_FORMAT.md.
 */
class FrameDecodeException(message: String) : Exception(message)

/**
 * The four PRD §4.2 reach tiers, in increasing geohash-prefix precision order.
 * This enum only carries the value transported on the wire; tier-to-precision
 * resolution logic lives elsewhere in protocol/.
 */
enum class ReachTier(val wireValue: Int) {
    LOCALITY(0),
    TOWN(1),
    CITY(2),
    COUNTRY(3),
    ;

    companion object {
        fun fromWireValue(value: Int): ReachTier =
            values().find { it.wireValue == value }
                ?: throw FrameDecodeException("Unknown reachTier value: $value")
    }
}

/**
 * The two v1 post content types (BUILD_PLAN.md open decision #4, PRD §4.1).
 * A "post" is either a photo or a short video — both first-class, neither
 * inferred from file extension or payload sniffing.
 */
enum class ContentType(val wireValue: Int) {
    PHOTO(0),
    VIDEO(1),
    ;

    companion object {
        fun fromWireValue(value: Int): ContentType =
            values().find { it.wireValue == value }
                ?: throw FrameDecodeException("Unknown contentType value: $value")
    }
}

/**
 * Reference implementation of the HOP wire frame, version 2.
 *
 * See /protocol/WIRE_FORMAT.md for the authoritative spec: byte layout,
 * field semantics, and the BLE-is-discovery-only / this-frame-is-the-WiFi-
 * Direct-transfer-frame distinction. This class implements version 2 only —
 * a future version bump is a new format, not a silent field change here.
 *
 * [Frame] is a pure wire envelope: it encodes/decodes raw bytes and has no
 * dependency on `crypto/`. It does not itself encrypt or decrypt anything —
 * [payload] is opaque ciphertext as far as this class is concerned, and
 * [contentEncryptionKey] is opaque key bytes. The encrypt/decrypt
 * orchestration that produces those bytes lives in `EncryptedFrameCodec`
 * (which does depend on `crypto/`, per ADR 0001's one-way rule) — see
 * /protocol/WIRE_FORMAT.md.
 *
 * Note: [clipHash], [senderDeviceId], [contentEncryptionKey], and [payload]
 * are [ByteArray]s, so this class overrides [equals]/[hashCode] to compare
 * array *contents* rather than Kotlin's default reference-equality behavior
 * for arrays.
 */
class Frame(
    val version: Int = CURRENT_VERSION,
    val clipHash: ByteArray,
    val senderDeviceId: ByteArray,
    val contentType: ContentType,
    val hopCount: Int,
    val originatedAtMs: Long,
    val ttlSeconds: Long,
    val reachTier: ReachTier,
    val dontRelay: Boolean,
    val keyIncluded: Boolean,
    val contentEncryptionKey: ByteArray,
    val payload: ByteArray,
) {
    init {
        require(clipHash.size == CLIP_HASH_SIZE) {
            "clipHash must be $CLIP_HASH_SIZE bytes, was ${clipHash.size}"
        }
        require(senderDeviceId.size == SENDER_DEVICE_ID_SIZE) {
            "senderDeviceId must be $SENDER_DEVICE_ID_SIZE bytes, was ${senderDeviceId.size}"
        }
        require(hopCount in 0..0xFF) { "hopCount must fit in a uint8 (0..255), was $hopCount" }
        require(ttlSeconds in 0..0xFFFFFFFFL) {
            "ttlSeconds must fit in a uint32 (0..${0xFFFFFFFFL}), was $ttlSeconds"
        }
        require(contentEncryptionKey.size == CONTENT_ENCRYPTION_KEY_SIZE) {
            "contentEncryptionKey must be $CONTENT_ENCRYPTION_KEY_SIZE bytes, was ${contentEncryptionKey.size}"
        }
        require(payload.size.toLong() <= 0xFFFFFFFFL) {
            "payload length must fit in a uint32, was ${payload.size}"
        }
    }

    /** Encodes this frame as a big-endian byte array per /protocol/WIRE_FORMAT.md. */
    fun encode(): ByteArray {
        val buffer = ByteBuffer.allocate(HEADER_SIZE + payload.size)
        buffer.order(ByteOrder.BIG_ENDIAN)
        buffer.put(version.toByte())
        buffer.put(clipHash)
        buffer.put(senderDeviceId)
        buffer.put(contentType.wireValue.toByte())
        buffer.put(hopCount.toByte())
        buffer.putLong(originatedAtMs)
        buffer.putInt(ttlSeconds.toInt())
        buffer.put(reachTier.wireValue.toByte())
        buffer.put(if (dontRelay) 1.toByte() else 0.toByte())
        buffer.put(if (keyIncluded) 1.toByte() else 0.toByte())
        // contentEncryptionKey is only meaningful when keyIncluded — zero-fill on
        // the wire otherwise rather than leaking whatever bytes the caller passed
        // (fixed-size reservation is a deliberate simplicity tradeoff, see
        // /protocol/WIRE_FORMAT.md; it's not an invitation to carry stale key
        // material in an unused field).
        buffer.put(if (keyIncluded) contentEncryptionKey else ByteArray(CONTENT_ENCRYPTION_KEY_SIZE))
        buffer.putInt(payload.size)
        buffer.put(payload)
        return buffer.array()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Frame) return false
        return version == other.version &&
            clipHash.contentEquals(other.clipHash) &&
            senderDeviceId.contentEquals(other.senderDeviceId) &&
            contentType == other.contentType &&
            hopCount == other.hopCount &&
            originatedAtMs == other.originatedAtMs &&
            ttlSeconds == other.ttlSeconds &&
            reachTier == other.reachTier &&
            dontRelay == other.dontRelay &&
            keyIncluded == other.keyIncluded &&
            contentEncryptionKey.contentEquals(other.contentEncryptionKey) &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = version
        result = 31 * result + clipHash.contentHashCode()
        result = 31 * result + senderDeviceId.contentHashCode()
        result = 31 * result + contentType.hashCode()
        result = 31 * result + hopCount
        result = 31 * result + originatedAtMs.hashCode()
        result = 31 * result + ttlSeconds.hashCode()
        result = 31 * result + reachTier.hashCode()
        result = 31 * result + dontRelay.hashCode()
        result = 31 * result + keyIncluded.hashCode()
        result = 31 * result + contentEncryptionKey.contentHashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }

    override fun toString(): String =
        "Frame(version=$version, clipHash=${clipHash.size}b, senderDeviceId=${senderDeviceId.size}b, " +
            "contentType=$contentType, hopCount=$hopCount, originatedAtMs=$originatedAtMs, ttlSeconds=$ttlSeconds, " +
            "reachTier=$reachTier, dontRelay=$dontRelay, keyIncluded=$keyIncluded, " +
            "contentEncryptionKey=${contentEncryptionKey.size}b, payload=${payload.size}b)"

    companion object {
        /** Current wire format version implemented by this reference implementation. */
        const val CURRENT_VERSION: Int = 2

        const val CLIP_HASH_SIZE: Int = 32
        const val SENDER_DEVICE_ID_SIZE: Int = 16
        const val CONTENT_ENCRYPTION_KEY_SIZE: Int = 32

        /** Fixed header size in bytes: everything before the variable-length payload. */
        const val HEADER_SIZE: Int =
            1 + CLIP_HASH_SIZE + SENDER_DEVICE_ID_SIZE + 1 + 1 + 8 + 4 + 1 + 1 + 1 + CONTENT_ENCRYPTION_KEY_SIZE + 4 // = 102

        /**
         * Decodes [bytes] into a [Frame] per /protocol/WIRE_FORMAT.md version 2.
         *
         * Throws [FrameDecodeException] on:
         * - fewer than [HEADER_SIZE] bytes (truncated header),
         * - a `version` byte other than [CURRENT_VERSION] (unknown/future version —
         *   rejected rather than guessed at, since the byte layout for other
         *   versions is not defined here; this includes versions 0 and 1, which
         *   this version-2 decoder no longer understands),
         * - an invalid `contentType`, `reachTier`, `dontRelay`, or `keyIncluded` value,
         * - a declared `payloadLength` longer than the bytes actually available
         *   (truncated payload).
         */
        fun decode(bytes: ByteArray): Frame {
            if (bytes.size < HEADER_SIZE) {
                throw FrameDecodeException(
                    "Truncated frame: got ${bytes.size} bytes, need at least $HEADER_SIZE for the header"
                )
            }

            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)

            val version = buffer.get().toInt() and 0xFF
            if (version != CURRENT_VERSION) {
                throw FrameDecodeException(
                    "Unsupported wire format version: $version (this decoder only understands version $CURRENT_VERSION)"
                )
            }

            val clipHash = ByteArray(CLIP_HASH_SIZE).also { buffer.get(it) }
            val senderDeviceId = ByteArray(SENDER_DEVICE_ID_SIZE).also { buffer.get(it) }
            val contentType = ContentType.fromWireValue(buffer.get().toInt() and 0xFF)
            val hopCount = buffer.get().toInt() and 0xFF
            val originatedAtMs = buffer.long
            val ttlSeconds = buffer.int.toLong() and 0xFFFFFFFFL
            val reachTier = ReachTier.fromWireValue(buffer.get().toInt() and 0xFF)

            val dontRelayByte = buffer.get().toInt() and 0xFF
            val dontRelay = when (dontRelayByte) {
                0 -> false
                1 -> true
                else -> throw FrameDecodeException(
                    "Invalid dontRelay byte: $dontRelayByte (expected 0 or 1)"
                )
            }

            val keyIncludedByte = buffer.get().toInt() and 0xFF
            val keyIncluded = when (keyIncludedByte) {
                0 -> false
                1 -> true
                else -> throw FrameDecodeException(
                    "Invalid keyIncluded byte: $keyIncludedByte (expected 0 or 1)"
                )
            }

            val contentEncryptionKey = ByteArray(CONTENT_ENCRYPTION_KEY_SIZE).also { buffer.get(it) }

            val payloadLength = buffer.int.toLong() and 0xFFFFFFFFL
            val remaining = buffer.remaining().toLong()
            if (payloadLength > remaining) {
                throw FrameDecodeException(
                    "Truncated frame: declared payloadLength=$payloadLength but only $remaining bytes remain"
                )
            }

            val payload = ByteArray(payloadLength.toInt()).also { buffer.get(it) }

            return Frame(
                version = version,
                clipHash = clipHash,
                senderDeviceId = senderDeviceId,
                contentType = contentType,
                hopCount = hopCount,
                originatedAtMs = originatedAtMs,
                ttlSeconds = ttlSeconds,
                reachTier = reachTier,
                dontRelay = dontRelay,
                keyIncluded = keyIncluded,
                contentEncryptionKey = contentEncryptionKey,
                payload = payload,
            )
        }
    }
}
