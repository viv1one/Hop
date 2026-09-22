package com.hop.crypto

import org.junit.jupiter.api.Test
import org.signal.libsignal.protocol.DuplicateMessageException
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SignalProtocolStore
import org.signal.libsignal.protocol.state.impl.InMemorySignalProtocolStore
import org.signal.libsignal.protocol.util.KeyHelper
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/**
 * Test coverage per ADR 0001's stated posture: not just the happy-path
 * encrypt/decrypt round trip, but forward secrecy after a simulated compromise
 * and out-of-order/dropped delivery (expected under store-and-forward), plus the
 * skipped-message-key-cap-exceeded fallback case.
 */
class DoubleRatchetSessionTest {

    /** One conversation participant's local state, mirroring what a device holds locally. */
    private class Party(val name: String, deviceId: Int = 1) {
        val address = SignalProtocolAddress(name, deviceId)
        val store: SignalProtocolStore =
            InMemorySignalProtocolStore(IdentityKeyPair.generate(), KeyHelper.generateRegistrationId(false))
    }

    /**
     * Wires up a fresh Alice/Bob pair with a session already established
     * (Alice as initiator, Bob as responder) via a real prekey-bundle handshake
     * — not a shortcut/mock — matching how two devices would actually establish
     * a session after a BLE/WiFi Direct hop or from-a-post chat start.
     */
    private fun establishedPair(): Triple<Party, Party, Pair<DoubleRatchetSession, DoubleRatchetSession>> {
        val alice = Party("alice")
        val bob = Party("bob")

        val bobBundle = DoubleRatchetSession.publishPreKeyBundle(bob.store, bob.address.deviceId)
        val aliceSession = DoubleRatchetSession.initiate(alice.store, bob.address, bobBundle)
        val bobSession = DoubleRatchetSession.forIncoming(bob.store, alice.address)

        return Triple(alice, bob, aliceSession to bobSession)
    }

    // --- Basic round trip, both directions ---

    @Test
    fun `alice encrypts and bob decrypts`() {
        val (_, _, sessions) = establishedPair()
        val (aliceSession, bobSession) = sessions

        val plaintext = "hop hop hop".toByteArray()
        val ciphertext = aliceSession.encrypt(plaintext)
        val decrypted = bobSession.decrypt(ciphertext)

        assertContentEquals(plaintext, decrypted)
    }

    @Test
    fun `bob encrypts and alice decrypts after receiving bobs first message`() {
        val (_, _, sessions) = establishedPair()
        val (aliceSession, bobSession) = sessions

        // Bob's side of the session isn't established until he's decrypted at
        // least one inbound (PreKey-type) message from Alice.
        bobSession.decrypt(aliceSession.encrypt("hello bob".toByteArray()))

        val reply = "hello alice".toByteArray()
        val ciphertext = bobSession.encrypt(reply)
        val decrypted = aliceSession.decrypt(ciphertext)

        assertContentEquals(reply, decrypted)
    }

