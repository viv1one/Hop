package com.hop.transport

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hop.crypto.DecayKeyStore
import com.hop.crypto.DoubleRatchetSession
import com.hop.crypto.MessagePayload
import com.hop.crypto.PreKeyBundleCodec
import com.hop.data.GroupEntity
import com.hop.data.HopDatabase
import com.hop.data.IdentityKeyPairKeystoreCipher
import com.hop.data.PEER_DEVICE_ID
import com.hop.data.PendingMessageEntity
import com.hop.data.PreKeyRotationManager
import com.hop.data.RoomSignalProtocolStore
import com.hop.data.peerSignalAddress
import com.hop.protocol.MessageCiphertextEnvelope
import com.hop.protocol.PreKeyBundleEnvelope
import com.hop.protocol.RelayPolicy
import com.hop.protocol.WireEnvelope
import com.hop.protocol.WirePayloadType
import com.hop.repository.BlockRepository
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Instrumented end-to-end tests for Phase 2 Slice 4's group messaging (see
 * `com.hop.repository.MessageRepository`'s own class doc) -- the 8 test cases
 * named in the slice's plan. Same posture as [WifiDirectTransportMessagingTest]/
 * [PendingMessageRelayTest]: does **not** construct a real [WifiDirectTransport]
 * (needs a real `Context`/`WifiP2pManager.Channel` this test environment
 * doesn't guarantee); instead [GroupLink] drives the exact same production
 * classes ([EnvelopeDispatcher], [PendingMessageRepository], real
 * [MessageRepository] + [RoomSignalProtocolStore] + `crypto/`
 * `DoubleRatchetSession`/`MessagePayload`) over plain loopback socket pairs.
 *
 * Three simulated devices throughout: `creator` (who calls [MessageRepository.createGroup]),
 * `bob`, and `carol`. `creator` connects directly to both `bob` and `carol`
 * (so creator<->member sessions exist, per the "only create a group from
 * existing 1:1 contacts" scope boundary) -- `bob` and `carol` are
 * **deliberately never connected to each other** in most tests, modeling the
 * member-introduction limitation named throughout this slice's plan and
 * `MessageRepository`'s own class doc.
 */
@RunWith(AndroidJUnit4::class)
class GroupMessagingTest {

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
        val activeLinks = CopyOnWriteArrayList<GroupLink>()

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

        val blockRepository = BlockRepository(db.blockedSenderDeviceDao())
        private val relayPolicy = RelayPolicy()
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

        val dispatcher = EnvelopeDispatcher(
            receivedFrameStore = receivedFrameStore,
            dontRelayRepository = dontRelayRepository,
            pendingMessageRepository = pendingMessageRepository,
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
    }

    /** Test-only stand-in for one [WifiDirectTransport] peer connection, mirroring [PendingMessageRelayTest.MessageLink]'s own precedent (duplicated per-file rather than shared, matching this test suite's established convention). */
    private class GroupLink(private val party: Party, private val socket: Socket) {
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

        fun onConnected() {
            party.activeLinks.add(this)
            val backlog = runBlocking { party.pendingMessageRepository.buildOutgoingBacklog() }
            for (envelopeBytes in backlog) sendEnvelopeBytes(envelopeBytes)
        }

        fun announceOwnBundle() {
            val bundle = party.preKeyRotationManager.currentBundle()
            val envelope = PreKeyBundleEnvelope(peerId = party.peerId, bundleBytes = PreKeyBundleCodec.encode(bundle))
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
                                deliverAnyPendingMessagesTo(result.senderPeerId)
                            }
                            else -> Unit
                        }
                    }
                } catch (e: Exception) {
                    // socket closed / test teardown -- expected.
                } finally {
                    party.activeLinks.remove(this)
                }
            }, "test-group-receive-${party.name}").start()
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

    /** Connects [a] and [b] over a fresh real loopback socket pair, mirroring [PendingMessageRelayTest.connect]'s own doc exactly. */
    private fun connect(a: Party, b: Party, exchangeBundles: Boolean = true): Pair<GroupLink, GroupLink> {
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

        val linkA = GroupLink(a, clientSocket)
        val linkB = GroupLink(b, bSocket)
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

    private fun awaitGroup(db: HopDatabase, groupId: String, timeoutMs: Long = 5_000): GroupEntity? {
        val deadline = System.currentTimeMillis() + timeoutMs
        var result = runBlocking { db.groupDao().getGroup(groupId) }
        while (result == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            result = runBlocking { db.groupDao().getGroup(groupId) }
        }
        return result
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

    private fun awaitPendingRow(db: HopDatabase, recipientPeerId: String, timeoutMs: Long = 5_000): PendingMessageEntity? {
        val deadline = System.currentTimeMillis() + timeoutMs
        var result = runBlocking { db.pendingMessageDao().getAll() }.find { it.recipientPeerId == recipientPeerId }
        while (result == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            result = runBlocking { db.pendingMessageDao().getAll() }.find { it.recipientPeerId == recipientPeerId }
        }
        return result
    }

    /** Polls (bounded, short interval) until [peerId]'s 1:1 conversation at [db] contains [expectedPlaintext], or fails the test -- used to prime/confirm a real Double Ratchet session before a test sends a hand-built adversarial payload over it. */
    private fun awaitMessage(db: HopDatabase, peerId: String, expectedPlaintext: String, timeoutMs: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val messages = runBlocking { db.messageDao().getMessagesForPeer(peerId).first() }
            if (messages.any { it.plaintext == expectedPlaintext }) return
            Thread.sleep(50)
        }
        throw AssertionError("Never observed a 1:1 message from $peerId with plaintext '$expectedPlaintext' within ${timeoutMs}ms")
    }

    // --- Test 1: group creation + invite fan-out, including one member offline at invite time ---

    @Test
    fun createGroupFansOutInviteToEveryMember_offlineMemberGetsItViaStoreAndForward() {
        val creator = Party("Creator", newInMemoryDb(), "creator-peer")
        val bob = Party("Bob", newInMemoryDb(), "bob-peer")
        val carol = Party("Carol", newInMemoryDb(), "carol-peer")

        connect(creator, bob)
        val (creatorCarolLink, carolLink) = connect(creator, carol)
        // Carol goes offline right before the invite is sent -- creator is
        // still connected to Bob.
        creatorCarolLink.close()
        carolLink.close()

        val groupId = runBlocking { creator.messageRepository.createGroup("Trip", listOf(bob.peerId, carol.peerId)) }

        // Bob was online -- he gets the invite live.
        val bobGroup = awaitGroup(bob.db, groupId)
        assertNotNull(bobGroup, "Bob never received the live GroupInvite")
        assertEquals(creator.peerId, bobGroup.creatorPeerId)

        // Carol was offline -- her invite went to relay-custody (Slice 3's
        // store-and-forward, exercised here with zero new relay code).
        assertNotNull(awaitPendingRow(creator.db, recipientPeerId = carol.peerId), "Carol's offline invite was never queued for relay custody")
        assertNull(runBlocking { carol.db.groupDao().getGroup(groupId) }, "Carol shouldn't have the group yet -- she was offline")

        // Carol reconnects -- her queued invite is delivered.
        connect(creator, carol, exchangeBundles = false)
        val carolGroup = awaitGroup(carol.db, groupId)
        assertNotNull(carolGroup, "Carol never received her store-and-forwarded GroupInvite after reconnecting")
        assertEquals(creator.peerId, carolGroup.creatorPeerId)
    }

    // --- Test 2: forged GroupInvite from a non-creator member is rejected ---

    @Test
    fun forgedGroupInviteFromANonCreatorMemberIsRejected() {
        val creator = Party("Creator", newInMemoryDb(), "creator-peer")
        val bob = Party("Bob", newInMemoryDb(), "bob-peer")
        val carol = Party("Carol", newInMemoryDb(), "carol-peer")

        connect(creator, bob)
        connect(creator, carol)
        // Bob and Carol also connect directly, so Bob *can* reach Carol --
        // this is what makes the forgery attempt possible to even
        // attempt/deliver in the first place. A real 1:1 message primes an
        // actual Double Ratchet session both ways (bundle exchange alone
        // doesn't establish one -- see `sendRawPayload`'s own doc).
        connect(bob, carol)
        runBlocking { bob.messageRepository.send(carol.peerId, "hi carol, priming our session") }
        awaitMessage(carol.db, bob.peerId, "hi carol, priming our session")

        val groupId = runBlocking { creator.messageRepository.createGroup("Trip", listOf(bob.peerId, carol.peerId)) }
        awaitGroup(carol.db, groupId)
        val originalMembers = runBlocking { carol.db.groupDao().getFanoutTargetPeerIds(groupId) }.toSet()

        // Bob (a legitimate current member, not the creator) sends a forged
        // GroupInvite to Carol redefining membership to just himself.
        val payload = MessagePayload.GroupInvite(groupId = groupId, name = "Hijacked", memberPeerIds = emptyList())
        runBlocking { bob.sendGroupInvite(carol.peerId, payload) }
        Thread.sleep(300)

        // Carol's membership must be unchanged -- the forged invite was
        // rejected (Bob is not carol's pinned creator for this group).
        val membersAfter = runBlocking { carol.db.groupDao().getFanoutTargetPeerIds(groupId) }.toSet()
        assertEquals(originalMembers, membersAfter, "a non-creator's forged GroupInvite must not change membership")
        assertEquals(creator.peerId, runBlocking { carol.db.groupDao().getCreatorPeerId(groupId) })
    }

    // --- Test 3: GroupInvite reusing a foreign group's groupId from a different sender is rejected ---

    @Test
    fun groupInviteReusingAForeignGroupIdFromADifferentSenderIsRejected() {
        val creatorA = Party("CreatorA", newInMemoryDb(), "creator-a-peer")
        val creatorB = Party("CreatorB", newInMemoryDb(), "creator-b-peer")
        val victim = Party("Victim", newInMemoryDb(), "victim-peer")

        connect(creatorA, victim)
        connect(creatorB, victim)
        // Prime a real session between creatorB and victim -- bundle
        // exchange alone doesn't establish one (see `sendRawPayload`'s own
        // doc); creatorA<->victim gets a real session as a side effect of
        // createGroup's own invite fan-out below, so no separate priming is
        // needed for that pair.
        runBlocking { creatorB.messageRepository.send(victim.peerId, "hi victim, priming our session") }
        awaitMessage(victim.db, creatorB.peerId, "hi victim, priming our session")

        val groupId = runBlocking { creatorA.messageRepository.createGroup("Real group", listOf(victim.peerId)) }
        awaitGroup(victim.db, groupId)

        // creatorB merely observed groupId in plaintext (e.g. victim
        // mentioned it) and tries to mint a colliding "group" under the same
        // id.
        val collidingInvite = MessagePayload.GroupInvite(groupId = groupId, name = "Colliding group", memberPeerIds = emptyList())
        runBlocking { creatorB.sendGroupInvite(victim.peerId, collidingInvite) }
        Thread.sleep(300)

        val group = runBlocking { victim.db.groupDao().getGroup(groupId) }
        assertNotNull(group)
        assertEquals("Real group", group.name, "the colliding invite must not overwrite the original group's name")
        assertEquals(creatorA.peerId, group.creatorPeerId, "the colliding invite must not overwrite the original group's pinned creator")
    }

    // --- Test 4: group message from a sender the receiving device has no GroupMemberEntity row for is rejected ---

    @Test
    fun groupMessageFromAnUnrecognizedSenderIsRejectedNotPersisted() {
        val creator = Party("Creator", newInMemoryDb(), "creator-peer")
        val bob = Party("Bob", newInMemoryDb(), "bob-peer")
        val stranger = Party("Stranger", newInMemoryDb(), "stranger-peer")

        connect(creator, bob)
        val groupId = runBlocking { creator.messageRepository.createGroup("Trip", listOf(bob.peerId)) }
        awaitGroup(bob.db, groupId)

        // A stranger who is not a member of the group nonetheless has a
        // direct session with Bob (e.g. an unrelated 1:1 contact) and sends
        // a Text payload claiming to be for this groupId.
        connect(stranger, bob)
        runBlocking { stranger.messageRepository.send(bob.peerId, "hi bob, priming our session") }
        awaitMessage(bob.db, stranger.peerId, "hi bob, priming our session")

        val payload = MessagePayload.Text(groupId = groupId, text = "I'm not really in this group")
        runBlocking { stranger.sendGroupText(bob.peerId, payload) }
        Thread.sleep(300)

        val bobGroupMessages = runBlocking { bob.db.groupMessageDao().getMessagesForGroup(groupId).first() }
        assertTrue(bobGroupMessages.none { it.plaintext == "I'm not really in this group" }, "a message from an unrecognized sender must not be persisted")
    }

    // --- Test 5: two members who've each only met the creator, never each other -- sendToGroup silently skips the unreachable member ---

    @Test
    fun sendToGroupSilentlySkipsAMemberWithNoSession_membersWhoNeverMetEachOther() {
        val creator = Party("Creator", newInMemoryDb(), "creator-peer")
        val bob = Party("Bob", newInMemoryDb(), "bob-peer")
        val carol = Party("Carol", newInMemoryDb(), "carol-peer")

        connect(creator, bob)
        connect(creator, carol)
        // Bob and Carol are deliberately never connected -- they've each
        // only met the creator (this slice's named member-introduction
        // limitation).

        val groupId = runBlocking { creator.messageRepository.createGroup("Trip", listOf(bob.peerId, carol.peerId)) }
        awaitGroup(bob.db, groupId)
        awaitGroup(carol.db, groupId)

        // Bob sends a group message -- he has a session with the creator,
        // but never with Carol.
        val result = runBlocking { bob.messageRepository.sendToGroup(groupId, "hi from bob") }
        assertEquals(SendResult.Sent, result, "sendToGroup always reports Sent regardless of per-member reachability")

        // The creator (reachable) gets it.
        awaitGroupMessage(creator.db, groupId, "hi from bob")

        // Carol (never met Bob) is silently skipped -- not an error, an
        // intentional, documented consequence of v1's fan-out scope.
        Thread.sleep(300)
        val carolMessages = runBlocking { carol.db.groupMessageDao().getMessagesForGroup(groupId).first() }
        assertTrue(carolMessages.none { it.plaintext == "hi from bob" }, "Carol should never receive a message from a member she's never met")

        // Bob's own local history still reflects exactly one outgoing row.
        val bobMessages = runBlocking { bob.db.groupMessageDao().getMessagesForGroup(groupId).first() }
        assertEquals(1, bobMessages.count { it.plaintext == "hi from bob" && it.isOutgoing })
    }

    // --- Test 6: a group message never appears in MessageDao's own queries (separate-table boundary) ---

    @Test
    fun aGroupMessageNeverAppearsInMessageDaosQueriesEvenWhenTheAuthorIsAlsoA1to1Contact() {
        val creator = Party("Creator", newInMemoryDb(), "creator-peer")
        val bob = Party("Bob", newInMemoryDb(), "bob-peer")

        connect(creator, bob)
        // Creator and Bob also have an ordinary 1:1 conversation (bob is
        // both a 1:1 contact and a group member -- required for v1's own
        // "must already be a 1:1 contact" scope boundary).
        runBlocking { creator.messageRepository.send(bob.peerId, "a real 1:1 message") }

        val groupId = runBlocking { creator.messageRepository.createGroup("Trip", listOf(bob.peerId)) }
        awaitGroup(bob.db, groupId)
        runBlocking { bob.messageRepository.sendToGroup(groupId, "a group message from the same peer id") }
        awaitGroupMessage(creator.db, groupId, "a group message from the same peer id")

        val creator1on1Messages = runBlocking { creator.messageRepository.observeConversation(bob.peerId).first() }
        assertTrue(
            creator1on1Messages.none { it.plaintext == "a group message from the same peer id" },
            "a group message must never leak into the 1:1 MessageDao/MessageEntity path",
        )
        val creatorLatestPerPeer = runBlocking { creator.db.messageDao().getLatestMessagePerPeer().first() }
        assertTrue(creatorLatestPerPeer.none { it.plaintext == "a group message from the same peer id" })
    }

    // --- Test 7: exactly one GroupMessageEntity row per sendToGroup call, including when some members are blocked/unreachable ---

    @Test
    fun exactlyOneGroupMessageEntityRowPerSendToGroupCall_includingWithBlockedAndUnreachableMembers() {
        val creator = Party("Creator", newInMemoryDb(), "creator-peer")
        val bob = Party("Bob", newInMemoryDb(), "bob-peer")
        val carol = Party("Carol", newInMemoryDb(), "carol-peer")
        val dave = Party("Dave", newInMemoryDb(), "dave-peer")

        connect(creator, bob)
        connect(creator, carol)
        // Dave is a member with no session at all (unreachable).
        val groupId = runBlocking {
            creator.messageRepository.createGroup("Trip", listOf(bob.peerId, carol.peerId, dave.peerId))
        }
        runBlocking { creator.blockRepository.block(bob.peerId) }

        runBlocking { creator.messageRepository.sendToGroup(groupId, "one message, several outcomes") }

        val creatorGroupMessages = runBlocking { creator.db.groupMessageDao().getMessagesForGroup(groupId).first() }
        assertEquals(1, creatorGroupMessages.count { it.plaintext == "one message, several outcomes" && it.isOutgoing })
    }

    // --- Test 8: blocked member skipped on send; message still reaches every other member ---

    @Test
    fun blockedMemberIsSkippedOnSend_messageStillReachesEveryOtherMember() {
        val creator = Party("Creator", newInMemoryDb(), "creator-peer")
        val bob = Party("Bob", newInMemoryDb(), "bob-peer")
        val carol = Party("Carol", newInMemoryDb(), "carol-peer")

        connect(creator, bob)
        connect(creator, carol)
        val groupId = runBlocking { creator.messageRepository.createGroup("Trip", listOf(bob.peerId, carol.peerId)) }
        awaitGroup(bob.db, groupId)
        awaitGroup(carol.db, groupId)

        runBlocking { creator.blockRepository.block(bob.peerId) }
        runBlocking { creator.messageRepository.sendToGroup(groupId, "only carol should get this live") }

        awaitGroupMessage(carol.db, groupId, "only carol should get this live")

        Thread.sleep(300)
        val bobMessages = runBlocking { bob.db.groupMessageDao().getMessagesForGroup(groupId).first() }
        assertTrue(bobMessages.none { it.plaintext == "only carol should get this live" }, "a blocked member must never receive the message")
    }

    /**
     * Test-only helper: encrypts+transmits a raw [payload] to [recipientPeerId]
     * the same way [MessageRepository]'s private `encryptAndTransmit` would,
     * for tests that need to construct a forged/adversarial payload
     * [MessageRepository]'s own public API would never produce. Requires a
     * real Double Ratchet session to already exist with [recipientPeerId] --
     * bundle exchange alone ([connect]'s `exchangeBundles`) only *caches* a
     * bundle, it doesn't establish one; a prior real [MessageRepository.send]
     * call between the two parties (see each caller) establishes it as a side
     * effect, same as production.
     */
    private fun Party.sendGroupInvite(recipientPeerId: String, payload: MessagePayload.GroupInvite) {
        sendRawPayload(recipientPeerId, payload.encode())
    }

    private fun Party.sendGroupText(recipientPeerId: String, payload: MessagePayload.Text) {
        sendRawPayload(recipientPeerId, payload.encode())
    }

    private fun Party.sendRawPayload(recipientPeerId: String, payloadBytes: ByteArray) {
        val address = peerSignalAddress(recipientPeerId)
        check(store.containsSession(address)) { "test helper requires an existing session with $recipientPeerId" }
        val session = DoubleRatchetSession.forIncoming(store, address)
        val ciphertext = session.encrypt(payloadBytes)
        val envelope = MessageCiphertextEnvelope(
            senderPeerId = peerId,
            recipientPeerId = recipientPeerId,
            hopCount = 0,
            originatedAtMs = System.currentTimeMillis(),
            ciphertext = ciphertext,
        )
        sendMessage(recipientPeerId, envelope.encode())
    }
}
