package com.hop.protocol

import java.security.SecureRandom
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class FrameTest {

    private val random = SecureRandom()

    private fun randomBytes(size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }

    private fun sampleFrame(
        contentType: ContentType = ContentType.VIDEO,
        hopCount: Int = 0,
        ttlSeconds: Long = 3600L,
        reachTier: ReachTier = ReachTier.LOCALITY,
        dontRelay: Boolean = false,
        keyIncluded: Boolean = true,
        contentEncryptionKey: ByteArray = randomBytes(Frame.CONTENT_ENCRYPTION_KEY_SIZE),
        payload: ByteArray = randomBytes(1024),
    ): Frame = Frame(
        clipHash = randomBytes(Frame.CLIP_HASH_SIZE),
        senderDeviceId = randomBytes(Frame.SENDER_DEVICE_ID_SIZE),
        contentType = contentType,
        hopCount = hopCount,
        originatedAtMs = 1_700_000_000_000L,
        ttlSeconds = ttlSeconds,
        reachTier = reachTier,
        dontRelay = dontRelay,
        keyIncluded = keyIncluded,
        contentEncryptionKey = contentEncryptionKey,
        payload = payload,
    )

    // --- Round-trip: encode -> decode preserves every field exactly ---

    @Test
    fun `round trip preserves all fields`() {
        val original = sampleFrame()

        val decoded = Frame.decode(original.encode())

        assertEquals(original, decoded)
        assertEquals(original.version, decoded.version)
        assertTrue(original.clipHash.contentEquals(decoded.clipHash))
        assertTrue(original.senderDeviceId.contentEquals(decoded.senderDeviceId))
        assertEquals(original.contentType, decoded.contentType)
        assertEquals(original.hopCount, decoded.hopCount)
        assertEquals(original.originatedAtMs, decoded.originatedAtMs)
        assertEquals(original.ttlSeconds, decoded.ttlSeconds)
        assertEquals(original.reachTier, decoded.reachTier)
        assertEquals(original.dontRelay, decoded.dontRelay)
        assertEquals(original.keyIncluded, decoded.keyIncluded)
        assertTrue(original.contentEncryptionKey.contentEquals(decoded.contentEncryptionKey))
        assertTrue(original.payload.contentEquals(decoded.payload))
    }

    @Test
    fun `round trip preserves each reach tier`() {
        for (tier in ReachTier.values()) {
            val original = sampleFrame(reachTier = tier)
            val decoded = Frame.decode(original.encode())
            assertEquals(tier, decoded.reachTier)
        }
    }

    @Test
    fun `round trip preserves each content type`() {
        for (type in ContentType.values()) {
            val original = sampleFrame(contentType = type)
            val decoded = Frame.decode(original.encode())
            assertEquals(type, decoded.contentType)
        }
    }

    @Test
    fun `round trip preserves dontRelay true and false`() {
        for (flag in listOf(true, false)) {
            val original = sampleFrame(dontRelay = flag)
            val decoded = Frame.decode(original.encode())
            assertEquals(flag, decoded.dontRelay)
        }
    }

    @Test
    fun `round trip preserves keyIncluded true with the content encryption key`() {
        val cek = randomBytes(Frame.CONTENT_ENCRYPTION_KEY_SIZE)
        val original = sampleFrame(keyIncluded = true, contentEncryptionKey = cek)

        val decoded = Frame.decode(original.encode())

        assertEquals(true, decoded.keyIncluded)
        assertTrue(cek.contentEquals(decoded.contentEncryptionKey))
    }

    @Test
    fun `round trip preserves keyIncluded false and zero-fills the key on the wire`() {
        // Per /protocol/WIRE_FORMAT.md, contentEncryptionKey is ignored/zero-filled
        // on encode when keyIncluded is false, regardless of what the constructor
        // was given -- this avoids leaking stale key material into an unused field.
        val original = sampleFrame(keyIncluded = false, contentEncryptionKey = randomBytes(Frame.CONTENT_ENCRYPTION_KEY_SIZE))

        val decoded = Frame.decode(original.encode())

        assertEquals(false, decoded.keyIncluded)
        assertTrue(ByteArray(Frame.CONTENT_ENCRYPTION_KEY_SIZE).contentEquals(decoded.contentEncryptionKey))
    }

    @Test
    fun `round trip preserves boundary values for hopCount and ttlSeconds`() {
        val original = sampleFrame(hopCount = 255, ttlSeconds = 0xFFFFFFFFL)
        val decoded = Frame.decode(original.encode())
        assertEquals(255, decoded.hopCount)
        assertEquals(0xFFFFFFFFL, decoded.ttlSeconds)
    }

    @Test
    fun `round trip works with empty payload`() {
        val original = sampleFrame(payload = ByteArray(0))
        val decoded = Frame.decode(original.encode())
        assertEquals(0, decoded.payload.size)
        assertEquals(original, decoded)
    }

    @Test
    fun `encoded size is header size plus payload size`() {
        val payload = randomBytes(4321)
        val frame = sampleFrame(payload = payload)
        assertEquals(Frame.HEADER_SIZE + payload.size, frame.encode().size)
    }

    // --- Version handling: unknown/future version must be rejected, not misparsed ---

    @Test
    fun `decoding an unknown future version throws instead of misparsing`() {
        val bytes = sampleFrame().encode()
        bytes[0] = 99 // mutate version byte to an unrecognized future version

        val exception = assertFailsWith<FrameDecodeException> { Frame.decode(bytes) }
        assertTrue(exception.message!!.contains("99"))
    }

    @Test
    fun `constructing a frame with a non-current version and decoding it round trips within this version`() {
        // Frame's constructor allows specifying `version` (e.g. for a future encoder
        // that speaks multiple versions), but this decoder only understands
        // CURRENT_VERSION — anything else must be rejected on decode. Uses version 3
        // (a not-yet-defined future version) since 2 is CURRENT_VERSION now.
        val frame = Frame(
            version = 3,
            clipHash = randomBytes(Frame.CLIP_HASH_SIZE),
            senderDeviceId = randomBytes(Frame.SENDER_DEVICE_ID_SIZE),
            contentType = ContentType.VIDEO,
            hopCount = 0,
            originatedAtMs = 0L,
            ttlSeconds = 0L,
            reachTier = ReachTier.LOCALITY,
            dontRelay = false,
            keyIncluded = false,
            contentEncryptionKey = randomBytes(Frame.CONTENT_ENCRYPTION_KEY_SIZE),
            payload = ByteArray(0),
        )

        assertFailsWith<FrameDecodeException> { Frame.decode(frame.encode()) }
    }

    // --- Truncated / malformed input must throw, never crash ambiguously or return garbage ---

    @Test
    fun `decoding empty bytes throws`() {
        assertFailsWith<FrameDecodeException> { Frame.decode(ByteArray(0)) }
    }

    @Test
    fun `decoding bytes shorter than the fixed header throws`() {
        val truncated = ByteArray(Frame.HEADER_SIZE - 1)
        assertFailsWith<FrameDecodeException> { Frame.decode(truncated) }
    }

    @Test
    fun `decoding a frame whose declared payload length exceeds available bytes throws`() {
        val full = sampleFrame(payload = randomBytes(100)).encode()
        // Drop the last 10 bytes of payload without correcting payloadLength,
        // simulating a connection that dropped mid-transfer.
        val truncated = full.copyOf(full.size - 10)

        assertFailsWith<FrameDecodeException> { Frame.decode(truncated) }
    }

    @Test
    fun `decoding a header-only frame with nonzero declared payload length throws`() {
        val headerOnly = sampleFrame(payload = randomBytes(50)).encode().copyOf(Frame.HEADER_SIZE)

        assertFailsWith<FrameDecodeException> { Frame.decode(headerOnly) }
    }

    @Test
    fun `decoding an invalid contentType value throws`() {
        val bytes = sampleFrame().encode()
        val contentTypeOffset = 1 + Frame.CLIP_HASH_SIZE + Frame.SENDER_DEVICE_ID_SIZE
        bytes[contentTypeOffset] = 42 // not a defined ContentType value

        assertFailsWith<FrameDecodeException> { Frame.decode(bytes) }
    }

    @Test
    fun `decoding an invalid reachTier value throws`() {
        val bytes = sampleFrame().encode()
        val reachTierOffset = 1 + Frame.CLIP_HASH_SIZE + Frame.SENDER_DEVICE_ID_SIZE + 1 + 1 + 8 + 4
        bytes[reachTierOffset] = 42 // not a defined ReachTier value

        assertFailsWith<FrameDecodeException> { Frame.decode(bytes) }
    }

    @Test
    fun `decoding an invalid dontRelay byte throws`() {
        val bytes = sampleFrame().encode()
        val dontRelayOffset = 1 + Frame.CLIP_HASH_SIZE + Frame.SENDER_DEVICE_ID_SIZE + 1 + 1 + 8 + 4 + 1
        bytes[dontRelayOffset] = 7 // neither 0 nor 1

        assertFailsWith<FrameDecodeException> { Frame.decode(bytes) }
    }

    @Test
    fun `decoding an invalid keyIncluded byte throws`() {
        val bytes = sampleFrame().encode()
        val keyIncludedOffset = 1 + Frame.CLIP_HASH_SIZE + Frame.SENDER_DEVICE_ID_SIZE + 1 + 1 + 8 + 4 + 1 + 1
        bytes[keyIncludedOffset] = 7 // neither 0 nor 1

        assertFailsWith<FrameDecodeException> { Frame.decode(bytes) }
    }

    // --- Rejecting malformed field sizes at construction time ---

    @Test
    fun `constructing a frame with a wrong-sized clipHash throws`() {
        assertFailsWith<IllegalArgumentException> {
            sampleFrame().let {
                Frame(
                    clipHash = randomBytes(31),
                    senderDeviceId = it.senderDeviceId,
                    contentType = it.contentType,
                    hopCount = it.hopCount,
                    originatedAtMs = it.originatedAtMs,
                    ttlSeconds = it.ttlSeconds,
                    reachTier = it.reachTier,
                    dontRelay = it.dontRelay,
                    keyIncluded = it.keyIncluded,
                    contentEncryptionKey = it.contentEncryptionKey,
                    payload = it.payload,
                )
            }
        }
    }

    // --- copy() -- general-purpose field override helper (Phase 2 Slice 1) ---

    @Test
    fun `copy with no arguments returns a frame equal to the original`() {
        val original = sampleFrame(hopCount = 3, dontRelay = false)
        val copied = original.copy()
        assertEquals(original, copied)
    }

    @Test
    fun `copy overrides hopCount and leaves every other field unchanged`() {
        val original = sampleFrame(hopCount = 3, dontRelay = false)
        val copied = original.copy(hopCount = 4)

        assertEquals(4, copied.hopCount)
        assertEquals(original.dontRelay, copied.dontRelay)
        assertTrue(original.clipHash.contentEquals(copied.clipHash))
        assertTrue(original.senderDeviceId.contentEquals(copied.senderDeviceId))
        assertEquals(original.contentType, copied.contentType)
        assertEquals(original.originatedAtMs, copied.originatedAtMs)
        assertEquals(original.ttlSeconds, copied.ttlSeconds)
        assertEquals(original.reachTier, copied.reachTier)
        assertEquals(original.keyIncluded, copied.keyIncluded)
        assertTrue(original.contentEncryptionKey.contentEquals(copied.contentEncryptionKey))
        assertTrue(original.payload.contentEquals(copied.payload))
    }

    @Test
    fun `copy overrides dontRelay and leaves every other field unchanged`() {
        val original = sampleFrame(dontRelay = false)
        val copied = original.copy(dontRelay = true)

        assertEquals(true, copied.dontRelay)
        assertEquals(original.hopCount, copied.hopCount)
    }

    @Test
    fun `copy can override both hopCount and dontRelay at once`() {
        val original = sampleFrame(hopCount = 1, dontRelay = false)
        val copied = original.copy(hopCount = 2, dontRelay = true)

        assertEquals(2, copied.hopCount)
        assertEquals(true, copied.dontRelay)
    }

    @Test
    fun `copy result round trips through encode-decode identically to a hand-built frame`() {
        val original = sampleFrame(hopCount = 0)
        val copied = original.copy(hopCount = 1)

        val decoded = Frame.decode(copied.encode())

        assertEquals(copied, decoded)
        assertEquals(1, decoded.hopCount)
    }

    @Test
    fun `constructing a frame with a wrong-sized contentEncryptionKey throws`() {
        assertFailsWith<IllegalArgumentException> {
            sampleFrame().let {
                Frame(
                    clipHash = it.clipHash,
                    senderDeviceId = it.senderDeviceId,
                    contentType = it.contentType,
                    hopCount = it.hopCount,
                    originatedAtMs = it.originatedAtMs,
                    ttlSeconds = it.ttlSeconds,
                    reachTier = it.reachTier,
                    dontRelay = it.dontRelay,
                    keyIncluded = it.keyIncluded,
                    contentEncryptionKey = randomBytes(31),
                    payload = it.payload,
                )
            }
        }
    }
}
