package com.hop.protocol

import com.hop.crypto.DecayKeyStore
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import javax.crypto.AEADBadTagException
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

/**
 * A [Clock] whose instant can be advanced under test control, so decay-window
 * expiry can be tested deterministically instead of via a real sleep. Mirrors
 * `crypto/`'s `DecayKeyStoreTest.MutableClock`.
 */
private class MutableClock(private var current: Instant) : Clock() {
    override fun getZone(): java.time.ZoneId = ZoneOffset.UTC
    override fun withZone(zone: java.time.ZoneId?): Clock = this
    override fun instant(): Instant = current
    fun advanceBy(duration: Duration) {
        current = current.plus(duration)
    }
}

/**
 * Covers the encrypt/decrypt orchestration between plaintext post content and
 * the version-2 [Frame] wire envelope, per ADR 0003's decay-by-key-deletion
 * primitive. Mirrors [FrameTest]'s rigor: round-trip, the actual decay
 * negative case (not just happy-path encrypt/decrypt), and tamper detection.
 */
class EncryptedFrameCodecTest {

    private val random = SecureRandom()

    private fun randomBytes(size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }

    private fun sampleClipHash(): ByteArray = randomBytes(Frame.CLIP_HASH_SIZE)

    private fun encodeSample(
        plaintext: ByteArray,
        clipHash: ByteArray = sampleClipHash(),
        ttlSeconds: Long = 3600L,
    ): Pair<ByteArray, ByteArray> {
        val encoded = EncryptedFrameCodec.encode(
            plaintext = plaintext,
            clipHash = clipHash,
            senderDeviceId = randomBytes(Frame.SENDER_DEVICE_ID_SIZE),
            contentType = ContentType.PHOTO,
            hopCount = 0,
            originatedAtMs = 1_700_000_000_000L,
            ttlSeconds = ttlSeconds,
            reachTier = ReachTier.LOCALITY,
            dontRelay = false,
        )
        return encoded to clipHash
    }

    // --- Round trip: encode -> decode returns the original plaintext ---

    @Test
    fun `encode then decode returns the original plaintext`() {
        val plaintext = "a locality-tier post".toByteArray()
        val (encoded, _) = encodeSample(plaintext)
        val decayKeyStore = DecayKeyStore()

        val decrypted = EncryptedFrameCodec.decode(encoded, decayKeyStore)

        assertContentEquals(plaintext, decrypted)
    }

    @Test
    fun `encoded frame carries keyIncluded true and payload is not the plaintext`() {
        val plaintext = randomBytes(2048)
        val (encoded, _) = encodeSample(plaintext)

        val frame = Frame.decode(encoded)

        // Phase 1 always inlines the key -- Town/City/Country's keyIncluded=false
        // path is Phase 4, not exercised here.
        assert(frame.keyIncluded)
        assert(!frame.payload.contentEquals(plaintext)) {
            "payload should be ciphertext, not the plaintext bytes"
        }
    }

    @Test
    fun `each encode call uses a fresh content encryption key`() {
        val plaintext = randomBytes(64)
        val (encodedA, _) = encodeSample(plaintext)
        val (encodedB, _) = encodeSample(plaintext)

        val cekA = Frame.decode(encodedA).contentEncryptionKey
        val cekB = Frame.decode(encodedB).contentEncryptionKey

        assert(!cekA.contentEquals(cekB)) { "expected distinct random CEKs per encode() call" }
    }

    // --- Decay: decryptFromStore fails closed once the decay window elapses ---