    @Test
    fun `round trip works for many sequential messages in both directions`() {
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
    fun `a message key is erased after use, so a later state snapshot cannot decrypt it again`() {
        val (alice, bob, sessions) = establishedPair()
        val (aliceSession, bobSession) = sessions

        // Alice sends three messages; Bob decrypts all three in order, consuming
        // (and per Double Ratchet, erasing) each message key as he goes.
        val ciphertexts = (0..2).map { aliceSession.encrypt("msg-$it".toByteArray()) }
        ciphertexts.forEach { bobSession.decrypt(it) }

        // Simulate a device compromise: an attacker extracts Bob's *current*
        // serialized ratchet state (e.g. forensic extraction of on-disk session
        // state) right after message 2 was decrypted.
        val compromisedSessionRecordBytes = bob.store.loadSession(alice.address).serialize()

        // Reconstruct a store from exactly that captured state -- same identity
        // material, but only the session state an attacker could actually have
        // captured at this moment in time.
        val attackerStore = InMemorySignalProtocolStore(bob.store.identityKeyPair, bob.store.localRegistrationId)
        attackerStore.storeSession(alice.address, SessionRecord(compromisedSessionRecordBytes))
        val attackerSession = DoubleRatchetSession.forIncoming(attackerStore, alice.address)

        // The already-used message key for message #1 (or #0) is gone from the
        // captured state -- Double Ratchet's forward secrecy means possessing
        // the current ratchet state does not let you recover past messages.
        assertFailsWith<DuplicateMessageException> { attackerSession.decrypt(ciphertexts[1]) }
        assertFailsWith<DuplicateMessageException> { attackerSession.decrypt(ciphertexts[0]) }
    }

    @Test
    fun `decrypting the same ciphertext twice fails the second time`() {
        val (_, _, sessions) = establishedPair()
        val (aliceSession, bobSession) = sessions

        val ciphertext = aliceSession.encrypt("only once".toByteArray())
        bobSession.decrypt(ciphertext)

        assertFailsWith<DuplicateMessageException> { bobSession.decrypt(ciphertext) }
    }

    @Test
    fun `successive message keys are not derivable from one another`() {
        // Closer proxy for "message keys aren't reused": encrypting the same
        // plaintext twice in a row produces different ciphertext bytes, because
        // each message is under a distinct, ratchet-derived key -- not a fixed
        // or incrementing one.
        val (_, _, sessions) = establishedPair()
        val (aliceSession, _) = sessions

        val first = aliceSession.encrypt("repeat me".toByteArray())
        val second = aliceSession.encrypt("repeat me".toByteArray())

        assertNotEquals(first.toList(), second.toList())
    }

    // --- Out-of-order / dropped message delivery ---

    @Test
    fun `messages decrypt correctly when delivered out of order`() {
        val (_, _, sessions) = establishedPair()
        val (aliceSession, bobSession) = sessions

        val ciphertexts = (0..3).map { aliceSession.encrypt("msg-$it".toByteArray()) }

        // Deliver in a shuffled, non-sequential order (0, 3, 1, 2) -- exercising
        // libsignal-client's skipped-message-key cache rather than a linear scan.
        assertContentEquals("msg-0".toByteArray(), bobSession.decrypt(ciphertexts[0]))
        assertContentEquals("msg-3".toByteArray(), bobSession.decrypt(ciphertexts[3]))
        assertContentEquals("msg-1".toByteArray(), bobSession.decrypt(ciphertexts[1]))
        assertContentEquals("msg-2".toByteArray(), bobSession.decrypt(ciphertexts[2]))
    }

    @Test
    fun `a dropped message does not prevent decrypting a later one`() {
        val (_, _, sessions) = establishedPair()
        val (aliceSession, bobSession) = sessions

        val ciphertexts = (0..2).map { aliceSession.encrypt("msg-$it".toByteArray()) }

        // msg-1 is simulated as lost in transit (store-and-forward drop) -- Bob
        // only ever sees msg-0 and msg-2.
        assertContentEquals("msg-0".toByteArray(), bobSession.decrypt(ciphertexts[0]))
        assertContentEquals("msg-2".toByteArray(), bobSession.decrypt(ciphertexts[2]))
    }

    // --- Skipped-message-key cap exceeded: must fall back to fresh handshake, not drop silently ---

    @Test
    fun `exceeding the skipped-message-key cap throws rather than silently losing the message`() {
        val (_, _, sessions) = establishedPair()
        val (aliceSession, bobSession) = sessions

        // Establish delivery of msg-0 so Bob's side of the session exists.
        bobSession.decrypt(aliceSession.encrypt("msg-0".toByteArray()))

        // msg-1 is generated but deliberately never delivered yet -- it will sit
        // as a not-yet-skipped message until Bob eventually receives something
        // past it.
        val skippedEarly = aliceSession.encrypt("msg-1".toByteArray())

        // Send far more messages than libsignal-client's internal skipped-key
        // cache can hold (empirically ~2000 as of 0.86.5 -- see
        // DoubleRatchetSession.decrypt's doc), none of them delivered either.
        val totalExtra = 2500
        var lastCiphertext: ByteArray = skippedEarly
        repeat(totalExtra) { i ->
            lastCiphertext = aliceSession.encrypt("skipped-$i".toByteArray())
        }

        // Decrypting the newest message works -- it doesn't need the skipped
        // cache, since its key is derived fresh off the current chain state.
        // This is also the moment libsignal-client caches (and, since the range
        // exceeds capacity, partially evicts) all the keys in between.
        val decryptedNewest = bobSession.decrypt(lastCiphertext)
        assertContentEquals("skipped-${totalExtra - 1}".toByteArray(), decryptedNewest)

        // msg-1 -- never delivered, and early enough in the skipped range to
        // have aged out of the bounded cache -- is no longer recoverable. This
        // is the case a caller must treat as "start a fresh handshake with this
        // peer," not as a message to silently drop.
        assertFailsWith<DuplicateMessageException> { bobSession.decrypt(skippedEarly) }
    }

    // --- generateOneTimePreKeys / generateSignedPreKey / generateKyberPreKey / assembleBundle ---
    //
    // These are the lower-level primitives `com.hop.data.PreKeyRotationManager`
    // builds its replenishment/rotation policy on top of (Room-specific id
    // bookkeeping lives there, not here -- these are plain generation/storage/
    // assembly functions against the SignalProtocolStore interface, so they're
    // testable with libsignal-client's own InMemorySignalProtocolStore, no
    // Room/instrumentation needed).

    @Test
    fun `generateOneTimePreKeys stores exactly count keys at sequential ids starting at startId`() {
        val bob = Party("bob")

        val ids = DoubleRatchetSession.generateOneTimePreKeys(bob.store, startId = 5, count = 4)

        assertEquals(listOf(5, 6, 7, 8), ids)
        ids.forEach { id -> assert(bob.store.containsPreKey(id)) { "prekey $id should exist after generation" } }
    }

    @Test
    fun `a bundle assembled from separately generated prekeys is usable to establish a real session`() {
        // Exercises the exact call sequence PreKeyRotationManager.currentBundle
        // uses in production: generate once, assemble (a cheap read) possibly
        // many times/later -- as opposed to publishPreKeyBundle's
        // generate-and-assemble-in-one-call convenience path.
        val bob = Party("bob")
        DoubleRatchetSession.generateSignedPreKey(bob.store, signedPreKeyId = 7)
        DoubleRatchetSession.generateOneTimePreKeys(bob.store, startId = 1, count = 3)
        DoubleRatchetSession.generateKyberPreKey(bob.store, kyberPreKeyId = 2)

        // Hand out one-time prekey id 2 specifically (not necessarily the
        // first generated) -- proves assembleBundle doesn't implicitly
        // assume "id 1" anywhere.
        val bundle = DoubleRatchetSession.assembleBundle(
            bob.store,
            deviceId = bob.address.deviceId,
            preKeyId = 2,
            signedPreKeyId = 7,
            kyberPreKeyId = 2,
        )

        val alice = Party("alice")
        val aliceSession = DoubleRatchetSession.initiate(alice.store, bob.address, bundle)
        val bobSession = DoubleRatchetSession.forIncoming(bob.store, alice.address)

        val plaintext = "hop hop hop".toByteArray()
        assertContentEquals(plaintext, bobSession.decrypt(aliceSession.encrypt(plaintext)))
    }

    @Test
    fun `two different one-time prekeys from the same batch can each independently establish a session`() {
        // The crux of the correctness bug PreKeyRotationManager fixes, at
        // the lowest level: two peers (Bob's two sessions here, standing in
        // for two different real peers) each initiating against a bundle
        // built from a *different* one-time prekey id must both succeed --
        // neither consumes or invalidates the other's.
        val responder = Party("responder")
        DoubleRatchetSession.generateSignedPreKey(responder.store, signedPreKeyId = 1)
        DoubleRatchetSession.generateKyberPreKey(responder.store, kyberPreKeyId = 1)
        DoubleRatchetSession.generateOneTimePreKeys(responder.store, startId = 1, count = 2)

        val bundleForFirstPeer = DoubleRatchetSession.assembleBundle(
            responder.store, deviceId = 1, preKeyId = 1, signedPreKeyId = 1, kyberPreKeyId = 1,
        )
        val bundleForSecondPeer = DoubleRatchetSession.assembleBundle(
            responder.store, deviceId = 1, preKeyId = 2, signedPreKeyId = 1, kyberPreKeyId = 1,
        )

        val firstPeer = Party("first-peer")
        val secondPeer = Party("second-peer")

        val firstPeerSession = DoubleRatchetSession.initiate(firstPeer.store, responder.address, bundleForFirstPeer)
        val firstMessage = firstPeerSession.encrypt("hello from first peer".toByteArray())
        val responderSessionWithFirst = DoubleRatchetSession.forIncoming(responder.store, firstPeer.address)
        assertContentEquals("hello from first peer".toByteArray(), responderSessionWithFirst.decrypt(firstMessage))

        // First peer's handshake has now consumed one-time prekey id 1 --
        // the second peer's independent handshake (against id 2) must still
        // succeed.
        val secondPeerSession = DoubleRatchetSession.initiate(secondPeer.store, responder.address, bundleForSecondPeer)
        val secondMessage = secondPeerSession.encrypt("hello from second peer".toByteArray())
        val responderSessionWithSecond = DoubleRatchetSession.forIncoming(responder.store, secondPeer.address)
        assertContentEquals("hello from second peer".toByteArray(), responderSessionWithSecond.decrypt(secondMessage))
    }
}
