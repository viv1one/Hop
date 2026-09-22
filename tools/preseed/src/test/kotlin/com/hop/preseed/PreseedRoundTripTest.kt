package com.hop.preseed

import com.hop.crypto.ContentEncryption
import com.hop.crypto.DecayKeyStore
import com.hop.dht.Contact
import com.hop.dht.DhtNode
import com.hop.dht.DhtUdpTransport
import com.hop.dht.NodeId
import com.hop.dht.PeerAddress
import com.hop.dht.RoutingTable
import com.hop.p2p.PeerChannel
import com.hop.p2p.PeerDialer
import com.hop.protocol.ContentType
import com.hop.protocol.Frame
import com.hop.protocol.ReachTier
import com.hop.protocol.TierKeyRequestEnvelope
import com.hop.protocol.TierKeyResponseEnvelope
import com.hop.protocol.TierMembershipClaim
import com.hop.protocol.WireEnvelope
import com.hop.protocol.WirePayloadType
import com.hop.rendezvous.RendezvousNode
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/** Same test-clock shape as [ClipPackagerTest]/[PreseedContentServerTest]'s own -- see either file's doc for why every real-code test file in this codebase defines its own private copy. */
private class RoundTripTestClock(private var current: Instant) : Clock() {
    override fun getZone(): java.time.ZoneId = ZoneOffset.UTC
    override fun withZone(zone: java.time.ZoneId?): Clock = this
    override fun instant(): Instant = current
    fun advanceBy(duration: Duration) {
        current = current.plus(duration)
    }
}

/**
 * The end-to-end round trip this task's spec calls for: [PreseedNode] seeds
 * a clip and joins the DHT through a real [RendezvousNode] (ADR 0002); a
 * second, independent in-process peer (built directly from `dht`/`topics`/
 * `p2p` -- this module has no app-layer `DhtNodeManager` to reuse, since that
 * class lives in `mobile/android/app` and depends on Android) bootstraps
 * through the same rendezvous node, browses the seeded clip's target cell,
 * discovers [PreseedNode] as a holder, dials in over a real TCP socket,
 * receives the seeded content as backlog, requests the tier key with a
 * valid claim, is granted it, and successfully decrypts the original
 * plaintext. A second test proves the negative/decay case explicitly, per
 * this codebase's own decay-testing convention: a claim presented after the
 * seeded clip's decay window has closed must be denied.
 *
 * Real sockets throughout (loopback), no mocking -- matching this
 * codebase's "drive real production classes over real sockets" convention
 * (see `DhtNodeManagerTest`, `RendezvousNodeTest`).
 */
class PreseedRoundTripTest {

    private val latitude = 48.8566 // Paris
    private val longitude = 2.3522
    private val tier = ReachTier.CITY

