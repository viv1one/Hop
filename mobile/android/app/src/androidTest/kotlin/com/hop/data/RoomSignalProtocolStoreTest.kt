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
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SignalProtocolStore
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

/**
 * Wires up a [RoomSignalProtocolStore] over every DAO [HopDatabase] exposes
 * for it. File-scoped (not a member of [RoomSignalProtocolStoreTest]) so the
 * private nested `Party` helper class can use it too -- a plain (non-`inner`)
 * nested class has no implicit access to its outer class's members.
 */
private fun HopDatabase.asSignalProtocolStore(): RoomSignalProtocolStore = RoomSignalProtocolStore(
    signalIdentityDao(),
    signalPreKeyDao(),
    signalSignedPreKeyDao(),
    signalKyberPreKeyDao(),
    signalSessionDao(),
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
        val attackerDb = newDb()
        try {
            val attackerStore = attackerDb.asSignalProtocolStore()
            attackerDb.signalIdentityDao().insertOwnIdentity(
                IdentityKeyPairEntity(
                    identityKeyPairBytes = bob.store.identityKeyPair.serialize(),
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
        // failing against a now-different identity).
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
}
