package com.hop.transport

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hop.crypto.DecayKeyStore
import com.hop.data.DontRelayFlagEntity
import com.hop.data.HopDatabase
import com.hop.data.RoomDecayKeyStorage
import com.hop.protocol.ContentType
import com.hop.protocol.DontRelayFlagEnvelope
import com.hop.protocol.EncryptedFrameCodec
import com.hop.protocol.Frame
import com.hop.protocol.ReachTier
import com.hop.protocol.RelayPolicy
import com.hop.protocol.WireEnvelope
import com.hop.protocol.WirePayloadType
import com.hop.repository.DontRelayRepository
import com.hop.repository.PendingMessageRepository
import com.hop.repository.PostRepository
import com.hop.repository.RelayRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Instrumented end-to-end tests for Phase 2 Slice 2's "don't relay"
 * distinct-attested-device flag counter (PRD §4.6, ADR 0004) -- a sibling to
 * [RelayTest], not an extension of it, since this file needs its own
 * harness variant that also drives [DontRelayFlagEnvelope]/[DontRelayRepository]
 * traffic, not just [Frame]/[RelayRepository] traffic. Same posture as
 * [RelayTest]'s own doc: real production classes ([EnvelopeDispatcher],
 * [DispatchResult], [DontRelayRepository], [RelayRepository], [RelayPolicy],
 * real `crypto/` [DecayKeyStore]) driven over plain loopback socket pairs --
 * only the socket plumbing itself is test-only glue.
 */