    private fun loopbackSocket(): DatagramSocket = DatagramSocket(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))

    private fun startRendezvousNode(): Pair<RendezvousNode, PeerAddress> {
        val socket = loopbackSocket()
        val address = PeerAddress.from(InetAddress.getLoopbackAddress(), socket.localPort)
        val node = RendezvousNode(socket, NodeId.fromKeyMaterial("rendezvous-under-test"))
        node.start()
        return node to address
    }

    /**
     * A minimal, standalone "browsing peer" -- everything a real device's
     * [com.hop.app.dht.DhtNodeManager]/`TopicSubscription` wiring would do,
     * assembled directly here since this module cannot depend on the
     * Android app. Returns the live [TopicSubscription] plus a teardown
     * function.
     */
    private class BrowsingPeer(
        val ownId: NodeId,
        val dhtNode: DhtNode,
        val transport: DhtUdpTransport,
        val scope: CoroutineScope,
    ) {
        val topicSubscription = com.hop.topics.TopicSubscription(dhtNode)
        fun stop() {
            transport.stop()
            scope.cancel()
        }
    }

    private suspend fun startBrowsingPeer(seed: ByteArray, bootstrapAddress: PeerAddress): BrowsingPeer {
        val ownId = NodeId.fromKeyMaterial(seed)
        val socket = loopbackSocket()
        val ownAddress = PeerAddress.from(InetAddress.getLoopbackAddress(), socket.localPort)
        val routingTable = RoutingTable(ownId = ownId)
        val transport = DhtUdpTransport(socket, ownId)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dhtNode = DhtNode(routingTable, transport, scope, listOf(ownAddress))
        transport.start()
        dhtNode.bootstrapJoin(bootstrapAddress)
        return BrowsingPeer(ownId, dhtNode, transport, scope)
    }

    @Test
    fun `a real peer discovers, dials, receives, and decrypts a seeded clip, granted its tier key`() = runBlocking {
        val (rendezvousNode, rendezvousAddress) = startRendezvousNode()
        var preseedNode: PreseedNode? = null
        var browsingPeer: BrowsingPeer? = null
        try {
            val decayKeyStore = DecayKeyStore()
            val plaintext = "round-trip pre-seeded clip bytes".toByteArray()
            val clip = ClipPackager.packageClip(
                plaintext = plaintext,
                contentType = ContentType.VIDEO,
                reachTier = tier,
                latitude = latitude,
                longitude = longitude,
                ttlSeconds = 3_600,
                senderDeviceId = ByteArray(16),
                decayKeyStore = decayKeyStore,
            )

            preseedNode = PreseedNode(
                ownNodeIdSeed = "preseed-node-under-test".toByteArray(),
                bootstrapHost = "127.0.0.1",
                bootstrapPort = rendezvousAddress.port,
                seededClips = listOf(clip),
                decayKeyStore = decayKeyStore,
            )
            preseedNode.start()
            preseedNode.publishSeededContent()

            browsingPeer = startBrowsingPeer(seed = "browsing-peer-under-test".toByteArray(), bootstrapAddress = rendezvousAddress)

            val holders = browsingPeer.topicSubscription.browse(latitude, longitude, tier)
            assertTrue(holders.isNotEmpty(), "the browsing peer must discover the preseed node as a holder for the seeded clip's target cell")
            val preseedContact: Contact = holders.first { it.id == preseedNode.ownNodeId }

            val candidates = PeerAddress.decodeList(preseedContact.address)
            val socket = PeerDialer.dial(candidates)
            val channel = PeerChannel(socket)
            try {
                // Connect-time backlog offer -- the seeded clip arrives
                // unsolicited, exactly like InternetPeerConnectionManager's
                // own sendBacklog offers a real newly-connected peer's queue.
                val backlogEnvelope = channel.receiveEnvelope()
                assertEquals(WirePayloadType.POST_FRAME, backlogEnvelope.type)
                val receivedFrame = Frame.decode(backlogEnvelope.payload)
                assertContentEquals(clip.let { Frame.decode(it.encodedFrame).clipHash }, receivedFrame.clipHash)
                assertFalse(receivedFrame.keyIncluded, "a Town/City/Country frame must never inline its real key on the wire -- ADR 0003")

                // Request the tier key with a valid, fresh, in-cell claim.
                val request = TierKeyRequestEnvelope(
                    contentId = receivedFrame.clipHash,
                    claim = TierMembershipClaim(
                        reachTier = tier,
                        geohashPrefix = receivedFrame.originGeohashPrefix,
                        claimedAtMs = System.currentTimeMillis(),
                    ),
                )
                channel.sendEnvelope(WireEnvelope(WirePayloadType.TIER_KEY_REQUEST, request.encode()))

                val responseEnvelope = channel.receiveEnvelope()
                assertEquals(WirePayloadType.TIER_KEY_RESPONSE, responseEnvelope.type)
                val response = TierKeyResponseEnvelope.decode(responseEnvelope.payload)
                assertTrue(response.granted, "a fresh, in-tier claim against a live seeded clip must be granted its key")

                val decrypted = ContentEncryption.decrypt(ContentEncryption.keyFromBytes(response.wrappedCek), receivedFrame.payload)
                assertContentEquals(plaintext, decrypted)
            } finally {
                channel.close()
            }
        } finally {
            browsingPeer?.stop()
            preseedNode?.stop()
            rendezvousNode.stop()
        }
    }

    @Test
    fun `a claim presented after the seeded clip's decay window has closed is denied -- the negative case, tested explicitly`() = runBlocking {
        val (rendezvousNode, rendezvousAddress) = startRendezvousNode()
        var preseedNode: PreseedNode? = null
        var browsingPeer: BrowsingPeer? = null
        try {
            val clock = RoundTripTestClock(Instant.parse("2026-01-01T00:00:00Z"))
            val decayKeyStore = DecayKeyStore(clock)
            val clip = ClipPackager.packageClip(
                plaintext = "this clip will decay before anyone asks for its key".toByteArray(),
                contentType = ContentType.PHOTO,
                reachTier = tier,
                latitude = latitude,
                longitude = longitude,
                ttlSeconds = 60,
                senderDeviceId = ByteArray(16),
                decayKeyStore = decayKeyStore,
                originatedAtMs = clock.instant().toEpochMilli(),
            )

            preseedNode = PreseedNode(
                ownNodeIdSeed = "preseed-node-decay-test".toByteArray(),
                bootstrapHost = "127.0.0.1",
                bootstrapPort = rendezvousAddress.port,
                seededClips = listOf(clip),
                decayKeyStore = decayKeyStore,
            )
            preseedNode.start()
            preseedNode.publishSeededContent()

            browsingPeer = startBrowsingPeer(seed = "browsing-peer-decay-test".toByteArray(), bootstrapAddress = rendezvousAddress)
            val holders = browsingPeer.topicSubscription.browse(latitude, longitude, tier)
            val preseedContact = holders.first { it.id == preseedNode.ownNodeId }

            // Past the seeded clip's own 60-second decay window -- an
            // honest client must no longer be able to get the unwrap key,
            // per ADR 0003's key-lifecycle enforcement (deletion on expiry
            // read, not client politeness).
            clock.advanceBy(Duration.ofSeconds(61))

            val socket = PeerDialer.dial(PeerAddress.decodeList(preseedContact.address))
            val channel = PeerChannel(socket)
            try {
                val backlogEnvelope = channel.receiveEnvelope() // still offered -- see PreseedNode's own "Decay is real" doc
                val receivedFrame = Frame.decode(backlogEnvelope.payload)

                val request = TierKeyRequestEnvelope(
                    contentId = receivedFrame.clipHash,
                    claim = TierMembershipClaim(
                        reachTier = tier,
                        geohashPrefix = receivedFrame.originGeohashPrefix,
                        claimedAtMs = clock.instant().toEpochMilli(),
                    ),
                )
                channel.sendEnvelope(WireEnvelope(WirePayloadType.TIER_KEY_REQUEST, request.encode()))

                val response = TierKeyResponseEnvelope.decode(channel.receiveEnvelope().payload)
                assertFalse(response.granted, "a client holding an expired key must fail to decrypt -- a decayed post's key must never be released, even to a valid, fresh, in-tier claim")
            } finally {
                channel.close()
            }
        } finally {
            browsingPeer?.stop()
            preseedNode?.stop()
            rendezvousNode.stop()
        }
    }
}
