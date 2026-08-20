package com.hop.repository

import android.util.Log
import com.hop.crypto.DoubleRatchetSession
import com.hop.crypto.MessagePayload
import com.hop.crypto.PreKeyBundleCodec
import com.hop.data.GroupDao
import com.hop.data.GroupEntity
import com.hop.data.GroupMemberEntity
import com.hop.data.GroupMessageDao
import com.hop.data.GroupMessageEntity
import com.hop.data.MessageDao
import com.hop.data.MessageEntity
import com.hop.data.SignalIdentityDao
import com.hop.data.peerSignalAddress
import com.hop.data.toPeerIdHex
import com.hop.protocol.MessageCiphertextEnvelope
import com.hop.protocol.WirePayloadType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.SignalProtocolStore
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * A conversation-list row: the latest message exchanged with one peer. No
 * display-name concept exists anywhere in this app (no accounts, by design)
 * -- callers label conversations with a short fragment of [peerId] (e.g. its
 * first 6 hex chars), not attempted here since that's UI-layer formatting
 * (Stage 4's job, not this repository's).
 */
data class ConversationSummary(
    val peerId: String,
    val lastMessagePreview: String,
    val lastMessageAtMs: Long,
    val lastMessageWasOutgoing: Boolean,
)

/**
 * A group conversation-list row, the group analog of [ConversationSummary] --
 * see [MessageRepository.observeGroupSummaries]'s doc for how it's built and
 * `com.hop.app.inbox.ConversationListViewModel`'s doc for how it's combined
 * with [ConversationSummary] into one sorted Inbox list.
 */
data class GroupSummary(
    val groupId: String,
    val name: String,
    val lastMessagePreview: String,
    val lastMessageAtMs: Long,
    val lastMessageWasOutgoing: Boolean,
)

/** Outcome of [MessageRepository.send] -- see each case's doc for what it means and what the caller should do. */
sealed interface SendResult {
    /**
     * Encryption and local persistence succeeded. This does **not** mean the
     * message reached the peer live -- [MessageRepository.send]'s own doc
     * covers exactly what "sent" does and doesn't guarantee. As of Phase 2
     * Slice 3, a recipient not directly connected right now is not simply
     * dropped: `WifiDirectTransport.sendMessage` falls back to a persisted
     * relay-custody queue (`PendingMessageRepository`, mirroring
     * `RelayRepository`'s own shape for posts) and floods the message to
     * every currently-connected peer, so an intermediate device can carry it
     * onward. This still isn't a delivery guarantee (no ack, best-effort,
     * bounded by `MessageCiphertextEnvelope.DEFAULT_TTL_SECONDS`) -- see
     * `PendingMessageRepository`'s own "Limits" doc.
     */
    data object Sent : SendResult

    /** [MessageRepository.send]'s `peerId` is in the local block list -- nothing was encrypted, sent, or persisted. */
    data object Blocked : SendResult

    /**
     * No live Double Ratchet session exists with this peer yet, and no
     * prekey bundle has ever been cached for them either (never connected,
     * or connected before this device had a bundle to receive) -- there is
     * no cryptographic material to encrypt against. Nothing was sent or
     * persisted. The plan's named UX for this ("you'll be able to message
     * once you're near this person again") belongs to Stage 4; this is the
     * signal that UX reacts to.
     */
    data object NoSessionAvailable : SendResult
}

