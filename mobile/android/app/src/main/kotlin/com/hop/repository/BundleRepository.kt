package com.hop.repository

import com.hop.data.BundleQueueDao
import com.hop.data.BundleQueueEntity
import com.hop.protocol.PreKeyBundleEnvelope
import com.hop.protocol.RelayPolicy
import com.hop.protocol.WireEnvelope
import com.hop.protocol.WirePayloadType

/**
 * Wraps [BundleQueueDao] plus [RelayPolicy]'s pure eligibility rules -- the
 * persisted prekey-bundle mesh flood relay queue for the prekey-bundle
 * relay/discovery follow-up to Phase 2's four original slices (post relay,
 * "don't relay" flags, 1:1 store-and-forward, group messaging). Mirrors
 * [PendingMessageRepository]'s shape deliberately: `open class` over a DAO +
 * policy object (not `Context`-coupled), so a JVM unit test can construct
 * this directly against a hand-rolled fake [BundleQueueDao], same
 * testability seam [PendingMessageRepository]/[RelayRepository] already use.
 *
 * **What this closes, with zero changes to `MessageRepository`'s group
 * logic**: two group members who've each only met the creator, never each
 * other, already recognize each other as legitimate group members from the
 * `GroupInvite` fan-out at creation time (`GroupDao.isKnownMember`) -- the
 * only missing ingredient was ever being able to *reach* each other's prekey
 * bundle at all, since bundles were previously only exchanged via direct
 * radio contact (`com.hop.transport.WifiDirectTransport.announceOwnPreKeyBundle`
 * ambient exchange). `MessageRepository.cachePeerBundle` already populates
 * identically regardless of how a `PREKEY_BUNDLE` envelope arrives -- flood
 * relay just gives it more chances to arrive. This is a deliberately
 * different design from creator-mediated bundle relay (which `MessageRepository`'s
 * own class doc names and rejects as a real MITM risk): general flood relay
 * has no single trusted intermediary a compromised/malicious relay could
 * exploit to substitute its own bundle for a member's real one -- every
 * relayed copy is still the bundle the owning device itself signed and
 * announced, only ever carried, never generated or altered, by an
 * intermediate hop (opaque `bundleBytes`, unchanged end to end).
 *
 * ## Limits (co-located per this repo's established disclosure style, see
 * [PendingMessageRepository]/[RelayRepository]/`com.hop.repository.DontRelayRepository`'s
 * own "Limits" sections)
 *
 * - **Wider exposure to this app's pre-existing TOFU-only trust model.** No
 *   safety-number verification exists anywhere in this app today (confirmed
 *   by reading `RoomSignalProtocolStore.isTrustedIdentity`/`saveIdentity`:
 *   first-contact identity trust is always silent trust-on-first-use,
 *   whether a bundle arrived via direct radio contact or via relay). This
 *   isn't a *new* category of risk this class introduces -- the underlying
 *   weak trust model already existed for every direct exchange -- but relay
 *   meaningfully widens the pool of device-pairs it applies to, from
 *   "physically-met pairs" to "anyone reachable via mesh flood." State this
 *   plainly; don't imply relay is risk-neutral.
 * - **Staleness fails safely already, verified by tracing the actual
 *   libsignal-client 0.86.5 call chain, not assumed:** `DoubleRatchetSession.initiate`
 *   never looks up prekey ids against any store -- the bundle carries its
 *   keys inline, only signature-checked -- so a stale (already-
 *   consumed/pruned) bundle always "succeeds" at `initiate()` time. The
 *   failure surfaces later, cleanly, on the *bundle owner's own device* when
 *   it tries to decrypt the resulting first message:
 *   `RoomSignalProtocolStore.loadPreKey`/`loadSignedPreKey`/`loadKyberPreKey`
 *   throw `InvalidKeyIdException` for a consumed/pruned id, which
 *   `MessageRepository.onEnvelopeReceived`'s existing broad exception catch
 *   already logs and drops -- no plaintext exposure, no downgrade, no
 *   forward-secrecy break. This is exactly why this class implements **no
 *   new staleness-gating logic** beyond ordinary TTL/hop relay eligibility:
 *   the crypto layer already fails this case safely on its own.
 */
