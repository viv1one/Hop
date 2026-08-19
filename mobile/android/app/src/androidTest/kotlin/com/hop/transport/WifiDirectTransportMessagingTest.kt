package com.hop.transport

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hop.crypto.DecayKeyStore
import com.hop.crypto.DoubleRatchetSession
import com.hop.crypto.PreKeyBundleCodec
import com.hop.data.HopDatabase
import com.hop.data.MessageEntity
import com.hop.data.PEER_DEVICE_ID
import com.hop.data.RoomSignalProtocolStore
import com.hop.protocol.PreKeyBundleEnvelope
import com.hop.protocol.WireEnvelope
import com.hop.protocol.WirePayloadType
import com.hop.repository.BlockRepository
import com.hop.repository.MessageRepository
import com.hop.repository.PostRepository
import com.hop.repository.SendResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.signal.libsignal.protocol.state.SignalProtocolStore
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Instrumented end-to-end test for the Phase 1 messaging transport dispatch
 * + [MessageRepository] integration, over a real loopback `Socket`/
 * `ServerSocket` pair -- "real crypto with only the radio mocked" (Stage 3's
 * verification bullet). Real Room (two separate in-memory [HopDatabase]
 * instances, one per simulated peer), real [RoomSignalProtocolStore], real
 * [DoubleRatchetSession] via [MessageRepository], real [WireEnvelope]/
 * [PreKeyBundleEnvelope]/`MessageCiphertextEnvelope` wire bytes.
 *
 * Does **not** construct a real [WifiDirectTransport] -- that class requires
 * a real `Context`/`WifiP2pManager.Channel`, which needs actual WiFi Direct
 * radio/service support this test environment doesn't guarantee. Instead,
 * [LoopbackLink] drives the exact same production dispatch logic
 * ([EnvelopeDispatcher], `internal` and directly constructible -- see its own
 * doc for why it's factored out precisely for this kind of test) over a
 * plain loopback socket pair, replicating only [WifiDirectTransport
 * .receivePosts]'s trivial, mechanical header-parsing loop (read a
 * [WireEnvelope.HEADER_SIZE]-byte header, then the declared payload length)
 * -- the actual interesting logic under test (bundle caching, session
 * get-or-create, encrypt/decrypt, block-check, persistence) all runs through
 * the real [MessageRepository]/[EnvelopeDispatcher] classes, unmodified.
 */
