package com.hop.data

import java.util.UUID
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.NoSessionException
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SignalProtocolStore
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.util.KeyHelper

/**
 * Persistent, Room-backed implementation of libsignal-client's
 * `SignalProtocolStore` -- the composite `IdentityKeyStore + PreKeyStore +
 * SignedPreKeyStore + SessionStore + SenderKeyStore + KyberPreKeyStore`
 * interface `crypto/`'s [com.hop.crypto.DoubleRatchetSession] already
 * accepts as its pluggable persistence seam (ADR 0001). No new interface is
 * declared here -- unlike [RoomDecayKeyStorage] (which implements a
 * `crypto/`-defined interface, `DecayKeyStorage`), libsignal-client already
 * supplies the interface to implement directly, so wrapping it again would
 * be pure indirection.
 *
 * Without this class, every Double Ratchet session -- and therefore every
 * 1:1 conversation's live ratchet state -- vanishes the moment the app
 * process dies, exactly the class of problem [RoomDecayKeyStorage] already
 * solved for decay keys (see its doc). Message *history* already survives a
 * restart via [MessageEntity]; this class is what lets the session that
 * produced that history survive too.
 *
 * The exact method set below was verified against the compiled 0.86.5 jar
 * (`javap` against each libsignal-client sub-interface) rather than assumed
 * from memory of another version -- see each override's doc for anything
 * non-obvious about a given method's contract.
 *
 * Deliberately synchronous, matching `SignalProtocolStore`'s own contract
 * (none of its methods are `suspend` -- libsignal-client's Java API is
 * blocking throughout). All six backing DAOs run blocking Room queries.
 * **Callers must invoke every method on this class from a background
 * thread/dispatcher, never the main thread** -- Room throws
 * `IllegalStateException` on a main-thread query, the same real bug class
 * already found and fixed once this session in `PostComposerViewModel`.
 *
 * Identity keys (own and remote) are stored as raw bytes, not
 * Android-Keystore-wrapped -- named, deliberate MVP simplification, flagged
 * in [IdentityKeyPairEntity]'s doc.
 */
