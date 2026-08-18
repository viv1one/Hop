package com.hop.crypto

import org.junit.jupiter.api.Test
import org.signal.libsignal.protocol.IdentityKeyPair
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Trivial smoke test confirming org.signal:libsignal-client resolves and runs its
 * native (JNI) code correctly under plain JVM in this Gradle module, before any
 * real crypto/ code is built against it. See task spec: do not proceed past this
 * until it passes cleanly.
 */
class SmokeTest {

    @Test
    fun `libsignal-client generates an identity key pair with expected key sizes`() {
        val identityKeyPair = IdentityKeyPair.generate()
        assertNotNull(identityKeyPair)

        // Curve25519 public key: 1 type byte + 32 bytes = 33 bytes serialized.
        val publicKeyBytes = identityKeyPair.publicKey.serialize()
        assertEquals(33, publicKeyBytes.size)

        // Curve25519 private key: 32 bytes serialized.
        val privateKeyBytes = identityKeyPair.privateKey.serialize()
        assertEquals(32, privateKeyBytes.size)
    }
}
