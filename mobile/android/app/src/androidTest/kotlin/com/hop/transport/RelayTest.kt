package com.hop.transport

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hop.data.DontRelayFlagEntity
import com.hop.data.HopDatabase
import com.hop.data.RoomDecayKeyStorage
import com.hop.crypto.DecayKeyStore
import com.hop.protocol.ContentType
import com.hop.protocol.DontRelayFlagEnvelope
import com.hop.protocol.EncryptedFrameCodec
import com.hop.protocol.Frame
import com.hop.protocol.ReachTier
import com.hop.protocol.RelayPolicy
import com.hop.protocol.WireEnvelope
import com.hop.protocol.WirePayloadType
import com.hop.repository.BundleRepository
import com.hop.repository.DontRelayRepository
import com.hop.repository.PendingMessageRepository
import com.hop.repository.PostRepository
import com.hop.repository.RelayRepository
import kotlinx.coroutines.flow.first
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
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Instrumented end-to-end tests for Phase 2 Slice 1's app-layer
 * store-and-forward relay -- the low-density, near-broken-mesh case (a
 * post's originator and its eventual recipient never share direct radio
 * contact) this slice exists for, per hop-dev's own testing guidance to
 * prioritize this over the dense-venue happy path.
 *
 * Does **not** construct a real [WifiDirectTransport] -- same reasoning as
 * [WifiDirectTransportMessagingTest]: that class needs a real
 * `Context`/`WifiP2pManager.Channel`, which needs actual WiFi Direct
 * radio/service support this test environment doesn't guarantee. Instead,
 * [RelayLink] (this file's analogue of that test's `LoopbackLink`) drives
 * the exact same production classes ([ReceivedFrameStore], [EnvelopeDispatcher],
 * [DispatchResult], [RelayRepository], [RelayPolicy], real `crypto/`
 * [DecayKeyStore]) over a plain loopback socket pair, mirroring only the
 * mechanical "on connect: send backlog; on receive: dispatch, and if a
 * frame is genuinely new, take relay custody + live-push to other links"
 * glue that [WifiDirectTransport] itself contains -- the actual interesting
 * logic under test (relay eligibility, decay-key anchoring, persisted-queue
 * dedup) all runs through the real classes, unmodified.
 */