@RunWith(AndroidJUnit4::class)
class DontRelayTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val sockets = mutableListOf<Socket>()
    private val closeableDbs = mutableListOf<HopDatabase>()

    @After
    fun tearDown() {
        sockets.forEach { runCatching { it.close() } }
        closeableDbs.forEach { runCatching { it.close() } }
    }

    private class MutableClock(private var current: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = current
    }

    /** One simulated device's full local relay + "don't relay" stack -- mirrors [RelayTest.Party], plus [dontRelayRepository]. */
    private inner class Party(val name: String, val db: HopDatabase, clock: Clock, threshold: Int) {
        val postsDir: File = tempFolder.newFolder("posts-$name-${UUID.randomUUID()}")
        val activeLinks = CopyOnWriteArrayList<FlagLink>()

        private val decayKeyStore = DecayKeyStore(clock, RoomDecayKeyStorage(db.decayKeyDao()))
        val postRepository = PostRepository(db.postDao(), decayKeyStore)
        private val relayPolicy = RelayPolicy(clock = clock)
        val dontRelayRepository = DontRelayRepository(db.dontRelayFlagDao(), db.relayQueueDao(), relayPolicy, threshold = threshold)
        val relayRepository = RelayRepository(
            db.relayQueueDao(),
            relayPolicy,
            isFlaggedForSuppression = dontRelayRepository::isSuppressed,
        )
        private val receivedFrameStore = ReceivedFrameStore(postRepository, decayKeyStore, postsDir, relayPolicy)
        private val pendingMessageRepository = PendingMessageRepository(db.pendingMessageDao(), relayPolicy)
        val dispatcher = EnvelopeDispatcher(
            receivedFrameStore = receivedFrameStore,
            dontRelayRepository = dontRelayRepository,
            pendingMessageRepository = pendingMessageRepository,
            getOwnPeerId = { name },
            onPreKeyBundleReceived = { _, _ -> },
            onMessageCiphertextReceived = { _, _ -> },
        )
    }

    /**
     * Test-only stand-in for a live peer connection carrying both post frames
     * and "don't relay" flags -- mirrors [WifiDirectTransportMessagingTest.LoopbackLink]'s
     * shape (a public [send] plus a background receive loop), extended to
     * also relay a genuinely-new flag onward, mirroring
     * [WifiDirectTransport.handleNewlyReceivedDontRelayFlag].
     */
    private class FlagLink(private val party: Party, private val socket: Socket) {
        private val out = DataOutputStream(socket.getOutputStream())
        private val writeLock = Any()

        fun send(type: WirePayloadType, payload: ByteArray): Boolean =
            rawSend(WireEnvelope.encode(type, payload))

        fun onConnected() {
            party.activeLinks.add(this)
            val postBacklog = runBlocking { party.relayRepository.buildOutgoingBacklog() }
            for (envelopeBytes in postBacklog) rawSend(envelopeBytes)
            val flagBacklog = runBlocking { party.dontRelayRepository.buildOutgoingFlagBacklog() }
            for (envelopeBytes in flagBacklog) rawSend(envelopeBytes)
        }

        private fun rawSend(alreadyEncodedEnvelope: ByteArray): Boolean = synchronized(writeLock) {
            try {
                out.write(alreadyEncodedEnvelope)
                out.flush()
                true
            } catch (e: Exception) {
                false
            }
        }

        fun startReceiving() {
            Thread({
                try {
                    val input = DataInputStream(socket.getInputStream())
                    while (true) {
                        val typeByte = try {
                            input.readUnsignedByte()
                        } catch (e: Exception) {
                            break
                        }
                        val length = input.readInt()
                        val payload = ByteArray(length)
                        input.readFully(payload)
                        val envelope = WireEnvelope(WirePayloadType.fromWireValue(typeByte), payload)
                        when (val result = runBlocking { party.dispatcher.dispatch(envelope) }) {
                            is DispatchResult.NewPostFrame -> {
                                runBlocking { party.relayRepository.considerForRelay(result.frame) }
                                val outgoing = result.frame.copy(hopCount = result.frame.hopCount + 1).encode()
                                for (other in party.activeLinks) {
                                    if (other === this) continue
                                    other.send(WirePayloadType.POST_FRAME, outgoing)
                                }
                            }
                            is DispatchResult.NewDontRelayFlag -> {
                                val flagEnvelope = DontRelayFlagEnvelope(
                                    clipHash = result.row.clipHash.hexToBytes(),
                                    attestedDeviceKey = result.row.attestedDeviceKey.hexToBytes(),
                                    flaggedAtMs = result.row.flaggedAtMs,
                                    originatedAtMs = result.row.originatedAtMs,
                                    ttlSeconds = result.row.ttlSeconds,
                                )
                                for (other in party.activeLinks) {
                                    if (other === this) continue
                                    other.send(WirePayloadType.DONT_RELAY_FLAG, flagEnvelope.encode())
                                }
                            }
                            else -> Unit
                        }
                    }
                } catch (e: Exception) {
                    // socket closed / test teardown -- expected.
                } finally {
                    party.activeLinks.remove(this)
                }
            }, "test-dont-relay-receive-${party.name}").start()
        }

        fun close() {
            party.activeLinks.remove(this)
            runCatching { socket.close() }
        }
    }

    private fun newInMemoryDb(): HopDatabase {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), HopDatabase::class.java).build()
        closeableDbs.add(db)
        return db
    }

    private fun connect(a: Party, b: Party): Pair<FlagLink, FlagLink> {
        val serverSocket = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val accepted = CountDownLatch(1)
        var acceptedSocket: Socket? = null
        Thread({
            acceptedSocket = serverSocket.accept()
            accepted.countDown()
        }, "test-accept").start()

        val clientSocket = Socket(InetAddress.getLoopbackAddress(), serverSocket.localPort)
        assertTrue(accepted.await(5, TimeUnit.SECONDS), "loopback accept did not complete in time")
        serverSocket.close()
        val bSocket = requireNotNull(acceptedSocket)
        sockets += clientSocket
        sockets += bSocket

        val linkA = FlagLink(a, clientSocket)
        val linkB = FlagLink(b, bSocket)
        linkA.onConnected()
        linkB.onConnected()
        linkA.startReceiving()
        linkB.startReceiving()
        return linkA to linkB
    }

    private fun composeFrame(seed: String, originatedAtMs: Long, ttlSeconds: Long = 3600L): Frame {
        val plaintext = "dont-relay test payload for $seed".toByteArray()
        val clipHash = MessageDigest.getInstance("SHA-256").digest(plaintext)
        val encoded = EncryptedFrameCodec.encode(
            plaintext = plaintext,
            clipHash = clipHash,
            senderDeviceId = ByteArray(Frame.SENDER_DEVICE_ID_SIZE) { it.toByte() },
            contentType = ContentType.PHOTO,
            hopCount = 0,
            originatedAtMs = originatedAtMs,
            ttlSeconds = ttlSeconds,
            reachTier = ReachTier.LOCALITY,
            dontRelay = false,
        )
        return Frame.decode(encoded)
    }

    private fun clipHashHex(frame: Frame): String = frame.clipHash.joinToString(separator = "") { "%02x".format(it) }

    private fun awaitSuppressed(party: Party, clipHashHex: String, timeoutMs: Long = 5_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (runBlocking { party.dontRelayRepository.isSuppressed(clipHashHex) }) return true
            Thread.sleep(20)
        }
        return runBlocking { party.dontRelayRepository.isSuppressed(clipHashHex) }
    }

    // --- A flag recorded on A reaches C via B, and C's own dontRelay bit flips once its locally-observed count crosses threshold ---

    @Test
    fun flagRecordedOnAReachesCViaBAndCsRelayRowFlipsOnceThresholdCrosses() {
        val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
        val threshold = 1 // single distinct flag suffices, to isolate the propagation path itself
        val a = Party("A", newInMemoryDb(), clock, threshold)
        val b = Party("B", newInMemoryDb(), clock, threshold)
        val c = Party("C", newInMemoryDb(), clock, threshold)

        // The post reaches all three devices first (A -> B -> C), same shape as RelayTest's chain scenario.
        val frame = composeFrame("flag-propagation", originatedAtMs = clock.instant().toEpochMilli())
        val clipHash = clipHashHex(frame)
        runBlocking { a.relayRepository.considerForRelay(frame) }

        val (linkAB1, _) = connect(a, b)
        Thread.sleep(300)
        linkAB1.close()
        val (linkBC1, _) = connect(b, c)
        Thread.sleep(300)
        linkBC1.close()

        assertNotNull(
            runBlocking { c.postRepository.getByClipHash(clipHash) },
            "C must have received the post before this test's flag propagation is meaningful",
        )

        // A flags the post it holds -- mirrors FeedViewModel.flagDontRelay's shape.
        val flagRow = DontRelayFlagEntity(
            clipHash = clipHash,
            attestedDeviceKey = "attested-key-of-a",
            flaggedAtMs = clock.instant().toEpochMilli(),
            originatedAtMs = frame.originatedAtMs,
            ttlSeconds = frame.ttlSeconds,
        )
        runBlocking { a.dontRelayRepository.recordFlag(flagRow) }

        // A live-pushes its own flag to B over a fresh connection -- mirrors WifiDirectTransport.broadcastDontRelayFlag.
        val (linkAB2, _) = connect(a, b)
        val flagEnvelope = DontRelayFlagEnvelope(
            clipHash = flagRow.clipHash.hexToBytes(),
            attestedDeviceKey = flagRow.attestedDeviceKey.hexToBytes(),
            flaggedAtMs = flagRow.flaggedAtMs,
            originatedAtMs = flagRow.originatedAtMs,
            ttlSeconds = flagRow.ttlSeconds,
        )
        linkAB2.send(WirePayloadType.DONT_RELAY_FLAG, flagEnvelope.encode())
        Thread.sleep(300)
        linkAB2.close()

        assertTrue(
            runBlocking { b.dontRelayRepository.isSuppressed(clipHash) },
            "B must have recorded A's flag and crossed threshold (threshold=1)",
        )

        // B relays the flag onward to C on its next connection (backlog path).
        val (linkBC2, _) = connect(b, c)
        Thread.sleep(300)
        linkBC2.close()

        assertTrue(
            awaitSuppressed(c, clipHash),
            "C must observe the flag relayed via B (never having connected to A directly) and cross threshold itself",
        )
    }

    // --- Order-independence: a flag arrives before the post it refers to, and the post is suppressed from the start once it does arrive ---

    @Test
    fun flagArrivingBeforeThePostSuppressesThePostFromTheStartOnceItArrives() {
        val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
        val threshold = 1
        val receiver = Party("Receiver", newInMemoryDb(), clock, threshold)

        val originatedAtMs = clock.instant().toEpochMilli()
        val ttlSeconds = 3600L
        val plaintext = "order-independence payload".toByteArray()
        val clipHash = MessageDigest.getInstance("SHA-256").digest(plaintext)
        val clipHashHex = clipHash.joinToString(separator = "") { "%02x".format(it) }

        // The flag arrives first -- this device has never seen the post itself,
        // so it has no RelayQueueEntity row for this clipHash yet.
        val flagRow = DontRelayFlagEntity(
            clipHash = clipHashHex,
            attestedDeviceKey = "attested-key-of-flagger",
            flaggedAtMs = originatedAtMs,
            originatedAtMs = originatedAtMs,
            ttlSeconds = ttlSeconds,
        )
        val recordedNew = runBlocking { receiver.dontRelayRepository.recordFlag(flagRow) }
        assertTrue(recordedNew, "recordFlag must succeed with no matching RelayQueueEntity row")
        assertTrue(runBlocking { receiver.db.relayQueueDao().getAll() }.isEmpty(), "no RelayQueueEntity row should exist yet")
        assertTrue(runBlocking { receiver.dontRelayRepository.isSuppressed(clipHashHex) })

        // The post now arrives via the normal relay-custody path.
        val encoded = EncryptedFrameCodec.encode(
            plaintext = plaintext,
            clipHash = clipHash,
            senderDeviceId = ByteArray(Frame.SENDER_DEVICE_ID_SIZE) { it.toByte() },
            contentType = ContentType.PHOTO,
            hopCount = 0,
            originatedAtMs = originatedAtMs,
            ttlSeconds = ttlSeconds,
            reachTier = ReachTier.LOCALITY,
            dontRelay = false,
        )
        val frame = Frame.decode(encoded)
        runBlocking { receiver.relayRepository.considerForRelay(frame) }

        assertNotNull(
            runBlocking { receiver.postRepository.getByClipHash(clipHashHex) },
            "the post's own PostEntity must still be stored/viewable regardless of suppression -- propagation halt never retroactively hides an already-received post",
        )

        val relayRow = runBlocking { receiver.db.relayQueueDao().getAll() }.single { it.clipHash == clipHashHex }
        assertTrue(
            relayRow.dontRelay,
            "the freshly-inserted RelayQueueEntity row must have dontRelay = true from the start -- this is the order-independence fix's regression test",
        )
    }
}

/**
 * File-scope (not a [DontRelayTest] member) so both [DontRelayTest] itself
 * and its nested, non-inner [DontRelayTest.FlagLink] can call it -- a
 * private member extension function on the outer class isn't visible from a
 * nested `private class` that isn't `inner`.
 */
private fun String.hexToBytes(): ByteArray =
    ByteArray(length / 2) { i -> ((Character.digit(this[i * 2], 16) shl 4) + Character.digit(this[i * 2 + 1], 16)).toByte() }
