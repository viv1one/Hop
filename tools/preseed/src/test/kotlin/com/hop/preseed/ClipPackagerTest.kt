package com.hop.preseed

import com.hop.crypto.ContentEncryption
import com.hop.crypto.DecayKeyStore
import com.hop.protocol.ContentType
import com.hop.protocol.Frame
import com.hop.protocol.Geohash
import com.hop.protocol.ReachTier
import com.hop.protocol.ReachTierGeohash
import com.hop.protocol.ReachTierKeyDistribution
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A [Clock] whose instant can be advanced under test control, same shape as
 * this codebase's other `MutableClock`/`AdvanceableTestClock` test helpers
 * (see e.g. `crypto/`'s `DecayKeyStoreTest`). Named uniquely per test file in
 * this module (`PackagerTestClock` here, `ContentServerTestClock` in
 * [PreseedContentServerTest], `RoundTripTestClock` in [PreseedRoundTripTest])
 * because a private top-level class still collides by simple name at the JVM
 * class-file level across files in the same package/module -- see
 * `protocol/`'s `ReachTierKeyDistributionTest`'s own `AdvanceableTestClock`
 * doc for the identical reasoning.
 */
private class PackagerTestClock(private var current: Instant) : Clock() {
    override fun getZone(): java.time.ZoneId = ZoneOffset.UTC
    override fun withZone(zone: java.time.ZoneId?): Clock = this
    override fun instant(): Instant = current
    fun advanceBy(duration: Duration) {
        current = current.plus(duration)
    }
}

/**
 * Covers [ClipPackager.packageClip] -- the real-wire-shape packaging step
 * every other class in this module builds on. Confirms the packaged
 * [SeedClip] is a genuine, real [Frame] (decodable, correct fields, a real
 * usable content-encryption key stored under the exact [ReachTierKeyDistribution]
 * composition a real [com.hop.protocol.TierKeyRequestEnvelope] lookup would
 * use), and that Locality is refused outright rather than silently accepted.
 */
class ClipPackagerTest {

    private val plaintext = "a pre-seeded clip's plaintext bytes".toByteArray()
    private val senderDeviceId = ByteArray(16) { it.toByte() }
    private val latitude = 40.7128 // New York
    private val longitude = -74.0060

    @Test
    fun `packaging a clip produces a real, decodable Frame with the expected fields`() {
        val decayKeyStore = DecayKeyStore()
        val clip = ClipPackager.packageClip(
            plaintext = plaintext,
            contentType = ContentType.VIDEO,
            reachTier = ReachTier.CITY,
            latitude = latitude,
            longitude = longitude,
            ttlSeconds = 3_600,
            senderDeviceId = senderDeviceId,
            decayKeyStore = decayKeyStore,
            originatedAtMs = 1_700_000_000_000,
        )

        val frame = Frame.decode(clip.encodedFrame)
        assertEquals(ContentType.VIDEO, frame.contentType)
        assertEquals(ReachTier.CITY, frame.reachTier)
        assertEquals(0, frame.hopCount)
        assertEquals(false, frame.dontRelay)
        assertEquals(1_700_000_000_000, frame.originatedAtMs)
        assertEquals(3_600, frame.ttlSeconds)
        assertContentEquals(senderDeviceId, frame.senderDeviceId)

        // Non-Locality: the real key is never inlined on the wire (ADR 0003)
        // -- keyIncluded must be false and the wire copy zero-filled.
        assertEquals(false, frame.keyIncluded)
        assertTrue(frame.contentEncryptionKey.all { it == 0.toByte() })

        // originGeohashPrefix is derived from the real target coordinates, at
        // CITY's own precision -- never the raw lat/lon themselves.
        val expectedPrefix = Geohash.encode(latitude, longitude, ReachTierGeohash.precisionFor(ReachTier.CITY))
        assertEquals(expectedPrefix, frame.originGeohashPrefix)
        assertEquals(expectedPrefix, clip.originGeohashPrefix)

        assertEquals(latitude, clip.latitude)
        assertEquals(longitude, clip.longitude)
        assertEquals(ReachTier.CITY, clip.reachTier)
    }

    @Test
    fun `the real content-encryption key is stored under ReachTierKeyDistribution's per-tier composition and actually decrypts the payload`() {
        val decayKeyStore = DecayKeyStore()
        val clip = ClipPackager.packageClip(
            plaintext = plaintext,
            contentType = ContentType.PHOTO,
            reachTier = ReachTier.TOWN,
            latitude = latitude,
            longitude = longitude,
            ttlSeconds = 3_600,
            senderDeviceId = senderDeviceId,
            decayKeyStore = decayKeyStore,
        )

        // The exact same lookup a real TIER_KEY_REQUEST responder
        // (PreseedContentServer / the app's own EnvelopeDispatcher) uses.
        val storageKey = ReachTierKeyDistribution.decayKeyStorageKey(clip.contentId, ReachTier.TOWN)
        val wrappedCek = decayKeyStore.retrieve(storageKey)
        assertNotNull(wrappedCek, "the real CEK must be retrievable under ReachTierKeyDistribution's own storage-key composition")

        val frame = Frame.decode(clip.encodedFrame)
        val decrypted = ContentEncryption.decrypt(ContentEncryption.keyFromBytes(wrappedCek), frame.payload)
        assertContentEquals(plaintext, decrypted)
    }

    @Test
    fun `contentId is the hex-encoded SHA-256 of the plaintext, matching PostComposerViewModel's own convention`() {
        val decayKeyStore = DecayKeyStore()
        val clip = ClipPackager.packageClip(
            plaintext = plaintext,
            contentType = ContentType.PHOTO,
            reachTier = ReachTier.TOWN,
            latitude = latitude,
            longitude = longitude,
            ttlSeconds = 3_600,
            senderDeviceId = senderDeviceId,
            decayKeyStore = decayKeyStore,
        )

        val expectedHash = java.security.MessageDigest.getInstance("SHA-256").digest(plaintext)
        val expectedHex = expectedHash.joinToString("") { "%02x".format(it) }
        assertEquals(expectedHex, clip.contentId)

        val frame = Frame.decode(clip.encodedFrame)
        assertContentEquals(expectedHash, frame.clipHash)
    }

    @Test
    fun `packaging at LOCALITY is refused outright -- structurally impossible to pre-seed over the internet`() {
        val decayKeyStore = DecayKeyStore()
        assertFailsWith<IllegalArgumentException> {
            ClipPackager.packageClip(
                plaintext = plaintext,
                contentType = ContentType.PHOTO,
                reachTier = ReachTier.LOCALITY,
                latitude = latitude,
                longitude = longitude,
                ttlSeconds = 3_600,
                senderDeviceId = senderDeviceId,
                decayKeyStore = decayKeyStore,
            )
        }
    }

    @Test
    fun `a zero-second ttl packages successfully but the key is immediately undecryptable -- decay is real, not waived`() {
        val clock = PackagerTestClock(Instant.parse("2026-01-01T00:00:00Z"))
        val decayKeyStore = DecayKeyStore(clock)
        val clip = ClipPackager.packageClip(
            plaintext = plaintext,
            contentType = ContentType.PHOTO,
            reachTier = ReachTier.TOWN,
            latitude = latitude,
            longitude = longitude,
            ttlSeconds = 0,
            senderDeviceId = senderDeviceId,
            decayKeyStore = decayKeyStore,
        )

        val storageKey = ReachTierKeyDistribution.decayKeyStorageKey(clip.contentId, ReachTier.TOWN)
        assertNull(decayKeyStore.retrieve(storageKey), "a zero-ttl seeded clip must decay immediately, exactly like any other post -- no seeded-content exemption")
    }
}
