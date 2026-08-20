package com.hop.repository

import com.hop.data.DontRelayFlagDao
import com.hop.data.DontRelayFlagEntity
import com.hop.data.RelayQueueDao
import com.hop.protocol.DontRelayFlagEnvelope
import com.hop.protocol.RelayPolicy
import com.hop.protocol.WireEnvelope
import com.hop.protocol.WirePayloadType

/**
 * Wraps [DontRelayFlagDao] plus [RelayPolicy]'s expiry rules -- Phase 2
 * Slice 2's "don't relay" distinct-attested-device flag counter (PRD §4.6,
 * ADR 0004). Mirrors [RelayRepository]'s shape deliberately: `open class`
 * over a DAO (+ a second DAO it mutates as a side effect) rather than
 * `Context`-coupled, so a JVM unit test can construct this directly against
 * hand-rolled fakes without Room/instrumentation.
 *
 * **Order-independence, by design**: a device can receive "don't relay"
 * flags for a clipHash before it ever receives the post itself -- mesh
 * delivery order isn't guaranteed. [recordFlag] never checks whether a
 * [RelayQueueEntity] row exists for the flagged clipHash, and
 * [buildOutgoingFlagBacklog] never gates on [RelayQueueEntity] presence
 * either -- flags are recorded and re-offered purely on their own terms. The
 * other half of this fix lives in [RelayRepository.considerForRelay], which
 * consults [isSuppressed] *at insert time* (via its injected
 * `isFlaggedForSuppression` lambda) so a post arriving after its flags
 * already crossed threshold is suppressed from the start, not just from the
 * next flag onward.
 *
 * **Explicit non-goal**: nothing here ever rewrites a `Frame`'s own on-wire
 * `dontRelay` bit. [recordFlag] only ever flips the local
 * `RelayQueueEntity.dontRelay` column, per-device, once *this* device has
 * independently observed [threshold] distinct attested flaggers -- see
 * `Frame.copy`'s own doc for why that distinction is deliberate.
 *
 * **Limits, stated plainly (matching [RelayPolicy]'s own disclosure style):**
 * - *Eventual-consistency*: different devices cross the distinct-flag
 *   threshold at different times depending on mesh partition/propagation
 *   order -- some devices flip `dontRelay=true` locally while others (who
 *   haven't yet seen enough flags) keep relaying for a while longer. This is
 *   expected behavior, not a bug (ADR 0003/0004's "deterrent, not a
 *   guarantee" framing).
 * - *Sybil-resistance gap* (stated separately from [RelayPolicy]'s existing
 *   `hopCount`-spoofing caveat, which requires a *modified* client): under
 *   `com.hop.crypto.StubAttestationProvider`, minting N distinct
 *   `attestedDeviceKey` values costs nothing -- not even for a stock,
 *   unmodified client, since the "key" is just the caller-supplied nonce
 *   echoed back. This class's wire format and counting logic are real; the
 *   Sybil-resistance property they're meant to provide is not, until real
 *   Play Integrity/App Attest replaces the stub.
 */
open class DontRelayRepository(
    private val flagDao: DontRelayFlagDao,
    private val relayQueueDao: RelayQueueDao,
    private val relayPolicy: RelayPolicy,
    /**
     * Flat, unmeasured placeholder -- same honesty posture as
     * [RelayPolicy.DEFAULT_MAX_HOPS]: not tuned against real-world abuse or
     * false-suppression data. Revisit once that exists.
     */
    private val threshold: Int = DONT_RELAY_THRESHOLD,
) {

    /**
     * Records [row]; if it's genuinely new (not a duplicate from this
     * attested key for this clipHash) and pushes the distinct-flagger count
     * to [threshold], flips the local [RelayQueueEntity][com.hop.data.RelayQueueEntity]
     * row's `dontRelay` bit (a no-op if no such row exists yet -- see class
     * doc). Returns whether it was new -- callers use this to decide whether
     * to propagate the flag onward (never re-broadcast a duplicate).
     *
     * No-ops (returns `false`, nothing stored) if [row]'s own decay window
     * has already passed as of now -- an expired flag is exactly as
     * meaningless as an expired post.
     */
    open suspend fun recordFlag(row: DontRelayFlagEntity): Boolean {
        if (relayPolicy.isExpired(row.originatedAtMs, row.ttlSeconds)) return false
        val inserted = flagDao.insert(row) != -1L
        if (inserted && flagDao.distinctFlaggerCount(row.clipHash) >= threshold) {
            relayQueueDao.markDontRelay(row.clipHash)
        }
        return inserted
    }

    /**
     * Whether [clipHash] has already crossed the distinct-flag threshold --
     * consulted by [RelayRepository.considerForRelay] at custody-taking time
     * so a post arriving *after* its flags is suppressed from the start
     * (closes the order-independence gap described in this class's doc).
     */
    open suspend fun isSuppressed(clipHash: String): Boolean =
        flagDao.distinctFlaggerCount(clipHash) >= threshold

    /**
     * Every non-expired flag this device holds, [WireEnvelope]-wrapped as a
     * [WirePayloadType.DONT_RELAY_FLAG] envelope, ready to offer a
     * newly-connected peer -- independent of whether this device also
     * holds/relays the underlying post (see class doc's order-independence
     * note: this never gates on [RelayQueueEntity][com.hop.data.RelayQueueEntity]
     * presence). Lazily prunes expired rows encountered along the way as a
     * side effect, not a separate sweep -- same posture as
     * [RelayRepository.buildOutgoingBacklog].
     */
    open suspend fun buildOutgoingFlagBacklog(): List<ByteArray> {
        val outgoing = mutableListOf<ByteArray>()
        for (row in flagDao.getAll()) {
            if (relayPolicy.isExpired(row.originatedAtMs, row.ttlSeconds)) {
                flagDao.deleteAllForClip(row.clipHash)
                continue
            }
            val envelope = DontRelayFlagEnvelope(
                clipHash = row.clipHash.hexToByteArray(),
                attestedDeviceKey = row.attestedDeviceKey.hexToByteArray(),
                flaggedAtMs = row.flaggedAtMs,
                originatedAtMs = row.originatedAtMs,
                ttlSeconds = row.ttlSeconds,
            )
            outgoing.add(WireEnvelope.encode(WirePayloadType.DONT_RELAY_FLAG, envelope.encode()))
        }
        return outgoing
    }

    private fun String.hexToByteArray(): ByteArray =
        ByteArray(length / 2) { i -> ((Character.digit(this[i * 2], 16) shl 4) + Character.digit(this[i * 2 + 1], 16)).toByte() }

    companion object {
        /**
         * Unmeasured placeholder, matching [RelayPolicy.DEFAULT_MAX_HOPS]'s
         * own "not tuned against real data" posture. Revisit once real
         * abuse/false-suppression data exists.
         */
        const val DONT_RELAY_THRESHOLD = 3
    }
}
