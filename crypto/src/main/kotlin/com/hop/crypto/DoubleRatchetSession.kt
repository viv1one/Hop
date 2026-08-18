package com.hop.crypto

import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignalProtocolStore
import org.signal.libsignal.protocol.state.SignedPreKeyRecord

/**
 * 1:1 Double Ratchet session wrapper over libsignal-client's session-building
 * and cipher APIs (ADR 0001; PRD §4.3-4.4). One instance = one ongoing
 * conversation with one remote device address.
 *
 * The actual ratchet state lives inside [store] (a `SignalProtocolStore`), which
 * is what gives this forward secrecy: each successful [decrypt] consumes and
 * erases that specific message key from the store before returning, so a later
 * compromise of the store can't recover a message that was already decrypted
 * (see `DoubleRatchetSessionTest`'s forward-secrecy case). Keys for messages
 * that arrive *out of order* (skipped ahead of) are cached in the store, bounded
 * by libsignal-client's internal skipped-message-key cap — see [decrypt]'s doc
 * for what happens when that cap is exceeded.
 *
 * Group messaging in v1 is per-member pairwise fan-out (PRD §4.3, ADR 0001) —
 * i.e. one [DoubleRatchetSession] per member, not a shared group ratchet. Fan-out
 * orchestration and store-and-forward delivery are Phase 2 (relay) concerns, out
 * of scope for this direct-peer-only wrapper.
 *
 * Relay nodes never see plaintext: nothing in this class is reachable by a relay
 * — [encrypt] hands back opaque ciphertext only, and there is no code path here
 * that a relay could invoke to decrypt on its behalf.
 */
class DoubleRatchetSession private constructor(
    private val store: SignalProtocolStore,
    private val remoteAddress: SignalProtocolAddress,
) {
    private val cipher = SessionCipher(store, remoteAddress)

    /**
     * Encrypts [plaintext] for the remote party. libsignal decides internally
     * whether this needs to be a PreKey-type message (handshake payload
     * attached — sent until the far side has acknowledged the session by
     * sending anything back) or a plain ratchet ("Whisper") message. The
     * returned blob is self-describing (1-byte type tag prefix) so [decrypt] on
     * the far side doesn't need out-of-band knowledge of which kind of message
     * this is — this tag is purely an artifact of this wrapper's own
     * encrypt/decrypt round trip, not a mesh wire-format concern (`protocol/`
     * carries this blob as an opaque encrypted payload; it doesn't need to
     * understand it).
     */
    fun encrypt(plaintext: ByteArray): ByteArray {
        val message = cipher.encrypt(plaintext)
        return byteArrayOf(message.type.toByte()) + message.serialize()
    }

    /**
     * Decrypts a blob produced by [encrypt]. If no session exists yet for this
     * remote address (this is the first message ever received from them), a
     * PreKey-type blob establishes it as a side effect — no separate "accept
     * handshake" step is needed on the receiving side.
     *
     * Out-of-order / dropped delivery: messages can be decrypted in any order
     * relative to how they were sent (expected under store-and-forward relay in
     * later phases) — libsignal-client caches the message keys for any counters
     * it skips past, up to an internal cap (empirically ~2000 entries as of
     * 0.86.5; not a stable/documented public constant, so don't hard-code
     * assumptions about the exact number anywhere that isn't a test). A message
     * whose key has aged out of that cache — or one that's genuinely a replay of
     * an already-decrypted message, which libsignal-client can't distinguish
     * from an evicted skip — throws [org.signal.libsignal.protocol.DuplicateMessageException].
     * Per hop-dev's testing posture, callers must treat that as "this session
     * needs a fresh handshake," not as a message to silently drop; this wrapper
     * deliberately does not catch or reinterpret that exception itself; deciding
     * *when* to re-handshake is a higher-layer (messaging-session-management)
     * policy this class doesn't own.
     */
    fun decrypt(ciphertext: ByteArray): ByteArray {
        require(ciphertext.isNotEmpty()) { "ciphertext must not be empty" }
        val type = ciphertext[0].toInt()
        val body = ciphertext.copyOfRange(1, ciphertext.size)
        return when (type) {
            CiphertextMessage.PREKEY_TYPE -> cipher.decrypt(PreKeySignalMessage(body))
            CiphertextMessage.WHISPER_TYPE -> cipher.decrypt(SignalMessage(body))
            else -> throw IllegalArgumentException("Unknown ciphertext message type: $type")
        }
    }

    companion object {
        /**
         * Initiator side: consumes a [PreKeyBundle] fetched from the remote peer
         * out-of-band (over direct BLE/WiFi Direct contact in Phase 1, per ADR
         * 0001's phase scoping — never through a HOP-operated server) to
         * establish a new session in [store], then returns a wrapper for it.
         */
        fun initiate(
            store: SignalProtocolStore,
            remoteAddress: SignalProtocolAddress,
            remoteBundle: PreKeyBundle,
        ): DoubleRatchetSession {
            SessionBuilder(store, remoteAddress).process(remoteBundle)
            return DoubleRatchetSession(store, remoteAddress)
        }

        /**
         * Responder side: no explicit handshake call is needed — the first
         * inbound [decrypt] of a PreKey-type message establishes the session as
         * a side effect (this mirrors libsignal-client's own `SessionCipher`
         * behavior). This factory just wires up the wrapper against
         * [store]/[remoteAddress] ahead of that first inbound message.
         */
        fun forIncoming(store: SignalProtocolStore, remoteAddress: SignalProtocolAddress): DoubleRatchetSession =
            DoubleRatchetSession(store, remoteAddress)

        /**
         * Generates a fresh one-time prekey, signed prekey, and Kyber (PQXDH)
         * prekey, stores them into [store], and assembles them into a
         * [PreKeyBundle] for out-of-band publication to a prospective
         * initiator. Not part of the ratchet itself, but the minimum
         * bundle-publishing step every responder needs before anyone can call
         * [initiate] against them — kept here rather than a separate file since
         * it's purely a session-establishment concern local to this wrapper.
         */
        fun publishPreKeyBundle(
            store: SignalProtocolStore,
            deviceId: Int,
            preKeyId: Int = 1,
            signedPreKeyId: Int = 1,
            kyberPreKeyId: Int = 1,
        ): PreKeyBundle {
            val identity = store.identityKeyPair

            val signedPreKeyPair = ECKeyPair.generate()
            val signedPreKeySignature =
                identity.privateKey.calculateSignature(signedPreKeyPair.publicKey.serialize())
            store.storeSignedPreKey(
                signedPreKeyId,
                SignedPreKeyRecord(signedPreKeyId, System.currentTimeMillis(), signedPreKeyPair, signedPreKeySignature),
            )

            val oneTimePreKeyPair = ECKeyPair.generate()
            store.storePreKey(preKeyId, PreKeyRecord(preKeyId, oneTimePreKeyPair))

            val kyberKeyPair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
            val kyberSignature = identity.privateKey.calculateSignature(kyberKeyPair.publicKey.serialize())
            store.storeKyberPreKey(
                kyberPreKeyId,
                KyberPreKeyRecord(kyberPreKeyId, System.currentTimeMillis(), kyberKeyPair, kyberSignature),
            )

            return PreKeyBundle(
                store.localRegistrationId,
                deviceId,
                preKeyId,
                oneTimePreKeyPair.publicKey,
                signedPreKeyId,
                signedPreKeyPair.publicKey,
                signedPreKeySignature,
                identity.publicKey,
                kyberPreKeyId,
                kyberKeyPair.publicKey,
                kyberSignature,
            )
        }
    }
}
