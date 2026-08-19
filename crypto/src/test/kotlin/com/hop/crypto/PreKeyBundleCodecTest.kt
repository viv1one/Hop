package com.hop.crypto

import org.junit.jupiter.api.Test
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.state.impl.InMemorySignalProtocolStore
import org.signal.libsignal.protocol.util.KeyHelper
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * [PreKeyBundle][org.signal.libsignal.protocol.state.PreKeyBundle] has no
 * `equals()`/`hashCode()` override (confirmed via `javap`), so these
 * round-trip assertions compare every individual field rather than the
 * bundle object itself.
 */
class PreKeyBundleCodecTest {

    private fun freshStore() =
        InMemorySignalProtocolStore(IdentityKeyPair.generate(), KeyHelper.generateRegistrationId(false))

    @Test
    fun `encode then decode reproduces every field of a freshly published bundle`() {
        val store = freshStore()
        val original = DoubleRatchetSession.publishPreKeyBundle(store, deviceId = 1)

        val decoded = PreKeyBundleCodec.decode(PreKeyBundleCodec.encode(original))

        assertEquals(original.registrationId, decoded.registrationId)
        assertEquals(original.deviceId, decoded.deviceId)
        assertEquals(original.preKeyId, decoded.preKeyId)
        assertContentEquals(requireNotNull(original.preKey).serialize(), requireNotNull(decoded.preKey).serialize())
        assertEquals(original.signedPreKeyId, decoded.signedPreKeyId)
        assertContentEquals(original.signedPreKey.serialize(), decoded.signedPreKey.serialize())
        assertContentEquals(original.signedPreKeySignature, decoded.signedPreKeySignature)
        assertContentEquals(original.identityKey.serialize(), decoded.identityKey.serialize())
        assertEquals(original.kyberPreKeyId, decoded.kyberPreKeyId)
        assertContentEquals(original.kyberPreKey.serialize(), decoded.kyberPreKey.serialize())
        assertContentEquals(original.kyberPreKeySignature, decoded.kyberPreKeySignature)
    }

    @Test
    fun `a decoded bundle is usable to establish a real session, not just byte-identical`() {
        // The real point of this codec: a decoded bundle must actually work as
        // input to DoubleRatchetSession.initiate, not just round-trip its raw
        // bytes -- a subtly wrong field (e.g. swapped preKey/signedPreKey)
        // could still pass a byte-content comparison against the wrong field
        // while producing a bundle libsignal-client rejects or silently
        // mishandles.
        val bobStore = freshStore()
        val bobBundle = DoubleRatchetSession.publishPreKeyBundle(bobStore, deviceId = 1)
        val transmitted = PreKeyBundleCodec.decode(PreKeyBundleCodec.encode(bobBundle))

        val aliceStore = freshStore()
        val aliceAddress = org.signal.libsignal.protocol.SignalProtocolAddress("alice", 1)
        val bobAddress = org.signal.libsignal.protocol.SignalProtocolAddress("bob", 1)

        val aliceSession = DoubleRatchetSession.initiate(aliceStore, bobAddress, transmitted)
        val bobSession = DoubleRatchetSession.forIncoming(bobStore, aliceAddress)

        val plaintext = "hop hop hop".toByteArray()
        assertContentEquals(plaintext, bobSession.decrypt(aliceSession.encrypt(plaintext)))
    }
}