    @Test
    fun `decryptFromStore succeeds while the key is still live, then returns null after decay`() {
        val plaintext = "expires after ttl".toByteArray()
        val ttlSeconds = 60L
        val (encoded, clipHash) = encodeSample(plaintext, ttlSeconds = ttlSeconds)

        val mutableClock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
        val decayKeyStore = DecayKeyStore(clock = mutableClock)

        // Receive-time decode: always succeeds, this is the "key just arrived" path.
        val frame = Frame.decode(encoded)
        val receivedPlaintext = EncryptedFrameCodec.decode(encoded, decayKeyStore)
        assertContentEquals(plaintext, receivedPlaintext)

        // Still within the decay window: a later re-decrypt (e.g. reopening a
        // cached post) succeeds.
        mutableClock.advanceBy(Duration.ofSeconds(ttlSeconds - 1))
        val stillLive = EncryptedFrameCodec.decryptFromStore(clipHash, frame.payload, decayKeyStore)
        assertNotNull(stillLive)
        assertContentEquals(plaintext, stillLive)

        // Decay window has elapsed: the key is gone from the store, so the
        // ciphertext (which the DHT/local cache may still hold regardless,
        // per ADR 0003) is now opaque to this honest client.
        mutableClock.advanceBy(Duration.ofSeconds(2))
        val afterDecay = EncryptedFrameCodec.decryptFromStore(clipHash, frame.payload, decayKeyStore)
        assertNull(afterDecay)
    }

    @Test
    fun `decryptFromStore returns null for a clipHash that was never stored`() {
        val decayKeyStore = DecayKeyStore()
        val result = EncryptedFrameCodec.decryptFromStore(
            clipHash = sampleClipHash(),
            encryptedPayload = randomBytes(64),
            decayKeyStore = decayKeyStore,
        )
        assertNull(result)
    }

    // --- decode() with no key present has nothing to decrypt with ---

    @Test
    fun `decode throws when the frame has no inline key`() {
        val frameWithoutKey = Frame(
            clipHash = sampleClipHash(),
            senderDeviceId = randomBytes(Frame.SENDER_DEVICE_ID_SIZE),
            contentType = ContentType.PHOTO,
            hopCount = 0,
            originatedAtMs = 0L,
            ttlSeconds = 3600L,
            reachTier = ReachTier.LOCALITY,
            dontRelay = false,
            keyIncluded = false,
            contentEncryptionKey = ByteArray(Frame.CONTENT_ENCRYPTION_KEY_SIZE),
            payload = randomBytes(32),
        )
        val decayKeyStore = DecayKeyStore()

        assertFailsWith<EncryptedFrameCodec.EncryptedFrameDecodeException> {
            EncryptedFrameCodec.decode(frameWithoutKey.encode(), decayKeyStore)
        }
    }

    // --- Tamper detection: GCM's tag must catch a corrupted ciphertext ---

    @Test
    fun `decode throws rather than returning garbage when the ciphertext payload is corrupted`() {
        val plaintext = randomBytes(256)
        val (encoded, _) = encodeSample(plaintext)

        // Corrupt one byte inside the payload (after the fixed HEADER_SIZE), matching
        // ContentEncryption's own documented fail-closed behavior on tamper.
        val tampered = encoded.copyOf()
        tampered[Frame.HEADER_SIZE] = (tampered[Frame.HEADER_SIZE] + 1).toByte()

        val decayKeyStore = DecayKeyStore()
        assertFailsWith<AEADBadTagException> {
            EncryptedFrameCodec.decode(tampered, decayKeyStore)
        }
    }

    @Test
    fun `decryptFromStore throws rather than returning garbage when the ciphertext is corrupted`() {
        val plaintext = randomBytes(256)
        val (encoded, clipHash) = encodeSample(plaintext)
        val decayKeyStore = DecayKeyStore()

        val frame = Frame.decode(encoded)
        EncryptedFrameCodec.decode(encoded, decayKeyStore) // populate the store

        val tamperedPayload = frame.payload.copyOf()
        tamperedPayload[0] = (tamperedPayload[0] + 1).toByte()

        assertFailsWith<AEADBadTagException> {
            EncryptedFrameCodec.decryptFromStore(clipHash, tamperedPayload, decayKeyStore)
        }
    }
}