open class BundleRepository(
    private val dao: BundleQueueDao,
    private val relayPolicy: RelayPolicy,
) {

    /**
     * Takes relay custody of [envelope] iff [RelayPolicy] still allows
     * further relay of it (hop bound not exceeded, not already expired
     * against [PreKeyBundleEnvelope.DEFAULT_TTL_SECONDS]) **and** it's
     * actually fresher than any row already held for [PreKeyBundleEnvelope.peerId]
     * -- a no-op (returns `null`) otherwise. Unlike every other relay
     * repository in this codebase, "already holding a row for this key" is
     * not itself a reason to reject -- see [BundleQueueEntity]'s own doc for
     * why a bundle needs conditional-replace, not first-custody-wins.
     *
     * `@Transaction`-guarded (a real Room transaction, not just
     * `@Synchronized`) because two connections in a dense-venue burst could
     * call this concurrently, on different threads, for the same [peerId] --
     * without a transaction spanning the read-compare-write below, two
     * concurrent calls could both read the same stale "existing" row, both
     * decide they're fresher than it, and both write, in which case the
     * *last* write wins by raw timing rather than by which envelope is
     * actually freshest. Wrapping read+compare+write in one transaction
     * closes that race.
     */
    @androidx.room.Transaction
    open suspend fun considerForRelay(envelope: PreKeyBundleEnvelope): BundleQueueEntity? {
        if (!relayPolicy.isEligibleForRelay(
                storedHopCount = envelope.hopCount,
                dontRelay = false,
                originatedAtMs = envelope.originatedAtMs,
                ttlSeconds = PreKeyBundleEnvelope.DEFAULT_TTL_SECONDS,
            )
        ) {
            return null
        }
        val existing = dao.getByPeerId(envelope.peerId)
        if (existing != null && existing.originatedAtMs >= envelope.originatedAtMs) {
            // Fresher-or-equal already held -- a stale, out-of-order
            // arrival. Must never clobber an already-fresher cached bundle.
            return null
        }
        val row = BundleQueueEntity(
            peerId = envelope.peerId,
            encodedEnvelope = envelope.encode(),
            hopCount = envelope.hopCount,
            originatedAtMs = envelope.originatedAtMs,
            receivedAtMs = System.currentTimeMillis(),
        )
        dao.insertOrReplace(row)
        return row
    }

    /**
     * Every non-expired, hop-eligible row, each decoded, re-encoded with
     * `hopCount + 1` (the hop count a peer this device is about to send to
     * will receive it at), and [WireEnvelope]-wrapped as a
     * [WirePayloadType.PREKEY_BUNDLE] envelope ready to flood-offer -- the
     * same shape [PendingMessageRepository.buildOutgoingBacklog] produces for
     * messages and [RelayRepository.buildOutgoingBacklog] produces for posts.
     *
     * Lazily prunes expired rows encountered here as a side effect -- same
     * posture as those two repositories' own "expiry enforced on read, not
     * by a background sweep." A row that's simply past the hop-count bound
     * is left in place (legitimately received/stored; only its *further*
     * propagation stopped), not deleted.
     */
    open suspend fun buildOutgoingBacklog(): List<ByteArray> {
        val outgoing = mutableListOf<ByteArray>()
        for (row in dao.getAll()) {
            if (relayPolicy.isExpired(row.originatedAtMs, PreKeyBundleEnvelope.DEFAULT_TTL_SECONDS)) {
                dao.delete(row.peerId)
                continue
            }
            if (!relayPolicy.isEligibleForRelay(
                    storedHopCount = row.hopCount,
                    dontRelay = false,
                    originatedAtMs = row.originatedAtMs,
                    ttlSeconds = PreKeyBundleEnvelope.DEFAULT_TTL_SECONDS,
                )
            ) {
                continue
            }
            val storedEnvelope = PreKeyBundleEnvelope.decode(row.encodedEnvelope)
            val outgoingEnvelope = storedEnvelope.copy(hopCount = storedEnvelope.hopCount + 1)
            outgoing.add(WireEnvelope.encode(WirePayloadType.PREKEY_BUNDLE, outgoingEnvelope.encode()))
        }
        return outgoing
    }
}
