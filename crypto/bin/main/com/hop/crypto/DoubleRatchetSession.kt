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
         * prekey (each at fixed id 1 by default), stores them into [store],
         * and assembles them into a [PreKeyBundle] for out-of-band
         * publication to a prospective initiator. Not part of the ratchet
         * itself, but the minimum bundle-publishing step every responder
         * needs before anyone can call [initiate] against them — kept here
         * rather than a separate file since it's purely a
         * session-establishment concern local to this wrapper.
         *
         * **No rotation/replenishment of its own** — every call re-publishes
         * fresh key material at the *same* fixed ids by default, which is
         * exactly the "always id 1" bug pattern described in
         * `com.hop.data.RoomSignalProtocolStore`'s doc: a second call
         * silently invalidates whatever the first call handed out, if the
         * first bundle's one-time prekey hasn't been consumed yet. This
         * convenience wrapper remains useful for tests and any one-shot
         * single-handshake scenario (it's what
         * `DoubleRatchetSessionTest`/`RoomSignalProtocolStoreTest` use to
         * stand up a single Alice/Bob pair), but production code that
         * announces a bundle to more than one peer over a device's lifetime
         * must use [generateOneTimePreKeys]/[generateSignedPreKey]/
         * [generateKyberPreKey]/[assembleBundle] directly through a
         * replenishment/rotation policy instead —
         * `com.hop.data.PreKeyRotationManager` is that policy for this app's
         * Room-backed store.
         */
        fun publishPreKeyBundle(
            store: SignalProtocolStore,
            deviceId: Int,
            preKeyId: Int = 1,
            signedPreKeyId: Int = 1,
            kyberPreKeyId: Int = 1,
        ): PreKeyBundle {
            generateSignedPreKey(store, signedPreKeyId)
            generateOneTimePreKeys(store, startId = preKeyId, count = 1)
            generateKyberPreKey(store, kyberPreKeyId)
            return assembleBundle(store, deviceId, preKeyId, signedPreKeyId, kyberPreKeyId)
        }

        /**
         * Generates [count] fresh one-time EC prekeys with sequential ids
         * starting at [startId] (`startId`, `startId+1`, ..., `startId+count-1`)
         * and stores each into [store], returning the ids generated. Pure
         * generation + storage — doesn't touch or know about any id-allocation
         * policy (that's the caller's job, e.g. `com.hop.data.PreKeyRotationManager`,
         * which is what actually decides *which* ids are safe to hand out
         * next so two different peers are never handed the same one-time
         * prekey before either has consumed it).
         */
        fun generateOneTimePreKeys(store: SignalProtocolStore, startId: Int, count: Int): List<Int> {
            require(count > 0) { "count must be positive, was $count" }
            return (0 until count).map { offset ->
                val id = startId + offset
                store.storePreKey(id, PreKeyRecord(id, ECKeyPair.generate()))
                id
            }
        }

        /**
         * Generates one fresh EC signed prekey at [signedPreKeyId], signs it
         * with [store]'s own long-term identity key, and stores it into
         * [store]. Callers doing rotation (`com.hop.data.PreKeyRotationManager`)
         * are responsible for picking a not-yet-used [signedPreKeyId] and for
         * eventually pruning a superseded one via `store.removeSignedPreKey`
         * once it's outside its grace period — this function only generates
         * and stores, it never removes anything.
         *
         * [timestampMs] defaults to the real wall clock but is overridable so
         * a rotation-policy caller (`com.hop.data.PreKeyRotationManager`, and
         * its tests) can drive the embedded `SignedPreKeyRecord.timestamp` —
         * the value rotation-age decisions are actually based on — off the
         * same injectable clock the caller itself uses, rather than mixing a
         * real timestamp into an otherwise fully-deterministic test.
         */
        fun generateSignedPreKey(
            store: SignalProtocolStore,
            signedPreKeyId: Int,
            timestampMs: Long = System.currentTimeMillis(),
        ): SignedPreKeyRecord {
            val identity = store.identityKeyPair
            val keyPair = ECKeyPair.generate()
            val signature = identity.privateKey.calculateSignature(keyPair.publicKey.serialize())
            val record = SignedPreKeyRecord(signedPreKeyId, timestampMs, keyPair, signature)
            store.storeSignedPreKey(signedPreKeyId, record)
            return record
        }

        /**
         * Generates one fresh Kyber (PQXDH) prekey at [kyberPreKeyId], signs
         * it with [store]'s own long-term identity key, and stores it into
         * [store]. Like [generateSignedPreKey], this is the "signed" (not
         * one-time-batch) side of prekey material in this app's bundle shape
         * — libsignal-client's `PreKeyBundle` carries exactly one Kyber
         * prekey, signed the same way the EC signed prekey is, so this app
         * rotates it on the same policy/timer as the EC signed prekey rather
         * than batch-issuing many of them the way [generateOneTimePreKeys]
         * does for the EC one-time prekey. `markKyberPreKeyUsed`'s "mark,
         * never delete" contract (see `RoomSignalProtocolStore.markKyberPreKeyUsed`'s
         * doc) is unrelated to and unaffected by rotation — rotation-driven
         * pruning of a long-superseded Kyber prekey is a separate, later
         * lifecycle event a caller triggers explicitly, never implied by
         * generating a new one here.
         *
         * [timestampMs]: see [generateSignedPreKey]'s doc -- same rationale,
         * applied to `KyberPreKeyRecord.timestamp`.
         */
        fun generateKyberPreKey(
            store: SignalProtocolStore,
            kyberPreKeyId: Int,
            timestampMs: Long = System.currentTimeMillis(),
        ): KyberPreKeyRecord {
            val identity = store.identityKeyPair
            val keyPair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
            val signature = identity.privateKey.calculateSignature(keyPair.publicKey.serialize())
            val record = KyberPreKeyRecord(kyberPreKeyId, timestampMs, keyPair, signature)
            store.storeKyberPreKey(kyberPreKeyId, record)
            return record
        }

        /**
         * Assembles a [PreKeyBundle] from key material *already stored* in
         * [store] at [preKeyId]/[signedPreKeyId]/[kyberPreKeyId] — unlike
         * [publishPreKeyBundle], this never generates anything itself, so
         * it's cheap (DB reads only) to call on every new connection once a
         * one-time prekey pool and current signed/Kyber prekeys already
         * exist (see `com.hop.data.PreKeyRotationManager.currentBundle`, the
         * production caller of this function). Throws
         * `org.signal.libsignal.protocol.InvalidKeyIdException` if any of the
         * three ids doesn't currently resolve in [store].
         */
        fun assembleBundle(
            store: SignalProtocolStore,
            deviceId: Int,
            preKeyId: Int,
            signedPreKeyId: Int,
            kyberPreKeyId: Int,
        ): PreKeyBundle {
            val preKey = store.loadPreKey(preKeyId)
            val signedPreKey = store.loadSignedPreKey(signedPreKeyId)
            val kyberPreKey = store.loadKyberPreKey(kyberPreKeyId)
            return PreKeyBundle(
                store.localRegistrationId,
                deviceId,
                preKeyId,
                preKey.keyPair.publicKey,
                signedPreKeyId,
                signedPreKey.keyPair.publicKey,
                signedPreKey.signature,
                store.identityKeyPair.publicKey,
                kyberPreKeyId,
                kyberPreKey.keyPair.publicKey,
                kyberPreKey.signature,
            )
        }
    }
}
