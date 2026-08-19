package com.hop.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hop.crypto.DoubleRatchetSession
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.signal.libsignal.protocol.DuplicateMessageException
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SignalProtocolStore
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Wires up a [RoomSignalProtocolStore] over every DAO [HopDatabase] exposes
 * for it, plus a fresh [IdentityKeyPairKeystoreCipher]. File-scoped (not a
 * member of [RoomSignalProtocolStoreTest]) so the private nested `Party`
 * helper class can use it too -- a plain (non-`inner`) nested class has no
 * implicit access to its outer class's members.
 *
 * Every call to this function within one test-process instantiates its own
 * [IdentityKeyPairKeystoreCipher], but all of them resolve to the *same*
 * underlying `AndroidKeyStore` entry (a fixed alias, per-app-process, not
 * per-instance) -- an accepted approximation of running two simulated
 * "devices" (Alice/Bob, each with their own [HopDatabase]) inside one real
 * test process/Keystore rather than two genuinely separate physical
 * Keystores. This doesn't weaken anything under test here: each entity's
 * ciphertext still carries its own random IV, so wrapping under a shared
 * key does not let one party's stored bytes decrypt as another's.
 */
private fun HopDatabase.asSignalProtocolStore(): RoomSignalProtocolStore = RoomSignalProtocolStore(
    signalIdentityDao(),
    signalPreKeyDao(),
    signalSignedPreKeyDao(),
    signalKyberPreKeyDao(),
    signalSessionDao(),
    IdentityKeyPairKeystoreCipher(),
)

/**
 * Extends `crypto/`'s `DoubleRatchetSessionTest` scenarios (round trip,
 * forward secrecy after simulated compromise, out-of-order/dropped
 * delivery, skipped-key-cap-exceeded fallback) with [RoomSignalProtocolStore]
 * substituted for libsignal-client's own `InMemorySignalProtocolStore` on
 * *both* sides of the conversation, plus a dedicated persistence case that
 * has no equivalent in the original in-memory-only test.
 *
 * Real Room via instrumentation (not Robolectric), matching this repo's
 * established DAO-test pattern -- see [RoomDecayKeyStorageTest], the
 * structural precedent this class follows for "a real crypto/-facing store
 * backed by Room instead of the default in-memory implementation."
 *
 * This is the actual point of this slice: proving Double Ratchet sessions
 * survive an app restart, not just that Room round-trips bytes correctly in
 * isolation from the ratchet logic (that's [SignalStoreDaoTest]'s job).
 */
@RunWith(AndroidJUnit4::class)
class RoomSignalProtocolStoreTest {

    private lateinit var aliceDb: HopDatabase
    private lateinit var bobDb: HopDatabase

    /** One conversation participant's local state, mirroring one physical device's local Room DB. */
    private class Party(val db: HopDatabase, name: String, deviceId: Int = 1) {
        val address = SignalProtocolAddress(name, deviceId)
        val store: SignalProtocolStore = db.asSignalProtocolStore()
    }