class RoomSignalProtocolStore(
    private val identityDao: SignalIdentityDao,
    private val preKeyDao: SignalPreKeyDao,
    private val signedPreKeyDao: SignalSignedPreKeyDao,
    private val kyberPreKeyDao: SignalKyberPreKeyDao,
    private val sessionDao: SignalSessionDao,
) : SignalProtocolStore {

    // --- IdentityKeyStore ---

    /**
     * Lazily generates and persists this device's own long-term identity
     * key pair + registration id on first call, rather than seeding it
     * eagerly at DB-creation time -- most app sessions never open the Inbox
     * at all. `@Synchronized` guards against two concurrent first-callers
     * each generating a different identity: without it, Room's `REPLACE`
     * conflict strategy would let the second caller's insert silently win,
     * leaving the first caller holding an [IdentityKeyPair] object that no
     * longer matches what's persisted.
     */
    @Synchronized
    private fun ensureOwnIdentity(): IdentityKeyPairEntity {
        identityDao.getOwnIdentity()?.let { return it }
        val generated = IdentityKeyPair.generate()
        val entity = IdentityKeyPairEntity(
            identityKeyPairBytes = generated.serialize(),
            registrationId = KeyHelper.generateRegistrationId(false),
            createdAtMs = System.currentTimeMillis(),
        )
        identityDao.insertOwnIdentity(entity)
        return entity
    }

    override fun getIdentityKeyPair(): IdentityKeyPair = IdentityKeyPair(ensureOwnIdentity().identityKeyPairBytes)

    override fun getLocalRegistrationId(): Int = ensureOwnIdentity().registrationId

    /**
     * Trust-on-first-use (see [RemoteIdentityEntity]'s doc): the identity
     * key for [address]'s name is stored unconditionally (an upsert,
     * mirroring libsignal-client's own `InMemoryIdentityKeyStore` behavior
     * verified via decompile), and the return value reports whether this
     * replaced a *different* previously-trusted key.
     */
    override fun saveIdentity(address: SignalProtocolAddress, identityKey: IdentityKey): IdentityKeyStore.IdentityChange {
        val existing = identityDao.getRemoteIdentity(address.name)
        identityDao.upsertRemoteIdentity(
            RemoteIdentityEntity(
                peerId = address.name,
                identityKeyBytes = identityKey.serialize(),
                firstSeenAtMs = existing?.firstSeenAtMs ?: System.currentTimeMillis(),
            )
        )
        val previousKey = existing?.let { IdentityKey(it.identityKeyBytes) }
        return if (previousKey == null || previousKey == identityKey) {
            IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
        } else {
            IdentityKeyStore.IdentityChange.REPLACED_EXISTING
        }
    }

    /**
     * TOFU: no stored key yet, or the stored key matches -> trusted. A
     * stored key that *differs* from [identityKey] -> untrusted, which
     * causes libsignal-client's `SessionBuilder`/`SessionCipher` to refuse
     * to proceed (`UntrustedIdentityException`) rather than silently
     * re-trusting -- see [RemoteIdentityEntity]'s doc for what this app does
     * (nothing, yet) to surface that refusal to the user.
     */
    override fun isTrustedIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey,
        direction: IdentityKeyStore.Direction,
    ): Boolean {
        val existing = identityDao.getRemoteIdentity(address.name) ?: return true
        return IdentityKey(existing.identityKeyBytes) == identityKey
    }

    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? =
        identityDao.getRemoteIdentity(address.name)?.let { IdentityKey(it.identityKeyBytes) }

    // --- PreKeyStore ---

    override fun loadPreKey(preKeyId: Int): PreKeyRecord {
        val entity = preKeyDao.getById(preKeyId) ?: throw InvalidKeyIdException("No such prekey: $preKeyId")
        return PreKeyRecord(entity.recordBytes)
    }

    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) {
        preKeyDao.insertOrReplace(PreKeyEntity(preKeyId = preKeyId, recordBytes = record.serialize()))
    }

    override fun containsPreKey(preKeyId: Int): Boolean = preKeyDao.countById(preKeyId) > 0

    override fun removePreKey(preKeyId: Int) {
        preKeyDao.deleteById(preKeyId)
    }

    // --- SignedPreKeyStore ---

    override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord {
        val entity = signedPreKeyDao.getById(signedPreKeyId)
            ?: throw InvalidKeyIdException("No such signed prekey: $signedPreKeyId")
        return SignedPreKeyRecord(entity.recordBytes)
    }

    override fun loadSignedPreKeys(): MutableList<SignedPreKeyRecord> =
        signedPreKeyDao.getAll().map { SignedPreKeyRecord(it.recordBytes) }.toMutableList()

    override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) {
        signedPreKeyDao.insertOrReplace(
            SignedPreKeyEntity(
                signedPreKeyId = signedPreKeyId,
                recordBytes = record.serialize(),
                createdAtMs = System.currentTimeMillis(),
            )
        )
    }

    override fun containsSignedPreKey(signedPreKeyId: Int): Boolean = signedPreKeyDao.countById(signedPreKeyId) > 0

    override fun removeSignedPreKey(signedPreKeyId: Int) {
        signedPreKeyDao.deleteById(signedPreKeyId)
    }

    // --- KyberPreKeyStore ---

    override fun loadKyberPreKey(kyberPreKeyId: Int): KyberPreKeyRecord {
        val entity = kyberPreKeyDao.getById(kyberPreKeyId)
            ?: throw InvalidKeyIdException("No such Kyber prekey: $kyberPreKeyId")
        return KyberPreKeyRecord(entity.recordBytes)
    }

    override fun loadKyberPreKeys(): MutableList<KyberPreKeyRecord> =
        kyberPreKeyDao.getAll().map { KyberPreKeyRecord(it.recordBytes) }.toMutableList()

    override fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) {
        kyberPreKeyDao.insertOrReplace(
            KyberPreKeyEntity(kyberPreKeyId = kyberPreKeyId, recordBytes = record.serialize(), used = false)
        )
    }

    override fun containsKyberPreKey(kyberPreKeyId: Int): Boolean = kyberPreKeyDao.countById(kyberPreKeyId) > 0

    /**
     * Marks a one-time Kyber prekey consumed (replay-protection semantics --
     * per libsignal-client's `KyberPreKeyStore` contract, a used one-time
     * prekey is marked, never deleted, since the id still needs to resolve
     * for e.g. re-processing an already-in-flight `PreKeySignalMessage`).
     *
     * [KyberPreKeyEntity]'s schema (per the Phase 1 messaging plan) tracks
     * only a `used` boolean, not a per-`(kyberPreKeyId, deviceId)` base-key
     * reuse table the way libsignal-client's own `InMemoryKyberPreKeyStore`
     * does internally (verified by decompiling it: it throws
     * `ReusedBaseKeyException` if the *same* one-time prekey id is consumed
     * twice with two *different* base keys, which this store does not
     * detect). That's a named, deliberate scope cut for this slice -- no
     * prekey rotation exists yet either (see the Phase 1 messaging plan) --
     * not a claim that reuse detection is unimportant; flagged here rather
     * than silently implied away.
     */
    override fun markKyberPreKeyUsed(kyberPreKeyId: Int, deviceId: Int, baseKey: ECPublicKey) {
        kyberPreKeyDao.markUsed(kyberPreKeyId)
    }

    // --- SessionStore ---

    override fun loadSession(address: SignalProtocolAddress): SessionRecord? =
        sessionDao.getByKey(addressKey(address))?.let { SessionRecord(it.sessionRecordBytes) }

    override fun loadExistingSessions(addresses: MutableList<SignalProtocolAddress>): MutableList<SessionRecord> =
        addresses.map { address ->
            sessionDao.getByKey(addressKey(address))?.let { SessionRecord(it.sessionRecordBytes) }
                ?: throw NoSessionException(address, "No session for $address")
        }.toMutableList()

    /** Mirrors libsignal-client's own `InMemorySessionStore`: excludes device id 1 (this app's fixed "primary" device id). */
    override fun getSubDeviceSessions(name: String): MutableList<Int> =
        sessionDao.getDeviceIdsForName(name).filter { it != 1 }.toMutableList()

    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) {
        sessionDao.insertOrReplace(
            SessionEntity(
                addressKey = addressKey(address),
                name = address.name,
                deviceId = address.deviceId,
                sessionRecordBytes = record.serialize(),
            )
        )
    }

    override fun containsSession(address: SignalProtocolAddress): Boolean =
        sessionDao.countByKey(addressKey(address)) > 0

    override fun deleteSession(address: SignalProtocolAddress) {
        sessionDao.deleteByKey(addressKey(address))
    }

    override fun deleteAllSessions(name: String) {
        sessionDao.deleteAllForName(name)
    }

    private fun addressKey(address: SignalProtocolAddress): String = SessionEntity.addressKey(address.name, address.deviceId)

    // --- SenderKeyStore (part of the composite SignalProtocolStore interface) ---
    //
    // Group messaging in v1 is per-member pairwise Double Ratchet fan-out
    // (PRD §4.3, ADR 0001), never libsignal-client's own sender-key group
    // ratchet -- so nothing in this app's actual code paths ever calls
    // these two methods, and no Room entity backs them (out of the
    // six-entity scope agreed for this slice). `SignalProtocolStore`
    // requires implementing them anyway, since it's a single composite
    // interface libsignal-client doesn't let you split. Left as loud
    // failures rather than silent no-ops so a future accidental call (e.g.
    // a group-ratchet implementation added without revisiting this
    // decision) fails fast instead of quietly doing nothing.

    override fun storeSenderKey(sender: SignalProtocolAddress, distributionId: UUID, record: SenderKeyRecord) {
        throw UnsupportedOperationException(
            "SenderKeyStore is unused by this app: HOP v1 group messaging is per-member " +
                "pairwise Double Ratchet fan-out, not libsignal-client's sender-key group " +
                "ratchet (PRD §4.3, ADR 0001)."
        )
    }

    override fun loadSenderKey(sender: SignalProtocolAddress, distributionId: UUID): SenderKeyRecord {
        throw UnsupportedOperationException(
            "SenderKeyStore is unused by this app: HOP v1 group messaging is per-member " +
                "pairwise Double Ratchet fan-out, not libsignal-client's sender-key group " +
                "ratchet (PRD §4.3, ADR 0001)."
        )
    }
}
