package com.hop.preseed

import com.hop.crypto.ContentEncryption
import com.hop.crypto.DecayKeyStore
import com.hop.p2p.PeerChannel
import com.hop.protocol.ContentType
import com.hop.protocol.DontRelayFlagEnvelope
import com.hop.protocol.Frame
import com.hop.protocol.ReachTier
import com.hop.protocol.TierKeyRequestEnvelope
import com.hop.protocol.TierKeyResponseEnvelope
import com.hop.protocol.TierMembershipClaim
import com.hop.protocol.WireEnvelope
import com.hop.protocol.WirePayloadType
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Same test-clock shape as [ClipPackagerTest]'s own -- see that file's doc for why every real-code file in this codebase that needs one defines its own private copy. */
private class ContentServerTestClock(private var current: Instant) : Clock() {
    override fun getZone(): java.time.ZoneId = ZoneOffset.UTC
    override fun withZone(zone: java.time.ZoneId?): Clock = this
    override fun instant(): Instant = current
    fun advanceBy(duration: Duration) {
        current = current.plus(duration)
    }
}

/**
 * Loopback-socket coverage for [PreseedContentServer]: the connect-time
 * backlog offer, and the [TierKeyRequestEnvelope]/[TierKeyResponseEnvelope]
 * decision logic mirrored from `EnvelopeDispatcher.dispatch`'s own
 * `TIER_KEY_REQUEST` branch. Prioritizes the negative/denial cases (unknown
 * content, wrong tier, decayed key) over the single happy path, per this
 * codebase's own decay/reach-tier testing convention.
 */
class PreseedContentServerTest {

    private val latitude = 51.5074 // London
    private val longitude = -0.1278

    private fun connectedLoopbackPair(): Pair<PeerChannel, PeerChannel> {
        val loopback = InetAddress.getByName("127.0.0.1")
        val server = ServerSocket(0, 50, loopback)
        val client = Socket()
        client.connect(InetSocketAddress(loopback, server.localPort), 2_000)
        val accepted = server.accept()
        server.close()
        return PeerChannel(client) to PeerChannel(accepted)
    }

    private fun packageOneClip(
        decayKeyStore: DecayKeyStore,
        reachTier: ReachTier = ReachTier.TOWN,
        ttlSeconds: Long = 3_600,
    ): SeedClip = ClipPackager.packageClip(
        plaintext = "seeded clip bytes".toByteArray(),
        contentType = ContentType.PHOTO,
        reachTier = reachTier,
        latitude = latitude,
        longitude = longitude,
        ttlSeconds = ttlSeconds,
        senderDeviceId = ByteArray(16),
        decayKeyStore = decayKeyStore,
    )

    @Test
    fun `a newly connected peer receives every seeded clip as a POST_FRAME backlog offer`() {
        val decayKeyStore = DecayKeyStore()
        val clipA = packageOneClip(decayKeyStore)
        val clipB = packageOneClip(decayKeyStore)
        val server = PreseedContentServer(listOf(clipA, clipB), decayKeyStore)

        val (clientChannel, serverSideChannel) = connectedLoopbackPair()
        try {
            server.acceptInbound(serverSideChannel)

            val received = (1..2).map { clientChannel.receiveEnvelope() }
            assertTrue(received.all { it.type == WirePayloadType.POST_FRAME })
            val receivedHashes = received.map { Frame.decode(it.payload).clipHash.toList() }
            assertTrue(clipA.let { Frame.decode(it.encodedFrame).clipHash.toList() } in receivedHashes)
            assertTrue(clipB.let { Frame.decode(it.encodedFrame).clipHash.toList() } in receivedHashes)
        } finally {
            clientChannel.close()
        }
    }

    @Test
    fun `a valid, fresh, in-tier claim gets back the correct wrapped CEK, decryptable by ContentEncryption`() {
        val decayKeyStore = DecayKeyStore()
        val clip = packageOneClip(decayKeyStore)
        val server = PreseedContentServer(listOf(clip), decayKeyStore)

        val (clientChannel, serverSideChannel) = connectedLoopbackPair()
        try {
            server.acceptInbound(serverSideChannel)
            // Drain the backlog offer before exercising the tier-key request.
            clientChannel.receiveEnvelope()

            val request = TierKeyRequestEnvelope(
                contentId = Frame.decode(clip.encodedFrame).clipHash,
                claim = TierMembershipClaim(
                    reachTier = ReachTier.TOWN,
                    geohashPrefix = clip.originGeohashPrefix,
                    claimedAtMs = System.currentTimeMillis(),
                ),
            )
            clientChannel.sendEnvelope(WireEnvelope(WirePayloadType.TIER_KEY_REQUEST, request.encode()))

            val responseEnvelope = clientChannel.receiveEnvelope()
            assertEquals(WirePayloadType.TIER_KEY_RESPONSE, responseEnvelope.type)
            val response = TierKeyResponseEnvelope.decode(responseEnvelope.payload)
            assertTrue(response.granted, "a fresh, in-tier claim against a live post must be granted")

            val frame = Frame.decode(clip.encodedFrame)
            val decrypted = ContentEncryption.decrypt(ContentEncryption.keyFromBytes(response.wrappedCek), frame.payload)
            assertContentEquals("seeded clip bytes".toByteArray(), decrypted)
        } finally {
            clientChannel.close()
        }
    }