@RunWith(AndroidJUnit4::class)
class WifiDirectTransportMessagingTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var aliceDb: HopDatabase
    private lateinit var bobDb: HopDatabase
    private lateinit var serverSocket: ServerSocket
    private lateinit var aliceSocket: Socket
    private lateinit var bobSocket: Socket

    /** One conversation participant's full local stack, mirroring one physical device. */
    private inner class Party(db: HopDatabase, val peerId: String) {
        val store: SignalProtocolStore = RoomSignalProtocolStore(
            db.signalIdentityDao(),
            db.signalPreKeyDao(),
            db.signalSignedPreKeyDao(),
            db.signalKyberPreKeyDao(),
            db.signalSessionDao(),
        )
        val blockRepository = BlockRepository(db.blockedSenderDeviceDao())
        private val postRepository = PostRepository(db.postDao(), DecayKeyStore())
        private val receivedFrameStore = ReceivedFrameStore(postRepository, DecayKeyStore(), tempFolder.newFolder())

        lateinit var link: LoopbackLink

        val messageRepository: MessageRepository = MessageRepository(
            messageDao = db.messageDao(),
            signalProtocolStore = store,
            blockRepository = blockRepository,
            getOwnPeerId = { peerId },
            sendToPeer = { _, type, payload -> link.send(type, payload) },
        )

        val dispatcher = EnvelopeDispatcher(
            receivedFrameStore = receivedFrameStore,
            onPreKeyBundleReceived = messageRepository::cachePeerBundle,
            onMessageCiphertextReceived = messageRepository::onEnvelopeReceived,
        )

        /** Publishes and sends this party's own current prekey bundle over [viaLink] -- test's stand-in for [WifiDirectTransport.announceOwnPreKeyBundle]. */
        fun announceBundleTo(viaLink: LoopbackLink) {
            val bundle = DoubleRatchetSession.publishPreKeyBundle(store, PEER_DEVICE_ID)
            val envelope = PreKeyBundleEnvelope(peerId = peerId, bundleBytes = PreKeyBundleCodec.encode(bundle))
            viaLink.send(WirePayloadType.PREKEY_BUNDLE, envelope.encode())
        }
    }

    /**
     * Test-only stand-in for [WifiDirectTransport.PeerConnection] +
     * [WifiDirectTransport.receivePosts]'s header-parsing loop, over one
     * already-connected [socket]. [send] mirrors
     * [WifiDirectTransport.PeerConnection.trySend] byte-for-byte; the
     * receive loop mirrors [WifiDirectTransport.receivePosts]'s framing
     * exactly, then hands each parsed [WireEnvelope] to the real
     * [EnvelopeDispatcher].
     */
    private class LoopbackLink(private val socket: Socket, private val dispatcher: EnvelopeDispatcher) {
        private val out = DataOutputStream(socket.getOutputStream())
        private val writeLock = Any()

        fun send(type: WirePayloadType, payload: ByteArray): Boolean = synchronized(writeLock) {
            try {
                out.write(WireEnvelope.encode(type, payload))
                out.flush()
                true
            } catch (e: Exception) {
                false
            }
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
                        runBlocking { dispatcher.dispatch(envelope) }
                        afterEachDispatch()
                    }
                } catch (e: Exception) {
                    // socket closed / test teardown -- expected.
                }
            }, "test-loopback-receive").start()
        }
    }

    private fun newInMemoryDb(): HopDatabase =
        Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), HopDatabase::class.java).build()

    @Before
    fun setUp() {
        aliceDb = newInMemoryDb()
        bobDb = newInMemoryDb()

        serverSocket = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val accepted = CountDownLatch(1)
        var acceptedSocket: Socket? = null
        Thread({
            acceptedSocket = serverSocket.accept()
            accepted.countDown()
        }, "test-accept").start()

        aliceSocket = Socket(InetAddress.getLoopbackAddress(), serverSocket.localPort)
        assertTrue(accepted.await(5, TimeUnit.SECONDS), "loopback accept did not complete in time")
        bobSocket = requireNotNull(acceptedSocket)
    }

    @After
    fun tearDown() {
        listOf(aliceSocket, bobSocket, serverSocket).forEach {
            try {
                it.close()
            } catch (e: Exception) {
                // best-effort cleanup
            }
        }
        aliceDb.close()
        bobDb.close()
    }

    /**
     * Waits for [times] dispatch cycles on this link's receive loop, with a
     * timeout -- deterministic synchronization for assertions that run right
     * after an async socket round trip, without a fixed sleep.
     */
    private fun startReceivingAndAwait(link: LoopbackLink, times: Int): CountDownLatch {
        val latch = CountDownLatch(times)
        link.startReceiving { latch.countDown() }
        return latch
    }

    @Test
    fun bundleExchangeThenSendThenReceiveEndToEndOverRealSockets() {
        val alice = Party(aliceDb, "alice-peer-id")
        val bob = Party(bobDb, "bob-peer-id")
        alice.link = LoopbackLink(aliceSocket, alice.dispatcher)
        bob.link = LoopbackLink(bobSocket, bob.dispatcher)

        // Ambient bundle exchange, both directions -- mirrors what
        // WifiDirectTransport.announceOwnPreKeyBundle does automatically on
        // every new connection, on both the group-owner and client sides.
        val aliceReceivedBundle = startReceivingAndAwait(alice.link, times = 1)
        val bobReceivedBundle = startReceivingAndAwait(bob.link, times = 1)
        alice.announceBundleTo(alice.link)
        bob.announceBundleTo(bob.link)
        assertTrue(aliceReceivedBundle.await(5, TimeUnit.SECONDS), "alice never received bob's bundle announcement")
        assertTrue(bobReceivedBundle.await(5, TimeUnit.SECONDS), "bob never received alice's bundle announcement")

        // Alice starts a chat with Bob (no prior session -- falls back to the
        // cached bundle + DoubleRatchetSession.initiate).
        val sendResult = runBlocking { alice.messageRepository.send(bob.peerId, "hello bob, this is alice") }
        assertEquals(SendResult.Sent, sendResult)

        // bob's receive loop is already running (startReceivingAndAwait
        // above); the CountDownLatch from the bundle exchange was already
        // consumed, so wait for the message itself by polling the DB.
        val decryptedAtBob = awaitMessage(bobDb, peerId = alice.peerId, expectedPlaintext = "hello bob, this is alice")
        assertEquals("hello bob, this is alice", decryptedAtBob.plaintext)
        assertEquals(false, decryptedAtBob.isOutgoing)

        // Alice's own local history reflects the send optimistically.
        val aliceLocalCopy = runBlocking { alice.messageRepository.observeConversation(bob.peerId).first() }
        assertEquals(1, aliceLocalCopy.size)
        assertEquals(true, aliceLocalCopy.single().isOutgoing)
        assertEquals("hello bob, this is alice", aliceLocalCopy.single().plaintext)

        // Bob replies -- exercises the "session already exists, reuse via
        // forIncoming" path on Bob's own send() (no re-handshake/new bundle
        // needed).
        val replyResult = runBlocking { bob.messageRepository.send(alice.peerId, "hi alice, bob here") }
        assertEquals(SendResult.Sent, replyResult)

        val decryptedAtAlice = awaitMessage(aliceDb, peerId = bob.peerId, expectedPlaintext = "hi alice, bob here")
        assertEquals("hi alice, bob here", decryptedAtAlice.plaintext)
        assertEquals(false, decryptedAtAlice.isOutgoing)
    }

    @Test
    fun sendToABlockedPeerIsRefusedAndNothingIsPersistedOrSent() {
        val alice = Party(aliceDb, "alice-peer-id")
        val bob = Party(bobDb, "bob-peer-id")
        alice.link = LoopbackLink(aliceSocket, alice.dispatcher)
        bob.link = LoopbackLink(bobSocket, bob.dispatcher)

        val aliceReceivedBundle = startReceivingAndAwait(alice.link, times = 1)
        bob.link.startReceiving()
        bob.announceBundleTo(bob.link)
        assertTrue(aliceReceivedBundle.await(5, TimeUnit.SECONDS))

        runBlocking { alice.blockRepository.block(bob.peerId) }

        val result = runBlocking { alice.messageRepository.send(bob.peerId, "should never be sent") }
        assertEquals(SendResult.Blocked, result)

        val aliceLocalHistory = runBlocking { alice.messageRepository.observeConversation(bob.peerId).first() }
        assertTrue(aliceLocalHistory.isEmpty(), "a blocked send must not insert a local MessageEntity either")

        // Give the (nonexistent) send a moment to have crossed the wire if
        // the block check were broken, then confirm Bob truly received
        // nothing.
        Thread.sleep(300)
        val bobHistory = runBlocking { bob.messageRepository.observeConversation(alice.peerId).first() }
        assertTrue(bobHistory.isEmpty(), "a blocked send must never reach the wire")
    }

    @Test
    fun sendToAPeerWithNoCachedBundleAndNoSessionFailsClearlyWithoutCrashing() {
        val alice = Party(aliceDb, "alice-peer-id")
        // Deliberately never exchange bundles -- alice has no session and no
        // cached bundle for a peer id that was never seen at all.
        alice.link = LoopbackLink(aliceSocket, alice.dispatcher)
        alice.link.startReceiving()

        val result = runBlocking { alice.messageRepository.send("never-met-peer-id", "hello?") }
        assertEquals(SendResult.NoSessionAvailable, result)

        val history = runBlocking { alice.messageRepository.observeConversation("never-met-peer-id").first() }
        assertTrue(history.isEmpty(), "a failed send (no session available) must not insert a local MessageEntity")
    }

    /** Polls (bounded, short interval) until [peerId]'s conversation contains [expectedPlaintext], or fails the test. */
    private fun awaitMessage(
        db: HopDatabase,
        peerId: String,
        expectedPlaintext: String,
        timeoutMs: Long = 5_000,
    ): MessageEntity {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val messages = runBlocking { db.messageDao().getMessagesForPeer(peerId).first() }
            messages.find { it.plaintext == expectedPlaintext }?.let { return it }
            Thread.sleep(50)
        }
        throw AssertionError("Never observed a message from $peerId with plaintext '$expectedPlaintext' within ${timeoutMs}ms")
    }
}
