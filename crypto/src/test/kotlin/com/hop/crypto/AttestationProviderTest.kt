package com.hop.crypto

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertIs

class AttestationProviderTest {

    @Test
    fun `stub provider always passes`() = runBlocking {
        val provider: AttestationProvider = StubAttestationProvider()
        val nonce = byteArrayOf(1, 2, 3, 4)

        val result = provider.attest(nonce)

        val passed = assertIs<AttestationResult.Passed>(result)
        assertContentEquals(nonce, passed.attestedPublicKey)
    }

    @Test
    fun `stub provider is not a source of Sybil resistance by itself`() = runBlocking {
        // Documents the limit explicitly, per hop-dev's testing posture for
        // attested-identity-gated code: N distinct calls to the stub all "pass"
        // trivially, since it has no notion of one-real-device. Nothing
        // downstream should treat this as N distinct attested devices -- that
        // property only holds once a real Play Integrity / App Attest
        // implementation replaces this stub.
        val provider: AttestationProvider = StubAttestationProvider()
        val results = (0 until 5).map { provider.attest(byteArrayOf(it.toByte())) }

        // All five trivially "pass" -- this is exactly what makes the stub unsafe
        // to ship, not a bug in the stub itself.
        assert(results.all { it is AttestationResult.Passed })
    }
}