/**
 * Wraps [MessageDao] + a persistent [SignalProtocolStore] (`RoomSignalProtocolStore`
 * in production) + [BlockRepository] + a peer-targeted transport send
 * function, to turn plaintext chat messages into Double Ratchet ciphertext
 * on the wire and back.
 *
 * As of Phase 2 Slice 4, also wraps [GroupDao]/[GroupMessageDao] for group
 * messaging -- per-member pairwise Double Ratchet fan-out (PRD §4.3, ADR
 * 0001), **not** a shared group ratchet (`RoomSignalProtocolStore.storeSenderKey`/
 * `loadSenderKey`, libsignal's real group-ratchet primitives, are `throw
 * UnsupportedOperationException` specifically to forbid that from ever landing
 * here). [createGroup]/[sendToGroup] both funnel their actual per-member
 * encrypt+transmit through [encryptAndTransmit], the same primitive [send]
 * itself uses -- one shared block-check+session+encrypt+transmit path,
 * persistence handled separately by each caller.
 *
 * **Known scope limit, stated here rather than implied away**: v1 group
 * fan-out requires the *sending* member to already have a direct 1:1 Double
 * Ratchet session with every *other* member it's fanning out to. "Only create
 * a group from existing 1:1 contacts" only guarantees creator<->member
 * sessions exist, not member<->member -- two members who've each only met the
 * creator, and never each other, may never actually exchange a group message
 * with one another (see [sendToGroup]'s own doc for exactly what happens in
 * that case). There is no serverless way to safely introduce two members
 * within this slice's scope (relaying prekey bundles through the creator would
 * reintroduce a real MITM risk, a `crypto/`-grade decision out of scope here)
 * -- see `GroupCreateScreen`'s copy for the plain-language version of this
 * limitation shown to users.
 *
 * [sendMessage] and [getOwnPeerId] are threaded in as narrow function
 * parameters (mirroring `PostComposerViewModel`'s established pattern for
 * exactly this reason) rather than the whole `TransportManager`/
 * `SettingsRepository` -- keeps this class constructible against trivial
 * fakes without an Android `Context`.
 *
 * Prekey bundles announced by peers are cached **in-memory only**
 * ([cachedPeerBundles]) -- session-scoped, not persisted across a process
 * restart. This is a named, deliberate MVP simplification (Phase 1 messaging plan):
 * after a process restart, a cached bundle is gone until the peer's next
 * ambient re-announcement on a fresh connection.
 *
 * **All libsignal/Room calls in [send]/[onEnvelopeReceived]/[createGroup]/
 * [sendToGroup] run on [ioDispatcher]** (defaults to [Dispatchers.IO],
 * injectable for tests) -- matching `PostComposerViewModel`'s fix for a real
 * main-thread-Room crash found this session; that class of bug is not being
 * reintroduced here. [cachePeerBundle] is the one exception: it is only ever
 * invoked by `WifiDirectTransport`'s receive loop, which already runs on a
 * dedicated background thread (never the main thread) by construction, and
 * does no Room I/O (only in-memory map + libsignal object construction) --
 * see its own doc.
 */
