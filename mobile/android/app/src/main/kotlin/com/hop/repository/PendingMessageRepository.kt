package com.hop.repository

import com.hop.data.PendingMessageDao
import com.hop.data.PendingMessageEntity
import com.hop.protocol.MessageCiphertextEnvelope
import com.hop.protocol.RelayPolicy
import com.hop.protocol.WireEnvelope
import com.hop.protocol.WirePayloadType
import java.security.MessageDigest

/**
 * Wraps [PendingMessageDao] plus [RelayPolicy]'s pure eligibility rules --
 * the persisted relay-custody queue Phase 2 Slice 3 adds for offline 1:1
 * message recipients (see [PendingMessageEntity]'s own doc). Mirrors
 * [RelayRepository]'s shape deliberately: `open class` over a DAO + policy
 * object (not `Context`-coupled), so a JVM unit test can construct this
 * directly against a hand-rolled fake [PendingMessageDao], same testability
 * seam [RelayRepository] already uses.
 *
 * [RelayPolicy] is reused as-is, not subclassed or reimplemented -- it is
 * already generic over hop-count/expiry primitives with no `Frame`-specific
 * coupling (see its own doc). Every call site here passes `dontRelay = false`
 * unconditionally: private messages have no community-flagging concept (see
 * [PendingMessageEntity]'s own doc for why this table is deliberately
 * separate from [com.hop.data.RelayQueueEntity], which does carry one).
 *
 * **This class never decrypts and never inspects the message's own
 * plaintext, stronger than [RelayRepository]'s own "never decrypts, never
 * inspects payload" framing for posts:** a device holding a row here has *no
 * Signal Protocol session for this conversation on-device at all* -- it
 * structurally cannot decrypt even if the code tried, since decrypting a
 * Double Ratchet ciphertext requires session state this device never had any
 * reason to create (it isn't a party to the conversation). Only the one
 * dispatch branch in `com.hop.transport.EnvelopeDispatcher` that matches
 * `recipientPeerId == this device's own peer id` ever touches `crypto/` for
 * a message envelope -- see that class's own doc.
 *
 * ## Limits (co-located per this repo's established disclosure style, see
 * [RelayRepository]/`com.hop.repository.DontRelayRepository`'s own "Limits"
 * sections)
 *
 * - **Metadata exposure**: an intermediate relay-carrying device learns
 *   [PendingMessageEntity.recipientPeerId] and (from the decoded envelope)
 *   `senderPeerId` for a specific conversation it isn't part of, for as long
 *   as it holds custody (up to [MessageCiphertextEnvelope.DEFAULT_TTL_SECONDS]).
 *   Content stays opaque (ciphertext only), but the *pairing* is new
 *   information a carrier didn't have for post relay (which only reveals
 *   "someone posted something," never who's talking to whom). This is
 *   analogous to any store-and-forward network exposing routing metadata (a
 *   postal worker sees to/from addresses) -- a real, bounded, stated cost,
 *   not a new category of harm, matching ADR 0003/0004's "deterrent, not
 *   guarantee" posture throughout this codebase.
 * - **Redundant/duplicate delivery**: a message can reach the true recipient
 *   more than once, via different relay paths that converge at different
 *   times, or before `com.hop.transport.WifiDirectTransport`'s local
 *   delivery-confirmation stop signal has a chance to fire. This is safe --
 *   `MessageRepository.onEnvelopeReceived`'s existing broad exception catch
 *   already absorbs libsignal's `DuplicateMessageException` on a genuine
 *   replay (confirmed by reading libsignal's own source, not assumed from
 *   this codebase's doc comments alone) -- but not eliminated, only made less
 *   frequent by that stop signal.
 * - **No bundle-relay**: this slice only helps an *already-established*
 *   session stay reachable once its two devices are no longer in
 *   simultaneous direct contact -- it does not solve first-contact messaging
 *   with a peer this device has never directly connected to (prekey bundles
 *   are still only exchanged via direct-connection ambient announcement,
 *   unchanged). Named future follow-up, not solved here.
 */
