package com.hop.data

import com.hop.crypto.DoubleRatchetSession
import org.signal.libsignal.protocol.state.PreKeyBundle
import java.time.Duration

/**
 * Owns this device's one-time EC prekey pool (batch-generated ahead of time,
 * replenished once it runs low) and its current signed prekey / Kyber
 * prekey (rotated on a timer, with a grace period so a peer holding a
 * recently-superseded bundle can still complete a handshake) -- the fix for
 * the named MVP gap called out in the Phase 1 messaging plan and (before this
 * class existed) `com.hop.crypto.DoubleRatchetSession.publishPreKeyBundle`'s
 * doc: "no rotation/replenishment, fixed ids preKeyId=1/signedPreKeyId=1/
 * kyberPreKeyId=1".
 *
 * **The correctness bug this actually closes:** libsignal-client deletes a
 * one-time EC prekey ([RoomSignalProtocolStore.removePreKey]) the moment ANY
 * peer completes a handshake that consumes it. Before this class existed,
 * `com.hop.transport.WifiDirectTransport` memoized a *single* published
 * bundle (always referencing prekey id 1) and handed that exact same bundle
 * to every peer that connected for the rest of the process's lifetime. Once
 * the first peer consumed id 1, every later peer's handshake attempt
 * referenced an id this device had already deleted -- their first message
 * would fail to decrypt (`InvalidKeyIdException`) and be silently dropped by
 * `MessageRepository.onEnvelopeReceived`'s catch-all, with zero visible
 * cause. [currentBundle] instead hands out a *different*, never-before-
 * handed-out one-time prekey id on every call, so two peers connecting in
 * the same process lifetime never collide on the same one-time prekey.
 *
 * **Id-allocation design:** a small persisted counter
 * ([SignalPreKeyCounterEntity], singleton row) rather than a
 * `MAX(preKeyId) FROM signal_prekeys`-style derivation -- see that entity's
 * own doc for why (short version: a `MAX`-based approach would still be
 * collision-safe today given deletion-only-lowers-the-max, but can't answer
 * "how many unconsumed prekeys are left" or "which id was already handed out
 * but maybe not yet consumed," both of which this class needs on every
 * call, and an explicit counter doesn't depend on that reasoning continuing
 * to hold against every future change to this table).
 *
 * **Rotation interval:** [SIGNED_PRE_KEY_ROTATION_INTERVAL] (48h) applies
 * identically to the EC signed prekey and the Kyber prekey -- both are the
 * "signed" (single, long-lived-but-not-permanent) half of this app's bundle
 * shape, as opposed to the one-time EC prekey pool (see
 * [com.hop.crypto.DoubleRatchetSession.generateKyberPreKey]'s doc for why
 * Kyber gets signed-prekey treatment, not one-time-batch treatment, in this
 * app's bundle shape). Real Signal's reference clients rotate roughly
 * weekly; this MVP picks a *shorter* 48h interval deliberately: HOP v1 has
 * no server-side coordination at all (ADR 0001/0002) and bundle exchange is
 * purely ambient, in-person, best-effort (ADR 0001's "no store-and-forward
 * yet" phase scoping) -- a shorter-lived signed prekey bounds how long a
 * single long-term-but-not-permanent key stays live without needing any
 * operational rotation infrastructure to enforce it. This is a named,
 * documented MVP choice, not a protocol-mandated value -- revisit once real
 * usage data (how often two devices actually re-meet) exists.
 *
 * **Grace period:** [SIGNED_PRE_KEY_GRACE_PERIOD] (48h, one full extra
 * rotation interval) is how much longer a superseded signed/Kyber prekey
 * stays loadable after a newer one becomes current, before [pruneStale] (via
 * [currentBundle]'s own bookkeeping) removes it -- covers a peer who cached
 * a bundle shortly before this device rotated and doesn't reconnect again
 * until shortly after. Bounded: at most 2 signed-prekey generations and 2
 * Kyber-prekey generations are ever live at once.
 *
 * `@Synchronized` on [currentBundle]: this method both reads and
 * conditionally mutates [counterDao]'s singleton row (replenish/rotate
 * decisions), so two connections announcing concurrently on different
 * threads (plausible in a dense-venue multi-peer scenario --
 * `com.hop.transport.WifiDirectTransport.handleConnection` spawns a fresh
 * thread per connection) must not race on that read-modify-write.
 */