@RunWith(AndroidJUnit4::class)
class RelayTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val sockets = mutableListOf<Socket>()
    private val closeableDbs = mutableListOf<HopDatabase>()
    private val fileDbNames = mutableListOf<String>()

    @After
    fun tearDown() {
        sockets.forEach { runCatching { it.close() } }
        closeableDbs.forEach { runCatching { it.close() } }
        val context = ApplicationProvider.getApplicationContext<Context>()
        fileDbNames.forEach { runCatching { context.deleteDatabase(it) } }
    }

    // --- Test-only clock, matching crypto/'s own DecayKeyStoreTest pattern (duplicated here since that one is test-only/private to crypto/'s own module). ---
    private class MutableClock(private var current: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = current
        fun advanceBy(duration: Duration) {
            current = current.plus(duration)
        }
    }

    /** One simulated device's full local relay stack -- mirrors what [com.hop.app.AppContainer] wires together in production. */
    private inner class Party(
        val name: String,
        var db: HopDatabase,
        val clock: Clock,
        val maxHops: Int = RelayPolicy.DEFAULT_MAX_HOPS,
    ) {
        val postsDir: File = tempFolder.newFolder("posts-$name-${UUID.randomUUID()}")
        val activeLinks = CopyOnWriteArrayList<RelayLink>()

        lateinit var postRepository: PostRepository
        lateinit var decayKeyStore: DecayKeyStore
        lateinit var relayPolicy: RelayPolicy
        lateinit var dontRelayRepository: DontRelayRepository
        lateinit var relayRepository: RelayRepository
        lateinit var pendingMessageRepository: PendingMessageRepository
        lateinit var bundleRepository: BundleRepository
        lateinit var receivedFrameStore: ReceivedFrameStore
        lateinit var dispatcher: EnvelopeDispatcher

        init {
            rebuildFromDb()
        }

        private fun rebuildFromDb() {
            decayKeyStore = DecayKeyStore(clock, RoomDecayKeyStorage(db.decayKeyDao()))
            postRepository = PostRepository(db.postDao(), decayKeyStore)
            relayPolicy = RelayPolicy(maxHops = maxHops, clock = clock)
            dontRelayRepository = DontRelayRepository(db.dontRelayFlagDao(), db.relayQueueDao(), relayPolicy)
            relayRepository = RelayRepository(
                db.relayQueueDao(),
                relayPolicy,
                isFlaggedForSuppression = dontRelayRepository::isSuppressed,
            )
            pendingMessageRepository = PendingMessageRepository(db.pendingMessageDao(), relayPolicy)
            bundleRepository = BundleRepository(db.bundleQueueDao(), relayPolicy)
            receivedFrameStore = ReceivedFrameStore(postRepository, decayKeyStore, postsDir, relayPolicy)
            dispatcher = EnvelopeDispatcher(
                receivedFrameStore = receivedFrameStore,
                dontRelayRepository = dontRelayRepository,
                pendingMessageRepository = pendingMessageRepository,
                bundleRepository = bundleRepository,
                getOwnPeerId = { name },
                onPreKeyBundleReceived = { _, _ -> },
                onMessageCiphertextReceived = { _, _ -> },
            )
        }

        /** Simulates this device's own app process dying and restarting: closes and reopens the same file-backed [db], rebuilding every dependent object from it. Requires [db] to be file-backed (see [newFileDb]) -- an in-memory db has nothing to reopen. */
        fun killAndRestart(dbName: String) {
            db.close()
            db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), HopDatabase::class.java, dbName).build()
            closeableDbs.add(db)
            rebuildFromDb()
        }
    }

    /**
     * Test-only stand-in for a live [WifiDirectTransport] peer connection --
     * mirrors [WifiDirectTransport.handleConnection]'s "on connect: send
     * relay backlog" and [WifiDirectTransport.receivePosts]'s "on receive:
     * dispatch, and on a genuinely-new frame, take relay custody + live-push
     * to every *other* active connection" behavior, over one already-
     * connected [socket]. The dispatch/custody/eligibility decisions
     * themselves run through the real production [EnvelopeDispatcher]/
     * [RelayRepository]/[RelayPolicy] -- only the socket plumbing is
     * duplicated here, matching [WifiDirectTransportMessagingTest]'s own
     * `LoopbackLink` precedent.
     */
    private class RelayLink(private val party: Party, private val socket: Socket) {
        private val out = DataOutputStream(socket.getOutputStream())
        private val writeLock = Any()

        private fun sendEnvelopeBytes(bytes: ByteArray): Boolean = synchronized(writeLock) {
            try {
                out.write(bytes)
                out.flush()
                true
            } catch (e: Exception) {
                false
            }
        }

        /** Mirrors [WifiDirectTransport.registerConnectionAndGetBacklog] + [WifiDirectTransport.sendBacklog]. */
        fun onConnected() {
            party.activeLinks.add(this)
            val backlog = runBlocking { party.relayRepository.buildOutgoingBacklog() }
            for (envelope in backlog) sendEnvelopeBytes(envelope)
            val flagBacklog = runBlocking { party.dontRelayRepository.buildOutgoingFlagBacklog() }
            for (envelope in flagBacklog) sendEnvelopeBytes(envelope)
        }

        /**
         * Mirrors [WifiDirectTransport.receivePosts] +
         * [WifiDirectTransport.handleNewlyReceivedFrame] +
         * [WifiDirectTransport.handleNewlyReceivedDontRelayFlag].
         */
        fun startReceiving(afterEachDispatch: () -> Unit = {}) {
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
                        val result = runBlocking { party.dispatcher.dispatch(envelope) }
                        when (result) {
                            is DispatchResult.NewPostFrame -> {
                                runBlocking { party.relayRepository.considerForRelay(result.frame) }
                                val outgoing = WireEnvelope.encode(
                                    WirePayloadType.POST_FRAME,
                                    result.frame.copy(hopCount = result.frame.hopCount + 1).encode(),
                                )
                                for (other in party.activeLinks) {
                                    if (other === this) continue
                                    other.sendEnvelopeBytes(outgoing)
                                }
                            }
                            is DispatchResult.NewDontRelayFlag -> {
                                // party.dispatcher.dispatch already called
                                // dontRelayRepository.recordFlag -- only the
                                // onward live-relay remains, mirroring
                                // WifiDirectTransport.handleNewlyReceivedDontRelayFlag.
                                val flagEnvelope = DontRelayFlagEnvelope(
                                    clipHash = result.row.clipHash.hexToBytes(),
                                    attestedDeviceKey = result.row.attestedDeviceKey.hexToBytes(),
                                    flaggedAtMs = result.row.flaggedAtMs,
                                    originatedAtMs = result.row.originatedAtMs,
                                    ttlSeconds = result.row.ttlSeconds,
                                )
                                val outgoing = WireEnvelope.encode(WirePayloadType.DONT_RELAY_FLAG, flagEnvelope.encode())
                                for (other in party.activeLinks) {
                                    if (other === this) continue
                                    other.sendEnvelopeBytes(outgoing)
                                }
                            }
                            else -> Unit
                        }
                        afterEachDispatch()
                    }
                } catch (e: Exception) {
                    // socket closed / test teardown -- expected.
                } finally {
                    party.activeLinks.remove(this)
                }
            }, "test-relay-receive-${party.name}").start()
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

    private fun newFileDb(name: String): HopDatabase {
        fileDbNames.add(name)
        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), HopDatabase::class.java, name).build()
        closeableDbs.add(db)
        return db
    }

    /** Connects [a] and [b] over a fresh real loopback socket pair, wires a [RelayLink] on each end, sends each side's current relay backlog, and starts both receive loops. Returns the two links so a test can [RelayLink.close] one side to simulate disconnect. */
    private fun connect(a: Party, b: Party): Pair<RelayLink, RelayLink> {
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

        val linkA = RelayLink(a, clientSocket)
        val linkB = RelayLink(b, bSocket)
        linkA.onConnected()
        linkB.onConnected()
        linkA.startReceiving()
        linkB.startReceiving()
        return linkA to linkB
    }

    /** Builds a self-authored post's [Frame] + encoded bytes -- test's stand-in for [com.hop.app.composer.PostComposerViewModel]'s output. */
    private fun composeFrame(
        seed: String,
        originatedAtMs: Long,
        ttlSeconds: Long = 3600L,
        dontRelay: Boolean = false,
    ): Frame {
        val plaintext = "relay test payload for $seed".toByteArray()
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
            dontRelay = dontRelay,
        )
        return Frame.decode(encoded)
    }

    private fun clipHashHex(frame: Frame): String = frame.clipHash.joinToString(separator = "") { "%02x".format(it) }

    /** Polls (bounded) until [party] has stored a [com.hop.data.PostEntity] for [clipHashHex], or returns null on timeout. */
    private fun awaitPost(party: Party, clipHashHex: String, timeoutMs: Long = 5_000) =
        run {
            val deadline = System.currentTimeMillis() + timeoutMs
            var result = runBlocking { party.postRepository.getByClipHash(clipHashHex) }
            while (result == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
                result = runBlocking { party.postRepository.getByClipHash(clipHashHex) }
            }
            result
        }

    /** "Simulates zero connected peers" -- test's stand-in for [WifiDirectTransport.broadcastPost]'s relay-custody step, with no live connections to push to. */
    private fun composeAndQueueForRelay(party: Party, frame: Frame) {
        runBlocking { party.relayRepository.considerForRelay(frame) }
    }

    // --- Scenario 1: 3-device relay chain, A<->C never connect directly ---

    @Test
    fun threeDeviceChain_postReachesCWithHopCountTwo() {
        val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
        val a = Party("A", newInMemoryDb(), clock)
        val b = Party("B", newInMemoryDb(), clock)
        val c = Party("C", newInMemoryDb(), clock)

        val frame = composeFrame("chain", originatedAtMs = clock.instant().toEpochMilli())
        composeAndQueueForRelay(a, frame)
        val clipHash = clipHashHex(frame)

        val (linkAtoB, _) = connect(a, b)
        val postAtB = awaitPost(b, clipHash)
        assertNotNull(postAtB, "B never received the post relayed from A")
        linkAtoB.close() // A disconnects

        connect(b, c)
        val postAtC = awaitPost(c, clipHash)
        assertNotNull(postAtC, "C never received the post relayed via B, despite never connecting to A directly")

        val decrypted = runBlocking { c.postRepository.decrypt(postAtC) }
        assertTrue(decrypted is PostRepository.DecryptResult.Decrypted, "C must be able to decrypt the relayed post")

        val relayRowAtC = runBlocking { c.db.relayQueueDao().getAll() }.single { it.clipHash == clipHash }
        assertEquals(2, relayRowAtC.hopCount, "the post must have hopCount == 2 by the time it reaches C (A -> B -> C)")
    }

    // --- Scenario 2: same chain, B's process dies mid-relay ---

    @Test
    fun threeDeviceChain_survivesBsProcessDeathMidRelay() {
        val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
        val a = Party("A", newInMemoryDb(), clock)
        val bDbName = "relay-test-b-${UUID.randomUUID()}"
        var b = Party("B", newFileDb(bDbName), clock)
        val c = Party("C", newInMemoryDb(), clock)

        val frame = composeFrame("process-death", originatedAtMs = clock.instant().toEpochMilli())
        composeAndQueueForRelay(a, frame)
        val clipHash = clipHashHex(frame)

        val (linkAtoB, _) = connect(a, b)
        assertNotNull(awaitPost(b, clipHash), "B never received the post from A before the simulated process death")
        linkAtoB.close()

        // Simulate B's app process dying and restarting -- real Room file-backed db, not in-memory.
        b.killAndRestart(bDbName)

        connect(b, c)
        val postAtC = awaitPost(c, clipHash)
        assertNotNull(postAtC, "the post must still reach C after B's process death and restart -- this is the persisted relay queue's whole point")
        assertTrue(runBlocking { c.postRepository.decrypt(postAtC) } is PostRepository.DecryptResult.Decrypted)
    }

    // --- Scenario 3: decay-anchor regression (Finding A) ---

    @Test
    fun decayKeyExpiryIsAnchoredToOriginationTimeNotToWhenTheRelayForwardedIt() {
        val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
        val a = Party("A", newInMemoryDb(), clock)
        val b = Party("B", newInMemoryDb(), clock)
        val c = Party("C", newInMemoryDb(), clock)

        val ttlSeconds = 100L
        val originatedAtMs = clock.instant().toEpochMilli()
        val frame = composeFrame("decay-anchor", originatedAtMs = originatedAtMs, ttlSeconds = ttlSeconds)
        composeAndQueueForRelay(a, frame)
        val clipHash = clipHashHex(frame)

        val (linkAtoB, _) = connect(a, b)
        assertNotNull(awaitPost(b, clipHash))
        linkAtoB.close()

        // B holds the post for 80 of the post's 100-second window before relaying onward.
        clock.advanceBy(Duration.ofSeconds(80))

        connect(b, c)
        val postAtC = awaitPost(c, clipHash)
        assertNotNull(postAtC, "C never received the post relayed via B")

        // Still within the *origin-anchored* window (100s from originatedAtMs,
        // we're at +80s) -- must still be decryptable.
        assertTrue(
            runBlocking { c.postRepository.decrypt(postAtC) } is PostRepository.DecryptResult.Decrypted,
            "post must still be decryptable at +80s, within the 100s origin-anchored window",
        )

        // Advance to +120s: past the *origin-anchored* expiry (100s), but --
        // if the bug this test exists to catch were present -- still well
        // within a *receipt-anchored* expiry (C received at +80s, so a buggy
        // anchor would compute expiry at +180s and still consider this live).
        clock.advanceBy(Duration.ofSeconds(40))

        val stillThere = runBlocking { c.postRepository.getByClipHash(clipHash) }
        assertNotNull(stillThere, "the PostEntity row itself must still exist -- decay is key expiry, not row deletion")
        assertEquals(
            PostRepository.DecryptResult.Decayed,
            runBlocking { c.postRepository.decrypt(stillThere) },
            "at +120s the post must be decayed relative to ORIGINATION (+100s), not relative to when B happened to relay it (+80s receipt + 100s ttl = +180s)",
        )
    }

    // --- Scenario 4: MAX_HOPS boundary ---

    @Test
    fun postReachesAndIsViewableAtMaxHopsButNeverRelaysPastIt() {
        val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
        val maxHops = 2
        // Chain of maxHops + 1 = 3 devices that take custody (D0, D1, D2),
        // plus one more (D3) to confirm D2 never offers it onward.
        val d0 = Party("D0", newInMemoryDb(), clock, maxHops = maxHops)
        val d1 = Party("D1", newInMemoryDb(), clock, maxHops = maxHops)
        val d2 = Party("D2", newInMemoryDb(), clock, maxHops = maxHops)
        val d3 = Party("D3", newInMemoryDb(), clock, maxHops = maxHops)

        val frame = composeFrame("max-hops", originatedAtMs = clock.instant().toEpochMilli())
        composeAndQueueForRelay(d0, frame)
        val clipHash = clipHashHex(frame)

        val (link01, _) = connect(d0, d1)
        assertNotNull(awaitPost(d1, clipHash))
        link01.close()

        val (link12, _) = connect(d1, d2)
        val postAtD2 = awaitPost(d2, clipHash)
        assertNotNull(postAtD2, "D2 (at hopCount == maxHops) must still receive and be able to view the post")
        assertTrue(runBlocking { d2.postRepository.decrypt(postAtD2) } is PostRepository.DecryptResult.Decrypted)
        link12.close()

        // D2 took custody at hopCount == maxHops, which RelayPolicy says is
        // NOT eligible for further relay -- its relay queue must have no row
        // for this clipHash at all.
        val d2QueueRows = runBlocking { d2.db.relayQueueDao().getAll() }
        assertTrue(d2QueueRows.none { it.clipHash == clipHash }, "a frame at exactly maxHops must never be queued for further relay")

        connect(d2, d3)
        Thread.sleep(300) // give a (nonexistent) relay a moment to have crossed the wire if the bound were broken
        val postAtD3 = runBlocking { d3.postRepository.getByClipHash(clipHash) }
        assertNull(postAtD3, "the post must never reach a device one hop past MAX_HOPS")
    }

    // --- Scenario 5: dontRelay respected on a received frame ---

    @Test
    fun dontRelayFrameIsStoredAndViewableButNeverQueuedForFurtherRelay() {
        val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
        val receiver = Party("Receiver", newInMemoryDb(), clock)

        val frame = composeFrame("dont-relay", originatedAtMs = clock.instant().toEpochMilli(), dontRelay = true)
        val clipHash = clipHashHex(frame)

        val envelope = WireEnvelope(WirePayloadType.POST_FRAME, frame.encode())
        val result = runBlocking { receiver.dispatcher.dispatch(envelope) }
        assertTrue(result is DispatchResult.NewPostFrame, "a dontRelay frame is still a genuinely-new receipt")
        runBlocking { receiver.relayRepository.considerForRelay(result.frame) }

        val storedPost = runBlocking { receiver.postRepository.getByClipHash(clipHash) }
        assertNotNull(storedPost, "propagation halt must not retroactively hide something already received -- the receiving device's own feed must still show it")
        assertTrue(runBlocking { receiver.postRepository.decrypt(storedPost) } is PostRepository.DecryptResult.Decrypted)

        val queueRows = runBlocking { receiver.db.relayQueueDao().getAll() }
        assertTrue(queueRows.none { it.clipHash == clipHash }, "a dontRelay frame must never be queued for further relay")
    }

    // --- Scenario 6: redundant-offer harmlessness ---

    @Test
    fun reconnectingToTheSamePeerDoesNotDuplicateOrMutateAnythingAlreadyStored() {
        val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
        val a = Party("A", newInMemoryDb(), clock)
        val b = Party("B", newInMemoryDb(), clock)

        val frame = composeFrame("redundant-offer", originatedAtMs = clock.instant().toEpochMilli())
        composeAndQueueForRelay(a, frame)
        val clipHash = clipHashHex(frame)

        val (link1, _) = connect(a, b)
        assertNotNull(awaitPost(b, clipHash))
        link1.close()

        val originalRow = runBlocking { b.db.relayQueueDao().getAll() }.single { it.clipHash == clipHash }

        // Reconnect to the very same peer -- A offers the same post again.
        val (link2, _) = connect(a, b)
        Thread.sleep(300) // give the redundant offer a moment to (harmlessly) cross the wire
        link2.close()

        val rowsForClip = runBlocking { b.db.postDao().getAllOrderedByReceivedDesc().first() }.filter { it.clipHash == clipHash }
        assertEquals(1, rowsForClip.size, "a redundant offer must not create a second PostEntity row")

        val rowAfter = runBlocking { b.db.relayQueueDao().getAll() }.single { it.clipHash == clipHash }
        assertEquals(originalRow.hopCount, rowAfter.hopCount, "a redundant offer must not mutate the stored hopCount")
        assertEquals(originalRow.receivedAtMs, rowAfter.receivedAtMs, "a redundant offer must not mutate the stored receivedAtMs")
    }

    // --- Scenario 7: two disjoint paths converge ---

    @Test
    fun twoDisjointRelayPathsConvergingOnTheSameDeviceProduceExactlyOneRow() {
        val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
        val a = Party("A", newInMemoryDb(), clock)
        val b = Party("B", newInMemoryDb(), clock)
        val d = Party("D", newInMemoryDb(), clock)
        val c = Party("C", newInMemoryDb(), clock)

        val frame = composeFrame("disjoint-paths", originatedAtMs = clock.instant().toEpochMilli())
        composeAndQueueForRelay(a, frame)
        val clipHash = clipHashHex(frame)

        val (linkAB, _) = connect(a, b)
        assertNotNull(awaitPost(b, clipHash))
        linkAB.close()

        val (linkAD, _) = connect(a, d)
        assertNotNull(awaitPost(d, clipHash))
        linkAD.close()

        // B and D never connect to each other -- both independently connect to C.
        val (linkBC, _) = connect(b, c)
        assertNotNull(awaitPost(c, clipHash))
        linkBC.close()

        val (linkDC, _) = connect(d, c)
        Thread.sleep(300) // give D's (redundant, from C's perspective) offer a moment to arrive
        linkDC.close()

        val postRowsForClip = runBlocking { c.db.postDao().getAllOrderedByReceivedDesc().first() }
            .filter { it.clipHash == clipHash }
        assertEquals(1, postRowsForClip.size, "C must end up with exactly one PostEntity row despite receiving via two disjoint paths")

        val relayRowsForClip = runBlocking { c.db.relayQueueDao().getAll() }.filter { it.clipHash == clipHash }
        assertEquals(1, relayRowsForClip.size, "C must end up with exactly one relay queue row despite receiving via two disjoint paths")
    }

    // --- Scenario 8: self-authored-post persistence (Finding B) ---

    @Test
    fun selfAuthoredPostSurvivesOwnProcessDeathAndStillReachesAPeerAfterRestart() {
        val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
        val aDbName = "relay-test-a-${UUID.randomUUID()}"
        var a = Party("A", newFileDb(aDbName), clock)
        val b = Party("B", newInMemoryDb(), clock)

        // A composes a post with zero connected peers (mirrors
        // WifiDirectTransport.broadcastPost's relay-custody step with an
        // empty activeConnections list).
        val frame = composeFrame("self-authored-survives-restart", originatedAtMs = clock.instant().toEpochMilli())
        composeAndQueueForRelay(a, frame)
        val clipHash = clipHashHex(frame)

        // A's own process dies and restarts -- before ever connecting to anyone.
        a.killAndRestart(aDbName)

        connect(a, b)
        val postAtB = awaitPost(b, clipHash)
        assertNotNull(postAtB, "a self-authored post must survive this device's own process restart and still reach a peer that connects afterward")
        assertTrue(runBlocking { b.postRepository.decrypt(postAtB) } is PostRepository.DecryptResult.Decrypted)
    }
}

/**
 * File-scope (not a [RelayTest] member) so both [RelayTest] itself and its
 * nested, non-inner [RelayTest.RelayLink] can call it -- a private member
 * extension function on the outer class isn't visible from a nested
 * `private class` that isn't `inner`.
 */
private fun String.hexToBytes(): ByteArray =
    ByteArray(length / 2) { i -> ((Character.digit(this[i * 2], 16) shl 4) + Character.digit(this[i * 2 + 1], 16)).toByte() }
