package com.hop.crypto

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class IdentityTest {

    @Test
    fun `generate produces a valid identity key pair with expected key sizes`() {
        val identity = DeviceIdentity.generate()

        assertEquals(33, identity.keyPair.publicKey.serialize().size)
        assertEquals(32, identity.keyPair.privateKey.serialize().size)
        assertTrue(identity.registrationId in 1..0x3FFF, "registration id should be a positive 14-bit value")
    }

    @Test
    fun `two generated identities have distinct key material`() {
        val a = DeviceIdentity.generate()
        val b = DeviceIdentity.generate()

        assertNotEquals(a.keyPair.publicKey.serialize().toList(), b.keyPair.publicKey.serialize().toList())
    }

    @Test
    fun `loadOrCreate generates and persists a new identity when storage is empty`() {
        val storage = InMemoryIdentityKeyStorage()
        assertEquals(null, storage.load())

        val identity = DeviceIdentity.loadOrCreate(storage)

        assertEquals(identity, storage.load())
    }

    @Test
    fun `loadOrCreate returns the same identity on a second call instead of regenerating`() {
        val storage = InMemoryIdentityKeyStorage()

        val first = DeviceIdentity.loadOrCreate(storage)
        val second = DeviceIdentity.loadOrCreate(storage)

        assertEquals(first, second)
        assertEquals(
            first.keyPair.publicKey.serialize().toList(),
            second.keyPair.publicKey.serialize().toList(),
        )
    }
}
