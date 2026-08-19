package com.hop.repository

import android.util.Log
import com.hop.crypto.DoubleRatchetSession
import com.hop.crypto.PreKeyBundleCodec
import com.hop.data.MessageDao
import com.hop.data.MessageEntity
import com.hop.data.SignalIdentityDao
import com.hop.data.peerSignalAddress
import com.hop.protocol.MessageCiphertextEnvelope
import com.hop.protocol.WirePayloadType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.SignalProtocolStore
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

/** Outcome of [MessageRepository.send] -- see each case's doc for what it means and what the caller should do. */
sealed interface SendResult {
    /**
     * Encryption and local persistence succeeded. This does **not** mean the
     * message reached the peer live -- [MessageRepository.send]'s own doc
     * covers exactly what "sent" does and doesn't guarantee under Phase 1's
     * no-store-and-forward, best-effort delivery model (matching
     * `WifiDirectTransport`'s outbox).
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
 * [sendToPeer] and [getOwnPeerId] are threaded in as narrow function
 * parameters (mirroring `PostComposerViewModel`'s established pattern for
 * exactly this reason) rather than the whole `TransportManager`/
 * `SettingsRepository` -- keeps this class constructible against trivial
 * fakes without an Android `Context`.
 *
 * Prekey bundles announced by peers are cached **in-memory only**
 * ([cachedPeerBundles]) -- session-scoped, matching
 * `WifiDirectTransport`'s own outbox's no-cross-restart-persistence model.
 * This is a named, deliberate MVP simplification (Phase 1 messaging plan):
 * after a process restart, a cached bundle is gone until the peer's next
 * ambient re-announcement on a fresh connection.
 *
 * **All libsignal/Room calls in [send]/[onEnvelopeReceived] run on
 * [ioDispatcher]** (defaults to [Dispatchers.IO], injectable for tests) --
 * matching `PostComposerViewModel`'s fix for a real main-thread-Room crash
 * found this session; that class of bug is not being reintroduced here.
 * [cachePeerBundle] is the one exception: it is only ever invoked by
 * `WifiDirectTransport`'s receive loop, which already runs on a dedicated
 * background thread (never the main thread) by construction, and does no
 * Room I/O (only in-memory map + libsignal object construction) -- see its
 * own doc.
 */
open class MessageRepository(
    private val messageDao: MessageDao,
    private val signalIdentityDao: SignalIdentityDao,
    private val signalProtocolStore: SignalProtocolStore,
    private val blockRepository: BlockRepository,
    private val getOwnPeerId: suspend () -> String,
    private val sendToPeer: (peerId: String, type: WirePayloadType, payload: ByteArray) -> Boolean,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val cachedPeerBundles = ConcurrentHashMap<String, PreKeyBundle>()

    open fun observeConversation(peerId: String): Flow<List<MessageEntity>> = messageDao.getMessagesForPeer(peerId)

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
     * Encrypts [plaintext] for [peerId] and hands it to [sendToPeer] (a
     * best-effort, session-scoped-only send -- no ack/retry, matching
     * `WifiDirectTransport`'s outbox model exactly), then optimistically
     * inserts an outgoing [MessageEntity] **regardless of whether [peerId]
     * is currently connected to receive it live** -- this device's own
     * local history reflects "I sent this," not "this was delivered," the
     * same distinction Phase 1's Feed posting already makes for posts.
     *
     * Session handling: if [signalProtocolStore] already has a session with
     * [peerId], reuses it via [DoubleRatchetSession.forIncoming] rather than
     * [DoubleRatchetSession.initiate] -- `initiate` explicitly starts a
     * *fresh* session from a bundle via `SessionBuilder.process`, which would
     * discard live ratchet state. `forIncoming` does no handshake step at
     * all (confirmed by reading [DoubleRatchetSession]'s own implementation,
     * not assumed from its name) -- it just wires a wrapper around
     * [signalProtocolStore]/the peer's address, exactly what's needed here
     * whether the session was originally established as the initiator or the
     * responder. Only when no session exists yet does this fall back to a
     * cached bundle + [DoubleRatchetSession.initiate] to start one.
     *
     * Returns [SendResult.Blocked] without encrypting or persisting anything
     * if [peerId] is in [blockRepository]'s block list, and
     * [SendResult.NoSessionAvailable] (also without persisting anything) if
     * no session exists and no bundle has ever been cached for [peerId] --
     * see [SendResult]'s own doc for what each case means to a caller.
     */
    open suspend fun send(peerId: String, plaintext: String): SendResult = withContext(ioDispatcher) {
        val blockedIds = blockRepository.observeBlockedSenderIds().first()
        if (peerId in blockedIds) {
            return@withContext SendResult.Blocked
        }

        val address = peerSignalAddress(peerId)
        val session = if (signalProtocolStore.containsSession(address)) {
            DoubleRatchetSession.forIncoming(signalProtocolStore, address)
        } else {
            val bundle = cachedPeerBundles[peerId] ?: return@withContext SendResult.NoSessionAvailable
            try {
                DoubleRatchetSession.initiate(signalProtocolStore, address, bundle)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initiate a session with peer $peerId from its cached bundle", e)
                return@withContext SendResult.NoSessionAvailable
            }
        }

        val ciphertext = session.encrypt(plaintext.toByteArray(Charsets.UTF_8))
        val envelope = MessageCiphertextEnvelope(
            senderPeerId = getOwnPeerId(),
            recipientPeerId = peerId,
            ciphertext = ciphertext,
        )
        if (!sendToPeer(peerId, WirePayloadType.MESSAGE_CIPHERTEXT, envelope.encode())) {
            Log.d(
                TAG,
                "Peer $peerId not currently connected -- message encrypted and persisted locally only; " +
                    "Phase 1 has no store-and-forward, so it will not be delivered unless/until this " +
                    "device reconnects to them within this same app session.",
            )
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
     * Decrypts [ciphertext] from [senderPeerId] and persists it as an
     * incoming [MessageEntity]. Establishes a session as a side effect if
     * none exists yet (the first message ever received from this peer --
     * see [DoubleRatchetSession.forIncoming]'s own doc).
     *
     * A decrypt failure (replayed/duplicate ciphertext, untrusted/changed
     * identity key, a session that doesn't exist yet and isn't a valid
     * PreKey-type first message, or any other decode failure) is logged and
     * dropped, never thrown -- matching `WifiDirectTransport.receivePosts`'s
     * "one bad frame doesn't kill the loop" posture. Per
     * [DoubleRatchetSession.decrypt]'s own documented contract, a message
     * whose key has aged out of libsignal-client's skipped-key cache is
     * genuinely unrecoverable and needs a fresh handshake, not a retry --
     * this method doesn't attempt to trigger one itself (out of scope for
     * this slice; a future UI affordance would).
     */
    open suspend fun onEnvelopeReceived(senderPeerId: String, ciphertext: ByteArray) {
        withContext(ioDispatcher) {
            val address = peerSignalAddress(senderPeerId)
            val session = DoubleRatchetSession.forIncoming(signalProtocolStore, address)
            val plaintext = try {
                session.decrypt(ciphertext)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decrypt a message ciphertext from peer $senderPeerId", e)
                return@withContext
            }

            messageDao.insert(
                MessageEntity(
                    peerId = senderPeerId,
                    plaintext = String(plaintext, Charsets.UTF_8),
                    sentAtMs = System.currentTimeMillis(),
                    isOutgoing = false,
                ),
            )
        }
    }

    private companion object {
        const val TAG = "MessageRepository"
    }
}
