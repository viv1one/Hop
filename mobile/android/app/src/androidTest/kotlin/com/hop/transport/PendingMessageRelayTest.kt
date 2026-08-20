package com.hop.transport

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hop.crypto.DecayKeyStore
import com.hop.crypto.PreKeyBundleCodec
import com.hop.data.HopDatabase
import com.hop.data.IdentityKeyPairKeystoreCipher
import com.hop.data.MessageEntity
import com.hop.data.PEER_DEVICE_ID
import com.hop.data.PendingMessageEntity
import com.hop.data.PreKeyRotationManager
import com.hop.data.RoomSignalProtocolStore
import com.hop.protocol.MessageCiphertextEnvelope
import com.hop.protocol.PreKeyBundleEnvelope
import com.hop.protocol.RelayPolicy
import com.hop.protocol.WireEnvelope
import com.hop.protocol.WirePayloadType
import com.hop.repository.BlockRepository
import com.hop.repository.BundleRepository
import com.hop.repository.DontRelayRepository
import com.hop.repository.MessageRepository
import com.hop.repository.PendingMessageRepository
import com.hop.repository.PostRepository
import com.hop.repository.SendResult
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Instrumented end-to-end tests for Phase 2 Slice 3's store-and-forward for
 * offline 1:1 message recipients (see
 * `mobile/android/app/src/main/kotlin/com/hop/repository/PendingMessageRepository.kt`'s
 * own doc) -- the 7 test cases named in the slice's plan. Same posture as
 * [RelayTest]/[WifiDirectTransportMessagingTest]: does **not** construct a
 * real [WifiDirectTransport] (needs a real `Context`/`WifiP2pManager.Channel`
 * this test environment doesn't guarantee); instead [MessageLink] (this
 * file's analogue of [RelayTest.RelayLink] / [WifiDirectTransportMessagingTest.LoopbackLink])
 * drives the exact same production classes ([EnvelopeDispatcher],
 * [DispatchResult], [PendingMessageRepository], [RelayPolicy], real
 * [MessageRepository] + [RoomSignalProtocolStore] + `crypto/`
 * `DoubleRatchetSession`) over plain loopback socket pairs, replicating only
 * the mechanical "on connect: send backlog + announce own bundle; on
 * receive: dispatch, and act on the result" glue that
 * [WifiDirectTransport.sendMessage]/[WifiDirectTransport.handleNewlyReceivedMessage]/
 * [WifiDirectTransport.deliverAnyPendingMessagesTo] contain.
 */
@RunWith(AndroidJUnit4::class)
class PendingMessageRelayTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val sockets = mutableListOf<Socket>()
    private val closeableDbs = mutableListOf<HopDatabase>()

    @After
    fun tearDown() {
        sockets.forEach { runCatching { it.close() } }
        closeableDbs.forEach { runCatching { it.close() } }
    }

    // --- Test-only clock, matching RelayTest's own duplicated pattern (test-only/private to that file). ---
    private class MutableClock(private var current: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = current
        fun advanceBy(duration: Duration) {
            current = current.plus(duration)
        }
    }

    /** One simulated device's full local messaging stack -- mirrors what [com.hop.app.AppContainer] wires together in production. */
    private inner class Party(val name: String, val db: HopDatabase, val peerId: String, val clock: Clock = Clock.systemUTC()) {
        val activeLinks = CopyOnWriteArrayList<MessageLink>()

        /** Every payload this party's [sendMessage] has ever been asked to send, in call order -- lets a test inspect exactly what was encrypted/encoded without depending on DB row iteration order. */
        val sentEnvelopes = mutableListOf<ByteArray>()

        val store: RoomSignalProtocolStore = RoomSignalProtocolStore(
            db.signalIdentityDao(),
            db.signalPreKeyDao(),
            db.signalSignedPreKeyDao(),
            db.signalKyberPreKeyDao(),
            db.signalSessionDao(),
            IdentityKeyPairKeystoreCipher(),
        )

        val preKeyRotationManager = PreKeyRotationManager(
            store = store,
            counterDao = db.signalPreKeyCounterDao(),
            deviceId = PEER_DEVICE_ID,
        )

        private val blockRepository = BlockRepository(db.blockedSenderDeviceDao())
        private val relayPolicy = RelayPolicy(clock = clock)
        val pendingMessageRepository = PendingMessageRepository(db.pendingMessageDao(), relayPolicy)

        val messageRepository: MessageRepository = MessageRepository(
            messageDao = db.messageDao(),
            signalIdentityDao = db.signalIdentityDao(),
            signalProtocolStore = store,
            blockRepository = blockRepository,
            groupDao = db.groupDao(),
            groupMessageDao = db.groupMessageDao(),
            getOwnPeerId = { peerId },
            sendMessage = { recipientPeerId, payload -> sendMessage(recipientPeerId, payload) },
        )

        private val dontRelayRepository = DontRelayRepository(db.dontRelayFlagDao(), db.relayQueueDao(), relayPolicy)
        private val postRepository = PostRepository(db.postDao(), DecayKeyStore())
        private val receivedFrameStore = ReceivedFrameStore(postRepository, DecayKeyStore(), tempFolder.newFolder())
        val bundleRepository = BundleRepository(db.bundleQueueDao(), relayPolicy)

        val dispatcher = EnvelopeDispatcher(
            receivedFrameStore = receivedFrameStore,
            dontRelayRepository = dontRelayRepository,
            pendingMessageRepository = pendingMessageRepository,
            bundleRepository = bundleRepository,
            getOwnPeerId = { peerId },
            onPreKeyBundleReceived = messageRepository::cachePeerBundle,
            onMessageCiphertextReceived = messageRepository::onEnvelopeReceived,
        )

        /**
         * Test's stand-in for [WifiDirectTransport.sendMessage] -- unicast-
         * first (direct send to an already-identified connected link), flood-
         * on-failure (take relay custody + push to every currently-connected
         * link) otherwise. Mirrors that production method's shape exactly.
         */
        fun sendMessage(recipientPeerId: String, payload: ByteArray): Boolean {
            sentEnvelopes.add(payload)
            val direct = activeLinks.firstOrNull { it.remotePeerId == recipientPeerId }
            if (direct != null && direct.send(WirePayloadType.MESSAGE_CIPHERTEXT, payload)) {
                return true
            }
            val envelope = MessageCiphertextEnvelope.decode(payload)
            runBlocking { pendingMessageRepository.considerForRelay(envelope) }
            val wireBytes = WireEnvelope.encode(WirePayloadType.MESSAGE_CIPHERTEXT, payload)
            for (link in activeLinks) link.sendEnvelopeBytes(wireBytes)
            return false
        }
    }

    /**
     * Test-only stand-in for one [WifiDirectTransport] peer connection's
     * messaging path -- mirrors [WifiDirectTransport.handleConnection]'s
     * "on connect: send relay backlog + announce own bundle" and
     * [WifiDirectTransport.receivePosts]'s "on receive: dispatch, and act on
     * the [DispatchResult]" behavior, over one already-connected [socket].
     * The dispatch/custody/eligibility decisions all run through the real
     * production [EnvelopeDispatcher]/[PendingMessageRepository]/[RelayPolicy]
     * -- only the socket plumbing and [WifiDirectTransport]'s own *private*
     * glue methods ([WifiDirectTransport.handleNewlyReceivedMessage],
     * [WifiDirectTransport.deliverAnyPendingMessagesTo]) are duplicated here,
     * matching [RelayTest.RelayLink]'s own precedent for exactly the same
     * reason (those methods aren't visible outside [WifiDirectTransport]).
     */
    private class MessageLink(private val party: Party, private val socket: Socket) {
        private val out = DataOutputStream(socket.getOutputStream())
        private val writeLock = Any()

        @Volatile
        var remotePeerId: String? = null

        fun send(type: WirePayloadType, payload: ByteArray): Boolean =
            sendEnvelopeBytes(WireEnvelope.encode(type, payload))

        fun sendEnvelopeBytes(bytes: ByteArray): Boolean = synchronized(writeLock) {
            try {
                out.write(bytes)
                out.flush()
                true
            } catch (e: Exception) {
                false
            }
        }

        /** Mirrors [WifiDirectTransport.registerConnectionAndGetBacklog]'s message-backlog half. */
        fun onConnected() {
            party.activeLinks.add(this)
            val backlog = runBlocking { party.pendingMessageRepository.buildOutgoingBacklog() }
            for (envelopeBytes in backlog) sendEnvelopeBytes(envelopeBytes)
        }

        /** Mirrors [WifiDirectTransport.announceOwnPreKeyBundle]. */
        fun announceOwnBundle() {
            val bundle = party.preKeyRotationManager.currentBundle()
            val envelope = PreKeyBundleEnvelope(
                peerId = party.peerId,
                hopCount = 0,
                originatedAtMs = System.currentTimeMillis(),
                bundleBytes = PreKeyBundleCodec.encode(bundle),
            )
            send(WirePayloadType.PREKEY_BUNDLE, envelope.encode())
        }

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
                            is DispatchResult.PeerIdentified -> {
                                remotePeerId = result.peerId
                                deliverAnyPendingMessagesTo(result.peerId)
                            }
                            is DispatchResult.NewRelayableMessage -> {
                                remotePeerId = result.senderPeerId
                                // Mirrors WifiDirectTransport.handleNewlyReceivedMessage:
                                // flood hopCount+1 onward to every *other* link.
                                val stored = MessageCiphertextEnvelope.decode(result.row.encodedEnvelope)
                                val outgoing = stored.copy(hopCount = stored.hopCount + 1)
                                val outgoingBytes = WireEnvelope.encode(WirePayloadType.MESSAGE_CIPHERTEXT, outgoing.encode())
                                for (other in party.activeLinks) {
                                    if (other === this) continue
                                    other.sendEnvelopeBytes(outgoingBytes)
                                }
                                deliverAnyPendingMessagesTo(result.senderPeerId)
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
            }, "test-message-receive-${party.name}").start()
        }

        /** Mirrors [WifiDirectTransport.deliverAnyPendingMessagesTo]'s local delivery-confirmation stop signal. */
        private fun deliverAnyPendingMessagesTo(identifiedPeerId: String) {
            val rows = runBlocking { party.pendingMessageRepository.findByRecipient(identifiedPeerId) }
            for (row in rows) {
                val envelope = WireEnvelope.encode(WirePayloadType.MESSAGE_CIPHERTEXT, row.encodedEnvelope)
                if (sendEnvelopeBytes(envelope)) {
                    runBlocking { party.pendingMessageRepository.delete(row.ciphertextHash) }
                }
            }
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

    /**
     * Connects [a] and [b] over a fresh real loopback socket pair, registers
     * a [MessageLink] on each end (flushing each side's existing relay
     * backlog immediately, mirroring [WifiDirectTransport.handleConnection]),
     * starts both receive loops, and -- unless [exchangeBundles] is `false`
     * -- announces each side's own current prekey bundle to the other
     * (mirroring [WifiDirectTransport.announceOwnPreKeyBundle] firing
     * automatically, both directions, on every real connection), then gives
     * that exchange a moment to land before returning.
     */
    private fun connect(a: Party, b: Party, exchangeBundles: Boolean = true): Pair<MessageLink, MessageLink> {
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

        val linkA = MessageLink(a, clientSocket)
        val linkB = MessageLink(b, bSocket)
        linkA.onConnected()
        linkB.onConnected()
        linkA.startReceiving()
        linkB.startReceiving()
        if (exchangeBundles) {
            linkA.announceOwnBundle()
            linkB.announceOwnBundle()
            Thread.sleep(300) // give the ambient bundle exchange a moment to land before a test sends anything depending on it
        }
        return linkA to linkB
    }

    /** Polls (bounded) until [db] holds a [PendingMessageEntity] addressed to [recipientPeerId], or returns null on timeout. */
    private fun awaitPendingRow(db: HopDatabase, recipientPeerId: String, timeoutMs: Long = 5_000): PendingMessageEntity? {
        val deadline = System.currentTimeMillis() + timeoutMs
        var result = runBlocking { db.pendingMessageDao().getAll() }.find { it.recipientPeerId == recipientPeerId }
        while (result == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            result = runBlocking { db.pendingMessageDao().getAll() }.find { it.recipientPeerId == recipientPeerId }
        }
        return result
    }

    /** Polls (bounded, short interval) until [peerId]'s conversation contains [expectedPlaintext] at [db], or fails the test. */
    private fun awaitMessage(db: HopDatabase, peerId: String, expectedPlaintext: String, timeoutMs: Long = 5_000): MessageEntity {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val messages = runBlocking { db.messageDao().getMessagesForPeer(peerId).first() }
            messages.find { it.plaintext == expectedPlaintext }?.let { return it }
            Thread.sleep(50)
        }
        throw AssertionError("Never observed a message from $peerId with plaintext '$expectedPlaintext' within ${timeoutMs}ms")
    }

    // --- Test 1: 3-device relay chain, recipient offline at send time ---

    @Test
    fun threeDeviceChain_offlineRecipientReceivesViaCarrier_carrierNeverDecrypts() {
        val alice = Party("Alice", newInMemoryDb(), "alice-peer")
        val carrier = Party("Carrier", newInMemoryDb(), "carrier-peer")
        val bob = Party("Bob", newInMemoryDb(), "bob-peer")

        // Alice and Bob have an "already-reachable" relationship -- they've
        // directly connected before and exchanged prekey bundles (this
        // slice's own named scope boundary: it helps an already-established
        // relationship stay reachable, not first contact).
        val (linkAliceBob, _) = connect(alice, bob)
        linkAliceBob.close()

        // Bob goes offline from Alice; Alice is now only in range of
        // Carrier, who has never met Alice or Bob before.
        val (linkAliceCarrier, _) = connect(alice, carrier, exchangeBundles = false)

        val sendResult = runBlocking { alice.messageRepository.send(bob.peerId, "hello bob via carrier") }
        assertEquals(SendResult.Sent, sendResult)

        val carrierRow = awaitPendingRow(carrier.db, recipientPeerId = bob.peerId)
        assertNotNull(carrierRow, "the carrier must take relay custody of the message addressed to bob")

        Thread.sleep(300)
        val carrierOwnHistory = runBlocking { carrier.messageRepository.observeConversation(alice.peerId).first() }
        assertTrue(
            carrierOwnHistory.isEmpty(),
            "the carrier must never call onMessageCiphertextReceived for a message not addressed to it -- it has no session for this conversation and structurally cannot decrypt it",
        )

        linkAliceCarrier.close()

        // Carrier now connects to Bob and offers its relay backlog.
        connect(carrier, bob)

        val decryptedAtBob = awaitMessage(bob.db, peerId = alice.peerId, expectedPlaintext = "hello bob via carrier")
        assertEquals("hello bob via carrier", decryptedAtBob.plaintext)
        assertEquals(false, decryptedAtBob.isOutgoing)
    }

    // --- Test 2: unicast-success regression (would have caught the original over-symmetric design) ---

    @Test
    fun unicastSuccessNeverTakesCustodyOrFloodsToABystander() {
        val alice = Party("Alice", newInMemoryDb(), "alice-peer")
        val bob = Party("Bob", newInMemoryDb(), "bob-peer")
        val bystander = Party("Bystander", newInMemoryDb(), "bystander-peer")

        connect(alice, bob) // ambient bundle exchange, both directions
        connect(alice, bystander, exchangeBundles = false)

        val result = runBlocking { alice.messageRepository.send(bob.peerId, "fast path only") }
        assertEquals(SendResult.Sent, result)

        awaitMessage(bob.db, peerId = alice.peerId, expectedPlaintext = "fast path only")

        Thread.sleep(300) // give a (nonexistent) flood a moment to have crossed the wire if the fast path were broken
        val bystanderRows = runBlocking { bystander.db.pendingMessageDao().getAll() }
        assertTrue(
            bystanderRows.isEmpty(),
            "a successful fast-path unicast must never flood to, or take custody on, an uninvolved bystander",
        )
        val aliceRows = runBlocking { alice.db.pendingMessageDao().getAll() }
        assertTrue(aliceRows.isEmpty(), "a successful fast-path unicast must never take relay custody at the sender either")
    }

    // --- Test 3: redundant delivery via two disjoint relay paths ---

    @Test
    fun redundantDeliveryViaTwoDisjointRelayPathsIsAbsorbedWithoutADuplicateRow() {
        val alice = Party("Alice", newInMemoryDb(), "alice-peer")
        val bob = Party("Bob", newInMemoryDb(), "bob-peer")
        val carrierB = Party("CarrierB", newInMemoryDb(), "carrier-b-peer")
        val carrierD = Party("CarrierD", newInMemoryDb(), "carrier-d-peer")

        val (linkAliceBob, _) = connect(alice, bob)
        linkAliceBob.close()

        connect(alice, carrierB, exchangeBundles = false)
        connect(alice, carrierD, exchangeBundles = false)

        val result = runBlocking { alice.messageRepository.send(bob.peerId, "reaches bob twice") }
        assertEquals(SendResult.Sent, result)

        assertNotNull(awaitPendingRow(carrierB.db, recipientPeerId = bob.peerId), "carrier B never took custody")
        assertNotNull(awaitPendingRow(carrierD.db, recipientPeerId = bob.peerId), "carrier D never took custody")

        // B and D never connect to each other -- both independently connect to Bob.
        connect(carrierB, bob)
        awaitMessage(bob.db, peerId = alice.peerId, expectedPlaintext = "reaches bob twice")

        connect(carrierD, bob) // the same ciphertext arrives a second time via a disjoint path
        Thread.sleep(500) // give the redundant delivery a moment to (harmlessly) arrive

        val matching = runBlocking { bob.messageRepository.observeConversation(alice.peerId).first() }
            .filter { it.plaintext == "reaches bob twice" }
        assertEquals(1, matching.size, "the same ciphertext delivered via two disjoint relay paths must not produce a second row, and must not crash")
    }

    // --- Test 4: out-of-order delivery (skipped-key cache) ---

    @Test
    fun outOfOrderDeliveryStillDecryptsBothMessages() {
        val alice = Party("Alice", newInMemoryDb(), "alice-peer")
        val bob = Party("Bob", newInMemoryDb(), "bob-peer")

        // Establish a real session at both ends first (direct, fast-path
        // delivery), so "first"/"second" below are ordinary ratchet messages,
        // not X3DH PreKey messages -- this test is about the ratchet's
        // skipped-message-key cache, not session bootstrap ordering.
        val (linkAliceBob, _) = connect(alice, bob)
        assertEquals(SendResult.Sent, runBlocking { alice.messageRepository.send(bob.peerId, "handshake") })
        awaitMessage(bob.db, peerId = alice.peerId, expectedPlaintext = "handshake")
        linkAliceBob.close()

        // Bob is now offline from Alice -- both further sends go through
        // relay custody at Alice (never delivered over a live link), so we
        // can capture their exact wire bytes and dispatch them to Bob's
        // dispatcher directly, out of order.
        assertEquals(SendResult.Sent, runBlocking { alice.messageRepository.send(bob.peerId, "first") })
        assertEquals(SendResult.Sent, runBlocking { alice.messageRepository.send(bob.peerId, "second") })
        assertEquals(3, alice.sentEnvelopes.size, "handshake + first + second")

        val firstBytes = alice.sentEnvelopes[1]
        val secondBytes = alice.sentEnvelopes[2]

        // Message 2 arrives and decrypts successfully before message 1.
        runBlocking {
            bob.dispatcher.dispatch(WireEnvelope(WirePayloadType.MESSAGE_CIPHERTEXT, secondBytes))
            bob.dispatcher.dispatch(WireEnvelope(WirePayloadType.MESSAGE_CIPHERTEXT, firstBytes))
        }

        awaitMessage(bob.db, peerId = alice.peerId, expectedPlaintext = "second")
        awaitMessage(bob.db, peerId = alice.peerId, expectedPlaintext = "first")
    }

    // --- Test 5: TTL expiry ---

    @Test
    fun pendingMessageRowPastTtlIsPrunedOnNextBacklogBuildAndNeverDelivered() {
        val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
        val carrier = Party("Carrier", newInMemoryDb(), "carrier-peer", clock = clock)
        val bob = Party("Bob", newInMemoryDb(), "bob-peer", clock = clock)

        val envelope = MessageCiphertextEnvelope(
            senderPeerId = "alice-peer",
            recipientPeerId = bob.peerId,
            hopCount = 0,
            originatedAtMs = clock.instant().toEpochMilli(),
            ciphertext = ByteArray(32) { it.toByte() },
        )
        runBlocking { carrier.pendingMessageRepository.considerForRelay(envelope) }
        assertEquals(1, runBlocking { carrier.db.pendingMessageDao().getAll() }.size)

        clock.advanceBy(Duration.ofSeconds(MessageCiphertextEnvelope.DEFAULT_TTL_SECONDS + 60))

        connect(carrier, bob, exchangeBundles = false)
        Thread.sleep(300)

        val bobHistory = runBlocking { bob.messageRepository.observeConversation("alice-peer").first() }
        assertTrue(bobHistory.isEmpty(), "an expired relay-custody message must never be delivered")
        val carrierRowsAfter = runBlocking { carrier.db.pendingMessageDao().getAll() }
        assertTrue(carrierRowsAfter.isEmpty(), "an expired row must be pruned on the next buildOutgoingBacklog() call")
    }

    // --- Test 6: NoSessionAvailable scope-boundary case (no bundle-relay) ---

    @Test
    fun noCachedBundleAndNoSessionReturnsNoSessionAvailableAndNeverTouchesPendingMessageRepository() {
        val alice = Party("Alice", newInMemoryDb(), "alice-peer")
        // Deliberately never connect to anyone / never cache a bundle --
        // alice has no session and no cached bundle for a peer id never seen
        // at all. This is the named scope cut: this slice does not solve
        // first-contact messaging with a peer never directly connected to.

        val result = runBlocking { alice.messageRepository.send("never-met-peer-id", "hello?") }
        assertEquals(SendResult.NoSessionAvailable, result)

        val history = runBlocking { alice.messageRepository.observeConversation("never-met-peer-id").first() }
        assertTrue(history.isEmpty(), "a failed send (no session available) must not insert a local MessageEntity")
        assertTrue(
            runBlocking { alice.db.pendingMessageDao().getAll() }.isEmpty(),
            "PendingMessageRepository must never be touched on a NoSessionAvailable outcome -- confirms the no-bundle-relay scope cut holds in code, not just in the plan doc",
        )
        assertTrue(alice.sentEnvelopes.isEmpty(), "sendMessage must never even be invoked on a NoSessionAvailable outcome")
    }

    // --- Test 7: local delivery-confirmation stop signal ---

    @Test
    fun carrierDropsItsOwnCustodyAfterDirectlyHandingOffToTheNowIdentifiedRecipient() {
        val alice = Party("Alice", newInMemoryDb(), "alice-peer")
        val carrier = Party("Carrier", newInMemoryDb(), "carrier-peer")
        val bob = Party("Bob", newInMemoryDb(), "bob-peer")

        val (linkAliceBob, _) = connect(alice, bob)
        linkAliceBob.close()

        val (linkAliceCarrier, _) = connect(alice, carrier, exchangeBundles = false)
        assertEquals(SendResult.Sent, runBlocking { alice.messageRepository.send(bob.peerId, "stop signal test") })
        assertNotNull(awaitPendingRow(carrier.db, recipientPeerId = bob.peerId), "carrier never took custody")
        linkAliceCarrier.close()

        // Carrier connects to Bob -- Bob's ambient bundle announcement lets
        // Carrier positively identify this connection as Bob, which fires
        // the local delivery-confirmation stop signal (hands the held
        // message directly over this connection and, on success, drops
        // local custody) independent of the ordinary backlog flood that
        // also happens on connect.
        connect(carrier, bob)

        awaitMessage(bob.db, peerId = alice.peerId, expectedPlaintext = "stop signal test")

        val deadline = System.currentTimeMillis() + 5_000
        var carrierRows = runBlocking { carrier.db.pendingMessageDao().getAll() }
        while (carrierRows.isNotEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            carrierRows = runBlocking { carrier.db.pendingMessageDao().getAll() }
        }
        assertTrue(
            carrierRows.isEmpty(),
            "the carrier's own relay-custody row must be deleted once it has directly handed the message to its now-identified recipient",
        )
    }
}
