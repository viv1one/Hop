package com.hop.crypto

import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.util.KeyHelper

/**
 * Device identity key generation (ADR 0004).
 *
 * This is the *key-generation* half of "local identity is bound to a hardware
 * attestation token, not left free-floating": a long-term libsignal
 * [IdentityKeyPair] plus a Signal-protocol registration id, both scoped to this
 * device only. There is no server-held account here — the identity key never
 * leaves the device except as its public half, exchanged directly with a peer
 * during a session handshake (never through any HOP-operated service, per the
 * no-server-in-the-content/discovery/message-delivery-path constraint).
 *
 * Proving that this key pair actually came from genuine, non-emulated hardware
 * (as opposed to a free/resettable software identity) is a separate concern —
 * see [AttestationProvider]. `DeviceIdentity` only generates and holds the key
 * material; it makes no claim about hardware provenance on its own.
 */
class DeviceIdentity private constructor(
    val keyPair: IdentityKeyPair,
    val registrationId: Int,
) {
    companion object {
        /** Generates a brand-new device identity, unconditionally (no persistence lookup). */
        fun generate(): DeviceIdentity =
            DeviceIdentity(IdentityKeyPair.generate(), KeyHelper.generateRegistrationId(false))

        /**
         * Loads a previously persisted identity from [storage], or generates and
         * persists a new one if none exists yet (e.g. first run on this device).
         */
        fun loadOrCreate(storage: IdentityKeyStorage): DeviceIdentity {
            val existing = storage.load()
            if (existing != null) return existing
            return generate().also { storage.save(it) }
        }
    }
}

/**
 * Where device identity key material gets persisted, as an interface so callers
 * don't need to know whether storage is hardware-backed or not.
 *
 * Real persistence — Android Keystore-backed, so the private key material never
 * exists outside secure hardware in plaintext — is a platform (`mobile/android/`)
 * concern for a later pass. This module only defines the contract and ships an
 * in-memory reference implementation for tests / headless use.
 */
interface IdentityKeyStorage {
    fun save(identity: DeviceIdentity)
    fun load(): DeviceIdentity?
}

/**
 * In-memory reference [IdentityKeyStorage]. Not durable across process restarts —
 * fine for tests and headless composition, not a substitute for the real
 * Android Keystore-backed implementation this interface exists to allow.
 */
class InMemoryIdentityKeyStorage : IdentityKeyStorage {
    private var stored: DeviceIdentity? = null

    override fun save(identity: DeviceIdentity) {
        stored = identity
    }

    override fun load(): DeviceIdentity? = stored
}
