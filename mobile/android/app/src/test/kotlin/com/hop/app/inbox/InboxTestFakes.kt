package com.hop.app.inbox

import com.hop.data.MessageDao
import com.hop.data.MessageEntity
import com.hop.repository.BlockRepository
import com.hop.repository.ConversationSummary
import com.hop.repository.MessageRepository
import com.hop.repository.SendResult
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SignalProtocolStore
import org.signal.libsignal.protocol.state.SignedPreKeyRecord

/**
 * Shared hand-rolled test doubles for `com.hop.app.inbox`'s ViewModel tests
 * (no mocking library in this repo -- matches `FeedViewModelTest`'s
 * pattern).
 *
 * [NoOpMessageDao]/[NoOpSignalProtocolStore] exist purely to satisfy
 * [MessageRepository]'s constructor when subclassing it as a fake -- every
 * method throws, and neither is ever actually invoked in these tests (the
 * subclasses in this package override every [MessageRepository] method the
 * view models under test actually call). Deliberately never constructing a
 * real libsignal-client crypto object here: this module's
 * `org.signal:libsignal-android` dependency ships Android-ABI native
 * libraries, not a host-JVM one, so a plain JVM unit test must not exercise
 * it (see `app/build.gradle.kts`'s own note on why -android, not -client, is
 * declared here).
 */
internal object NoOpMessageDao : MessageDao {
    override suspend fun insert(message: MessageEntity): Long = error("unused by this fake")
    override fun getMessagesForPeer(peerId: String): Flow<List<MessageEntity>> = error("unused by this fake")
    override fun getLatestMessagePerPeer(): Flow<List<MessageEntity>> = error("unused by this fake")
}

internal object NoOpSignalProtocolStore : SignalProtocolStore {
    override fun getIdentityKeyPair(): IdentityKeyPair = error("unused by this fake")
    override fun getLocalRegistrationId(): Int = error("unused by this fake")
    override fun saveIdentity(address: SignalProtocolAddress, identityKey: IdentityKey): IdentityKeyStore.IdentityChange =
        error("unused by this fake")
    override fun isTrustedIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey,
        direction: IdentityKeyStore.Direction,
    ): Boolean = error("unused by this fake")
    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? = error("unused by this fake")

    override fun loadPreKey(preKeyId: Int): PreKeyRecord = error("unused by this fake")
    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) = error("unused by this fake")
    override fun containsPreKey(preKeyId: Int): Boolean = error("unused by this fake")
    override fun removePreKey(preKeyId: Int) = error("unused by this fake")

    override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord = error("unused by this fake")
    override fun loadSignedPreKeys(): MutableList<SignedPreKeyRecord> = error("unused by this fake")
    override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) = error("unused by this fake")
    override fun containsSignedPreKey(signedPreKeyId: Int): Boolean = error("unused by this fake")
    override fun removeSignedPreKey(signedPreKeyId: Int) = error("unused by this fake")

    override fun loadKyberPreKey(kyberPreKeyId: Int): KyberPreKeyRecord = error("unused by this fake")
    override fun loadKyberPreKeys(): MutableList<KyberPreKeyRecord> = error("unused by this fake")
    override fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) = error("unused by this fake")
    override fun containsKyberPreKey(kyberPreKeyId: Int): Boolean = error("unused by this fake")
    override fun markKyberPreKeyUsed(kyberPreKeyId: Int, deviceId: Int, baseKey: ECPublicKey) = error("unused by this fake")

    override fun loadSession(address: SignalProtocolAddress): SessionRecord? = error("unused by this fake")
    override fun loadExistingSessions(addresses: MutableList<SignalProtocolAddress>): MutableList<SessionRecord> =
        error("unused by this fake")
    override fun getSubDeviceSessions(name: String): MutableList<Int> = error("unused by this fake")
    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) = error("unused by this fake")
    override fun containsSession(address: SignalProtocolAddress): Boolean = error("unused by this fake")
    override fun deleteSession(address: SignalProtocolAddress) = error("unused by this fake")
    override fun deleteAllSessions(name: String) = error("unused by this fake")

    override fun storeSenderKey(sender: SignalProtocolAddress, distributionId: UUID, record: SenderKeyRecord) =
        error("unused by this fake")
    override fun loadSenderKey(sender: SignalProtocolAddress, distributionId: UUID): SenderKeyRecord =
        error("unused by this fake")
}

/**
 * Configurable [MessageRepository] fake -- subclasses the real class per its
 * own `open`-for-testability doc (same shape as `FeedViewModelTest`'s
 * `CountingFakePostRepository`), overriding only the three methods this
 * package's view models actually call.
 */
internal class FakeMessageRepository(
    private val conversationSummaries: Flow<List<ConversationSummary>> = MutableStateFlow(emptyList()),
    private val conversationMessages: Flow<List<MessageEntity>> = MutableStateFlow(emptyList()),
    private val sendResults: MutableList<SendResult> = mutableListOf(),
    var lastSendCall: Pair<String, String>? = null,
) : MessageRepository(
    messageDao = NoOpMessageDao,
    signalProtocolStore = NoOpSignalProtocolStore,
    blockRepository = BlockRepository(NoOpBlockedSenderDeviceDao),
    getOwnPeerId = { "unused" },
    sendToPeer = { _, _, _ -> error("unused by this fake") },
) {
    override fun observeConversationSummaries(): Flow<List<ConversationSummary>> = conversationSummaries

    override fun observeConversation(peerId: String): Flow<List<MessageEntity>> = conversationMessages

    override suspend fun send(peerId: String, plaintext: String): SendResult {
        lastSendCall = peerId to plaintext
        return if (sendResults.isEmpty()) SendResult.Sent else sendResults.removeAt(0)
    }
}

private object NoOpBlockedSenderDeviceDao : com.hop.data.BlockedSenderDeviceDao {
    override suspend fun insert(entity: com.hop.data.BlockedSenderDeviceEntity) = error("unused by this fake")
    override fun observeAll(): Flow<List<String>> = MutableStateFlow(emptyList())
}