    @Test
    fun `a request for content this node never seeded is denied`() {
        val decayKeyStore = DecayKeyStore()
        val server = PreseedContentServer(emptyList(), decayKeyStore)

        val (clientChannel, serverSideChannel) = connectedLoopbackPair()
        try {
            server.acceptInbound(serverSideChannel)

            val request = TierKeyRequestEnvelope(
                contentId = ByteArray(Frame.CLIP_HASH_SIZE) { it.toByte() },
                claim = TierMembershipClaim(
                    reachTier = ReachTier.TOWN,
                    geohashPrefix = "gcpvj", // arbitrary, precision-5 TOWN prefix
                    claimedAtMs = System.currentTimeMillis(),
                ),
            )
            clientChannel.sendEnvelope(WireEnvelope(WirePayloadType.TIER_KEY_REQUEST, request.encode()))

            val response = TierKeyResponseEnvelope.decode(clientChannel.receiveEnvelope().payload)
            assertFalse(response.granted, "content this node never seeded must be denied")
        } finally {
            clientChannel.close()
        }
    }

    @Test
    fun `a claim whose reachTier does not match the post's own tier is denied`() {
        val decayKeyStore = DecayKeyStore()
        val clip = packageOneClip(decayKeyStore, reachTier = ReachTier.CITY)
        val server = PreseedContentServer(listOf(clip), decayKeyStore)

        val (clientChannel, serverSideChannel) = connectedLoopbackPair()
        try {
            server.acceptInbound(serverSideChannel)
            clientChannel.receiveEnvelope() // drain backlog

            // A TOWN claim against a CITY-tagged post -- different geohash
            // precisions, never directly comparable cells (see
            // EnvelopeDispatcher's own doc for the identical real-app check
            // this mirrors).
            val request = TierKeyRequestEnvelope(
                contentId = Frame.decode(clip.encodedFrame).clipHash,
                claim = TierMembershipClaim(
                    reachTier = ReachTier.TOWN,
                    // Arbitrary, syntactically-valid TOWN-precision (5) prefix --
                    // its actual cell content is irrelevant here since the
                    // tier mismatch itself must deny this request before any
                    // cell check even runs.
                    geohashPrefix = "gcpvj",
                    claimedAtMs = System.currentTimeMillis(),
                ),
            )
            clientChannel.sendEnvelope(WireEnvelope(WirePayloadType.TIER_KEY_REQUEST, request.encode()))

            val response = TierKeyResponseEnvelope.decode(clientChannel.receiveEnvelope().payload)
            assertFalse(response.granted, "a claim at the wrong tier must be denied, even for a post this node does hold")
        } finally {
            clientChannel.close()
        }
    }

    @Test
    fun `a claim against a post whose decay window has already closed is denied -- decay is real, not waived for seeded content`() {
        val clock = ContentServerTestClock(Instant.parse("2026-01-01T00:00:00Z"))
        val decayKeyStore = DecayKeyStore(clock)
        val clip = packageOneClip(decayKeyStore, ttlSeconds = 60)
        val server = PreseedContentServer(listOf(clip), decayKeyStore)

        // Past the seeded clip's own 60-second decay window.
        clock.advanceBy(Duration.ofSeconds(61))

        val (clientChannel, serverSideChannel) = connectedLoopbackPair()
        try {
            server.acceptInbound(serverSideChannel)
            clientChannel.receiveEnvelope() // drain backlog (still offered -- see PreseedNode's own "Decay is real" doc)

            val request = TierKeyRequestEnvelope(
                contentId = Frame.decode(clip.encodedFrame).clipHash,
                claim = TierMembershipClaim(
                    reachTier = ReachTier.TOWN,
                    geohashPrefix = clip.originGeohashPrefix,
                    claimedAtMs = clock.instant().toEpochMilli(),
                ),
            )
            clientChannel.sendEnvelope(WireEnvelope(WirePayloadType.TIER_KEY_REQUEST, request.encode()))

            val response = TierKeyResponseEnvelope.decode(clientChannel.receiveEnvelope().payload)
            assertFalse(response.granted, "a decayed key must never be released, even for a legitimate in-tier, fresh claim")
        } finally {
            clientChannel.close()
        }
    }

    @Test
    fun `an envelope type this tool doesn't handle is ignored, not fatal -- the receive loop keeps serving afterward`() {
        val decayKeyStore = DecayKeyStore()
        val clip = packageOneClip(decayKeyStore)
        val server = PreseedContentServer(listOf(clip), decayKeyStore)

        val (clientChannel, serverSideChannel) = connectedLoopbackPair()
        try {
            server.acceptInbound(serverSideChannel)
            clientChannel.receiveEnvelope() // drain backlog

            // This tool is not a relay node -- a DONT_RELAY_FLAG (or any
            // non-TIER_KEY_REQUEST envelope) sent at it must be silently
            // ignored, not crash the receive loop.
            val junkFlag = DontRelayFlagEnvelope(
                clipHash = Frame.decode(clip.encodedFrame).clipHash,
                attestedDeviceKey = ByteArray(32),
                flaggedAtMs = System.currentTimeMillis(),
                originatedAtMs = System.currentTimeMillis(),
                ttlSeconds = 3_600,
            )
            clientChannel.sendEnvelope(WireEnvelope(WirePayloadType.DONT_RELAY_FLAG, junkFlag.encode()))

            // The receive loop must still be alive afterward -- a genuine
            // tier-key request sent right after gets a real answer.
            val request = TierKeyRequestEnvelope(
                contentId = Frame.decode(clip.encodedFrame).clipHash,
                claim = TierMembershipClaim(
                    reachTier = ReachTier.TOWN,
                    geohashPrefix = clip.originGeohashPrefix,
                    claimedAtMs = System.currentTimeMillis(),
                ),
            )
            clientChannel.sendEnvelope(WireEnvelope(WirePayloadType.TIER_KEY_REQUEST, request.encode()))

            val response = TierKeyResponseEnvelope.decode(clientChannel.receiveEnvelope().payload)
            assertTrue(response.granted, "the receive loop must keep serving real requests after an ignored envelope type")
        } finally {
            clientChannel.close()
        }
    }
}
