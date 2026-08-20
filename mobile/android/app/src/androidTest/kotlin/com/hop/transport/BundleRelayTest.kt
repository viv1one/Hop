package com.hop.transport

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hop.crypto.DecayKeyStore
import com.hop.crypto.PreKeyBundleCodec
import com.hop.data.HopDatabase
import com.hop.data.IdentityKeyPairKeystoreCipher
import com.hop.data.PEER_DEVICE_ID
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Instrumented end-to-end tests for the prekey-bundle relay/discovery
 * follow-up to Phase 2's four original slices (post relay, "don't relay"
 * flags, 1:1 store-and-forward, group messaging) -- test cases 5-7 named in
 * the slice's plan (cases 1-4, [BundleRepository]'s own eligibility/
 * conditional-replace logic, are plain JVM tests in
 * `com.hop.repository.BundleRepositoryTest`).
 *
 * Same posture as [PendingMessageRelayTest]/[GroupMessagingTest]: does
 * **not** construct a real [WifiDirectTransport] (needs a real
 * `Context`/`WifiP2pManager.Channel` this test environment doesn't
 * guarantee); instead [BundleLink] drives the exact same production classes
 * ([EnvelopeDispatcher], [DispatchResult], [BundleRepository],
 * [PendingMessageRepository], [RelayPolicy], real [MessageRepository] +
 * [RoomSignalProtocolStore] + `crypto/` `DoubleRatchetSession`) over plain
 * loopback socket pairs, replicating only the mechanical "on connect: send
 * backlog + announce own bundle; on receive: dispatch, and act on the
 * result" glue [WifiDirectTransport] itself contains.
 *
 * **A note on how cases 5/6 set up their relay preconditions**: per
 * [EnvelopeDispatcher.dispatch]'s own `PREKEY_BUNDLE` branch, a device
 * takes [BundleRepository] custody of a bundle at *every* hop, including a
 * genuine direct announce (`hopCount == 0`) -- see
 * [DispatchResult.DirectBundleAnnounce]'s own doc for why an earlier version
 * of this feature got this wrong (only taking custody on the
 * `hopCount >= 1` branch, which meant a device that only ever learned a
 * bundle directly could never re-offer it, and the mesh had no first hop to
 * relay from at all -- see [EnvelopeDispatcherBundleRelayTest] for the fast
 * JVM regression tests that exercise the real dispatch entry point for this
 * specific fix). What `hopCount` still gates is only whether the connection
 * gets tagged with the bundle owner's peer id, never whether custody is
 * taken. Both tests below seed the "this device is already carrying a
 * relayed copy of someone else's bundle" precondition directly via
 * [BundleRepository.considerForRelay] with a hand-built `hopCount = 1`
 * envelope -- representing a bundle this device would have received from
 * some earlier hop already having crossed the mesh -- exactly mirroring
 * [PendingMessageRelayTest]'s own established pattern of
 * hand-constructing/injecting wire envelopes to exercise a specific relay
 * state (that file's out-of-order-delivery case) rather than reproducing an
 * entire multi-device chain from ambient hop-0 exchange alone; the hop-0
 * bootstrap step itself is covered separately by
 * [EnvelopeDispatcherBundleRelayTest], not by this file.
 */
@RunWith(AndroidJUnit4::class)
class BundleRelayTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val sockets = mutableListOf<Socket>()
    private val closeableDbs = mutableListOf<HopDatabase>()

    @After
    fun tearDown() {
        sockets.forEach { runCatching { it.close() } }
        closeableDbs.forEach { runCatching { it.close() } }
    }

    /** One simulated device's full local messaging stack -- mirrors what [com.hop.app.AppContainer] wires together in production. */
    private inner class Party(val name: String, val db: HopDatabase, val peerId: String) {
        val activeLinks = CopyOnWriteArrayList<BundleLink>()

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
        private val relayPolicy = RelayPolicy()
        val pendingMessageRepository = PendingMessageRepository(db.pendingMessageDao(), relayPolicy)
        val bundleRepository = BundleRepository(db.bundleQueueDao(), relayPolicy)

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

        val dispatcher = EnvelopeDispatcher(
            receivedFrameStore = receivedFrameStore,
            dontRelayRepository = dontRelayRepository,
            pendingMessageRepository = pendingMessageRepository,
            bundleRepository = bundleRepository,
            getOwnPeerId = { peerId },
            onPreKeyBundleReceived = messageRepository::cachePeerBundle,
            onMessageCiphertextReceived = messageRepository::onEnvelopeReceived,
        )

        /** Test's stand-in for [WifiDirectTransport.sendMessage] -- unicast-first, flood-on-failure. Mirrors that production method's shape exactly. */
        fun sendMessage(recipientPeerId: String, payload: ByteArray): Boolean {
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

        /**
         * Test's stand-in for [WifiDirectTransport.sendToPeer] -- used by case
         * 5 to assert the `hopCount == 0` gating fix holds: a connection that
         * only ever carried a relayed bundle must never be found here.
         */
        fun sendToPeer(targetPeerId: String, type: WirePayloadType, payload: ByteArray): Boolean {
            val link = activeLinks.firstOrNull { it.remotePeerId == targetPeerId } ?: return false
            return link.send(type, payload)
        }
    }

    /**
     * Test-only stand-in for one [WifiDirectTransport] peer connection --
     * mirrors [WifiDirectTransport.handleConnection]'s "on connect: send
     * relay backlog (posts/messages/bundles) + announce own bundle" and
     * [WifiDirectTransport.receivePosts]'s "on receive: dispatch, and act on
     * the [DispatchResult]" behavior, over one already-connected [socket].
     */
    private class BundleLink(private val party: Party, private val socket: Socket) {
        private val out = DataOutputStream(socket.getOutputStream())
        private val writeLock = Any()

        @Volatile
        var remotePeerId: String? = null

        fun send(type: WirePayloadType, payload: ByteArray): Boolean = sendEnvelopeBytes(WireEnvelope.encode(type, payload))

        fun sendEnvelopeBytes(bytes: ByteArray): Boolean = synchronized(writeLock) {
            try {
                out.write(bytes)
                out.flush()
                true
            } catch (e: Exception) {
                false
            }
        }

        /** Mirrors [WifiDirectTransport.registerConnectionAndGetBacklog]'s message + bundle backlog halves. */
        fun onConnected() {
            party.activeLinks.add(this)
            val messageBacklog = runBlocking { party.pendingMessageRepository.buildOutgoingBacklog() }
            for (envelopeBytes in messageBacklog) sendEnvelopeBytes(envelopeBytes)
            val bundleBacklog = runBlocking { party.bundleRepository.buildOutgoingBacklog() }
            for (envelopeBytes in bundleBacklog) sendEnvelopeBytes(envelopeBytes)
        }

        /** Mirrors [WifiDirectTransport.announceOwnPreKeyBundle] -- always hopCount = 0, a genuine direct announce. */
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
                        val result = runBlocking { party.dispatcher.dispatch(envelope) }
                        when (result) {
                            is DispatchResult.PeerIdentified -> {
                                remotePeerId = result.peerId
                                deliverAnyPendingMessagesTo(result.peerId)
                            }
                            is DispatchResult.NewRelayableMessage -> {
                                remotePeerId = result.senderPeerId
                                val stored = MessageCiphertextEnvelope.decode(result.row.encodedEnvelope)
                                val outgoing = stored.copy(hopCount = stored.hopCount + 1)
                                val outgoingBytes = WireEnvelope.encode(WirePayloadType.MESSAGE_CIPHERTEXT, outgoing.encode())
                                for (other in party.activeLinks) {
                                    if (other === this) continue
                                    other.sendEnvelopeBytes(outgoingBytes)
                                }
                                deliverAnyPendingMessagesTo(result.senderPeerId)
                            }
                            is DispatchResult.NewRelayableBundle -> {
                                // Deliberately NEVER assigns remotePeerId here
                                // -- this is the "carrier, not owner" case the
                                // hopCount == 0 gating fix exists to isolate.
                                // Mirrors WifiDirectTransport.handleNewlyReceivedBundle:
                                // hopCount+1 re-encode, live-push to every
                                // *other* link.
                                val stored = PreKeyBundleEnvelope.decode(result.row.encodedEnvelope)
                                val outgoing = stored.copy(hopCount = stored.hopCount + 1)
                                val outgoingBytes = WireEnvelope.encode(WirePayloadType.PREKEY_BUNDLE, outgoing.encode())
                                for (other in party.activeLinks) {
                                    if (other === this) continue
                                    other.sendEnvelopeBytes(outgoingBytes)
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
            }, "test-bundle-receive-${party.name}").start()
        }

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

    /** Connects [a] and [b] over a fresh real loopback socket pair -- mirrors [PendingMessageRelayTest.connect]/[GroupMessagingTest.connect]'s own doc exactly. */
    private fun connect(a: Party, b: Party, exchangeBundles: Boolean = true): Pair<BundleLink, BundleLink> {
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

        val linkA = BundleLink(a, clientSocket)
        val linkB = BundleLink(b, bSocket)
        linkA.onConnected()
        linkB.onConnected()
        linkA.startReceiving()
        linkB.startReceiving()
        if (exchangeBundles) {
            linkA.announceOwnBundle()
            linkB.announceOwnBundle()
            Thread.sleep(300)
        }
        return linkA to linkB
    }

    private fun awaitGroupMessage(db: HopDatabase, groupId: String, expectedPlaintext: String, timeoutMs: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val messages = runBlocking { db.groupMessageDao().getMessagesForGroup(groupId).first() }
            if (messages.any { it.plaintext == expectedPlaintext }) return
            Thread.sleep(50)
        }
        throw AssertionError("Never observed a group message with plaintext '$expectedPlaintext' in group $groupId within ${timeoutMs}ms")
    }

    // --- Test 5: multi-hop bundle relay, and the hopCount == 0 fix holds ---

    @Test
    fun multiHopBundleRelayReachesAThirdDeviceWithoutMistaggingTheCarrierConnection() {
        val a = Party("A", newInMemoryDb(), "a-peer")
        val b = Party("B", newInMemoryDb(), "b-peer")
        val c = Party("C", newInMemoryDb(), "c-peer")

        // Seed B as already carrying a relayed (hopCount = 1) copy of A's
        // bundle -- see this file's own class doc for why this precondition
        // is seeded directly rather than produced purely from an ambient
        // hop-0 exchange (which never takes BundleRepository custody, by
        // design).
        val aBundle = a.preKeyRotationManager.currentBundle()
        val relayedFromA = PreKeyBundleEnvelope(
            peerId = a.peerId,
            hopCount = 1,
            originatedAtMs = System.currentTimeMillis(),
            bundleBytes = PreKeyBundleCodec.encode(aBundle),
        )
        assertNotNull(runBlocking { b.bundleRepository.considerForRelay(relayedFromA) }, "B must take custody of the relayed bundle")

        // C connects only to B -- A and C never connect directly.
        val (linkBSide, linkCSide) = connect(b, c, exchangeBundles = false)
        // B's own bundle backlog (built at connect time) offers A's relayed
        // bundle onward to C at hopCount = 2.
        Thread.sleep(300)

        // C must now have A's bundle cached -- proven indirectly by C being
        // able to initiate a fresh session and "send" to A (A isn't
        // connected at all, so this only succeeds via the cached bundle).
        val sendResult = runBlocking { c.messageRepository.send(a.peerId, "hello A via B's relay") }
        assertEquals(SendResult.Sent, sendResult, "C must have cached A's bundle via the relayed PREKEY_BUNDLE envelope")

        // The hopCount == 0 fix: C's connection to B must never be mistagged
        // as A's peer id -- A was never actually connected to C.
        assertFalse(
            c.sendToPeer(a.peerId, WirePayloadType.MESSAGE_CIPHERTEXT, byteArrayOf(1, 2, 3)),
            "C's connection to B must never be identified as a direct line to A -- B only ever carried A's bundle",
        )

        linkBSide.close()
        linkCSide.close()
    }

    // --- Test 6: group member-introduction end-to-end (the load-bearing test for this whole plan) ---

    @Test
    fun groupMemberIntroductionViaBundleRelayThroughCreator_closesTheGapWithZeroMessageRepositoryChanges() {
        val creator = Party("Creator", newInMemoryDb(), "creator-peer")
        val bob = Party("Bob", newInMemoryDb(), "bob-peer")
        val carol = Party("Carol", newInMemoryDb(), "carol-peer")

        val (linkCreatorBob, linkBobCreator) = connect(creator, bob)
        val (linkCreatorCarol, linkCarolCreator) = connect(creator, carol)

        val groupId = runBlocking { creator.messageRepository.createGroup("Trip", listOf(bob.peerId, carol.peerId)) }
        Thread.sleep(300)

        // Before any bundle relay: Bob has no session/cached bundle for
        // Carol (they've never connected), so sendToGroup silently skips her
        // -- today's documented, unchanged MessageRepository.sendToGroup
        // behavior.
        runBlocking { bob.messageRepository.sendToGroup(groupId, "hi all, before we've met") }
        Thread.sleep(300)
        val carolHistoryBefore = runBlocking { carol.db.groupMessageDao().getMessagesForGroup(groupId).first() }
        assertTrue(
            carolHistoryBefore.none { it.plaintext == "hi all, before we've met" },
            "Carol must not receive Bob's group message before their bundles have crossed paths anywhere in the mesh",
        )

        // Creator has already relayed Bob's and Carol's bundles to each
        // other -- see this file's own class doc for why this precondition
        // is seeded directly (mirroring "typically via the creator, but not
        // required to be" from the plan's own Context section) rather than
        // waiting on the hop-0-only ambient exchange to somehow produce it.
        val bobBundle = PreKeyBundleCodec.encode(bob.preKeyRotationManager.currentBundle())
        val carolBundle = PreKeyBundleCodec.encode(carol.preKeyRotationManager.currentBundle())
        val bobRelayed = PreKeyBundleEnvelope(peerId = bob.peerId, hopCount = 1, originatedAtMs = System.currentTimeMillis(), bundleBytes = bobBundle)
        val carolRelayed = PreKeyBundleEnvelope(peerId = carol.peerId, hopCount = 1, originatedAtMs = System.currentTimeMillis(), bundleBytes = carolBundle)
        assertNotNull(runBlocking { creator.bundleRepository.considerForRelay(bobRelayed) })
        assertNotNull(runBlocking { creator.bundleRepository.considerForRelay(carolRelayed) })

        // Creator reconnects to Bob and to Carol -- ordinary connect-time
        // bundle backlog now offers each other's relayed bundle.
        linkCreatorBob.close()
        linkBobCreator.close()
        linkCreatorCarol.close()
        linkCarolCreator.close()
        val (newLinkCreatorBob, newLinkBobCreator) = connect(creator, bob, exchangeBundles = false)
        val (newLinkCreatorCarol, newLinkCarolCreator) = connect(creator, carol, exchangeBundles = false)
        Thread.sleep(300)

        // A subsequent sendToGroup from Bob now actually reaches Carol via
        // the existing message-relay path through creator -- Bob and Carol
        // still never connect to each other directly -- proving both the
        // bundle-relay mechanism and the "zero MessageRepository code
        // change" claim together.
        runBlocking { bob.messageRepository.sendToGroup(groupId, "hi all, now that we've crossed paths") }
        awaitGroupMessage(carol.db, groupId, "hi all, now that we've crossed paths")

        newLinkCreatorBob.close()
        newLinkBobCreator.close()
        newLinkCreatorCarol.close()
        newLinkCarolCreator.close()
    }

    // --- Test 7: staleness clean-failure regression (no new production code -- locks in a design-validation finding) ---

    @Test
    fun staleBundleInitiatesCleanlyAndTheOwnerAbsorbsTheResultingInvalidKeyIdExceptionWithoutCrashing() {
        val x = Party("X", newInMemoryDb(), "x-peer")
        val y = Party("Y", newInMemoryDb(), "y-peer")

        val bundle = x.preKeyRotationManager.currentBundle()
        val staleOneTimePreKeyId = bundle.preKeyId

        // Y caches X's bundle directly -- the bundle-exchange/relay
        // mechanism itself is exercised by tests 5/6 above; this test is
        // specifically about what happens once a cached bundle goes stale.
        y.messageRepository.cachePeerBundle(x.peerId, PreKeyBundleCodec.encode(bundle))

        // X's own store independently consumes/prunes the one-time prekey
        // this bundle references -- e.g. some other device already used it,
        // or rotation pruned it. Cause is irrelevant to this regression;
        // what matters is that it's gone from X's store by the time Y's
        // stale-bundle-based initiate() reaches X.
        x.store.removePreKey(staleOneTimePreKeyId)

        // initiate() itself must succeed -- DoubleRatchetSession.initiate
        // never looks up prekey ids against any store (traced during design
        // validation): the bundle carries its keys inline, only
        // signature-checked.
        val sendResult = runBlocking { y.messageRepository.send(x.peerId, "hello via a now-stale bundle") }
        assertEquals(SendResult.Sent, sendResult, "initiate() must succeed even though the bundle's one-time prekey is already gone from X's store")

        // Y's own local history still reflects the send optimistically
        // ("local state reflects intent, not delivery" -- MessageRepository.send's
        // own posture), independent of what happens on X's side below.
        val yLocalHistory = runBlocking { y.messageRepository.observeConversation(x.peerId).first() }
        assertEquals(1, yLocalHistory.size)
        assertTrue(yLocalHistory.single().isOutgoing)

        // Hand the resulting ciphertext directly to X's onEnvelopeReceived --
        // no live socket needed for this assertion, matching this test's
        // narrow crypto-adjacent scope. Decoding the envelope here (not
        // dispatching through EnvelopeDispatcher) is deliberate: the
        // WirePayloadType.MESSAGE_CIPHERTEXT dispatch path is already
        // covered by every other messaging test in this suite; this test is
        // specifically about MessageRepository.onEnvelopeReceived's own
        // exception handling.
        val sentEnvelopeBytes = capturedEnvelopeBytesFrom(y, recipientPeerId = x.peerId)
        val decoded = MessageCiphertextEnvelope.decode(sentEnvelopeBytes)

        // The resulting InvalidKeyIdException must be absorbed cleanly by
        // MessageRepository.onEnvelopeReceived's existing broad catch -- no
        // exception escapes this call, no message is persisted at X, no
        // crash.
        runBlocking { x.messageRepository.onEnvelopeReceived(decoded.senderPeerId, decoded.ciphertext) }

        val xHistory = runBlocking { x.messageRepository.observeConversation(y.peerId).first() }
        assertTrue(xHistory.isEmpty(), "a message that fails to decrypt due to a stale/consumed prekey id must never be persisted")
    }

    /**
     * Re-derives the exact wire bytes [Party.sendMessage] would have handed
     * to a transport for [y]'s single outgoing send in
     * [staleBundleInitiatesCleanlyAndTheOwnerAbsorbsTheResultingInvalidKeyIdExceptionWithoutCrashing] --
     * [y] has no live connection in that test (unicast fast path fails by
     * construction, since no link was ever opened), so [PendingMessageRepository]
     * took relay custody of the exact envelope bytes needed here already.
     */
    private fun capturedEnvelopeBytesFrom(y: Party, recipientPeerId: String): ByteArray {
        val rows = runBlocking { y.pendingMessageRepository.findByRecipient(recipientPeerId) }
        return requireNotNull(rows.singleOrNull()?.encodedEnvelope) {
            "expected exactly one relay-custody row for X's undelivered message at Y"
        }
    }
}
