package com.hop.repository

import com.hop.data.RelayQueueDao
import com.hop.data.RelayQueueEntity
import com.hop.protocol.Frame
import com.hop.protocol.RelayPolicy
import com.hop.protocol.WireEnvelope
import com.hop.protocol.WirePayloadType

/**
 * Wraps [RelayQueueDao] plus [RelayPolicy]'s pure eligibility rules -- the
 * persisted store-and-forward relay queue Phase 2 Slice 1 adds (see
 * `RelayQueueEntity`'s own doc). Mirrors [PostRepository]'s shape
 * deliberately: `open class` over a DAO + policy object (not
 * `Context`-coupled), so a JVM unit test can construct this directly against
 * a hand-rolled fake [RelayQueueDao] without Room/instrumentation, same
 * testability seam [PostRepository] already uses.
 *
 * This class never decrypts and never inspects [Frame.payload] --
 * `encodedFrame` is opaque `Frame.encode()` bytes as far as this class is
 * concerned, matching [Frame]'s and [WireEnvelope]'s own "opaque payload"
 * posture.
 *
 * Reach-tier scope note: this slice does not yet gate relay eligibility by
 * reach tier / tier-membership proof (ADR 0003's per-tier key-wrapping) --
 * Phase 1/2 Slice 1 only has Locality tier wired end-to-end (see
 * `EncryptedFrameCodec`'s own doc), so there is no tier check to add yet.
 * Don't read [considerForRelay] as an access-control decision beyond
 * hop-count/dontRelay/expiry -- it isn't one.
 */
open class RelayRepository(
    private val relayQueueDao: RelayQueueDao,
    private val relayPolicy: RelayPolicy,
    /**
     * Phase 2 Slice 2's order-independence fix: consulted at custody-taking
     * time (inside [considerForRelay], after [RelayPolicy]'s own eligibility
     * check passes) so a post arriving *after* its "don't relay" flags
     * already crossed threshold is suppressed from the start, not just from
     * the next flag onward. A lambda-injected dependency (mirroring the
     * existing `getOwnPeerId: suspend () -> String` pattern already used in
     * `WifiDirectTransport`/`MessageRepository`), not a hard compile-time
     * dependency on `com.hop.repository.DontRelayRepository` -- keeps the
     * two repositories decoupled at the type level. Defaults to "never
     * suppressed" so every existing construction site/test of this class
     * keeps working unchanged.
     */
    private val isFlaggedForSuppression: suspend (clipHashHex: String) -> Boolean = { false },
) {

    /**
     * Takes custody of [frame] (self-authored, just broadcast by this
     * device, or freshly received from a peer) into the persisted relay
     * queue iff [RelayPolicy] still allows further relay of it -- a no-op if
     * [frame] is already past [RelayPolicy]'s hop-count bound, is marked
     * `dontRelay`, or has already decayed. [frame]'s own [Frame.hopCount] is
     * stored as-is (the hop count *this device* received/originated it at,
     * per [RelayQueueEntity.hopCount]'s doc) -- incrementing happens only
     * when a copy is actually sent onward, in [buildOutgoingBacklog].
     *
     * After [RelayPolicy]'s eligibility check passes, additionally consults
     * [isFlaggedForSuppression]: if this clipHash has already crossed the
     * "don't relay" distinct-flag threshold (see that lambda's own doc), the
     * row is still inserted (this device still takes custody and can still
     * show it locally, same posture as any other `dontRelay` frame -- see
     * `Frame.copy`'s doc), but with `dontRelay = true` regardless of
     * [frame]'s own (possibly still-`false`) wire value. This is what closes
     * the order-independence gap: a post that arrives after enough flags
     * already exist is suppressed immediately, not only once its own future
     * flags separately cross threshold.
     *
     * Relies on [RelayQueueDao.insert]'s `IGNORE` conflict strategy for
     * idempotency: calling this more than once for the same `clipHash` (a
     * redundant offer from a second peer, or a reconnect) is harmless --
     * first custody wins, nothing here needs to check for an existing row
     * itself.
     */
    open suspend fun considerForRelay(frame: Frame) {
        if (!relayPolicy.isEligibleForRelay(
                storedHopCount = frame.hopCount,
                dontRelay = frame.dontRelay,
                originatedAtMs = frame.originatedAtMs,
                ttlSeconds = frame.ttlSeconds,
            )
        ) {
            return
        }

        val clipHashHex = frame.clipHash.toHexString()
        val suppressed = isFlaggedForSuppression(clipHashHex)

        relayQueueDao.insert(
            RelayQueueEntity(
                clipHash = clipHashHex,
                encodedFrame = frame.encode(),
                hopCount = frame.hopCount,
                originatedAtMs = frame.originatedAtMs,
                ttlSeconds = frame.ttlSeconds,
                dontRelay = frame.dontRelay || suppressed,
                receivedAtMs = System.currentTimeMillis(),
            ),
        )
    }

    /**
     * Every currently relay-eligible row in the persisted queue, each
     * decoded, re-encoded with `hopCount + 1` (the hop count a peer this
     * device is about to send to will receive it at), and [WireEnvelope]-
     * wrapped as a [WirePayloadType.POST_FRAME] envelope ready to write
     * straight to a socket -- the same shape [WifiDirectTransport]'s
     * `outbox` already produces for the live-session send path.
     *
     * Lazily deletes rows encountered here that have already decayed
     * (`RelayPolicy.isExpired`) as a side effect -- not a separate sweep --
     * matching [DecayKeyStore.retrieve]'s own "expiry enforced on read, not
     * by a background job" posture. A row that's simply past the hop-count
     * bound or marked `dontRelay` is left in place (it was, and remains,
     * legitimately received/stored -- only its *further* propagation
     * stopped), not deleted.
     */
    open suspend fun buildOutgoingBacklog(): List<ByteArray> {
        val outgoing = mutableListOf<ByteArray>()
        for (row in relayQueueDao.getAll()) {
            if (relayPolicy.isExpired(row.originatedAtMs, row.ttlSeconds)) {
                relayQueueDao.delete(row.clipHash)
                continue
            }
            if (!relayPolicy.isEligibleForRelay(row.hopCount, row.dontRelay, row.originatedAtMs, row.ttlSeconds)) {
                continue
            }
            val storedFrame = Frame.decode(row.encodedFrame)
            val outgoingFrame = storedFrame.copy(hopCount = storedFrame.hopCount + 1)
            outgoing.add(WireEnvelope.encode(WirePayloadType.POST_FRAME, outgoingFrame.encode()))
        }
        return outgoing
    }

    private fun ByteArray.toHexString(): String = joinToString(separator = "") { "%02x".format(it) }
}