open class MessageRepository(
    private val messageDao: MessageDao,
    private val signalIdentityDao: SignalIdentityDao,
    private val signalProtocolStore: SignalProtocolStore,
    private val blockRepository: BlockRepository,
    private val groupDao: GroupDao,
    private val groupMessageDao: GroupMessageDao,
    private val getOwnPeerId: suspend () -> String,
    /**
     * Phase 2 Slice 3: delegates to `WifiDirectTransport.sendMessage`'s
     * unicast-first, flood-on-failure send (not the older peer-targeted-only
     * `sendToPeer`, still used elsewhere for prekey bundles). Takes just
     * `(peerId, payload)`, not a `WirePayloadType` -- unlike `sendToPeer`,
     * this lambda's only caller ([send]) ever sends exactly one type
     * ([WirePayloadType.MESSAGE_CIPHERTEXT]), so there's nothing for a type
     * parameter to select between.
     */
    private val sendMessage: (peerId: String, payload: ByteArray) -> Boolean,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val cachedPeerBundles = ConcurrentHashMap<String, PreKeyBundle>()

    open fun observeConversation(peerId: String): Flow<List<MessageEntity>> = messageDao.getMessagesForPeer(peerId)

    /** The group analog of [observeConversation] -- backs `GroupConversationDetailViewModel`'s message list. */
    open fun observeGroupConversation(groupId: String): Flow<List<GroupMessageEntity>> =
        groupMessageDao.getMessagesForGroup(groupId)

    /**
     * True whenever [peerId] has a pending, not-yet-trusted identity key
     * change ([RemoteIdentityEntity.pendingIdentityKeyBytes] is non-null) --
     * the UI-facing signal for the plain-language "this person's messaging
     * details changed" warning (PRD §5's mesh-invisibility mandate rules out
     * any "identity key"/"safety number" language in what's actually shown).
     * Sourced from [SignalIdentityDao.observeRemoteIdentity], not
     * [signalProtocolStore] -- `SignalProtocolStore` has no query surface of
     * its own for this, by design (it's a UI concern layered on top of the
     * trust-on-first-use mechanism, not part of libsignal-client's
     * contract). See [trustChangedIdentity] for the corresponding "dismiss
     * and continue" action.
     */
    open fun observeIdentityChangeWarning(peerId: String): Flow<Boolean> =
        signalIdentityDao.observeRemoteIdentity(peerId).map { it?.pendingIdentityKeyBytes != null }

    /**
     * Promotes [peerId]'s pending, not-yet-trusted identity key (recorded by
     * [com.hop.data.RoomSignalProtocolStore.isTrustedIdentity] the moment it
     * detected a mismatch) into the actually-trusted key, then drops any
     * session built against the old identity so the next [send]/
     * [onEnvelopeReceived] starts a clean handshake against the new one --
     * an old session's ratchet state has no valid continuation once the far
     * side's identity has changed underneath it. A no-op if [peerId] has no
     * pending change (nothing to promote).
     *
     * Deliberately does **not** touch [cachedPeerBundles]: the pending key
     * this promotes is the identity key already carried by whatever bundle
     * is currently cached for [peerId] (that's *why* the mismatch was
     * detected in the first place -- see [com.hop.data.RoomSignalProtocolStore.isTrustedIdentity]'s
     * doc), so the same cached bundle becomes usable immediately once
     * trusted, no fresh bundle announcement required.
     *
     * [pendingIdentityKeyBytes] is only ever written by [com.hop.data.RoomSignalProtocolStore.isTrustedIdentity]
     * from an already-validated `IdentityKey`'s own `.serialize()` output, so
     * `IdentityKey(pendingKeyBytes)` below should never actually throw in
     * real operation -- but this is called directly from a UI button tap
     * ([com.hop.app.inbox.ConversationDetailViewModel.trustChangedIdentity]),
     * so it's wrapped rather than left to crash the whole app on any future
     * change that breaks that invariant, matching [send]/[onEnvelopeReceived]'s
     * own "log and drop, never throw" posture for libsignal-touching calls.
     */
    open suspend fun trustChangedIdentity(peerId: String) = withContext(ioDispatcher) {
        val entity = signalIdentityDao.getRemoteIdentity(peerId) ?: return@withContext
        val pendingKeyBytes = entity.pendingIdentityKeyBytes ?: return@withContext

        try {
            signalProtocolStore.saveIdentity(peerSignalAddress(peerId), IdentityKey(pendingKeyBytes))
            signalProtocolStore.deleteAllSessions(peerId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to trust peer $peerId's changed identity key", e)
            return@withContext
        }
        signalIdentityDao.clearIdentityChangePending(peerId)
    }

    open fun observeConversationSummaries(): Flow<List<ConversationSummary>> =
        messageDao.getLatestMessagePerPeer().map { latestPerPeer ->
            latestPerPeer.map { message ->
                ConversationSummary(
                    peerId = message.peerId,
                    lastMessagePreview = message.plaintext,
                    lastMessageAtMs = message.sentAtMs,
                    lastMessageWasOutgoing = message.isOutgoing,
                )
            }
        }

    /**
     * The group analog of [observeConversationSummaries] -- one row per group
     * with at least one [GroupMessageEntity] (a group with no messages yet
     * doesn't have a "latest message" to summarize; `GroupCreateScreen`
     * navigates straight into the new group's conversation rather than relying
     * on it appearing in this list first). Joins [GroupMessageDao.getLatestMessagePerGroup]
     * against [GroupDao.observeGroups] purely for each group's display
     * [GroupEntity.name] -- no accounts/display-name system exists for peers
     * ([ConversationSummary]'s own doc), but a group's name is a real,
     * locally-stored field set at creation time, unlike a peer id.
     */
    open fun observeGroupSummaries(): Flow<List<GroupSummary>> =
        combine(groupMessageDao.getLatestMessagePerGroup(), groupDao.observeGroups()) { latestPerGroup, groups ->
            val namesByGroupId = groups.associateBy(GroupEntity::groupId)
            latestPerGroup.mapNotNull { message ->
                val group = namesByGroupId[message.groupId] ?: return@mapNotNull null
                GroupSummary(
                    groupId = message.groupId,
                    name = group.name,
                    lastMessagePreview = message.plaintext,
                    lastMessageAtMs = message.sentAtMs,
                    lastMessageWasOutgoing = message.isOutgoing,
                )
            }
        }

    /**
     * Decodes and caches [bundleBytes] (a [WirePayloadType.PREKEY_BUNDLE]
     * envelope's opaque payload) as this device's record of [peerId]'s
     * current prekey bundle -- called from `WifiDirectTransport`'s receive
     * loop every time a peer announces one, per-process, in-memory only (see
     * this class's own doc). A malformed/corrupt bundle is logged and
     * dropped, not thrown -- matching `WifiDirectTransport`'s own "one bad
     * envelope doesn't kill the receive loop" posture.
     *
     * Not `suspend` and does no dispatcher switch: this is only ever called
     * from `WifiDirectTransport`'s dedicated background receive thread
     * (never the main thread), and does no Room I/O -- just an in-memory map
     * write plus constructing plain libsignal value objects from bytes
     * already in hand.
     */
    open fun cachePeerBundle(peerId: String, bundleBytes: ByteArray) {
        try {
            cachedPeerBundles[peerId] = PreKeyBundleCodec.decode(bundleBytes)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode prekey bundle announced by peer $peerId", e)
        }
    }

    /**
     * Shared block-check + session lookup/establish + encrypt + transmit
     * primitive behind [send], [createGroup]'s `GroupInvite` fan-out, and
     * [sendToGroup]'s per-member fan-out -- the one place any of them
     * actually touches [signalProtocolStore]/[DoubleRatchetSession]/[sendMessage].
     * Does **no persistence** of its own: [send] persists exactly one
     * [MessageEntity] row, [sendToGroup] persists exactly one
     * [GroupMessageEntity] row regardless of how many members this was called
     * for, and [createGroup]'s own [GroupEntity]/[GroupMemberEntity] rows are
     * written before any fan-out happens at all -- each caller owns its own
     * persistence, this method owns none of it.
     *
     * Session handling (unchanged from [send]'s original inline logic): if
     * [signalProtocolStore] already has a session with [peerId], reuses it via
     * [DoubleRatchetSession.forIncoming] rather than [DoubleRatchetSession.initiate]
     * -- `initiate` explicitly starts a *fresh* session from a bundle via
     * `SessionBuilder.process`, which would discard live ratchet state.
     * `forIncoming` does no handshake step at all (confirmed by reading
     * [DoubleRatchetSession]'s own implementation, not assumed from its name)
     * -- it just wires a wrapper around [signalProtocolStore]/the peer's
     * address, exactly what's needed here whether the session was originally
     * established as the initiator or the responder. Only when no session
     * exists yet does this fall back to a cached bundle +
     * [DoubleRatchetSession.initiate] to start one.
     *
     * Returns `false` (nothing sent) if [peerId] is currently blocked, if no
     * live session exists and no prekey bundle has ever been cached for
     * [peerId] either, or if session establishment from a cached bundle
     * fails -- the exact three "can't reach this peer right now" cases [send]
     * itself used to distinguish inline, now collapsed into one boolean since
     * a per-member fan-out call site (unlike a direct 1:1 [send]) has no
     * user-facing reason to distinguish *why* one specific member was
     * unreachable -- "this member was skipped" is the only outcome
     * [sendToGroup]/[createGroup] need to surface (see [sendToGroup]'s own doc
     * for the member-introduction limitation this "silently skip" posture is
     * most consequential for).
     */
    private suspend fun encryptAndTransmit(peerId: String, payloadBytes: ByteArray): Boolean {
        val blockedIds = blockRepository.observeBlockedSenderIds().first()
        if (peerId in blockedIds) {
            return false
        }

        val address = peerSignalAddress(peerId)
        val session = if (signalProtocolStore.containsSession(address)) {
            DoubleRatchetSession.forIncoming(signalProtocolStore, address)
        } else {
            val bundle = cachedPeerBundles[peerId] ?: return false
            try {
                DoubleRatchetSession.initiate(signalProtocolStore, address, bundle)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initiate a session with peer $peerId from its cached bundle", e)
                return false
            }
        }

        val ciphertext = session.encrypt(payloadBytes)
        val envelope = MessageCiphertextEnvelope(
            senderPeerId = getOwnPeerId(),
            recipientPeerId = peerId,
            hopCount = 0,
            originatedAtMs = System.currentTimeMillis(),
            ciphertext = ciphertext,
        )
        if (!sendMessage(peerId, envelope.encode())) {
            Log.d(
                TAG,
                "Peer $peerId not currently connected -- message encrypted and handed to the " +
                    "relay-custody/flood fallback (Phase 2 Slice 3); this is a real, expected " +
                    "\"can't deliver live right now\" outcome, not a bug, and still not a delivery " +
                    "guarantee -- see PendingMessageRepository's own \"Limits\" doc.",
            )
        }
        return true
    }

    /**
     * Encrypts [plaintext] for [peerId] via [encryptAndTransmit] and, on
     * success, optimistically inserts an outgoing [MessageEntity]
     * **regardless of whether [peerId] is currently connected to receive it
     * live** -- this device's own local history reflects "I sent this," not
     * "this was delivered," the same distinction Phase 1's Feed posting
     * already makes for posts. Encodes `plaintext` as a [MessagePayload.Text]
     * with `groupId = null` -- today's unchanged plain 1:1 shape.
     *
     * Returns [SendResult.Blocked] without encrypting or persisting anything
     * if [peerId] is in [blockRepository]'s block list, and
     * [SendResult.NoSessionAvailable] (also without persisting anything) if
     * no session exists and no bundle has ever been cached for [peerId] --
     * see [SendResult]'s own doc for what each case means to a caller.
     * External behavior/signature is unchanged from before this method's
     * [encryptAndTransmit] extraction (Phase 2 Slice 4).
     */
    open suspend fun send(peerId: String, plaintext: String): SendResult = withContext(ioDispatcher) {
        val blockedIds = blockRepository.observeBlockedSenderIds().first()
        if (peerId in blockedIds) {
            return@withContext SendResult.Blocked
        }

        val address = peerSignalAddress(peerId)
        if (!signalProtocolStore.containsSession(address) && !cachedPeerBundles.containsKey(peerId)) {
            return@withContext SendResult.NoSessionAvailable
        }

        val payloadBytes = MessagePayload.Text(groupId = null, text = plaintext).encode()
        if (!encryptAndTransmit(peerId, payloadBytes)) {
            // Only reachable here if session establishment itself failed
            // (e.g. a corrupt cached bundle) despite the pre-check above
            // passing -- encryptAndTransmit already logged the specific
            // cause; NoSessionAvailable is still the closest/only applicable
            // SendResult for a caller to react to.
            return@withContext SendResult.NoSessionAvailable
        }

        messageDao.insert(
            MessageEntity(
                peerId = peerId,
                plaintext = plaintext,
                sentAtMs = System.currentTimeMillis(),
                isOutgoing = true,
            ),
        )

        SendResult.Sent
    }

    /**
     * Creates a local group named [name] from [memberPeerIds] (must already
     * be 1:1 contacts this device has messaged before -- `GroupCreateScreen`
     * only offers [observeConversationSummaries]'s peers as candidates;
     * nothing here re-checks that at the repository layer), then fans out a
     * [MessagePayload.GroupInvite] to each of them via [encryptAndTransmit] --
     * an offline member's copy rides the same store-and-forward fallback
     * [send] already gets, for free.
     *
     * This device's own peer id becomes the new group's
     * [GroupEntity.creatorPeerId] -- see that field's own doc for what this
     * pin is for. The local [GroupMemberEntity] rows created here are exactly
     * [memberPeerIds] (this device's own id is never one of them -- see
     * [GroupMemberEntity]'s class doc).
     *
     * Always returns the new [GroupEntity.groupId], even if every invite
     * fan-out below fails to reach its member live -- matches [send]'s own
     * "local state reflects intent, not delivery" posture; an offline member
     * still gets a store-and-forward-queued invite via [encryptAndTransmit],
     * and a member with no session or cached bundle at all yet is silently
     * skipped (see [sendToGroup]'s own doc for why that's expected, documented
     * behavior here too, not a bug to raise to the caller).
     */
    open suspend fun createGroup(name: String, memberPeerIds: List<String>): String = withContext(ioDispatcher) {
        val groupId = generateGroupId()
        val ownPeerId = getOwnPeerId()

        groupDao.insertGroupWithMembers(
            GroupEntity(groupId = groupId, name = name, creatorPeerId = ownPeerId, createdAtMs = System.currentTimeMillis()),
            memberPeerIds.map { memberPeerId -> GroupMemberEntity(groupId = groupId, peerId = memberPeerId) },
        )

        val invitePayload = MessagePayload.GroupInvite(groupId = groupId, name = name, memberPeerIds = memberPeerIds).encode()
        memberPeerIds.forEach { memberPeerId ->
            encryptAndTransmit(memberPeerId, invitePayload)
        }

        groupId
    }

    /**
     * Fans [text] out to every member of [groupId] this device currently has
     * a session with ([GroupDao.getFanoutTargetPeerIds] + [encryptAndTransmit],
     * one independent Double Ratchet-encrypted message per member -- **not** a
     * shared group key or group-wide ratchet, see this class's own doc). A
     * member this device has no session with -- and no cached bundle for
     * either -- is **silently skipped**: the direct, intentional consequence
     * of the member-introduction limitation named in this class's own doc.
     * "Only create a group from existing 1:1 contacts" only guarantees
     * creator<->member sessions exist, not member<->member -- two members
     * who've each only met the creator, and never each other, may never
     * actually exchange a group message with one another. This is documented,
     * expected behavior for this slice (see `GroupCreateScreen`'s copy for the
     * plain-language version shown to users), matching the "an unreachable
     * 1:1 recipient still gets [SendResult.Sent]" posture [send] already
     * established, just applied per-member instead of per-conversation.
     *
     * Persists **exactly one** outgoing [GroupMessageEntity] row regardless of
     * how many members were actually reachable -- this device's own local
     * group history reflects "I sent this to the group," not a per-member
     * delivery ledger. A blocked member is filtered out before ever reaching
     * [encryptAndTransmit] (redundant with that method's own internal block
     * check, kept here so the intent -- "blocking prevents *sending to* that
     * member" -- reads directly at this call site too).
     *
     * Always returns [SendResult.Sent] -- no new result variant, matching
     * [send]'s own "local history reflects intent, not delivery" posture; a
     * caller has no actionable per-member distinction to react to here
     * (unlike [send]'s [SendResult.Blocked]/[SendResult.NoSessionAvailable],
     * each of which maps to a specific single-peer UX affordance with no group
     * analog).
     */
    open suspend fun sendToGroup(groupId: String, text: String): SendResult = withContext(ioDispatcher) {
        val targetPeerIds = groupDao.getFanoutTargetPeerIds(groupId)
        val blockedIds = blockRepository.observeBlockedSenderIds().first()
        val payloadBytes = MessagePayload.Text(groupId = groupId, text = text).encode()

        targetPeerIds.forEach { memberPeerId ->
            if (memberPeerId !in blockedIds) {
                encryptAndTransmit(memberPeerId, payloadBytes)
            }
        }

        groupMessageDao.insert(
            GroupMessageEntity(
                groupId = groupId,
                authorPeerId = getOwnPeerId(),
                plaintext = text,
                sentAtMs = System.currentTimeMillis(),
                isOutgoing = true,
            ),
        )

        SendResult.Sent
    }

    /** Random 128-bit hex id for a new group -- locally generated, not wire-negotiated or secret (see [GroupEntity]'s class doc). */
    private fun generateGroupId(): String = ByteArray(16).also { SecureRandom().nextBytes(it) }.toPeerIdHex()

    /**
     * Decrypts [ciphertext] from [senderPeerId], decodes it as a
     * [MessagePayload], and routes it to the right persistence + validation
     * path below. Establishes a session as a side effect if none exists yet
     * (the first message ever received from this peer -- see
     * [DoubleRatchetSession.forIncoming]'s own doc).
     *
     * A decrypt *or decode* failure (replayed/duplicate ciphertext,
     * untrusted/changed identity key, a session that doesn't exist yet and
     * isn't a valid PreKey-type first message, a malformed [MessagePayload],
     * or any other failure) is logged and dropped, never thrown -- matching
     * `WifiDirectTransport.receivePosts`'s "one bad frame doesn't kill the
     * loop" posture. Per [DoubleRatchetSession.decrypt]'s own documented
     * contract, a message whose key has aged out of libsignal-client's
     * skipped-key cache is genuinely unrecoverable and needs a fresh
     * handshake, not a retry -- this method doesn't attempt to trigger one
     * itself (out of scope for this slice; a future UI affordance would).
     *
     * Three [MessagePayload] cases, each with its own persistence/validation:
     * - [MessagePayload.Text] with `groupId = null` -- unchanged 1:1 path,
     *   persisted straight to [MessageEntity], same as before Phase 2 Slice 4.
     * - [MessagePayload.Text] with a non-null `groupId` -- a group message.
     *   **Validated before persisting**: dropped (logged, not persisted) unless
     *   [GroupDao.isKnownMember] confirms this device recognizes both the
     *   group and [senderPeerId] as one of its members. On success, persisted
     *   as a [GroupMessageEntity].
     * - [MessagePayload.GroupInvite] -- the membership-forgery fix (see
     *   [GroupEntity.creatorPeerId]'s own doc for the full rationale). If no
     *   [GroupEntity] exists yet for this `groupId`, one is created with
     *   `creatorPeerId = senderPeerId` (trust-on-first-use) plus
     *   [GroupMemberEntity] rows for every id in the invite's member list
     *   (minus this device's own peer id, which is never stored as a row of
     *   its own group -- see [GroupMemberEntity]'s class doc). If a
     *   [GroupEntity] for this `groupId` **already exists**, the invite is
     *   only accepted (as a no-op -- its membership list is ignored either
     *   way, per the design this pin exists for) if [senderPeerId] matches
     *   the already-pinned [GroupEntity.creatorPeerId]; otherwise it's
     *   rejected and logged. This single check closes both the "a stranger
     *   mints a colliding group under a `groupId` it merely observed in
     *   plaintext" case and the "a current, legitimate member unilaterally
     *   redefines who else is in the group" case.
     */
    open suspend fun onEnvelopeReceived(senderPeerId: String, ciphertext: ByteArray) {
        withContext(ioDispatcher) {
            val address = peerSignalAddress(senderPeerId)
            val session = DoubleRatchetSession.forIncoming(signalProtocolStore, address)
            val payload = try {
                MessagePayload.decode(session.decrypt(ciphertext))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decrypt/decode a message ciphertext from peer $senderPeerId", e)
                return@withContext
            }

            when (payload) {
                is MessagePayload.Text -> {
                    val groupId = payload.groupId
                    if (groupId == null) {
                        messageDao.insert(
                            MessageEntity(
                                peerId = senderPeerId,
                                plaintext = payload.text,
                                sentAtMs = System.currentTimeMillis(),
                                isOutgoing = false,
                            ),
                        )
                    } else {
                        if (!groupDao.isKnownMember(groupId, senderPeerId)) {
                            Log.w(
                                TAG,
                                "Dropping a group message for group $groupId from $senderPeerId -- this " +
                                    "device doesn't recognize the group, or doesn't recognize this sender " +
                                    "as one of its members.",
                            )
                            return@withContext
                        }
                        groupMessageDao.insert(
                            GroupMessageEntity(
                                groupId = groupId,
                                authorPeerId = senderPeerId,
                                plaintext = payload.text,
                                sentAtMs = System.currentTimeMillis(),
                                isOutgoing = false,
                            ),
                        )
                    }
                }

                is MessagePayload.GroupInvite -> {
                    val existingCreatorPeerId = groupDao.getCreatorPeerId(payload.groupId)
                    when {
                        existingCreatorPeerId == null -> {
                            val ownPeerId = getOwnPeerId()
                            val memberRows = payload.memberPeerIds
                                .filter { it != ownPeerId }
                                .distinct()
                                .map { memberPeerId -> GroupMemberEntity(groupId = payload.groupId, peerId = memberPeerId) }
                            groupDao.insertGroupWithMembers(
                                GroupEntity(
                                    groupId = payload.groupId,
                                    name = payload.name,
                                    creatorPeerId = senderPeerId,
                                    createdAtMs = System.currentTimeMillis(),
                                ),
                                memberRows,
                            )
                        }

                        existingCreatorPeerId == senderPeerId -> {
                            Log.d(
                                TAG,
                                "Ignoring a re-announced GroupInvite for group ${payload.groupId} from its " +
                                    "already-trusted creator -- membership is not re-derived from it.",
                            )
                        }

                        else -> {
                            Log.w(
                                TAG,
                                "Rejected a GroupInvite for group ${payload.groupId}: sender $senderPeerId is " +
                                    "not this group's pinned creator ($existingCreatorPeerId) -- possible " +
                                    "forged/colliding group id.",
                            )
                        }
                    }
                }
            }
        }
    }

    private companion object {
        const val TAG = "MessageRepository"
    }
}