class PreKeyRotationManager(
    private val store: RoomSignalProtocolStore,
    private val counterDao: SignalPreKeyCounterDao,
    private val deviceId: Int,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    companion object {
        /**
         * How many currently-unconsumed one-time EC prekeys to generate in
         * one batch. Large enough that a dense-venue burst of simultaneous
         * connections (5-20 devices, per BUILD_PLAN.md's explicitly
         * under-tested N-peer case) doesn't need a fresh batch mid-burst;
         * small enough that a device that goes a long time between opens
         * isn't carrying an enormous unused pool of public keys at rest.
         */
        const val ONE_TIME_PRE_KEY_BATCH_SIZE = 20

        /** Replenish (generate a fresh [ONE_TIME_PRE_KEY_BATCH_SIZE]-sized batch) once the un-handed-out pool would drop to this many or fewer remaining. */
        const val ONE_TIME_PRE_KEY_LOW_WATERMARK = 5

        /** See this class's own doc for why 48h was chosen. */
        val SIGNED_PRE_KEY_ROTATION_INTERVAL: Duration = Duration.ofHours(48)

        /** See this class's own doc for why one full extra rotation interval was chosen. */
        val SIGNED_PRE_KEY_GRACE_PERIOD: Duration = Duration.ofHours(48)

        private const val INITIAL_ID = 1
    }

    /**
     * Returns this device's current [PreKeyBundle]: a freshly-handed-out
     * (never-before-handed-out) one-time EC prekey, plus whichever signed
     * EC/Kyber prekeys are currently active (rotating them first if they've
     * aged past [SIGNED_PRE_KEY_ROTATION_INTERVAL]). Safe -- and cheap -- to
     * call once per new connection: the common case touches only a handful
     * of small DB reads/writes, never new EC/Kyber keypair generation, which
     * only happens when the one-time pool is actually low or a signed prekey
     * is actually due for rotation.
     */
    @Synchronized
    fun currentBundle(): PreKeyBundle {
        var counter = counterDao.get() ?: seedInitialState()

        val (afterHandOut, oneTimePreKeyId) = handOutOneTimePreKey(counter)
        counter = afterHandOut

        val (afterSignedRotation, signedPreKeyId) = ensureCurrentSignedPreKey(counter)
        counter = afterSignedRotation

        val (afterKyberRotation, kyberPreKeyId) = ensureCurrentKyberPreKey(counter)
        counter = afterKyberRotation

        counterDao.upsert(counter)
        return DoubleRatchetSession.assembleBundle(store, deviceId, oneTimePreKeyId, signedPreKeyId, kyberPreKeyId)
    }

    /** First-ever call for this store: seed the initial one-time-prekey batch, signed prekey, and Kyber prekey, all starting at [INITIAL_ID]. */
    private fun seedInitialState(): SignalPreKeyCounterEntity {
        DoubleRatchetSession.generateOneTimePreKeys(store, startId = INITIAL_ID, count = ONE_TIME_PRE_KEY_BATCH_SIZE)
        DoubleRatchetSession.generateSignedPreKey(store, INITIAL_ID, timestampMs = nowMs())
        DoubleRatchetSession.generateKyberPreKey(store, INITIAL_ID, timestampMs = nowMs())
        return SignalPreKeyCounterEntity(
            nextOneTimePreKeyIdToGenerate = INITIAL_ID + ONE_TIME_PRE_KEY_BATCH_SIZE,
            nextOneTimePreKeyIdToHandOut = INITIAL_ID,
            currentSignedPreKeyId = INITIAL_ID,
            currentKyberPreKeyId = INITIAL_ID,
        )
    }

    /**
     * Hands out the next never-before-handed-out one-time prekey id,
     * replenishing the pool first (a fresh [ONE_TIME_PRE_KEY_BATCH_SIZE]
     * batch) if doing so would leave fewer than [ONE_TIME_PRE_KEY_LOW_WATERMARK]
     * remaining un-handed-out afterward.
     */
    private fun handOutOneTimePreKey(counter: SignalPreKeyCounterEntity): Pair<SignalPreKeyCounterEntity, Int> {
        var generateNext = counter.nextOneTimePreKeyIdToGenerate
        val idToHandOut = counter.nextOneTimePreKeyIdToHandOut
        val remainingAfterThisHandOut = generateNext - (idToHandOut + 1)
        if (remainingAfterThisHandOut < ONE_TIME_PRE_KEY_LOW_WATERMARK) {
            DoubleRatchetSession.generateOneTimePreKeys(store, startId = generateNext, count = ONE_TIME_PRE_KEY_BATCH_SIZE)
            generateNext += ONE_TIME_PRE_KEY_BATCH_SIZE
        }
        val updated = counter.copy(
            nextOneTimePreKeyIdToGenerate = generateNext,
            nextOneTimePreKeyIdToHandOut = idToHandOut + 1,
        )
        return updated to idToHandOut
    }

    /**
     * Rotates the EC signed prekey (generates a new one at `currentId + 1`
     * and prunes any now-out-of-grace-period old ones) if the current one is
     * older than [SIGNED_PRE_KEY_ROTATION_INTERVAL]; otherwise a no-op that
     * just returns the current id. Age is read from the stored
     * `SignedPreKeyRecord.timestamp`, not a separately-tracked column (see
     * [SignalPreKeyCounterEntity]'s doc).
     */
    private fun ensureCurrentSignedPreKey(counter: SignalPreKeyCounterEntity): Pair<SignalPreKeyCounterEntity, Int> {
        val current = store.loadSignedPreKey(counter.currentSignedPreKeyId)
        if (nowMs() - current.timestamp < SIGNED_PRE_KEY_ROTATION_INTERVAL.toMillis()) {
            return counter to counter.currentSignedPreKeyId
        }
        val newId = counter.currentSignedPreKeyId + 1
        DoubleRatchetSession.generateSignedPreKey(store, newId, timestampMs = nowMs())
        pruneStaleSignedPreKeys(excludingId = newId)
        return counter.copy(currentSignedPreKeyId = newId) to newId
    }

    /** Kyber counterpart to [ensureCurrentSignedPreKey] -- same interval/grace-period policy, applied to the Kyber prekey instead. */
    private fun ensureCurrentKyberPreKey(counter: SignalPreKeyCounterEntity): Pair<SignalPreKeyCounterEntity, Int> {
        val current = store.loadKyberPreKey(counter.currentKyberPreKeyId)
        if (nowMs() - current.timestamp < SIGNED_PRE_KEY_ROTATION_INTERVAL.toMillis()) {
            return counter to counter.currentKyberPreKeyId
        }
        val newId = counter.currentKyberPreKeyId + 1
        DoubleRatchetSession.generateKyberPreKey(store, newId, timestampMs = nowMs())
        pruneStaleKyberPreKeys(excludingId = newId)
        return counter.copy(currentKyberPreKeyId = newId) to newId
    }

    /** Removes every stored signed prekey (other than [excludingId], the just-generated current one) whose age exceeds rotation interval + grace period. */
    private fun pruneStaleSignedPreKeys(excludingId: Int) {
        val cutoffMs = nowMs() - (SIGNED_PRE_KEY_ROTATION_INTERVAL + SIGNED_PRE_KEY_GRACE_PERIOD).toMillis()
        store.loadSignedPreKeys().forEach { record ->
            if (record.id != excludingId && record.timestamp < cutoffMs) {
                store.removeSignedPreKey(record.id)
            }
        }
    }

    /** Kyber counterpart to [pruneStaleSignedPreKeys] -- uses [RoomSignalProtocolStore.pruneKyberPreKey] since `KyberPreKeyStore` has no remove method of its own (see that method's doc). */
    private fun pruneStaleKyberPreKeys(excludingId: Int) {
        val cutoffMs = nowMs() - (SIGNED_PRE_KEY_ROTATION_INTERVAL + SIGNED_PRE_KEY_GRACE_PERIOD).toMillis()
        store.loadKyberPreKeys().forEach { record ->
            if (record.id != excludingId && record.timestamp < cutoffMs) {
                store.pruneKyberPreKey(record.id)
            }
        }
    }
}