open class PendingMessageRepository(
    private val dao: PendingMessageDao,
    private val relayPolicy: RelayPolicy,
) {

    /**
     * Takes custody of [envelope] iff [RelayPolicy] still allows further
     * relay of it (hop bound not exceeded, not already expired against
     * [MessageCiphertextEnvelope.DEFAULT_TTL_SECONDS]) -- a no-op (returns
     * `null`) if ineligible. [envelope]'s own [MessageCiphertextEnvelope.hopCount]
     * is stored as-is (the hop count *this device* received it at, mirroring
     * [RelayQueueEntity.hopCount]'s doc); incrementing happens only when a
     * copy is actually sent onward, in [buildOutgoingBacklog].
     *
     * Checks [PendingMessageDao.getByHash] before inserting so the caller can
     * be told which case it was; [PendingMessageDao.insert]'s own `IGNORE`
     * conflict strategy is a defensive backstop against a race between that
     * check and the insert (mirroring [RelayQueueDao.insert]'s own doc), not
     * the primary de-dup mechanism here. Either way, calling this more than
     * once for the same [PendingMessageEntity.ciphertextHash] (a redundant
     * offer from a second peer, or a reconnect) is harmless -- first custody
     * wins.
     *
     * Returns the inserted row on genuine new custody (never on a dup or an
     * ineligible envelope) so a caller (e.g.
     * `com.hop.transport.WifiDirectTransport.sendMessage`) can build the
     * `hopCount + 1` outgoing copy without a second DAO query.
     */
    open suspend fun considerForRelay(envelope: MessageCiphertextEnvelope): PendingMessageEntity? {
        if (!relayPolicy.isEligibleForRelay(
                storedHopCount = envelope.hopCount,
                dontRelay = false,
                originatedAtMs = envelope.originatedAtMs,
                ttlSeconds = MessageCiphertextEnvelope.DEFAULT_TTL_SECONDS,
            )
        ) {
            return null
        }

        val hash = envelope.ciphertext.sha256Hex()
        if (dao.getByHash(hash) != null) {
            // Already-held custody -- a redundant offer from a second peer,
            // or the same peer reconnecting. Not genuine new custody.
            return null
        }
        val row = PendingMessageEntity(
            ciphertextHash = hash,
            recipientPeerId = envelope.recipientPeerId,
            encodedEnvelope = envelope.encode(),
            hopCount = envelope.hopCount,
            originatedAtMs = envelope.originatedAtMs,
            receivedAtMs = System.currentTimeMillis(),
        )
        dao.insert(row)
        return row
    }

    /**
     * Every non-expired, hop-eligible row, each decoded, re-encoded with
     * `hopCount + 1` (the hop count a peer this device is about to send to
     * will receive it at), and [WireEnvelope]-wrapped as a
     * [WirePayloadType.MESSAGE_CIPHERTEXT] envelope ready to flood-offer --
     * the same shape [RelayRepository.buildOutgoingBacklog] produces for
     * posts.
     *
     * Lazily prunes expired rows encountered here as a side effect -- same
     * posture as [RelayRepository.buildOutgoingBacklog]'s own "expiry
     * enforced on read, not by a background sweep." A row that's simply past
     * the hop-count bound is left in place (legitimately received/stored;
     * only its *further* propagation stopped), not deleted.
     */
    open suspend fun buildOutgoingBacklog(): List<ByteArray> {
        val outgoing = mutableListOf<ByteArray>()
        for (row in dao.getAll()) {
            if (relayPolicy.isExpired(row.originatedAtMs, MessageCiphertextEnvelope.DEFAULT_TTL_SECONDS)) {
                dao.delete(row.ciphertextHash)
                continue
            }
            if (!relayPolicy.isEligibleForRelay(
                    storedHopCount = row.hopCount,
                    dontRelay = false,
                    originatedAtMs = row.originatedAtMs,
                    ttlSeconds = MessageCiphertextEnvelope.DEFAULT_TTL_SECONDS,
                )
            ) {
                continue
            }
            val storedEnvelope = MessageCiphertextEnvelope.decode(row.encodedEnvelope)
            val outgoingEnvelope = storedEnvelope.copy(hopCount = storedEnvelope.hopCount + 1)
            outgoing.add(WireEnvelope.encode(WirePayloadType.MESSAGE_CIPHERTEXT, outgoingEnvelope.encode()))
        }
        return outgoing
    }

    /**
     * Every currently-held row addressed to [recipientPeerId] -- used by
     * `com.hop.transport.WifiDirectTransport`'s local delivery-confirmation
     * stop signal (see that class's own doc) once a connection's remote peer
     * id has been positively identified as [recipientPeerId], so it can be
     * handed directly over that connection and, on success, deleted via
     * [delete].
     */
    open suspend fun findByRecipient(recipientPeerId: String): List<PendingMessageEntity> =
        dao.getAll().filter { it.recipientPeerId == recipientPeerId }

    /**
     * Deletes the row keyed by [ciphertextHash] -- called only after this
     * device has directly, successfully handed the message off to the
     * connection it identified as the intended recipient (a self-observed
     * fact, never a claim asserted by another device -- see
     * `com.hop.transport.WifiDirectTransport`'s own "local stop signal" doc
     * for why that distinction matters: a forged "delivered" flag from a
     * hostile peer would otherwise let it cause premature deletion of a real
     * carrier's copy).
     */
    open suspend fun delete(ciphertextHash: String) = dao.delete(ciphertextHash)

    private fun ByteArray.sha256Hex(): String =
        MessageDigest.getInstance("SHA-256").digest(this).joinToString(separator = "") { "%02x".format(it) }
}