    private fun newDb(): HopDatabase =
        Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), HopDatabase::class.java).build()

    @Before
    fun setUp() {
        aliceDb = newDb()
        bobDb = newDb()
    }

    @After
    fun tearDown() {
        aliceDb.close()
        bobDb.close()
    }

    /**
     * Wires up a fresh Alice/Bob pair with a session already established
     * (Alice as initiator, Bob as responder) via a real prekey-bundle
     * handshake -- not a shortcut/mock -- matching
     * `DoubleRatchetSessionTest.establishedPair`'s exact approach, just with
     * each party's store backed by its own real Room database instead of
     * libsignal-client's in-memory default.
     */
    private fun establishedPair(): Triple<Party, Party, Pair<DoubleRatchetSession, DoubleRatchetSession>> {
        val alice = Party(aliceDb, "alice")
        val bob = Party(bobDb, "bob")

        val bobBundle = DoubleRatchetSession.publishPreKeyBundle(bob.store, bob.address.deviceId)
        val aliceSession = DoubleRatchetSession.initiate(alice.store, bob.address, bobBundle)
        val bobSession = DoubleRatchetSession.forIncoming(bob.store, alice.address)

        return Triple(alice, bob, aliceSession to bobSession)
    }

    // --- Basic round trip, both directions, both sides Room-backed ---

    @Test
    fun aliceEncryptsAndBobDecrypts() {
        val (_, _, sessions) = establishedPair()
        val (aliceSession, bobSession) = sessions

        val plaintext = "hop hop hop".toByteArray()
        val decrypted = bobSession.decrypt(aliceSession.encrypt(plaintext))

        assertContentEquals(plaintext, decrypted)
    }

    @Test
    fun bobEncryptsAndAliceDecryptsAfterReceivingBobsFirstMessage() {
        val (_, _, sessions) = establishedPair()
        val (aliceSession, bobSession) = sessions

        bobSession.decrypt(aliceSession.encrypt("hello bob".toByteArray()))

        val reply = "hello alice".toByteArray()
        val decrypted = aliceSession.decrypt(bobSession.encrypt(reply))

        assertContentEquals(reply, decrypted)
    }

    @Test
    fun roundTripWorksForManySequentialMessagesInBothDirections() {
        val (_, _, sessions) = establishedPair()
        val (aliceSession, bobSession) = sessions

        repeat(20) { i ->
            val toBob = "alice-$i".toByteArray()
            assertContentEquals(toBob, bobSession.decrypt(aliceSession.encrypt(toBob)))

            val toAlice = "bob-$i".toByteArray()
            assertContentEquals(toAlice, aliceSession.decrypt(bobSession.encrypt(toAlice)))
        }
    }

    // --- Forward secrecy after simulated compromise ---

    @Test
    fun aMessageKeyIsErasedAfterUseSoALaterRoomStateSnapshotCannotDecryptItAgain() {
        val (alice, bob, sessions) = establishedPair()
        val (aliceSession, bobSession) = sessions

        val ciphertexts = (0..2).map { aliceSession.encrypt("msg-$it".toByteArray()) }
        ciphertexts.forEach { bobSession.decrypt(it) }

        // Simulate a device compromise: an attacker extracts Bob's *current*
        // Room-persisted session state (e.g. forensic extraction of the
        // on-disk hop.db file) right after message 2 was decrypted -- read
        // it via the store's own serialize(), matching
        // DoubleRatchetSessionTest's exact pattern against
        // InMemorySignalProtocolStore.
        val compromisedSessionRecordBytes = bob.store.loadSession(alice.address).serialize()

        // Reconstruct an attacker's store from exactly that captured state:
        // a fresh Room DB seeded with the same identity key pair/registration
        // id (an attacker who captured the session state also captured the
        // identity material sitting right next to it on disk) plus only the
        // session bytes captured at this moment -- never Bob's earlier
        // session states, which were already erased by [DoubleRatchetSession]
        // as each message was consumed.
        //
        // The seeded identityKeyPairBytes is re-wrapped via a fresh
        // IdentityKeyPairKeystoreCipher (not Bob's raw plain
        // .serialize() bytes) so it round-trips through
        // attackerStore.identityKeyPair the same way a real captured-and-
        // reinserted Room row would. Per IdentityKeyPairKeystoreCipher's own
        // doc, this only models an attacker who also has live Keystore
        // access (e.g. a compromised running app process) -- a raw
        // disk-only extraction of hop.db would instead leave the attacker
        // holding undecryptable ciphertext, a *stronger* property than this
        // particular test exercises; this test's actual subject is the
        // session ratchet's forward secrecy, not identity-key
        // confidentiality itself.
        val attackerDb = newDb()
        try {
            val attackerStore = attackerDb.asSignalProtocolStore()
            attackerDb.signalIdentityDao().insertOwnIdentity(
                IdentityKeyPairEntity(
                    identityKeyPairBytes = IdentityKeyPairKeystoreCipher().encrypt(bob.store.identityKeyPair.serialize()),
                    registrationId = bob.store.localRegistrationId,
                    createdAtMs = 0L,
                )
            )
            attackerStore.storeSession(alice.address, SessionRecord(compromisedSessionRecordBytes))
            val attackerSession = DoubleRatchetSession.forIncoming(attackerStore, alice.address)

            // The already-used message keys for messages #0 and #1 are gone
            // from the captured state -- Double Ratchet's forward secrecy
            // means possessing the current ratchet state does not let you
            // recover past messages, and that guarantee survives being
            // persisted to and reloaded from Room, not just the in-memory
            // case.
            assertFailsWith<DuplicateMessageException> { attackerSession.decrypt(ciphertexts[1]) }
            assertFailsWith<DuplicateMessageException> { attackerSession.decrypt(ciphertexts[0]) }
        } finally {
            attackerDb.close()
        }
    }

    @Test
    fun decryptingTheSameCiphertextTwiceFailsTheSecondTime() {
        val (_, _, sessions) = establishedPair()
        val (aliceSession, bobSession) = sessions

        val ciphertext = aliceSession.encrypt("only once".toByteArray())
        bobSession.decrypt(ciphertext)

        assertFailsWith<DuplicateMessageException> { bobSession.decrypt(ciphertext) }
    }

    // --- Out-of-order / dropped message delivery ---

    @Test
    fun messagesDecryptCorrectlyWhenDeliveredOutOfOrder() {
        val (_, _, sessions) = establishedPair()
        val (aliceSession, bobSession) = sessions

        val ciphertexts = (0..3).map { aliceSession.encrypt("msg-$it".toByteArray()) }

        // Deliver in a shuffled, non-sequential order (0, 3, 1, 2) --
        // exercising libsignal-client's skipped-message-key cache, now
        // persisted through Room rather than held only in memory.
        assertContentEquals("msg-0".toByteArray(), bobSession.decrypt(ciphertexts[0]))
        assertContentEquals("msg-3".toByteArray(), bobSession.decrypt(ciphertexts[3]))
        assertContentEquals("msg-1".toByteArray(), bobSession.decrypt(ciphertexts[1]))
        assertContentEquals("msg-2".toByteArray(), bobSession.decrypt(ciphertexts[2]))
    }

    @Test
    fun aDroppedMessageDoesNotPreventDecryptingALaterOne() {
        val (_, _, sessions) = establishedPair()
        val (aliceSession, bobSession) = sessions

        val ciphertexts = (0..2).map { aliceSession.encrypt("msg-$it".toByteArray()) }

        // msg-1 is simulated as lost in transit (store-and-forward drop) --
        // Bob only ever sees msg-0 and msg-2.
        assertContentEquals("msg-0".toByteArray(), bobSession.decrypt(ciphertexts[0]))
        assertContentEquals("msg-2".toByteArray(), bobSession.decrypt(ciphertexts[2]))
    }

    // --- Skipped-message-key cap exceeded: must fall back to fresh handshake, not drop silently ---

    @Test
    fun exceedingTheSkippedMessageKeyCapThrowsRatherThanSilentlyLosingTheMessage() {
        val (_, _, sessions) = establishedPair()
        val (aliceSession, bobSession) = sessions

        // Establish delivery of msg-0 so Bob's side of the session exists
        // (and is persisted to his Room DB).
        bobSession.decrypt(aliceSession.encrypt("msg-0".toByteArray()))

        // msg-1 is generated but deliberately never delivered yet.
        val skippedEarly = aliceSession.encrypt("msg-1".toByteArray())

        // Send far more messages than libsignal-client's internal
        // skipped-key cache can hold (empirically ~2000 as of 0.86.5 -- see
        // DoubleRatchetSession.decrypt's doc), none of them delivered either.
        val totalExtra = 2500
        var lastCiphertext: ByteArray = skippedEarly
        repeat(totalExtra) { i ->
            lastCiphertext = aliceSession.encrypt("skipped-$i".toByteArray())
        }

        // Decrypting the newest message works, and is the moment
        // libsignal-client caches (and, since the range exceeds capacity,
        // partially evicts) all the skipped keys in between -- persisted to
        // Bob's Room DB as part of the same decrypt call.
        val decryptedNewest = bobSession.decrypt(lastCiphertext)
        assertContentEquals("skipped-${totalExtra - 1}".toByteArray(), decryptedNewest)

        // msg-1 -- never delivered, and early enough in the skipped range to
        // have aged out of the bounded cache -- is no longer recoverable.
        // Per DoubleRatchetSession.decrypt's documented contract, a caller
        // must treat this as "start a fresh handshake with this peer," not
        // as a message to silently drop.
        assertFailsWith<DuplicateMessageException> { bobSession.decrypt(skippedEarly) }
    }

    // --- Persistence itself: the entire point of this slice ---

    @Test
    fun sessionSurvivesCloseAndReopenOfTheRoomDatabaseInBothDirections() {
        val (alice, bob, sessions) = establishedPair()
        val (aliceSession, bobSession) = sessions

        // Bob's side of the session only exists once he's decrypted Alice's
        // first (PreKey-type) message.
        val firstToBob = aliceSession.encrypt("hello bob".toByteArray())
        assertContentEquals("hello bob".toByteArray(), bobSession.decrypt(firstToBob))
        val firstReply = bobSession.encrypt("hi alice".toByteArray())
        assertContentEquals("hi alice".toByteArray(), aliceSession.decrypt(firstReply))

        // Simulate "close and reopen the app": a fresh RoomSignalProtocolStore
        // instance re-wrapping DAOs from the *same underlying Room database
        // instance* -- matching RoomDecayKeyStorageTest's own established
        // pattern for simulating a restart against Room's in-memory test
        // builder (a real on-disk hop.db reopen would exercise the identical
        // DAO/Room-query code path this exercises; in-memory-but-same-database-
        // instance is the documented substitute already used elsewhere in this
        // test suite, not a new convention introduced here).
        val reopenedAliceStore = alice.db.asSignalProtocolStore()
        val reopenedBobStore = bob.db.asSignalProtocolStore()

        // Reconstruct DoubleRatchetSession wrappers over the reopened stores
        // -- forIncoming on both sides, since it's just wiring (store +
        // remoteAddress) with no handshake side effect either way; the real
        // session state lives entirely in the store, which is what's under
        // test here -- and confirm the conversation continues exactly where
        // it left off in both directions, with no re-handshake.
        val reopenedAliceSession = DoubleRatchetSession.forIncoming(reopenedAliceStore, bob.address)
        val reopenedBobSession = DoubleRatchetSession.forIncoming(reopenedBobStore, alice.address)

        val secondToBob = reopenedAliceSession.encrypt("still alice, after restart".toByteArray())
        assertContentEquals("still alice, after restart".toByteArray(), reopenedBobSession.decrypt(secondToBob))

        val secondReply = reopenedBobSession.encrypt("still bob, after restart".toByteArray())
        assertContentEquals("still bob, after restart".toByteArray(), reopenedAliceSession.decrypt(secondReply))
    }

    @Test
    fun ownIdentityKeyPairIsStableAcrossReopenedStoreInstances() {
        // A fresh RoomSignalProtocolStore over the same DB must not
        // regenerate a new identity key pair on every instantiation --
        // otherwise every "restart" would silently break every existing
        // remote session (the far side's isTrustedIdentity check would start
        // failing against a now-different identity). Since getIdentityKeyPair
        // now unwraps IdentityKeyPairKeystoreCipher-wrapped bytes on every
        // call, this also doubles as a round trip through the Keystore
        // wrap/unwrap path itself, across two store instances simulating an
        // app restart (Keystore-wrapping section below adds a same-instance
        // variant and the actual ciphertext regression guard).
        val db = newDb()
        try {
            val first = db.asSignalProtocolStore()
            val firstIdentity = first.identityKeyPair
            val firstRegistrationId = first.localRegistrationId

            val second = db.asSignalProtocolStore()
            assertContentEquals(firstIdentity.serialize(), second.identityKeyPair.serialize())
            assert(firstRegistrationId == second.localRegistrationId) {
                "registration id should be stable across reopened store instances"
            }
        } finally {
            db.close()
        }
    }

    // --- Android-Keystore wrapping of the own identity key pair ---

    @Test
    fun getIdentityKeyPairReturnsTheSameKeyPairAcrossRepeatedCallsOnOneStoreInstance() {
        // Companion to ownIdentityKeyPairIsStableAcrossReopenedStoreInstances
        // above (which covers *two* store instances / a simulated restart):
        // this covers the simpler same-instance case, decrypting the
        // Keystore-wrapped bytes twice must not somehow perturb them (e.g. no
        // accidental re-encryption-on-read, no IV reuse bug that corrupts the
        // stored ciphertext).
        val db = newDb()
        try {
            val store = db.asSignalProtocolStore()
            val first = store.identityKeyPair
            val second = store.identityKeyPair
            assertContentEquals(first.serialize(), second.serialize())
        } finally {
            db.close()
        }
    }

    @Test
    fun rawPersistedIdentityKeyPairBytesAreNotThePlainSerializedKeyPair() {
        // The actual proof encryption is happening, not just that the round
        // trip compiles and passes -- a bug that silently no-ops
        // IdentityKeyPairKeystoreCipher (e.g. returns its input unchanged)
        // would still pass every round-trip test above. Bypasses
        // RoomSignalProtocolStore entirely and reads the raw Room row via
        // the DAO directly, then asserts its identityKeyPairBytes is NOT
        // byte-equal to the identity key pair's own plain .serialize()
        // output.
        val db = newDb()
        try {
            val store = db.asSignalProtocolStore()
            val plainSerialized = store.identityKeyPair.serialize()

            val rawEntity = db.signalIdentityDao().getOwnIdentity()
            assertNotNull(rawEntity, "own identity row must exist after getIdentityKeyPair() forced its creation")
            assert(!rawEntity.identityKeyPairBytes.contentEquals(plainSerialized)) {
                "raw Room-persisted identityKeyPairBytes must be Keystore-wrapped ciphertext, " +
                    "not the plain serialized key pair -- encryption is not actually happening"
            }
        } finally {
            db.close()
        }
    }

    // --- PreKeyRotationManager: the correctness bug this class exists to fix ---
    //
    // Before PreKeyRotationManager existed, WifiDirectTransport memoized a
    // *single* published prekey bundle (always fixed ids preKeyId=1/
    // signedPreKeyId=1/kyberPreKeyId=1) and handed that exact same bundle to
    // every peer that connected for the rest of the app process's lifetime.
    // libsignal-client deletes a one-time EC prekey (RoomSignalProtocolStore
    // .removePreKey) the moment ANY peer completes a handshake that consumes
    // it -- so the moment a first peer (Bob) did that, a second peer's
    // (Carol's) subsequent handshake attempt against the *same* memoized
    // bundle referenced an id Alice's store had already deleted:
    // RoomSignalProtocolStore.loadPreKey would throw InvalidKeyIdException,
    // decrypt would fail, and MessageRepository.onEnvelopeReceived's
    // catch-all would silently drop Carol's first message with zero visible
    // cause. This is not a hypothetical -- it reliably reproduced against
    // the pre-fix code (Alice's bundle announcement calling
    // `DoubleRatchetSession.publishPreKeyBundle(store, deviceId)` fresh on
    // every connection, but always at the same fixed ids, so the *second*
    // call would overwrite -- not add to -- whatever the first call handed
    // out before it was necessarily consumed). The test below is the actual
    // proof the fix holds: Carol's handshake, after Bob's has already
    // consumed a one-time prekey, must still succeed.
    @Test
    fun aSecondPeersFirstMessageDecryptsSuccessfullyAfterAFirstPeerAlreadyConsumedAOneTimePrekey() {
        // Alice is the responder throughout -- Bob and Carol both initiate a
        // fresh session against whatever bundle Alice most recently
        // announced, mirroring com.hop.transport.WifiDirectTransport's
        // ambient "announce own current bundle to every new connection"
        // behavior (announceOwnPreKeyBundle, now backed by
        // PreKeyRotationManager.currentBundle), just without the socket/
        // transport layer in the way.
        val aliceStore = aliceDb.asSignalProtocolStore()
        val aliceAddress = SignalProtocolAddress("alice", 1)
        val alicePreKeyRotationManager = PreKeyRotationManager(
            store = aliceStore,
            counterDao = aliceDb.signalPreKeyCounterDao(),
            deviceId = aliceAddress.deviceId,
        )

        val bob = Party(bobDb, "bob")
        val carolDb = newDb()
        try {
            val carol = Party(carolDb, "carol")

            // Alice announces her current bundle to Bob -- the first-ever
            // announcement for this device, so this hands out one-time
            // prekey id 1.
            val bundleForBob = alicePreKeyRotationManager.currentBundle()

            // Bob completes a handshake against that bundle and sends Alice
            // a first (PreKey-type) message. Per libsignal-client's own
            // PreKeyStore contract, this consumes (deletes) the one-time
            // prekey bundleForBob referenced -- see
            // RoomSignalProtocolStore.removePreKey's doc.
            val bobSession = DoubleRatchetSession.initiate(bob.store, aliceAddress, bundleForBob)
            val bobsFirstMessage = bobSession.encrypt("hello alice, this is bob".toByteArray())
            val aliceSessionWithBob = DoubleRatchetSession.forIncoming(aliceStore, bob.address)
            assertContentEquals(
                "hello alice, this is bob".toByteArray(),
                aliceSessionWithBob.decrypt(bobsFirstMessage),
            )

            // Alice's one-time prekey from bundleForBob is now consumed and
            // gone from her store.
            assertFalse(
                aliceStore.containsPreKey(bundleForBob.preKeyId),
                "Bob's completed handshake should have consumed (deleted) Alice's one-time prekey",
            )

            // Alice now announces her *current* bundle to Carol -- a brand
            // new connection, exactly like a second peer connecting to
            // WifiDirectTransport within the same app-process lifetime.
            val bundleForCarol = alicePreKeyRotationManager.currentBundle()
            assertNotEquals(
                bundleForBob.preKeyId,
                bundleForCarol.preKeyId,
                "Carol must be handed a different, not-yet-consumed one-time prekey than Bob's -- " +
                    "handing out the same (already-consumed) id is exactly the bug this class fixes",
            )

            // Carol completes her own handshake against her bundle and sends
            // Alice a first message. Before the fix, this would reference
            // the same already-deleted one-time prekey id Bob's did --
            // decrypt would fail and the message would be silently dropped.
            // With the fix, Carol's bundle references a distinct,
            // still-unconsumed one-time prekey, so this must succeed.
            val carolSession = DoubleRatchetSession.initiate(carol.store, aliceAddress, bundleForCarol)
            val carolsFirstMessage = carolSession.encrypt("hello alice, this is carol".toByteArray())
            val aliceSessionWithCarol = DoubleRatchetSession.forIncoming(aliceStore, carol.address)
            assertContentEquals(
                "hello alice, this is carol".toByteArray(),
                aliceSessionWithCarol.decrypt(carolsFirstMessage),
            )
        } finally {
            carolDb.close()
        }
    }

    // --- PreKeyRotationManager: one-time prekey batch replenishment ---

    @Test
    fun replenishmentGeneratesNewNonCollidingOneTimePreKeyIdsOnceThePoolRunsLow() {
        val db = newDb()
        try {
            val store = db.asSignalProtocolStore()
            val manager = PreKeyRotationManager(store, db.signalPreKeyCounterDao(), deviceId = 1)

            // Hand out enough one-time prekeys to run well past a single
            // batch -- proves replenishment actually happens (not just that
            // the first ONE_TIME_PRE_KEY_BATCH_SIZE calls work) and that
            // every newly generated id is genuinely new, never a repeat of
            // one already handed out.
            val callCount = PreKeyRotationManager.ONE_TIME_PRE_KEY_BATCH_SIZE * 2 + 3
            val handedOutIds = (1..callCount).map { manager.currentBundle().preKeyId }

            assertEquals(
                handedOutIds.size,
                handedOutIds.toSet().size,
                "every handed-out one-time prekey id must be distinct -- a repeat means two peers " +
                    "could be handed (and race to consume) the same one-time prekey",
            )
            assertTrue(
                handedOutIds.max() > PreKeyRotationManager.ONE_TIME_PRE_KEY_BATCH_SIZE,
                "replenishment should have generated ids beyond the very first batch",
            )

            // Every handed-out id must actually resolve real, still-present
            // key material in the store -- not just an id number with
            // nothing backing it.
            handedOutIds.forEach { id ->
                assertTrue(store.containsPreKey(id), "handed-out prekey id $id should exist in the store")
            }
        } finally {
            db.close()
        }
    }

    // --- PreKeyRotationManager: signed/Kyber prekey rotation + grace period + pruning ---

    @Test
    fun signedAndKyberPreKeysRotateAfterIntervalWhilePreviousStaysValidWithinGracePeriodThenGetsPrunedAfter() {
        val db = newDb()
        try {
            val store = db.asSignalProtocolStore()
            var clockMs = 0L
            val manager = PreKeyRotationManager(store, db.signalPreKeyCounterDao(), deviceId = 1) { clockMs }

            val bundle1 = manager.currentBundle()
            val signedId1 = bundle1.signedPreKeyId
            val kyberId1 = bundle1.kyberPreKeyId

            // Advance just past the rotation interval -- both the signed and
            // Kyber prekeys are now due for rotation.
            clockMs = PreKeyRotationManager.SIGNED_PRE_KEY_ROTATION_INTERVAL.toMillis() + 1
            val bundle2 = manager.currentBundle()
            val signedId2 = bundle2.signedPreKeyId
            val kyberId2 = bundle2.kyberPreKeyId

            assertNotEquals(signedId1, signedId2, "signed prekey should have rotated to a new id")
            assertNotEquals(kyberId1, kyberId2, "Kyber prekey should have rotated to a new id")

            // The just-superseded ids remain loadable -- a peer who cached a
            // bundle shortly before rotation can still complete a handshake
            // against it.
            assertTrue(
                store.containsSignedPreKey(signedId1),
                "previous signed prekey should remain valid within its grace period",
            )
            assertTrue(
                store.containsKyberPreKey(kyberId1),
                "previous Kyber prekey should remain valid within its grace period",
            )
            assertEquals(signedId1, store.loadSignedPreKey(signedId1).id)
            assertEquals(kyberId1, store.loadKyberPreKey(kyberId1).id)

            // Advance past rotation interval + grace period from the
            // original (id1) prekeys' creation time -- id1's generation is
            // now safely outside every peer's grace period and should be
            // pruned.
            clockMs = (
                PreKeyRotationManager.SIGNED_PRE_KEY_ROTATION_INTERVAL + PreKeyRotationManager.SIGNED_PRE_KEY_GRACE_PERIOD
                ).toMillis() + 1
            manager.currentBundle()

            assertFalse(
                store.containsSignedPreKey(signedId1),
                "long-superseded signed prekey should be pruned once outside its grace period",
            )
            assertFalse(
                store.containsKyberPreKey(kyberId1),
                "long-superseded Kyber prekey should be pruned once outside its grace period",
            )
            assertFailsWith<InvalidKeyIdException> { store.loadSignedPreKey(signedId1) }
            assertFailsWith<InvalidKeyIdException> { store.loadKyberPreKey(kyberId1) }
        } finally {
            db.close()
        }
    }
}
