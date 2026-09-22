package com.hop.crypto

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets

/**
 * The plaintext structure carried inside [DoubleRatchetSession.encrypt]/
 * [DoubleRatchetSession.decrypt]'s opaque byte arrays -- i.e. what a message
 * actually *is*, once decrypted, as opposed to what a relay carrier is
 * allowed to see on the wire.
 *
 * Lives in `crypto/`, not `protocol/`, deliberately: `protocol/`'s entire job
 * (stated in every doc comment in that module, see e.g.
 * `com.hop.protocol.MessageCiphertextEnvelope`'s own doc) is describing what a
 * relay carrier can see on the wire -- `ciphertext` there is fully opaque, a
 * relay never decrypts it. This type only ever exists as plaintext between one
 * [DoubleRatchetSession.encrypt] call and the matching [DoubleRatchetSession.decrypt]
 * call on the far side, so it has no business in `protocol/`. Exact precedent:
 * [PreKeyBundleCodec] (the actual libsignal key-material shape, `crypto/`) vs
 * `com.hop.protocol.PreKeyBundleEnvelope` (`protocol/`'s deliberately opaque
 * `bundleBytes` wire wrapper around it) -- same split, applied here to what a
 * decrypted message payload looks like instead of what a prekey bundle looks
 * like.
 *
 * [DoubleRatchetSession.encrypt]/[DoubleRatchetSession.decrypt] themselves stay
 * completely payload-shape-agnostic -- callers (`com.hop.repository.MessageRepository`)
 * call [encode] to build the bytes handed to `encrypt`, and [decode] on the
 * bytes handed back by `decrypt`. This keeps the ratchet wrapper reusable for
 * any future plaintext shape, not coupled to groups specifically.
 *
 * Group messaging in v1 is per-member pairwise Double Ratchet fan-out (PRD
 * §4.3, ADR 0001) -- there is no shared group key or group-wide ratchet
 * anywhere in this type or its callers. [GroupInvite]/[Text.groupId] exist
 * purely to carry a group's identity and membership list *inside* the
 * already-1:1-encrypted plaintext exchanged with one specific member; nothing
 * about a group's membership or messages is ever visible to a relay carrier,
 * which only ever sees the same [DoubleRatchetSession.encrypt] output shape
 * `protocol/` already treats as opaque today.
 */
sealed class MessagePayload {

    /**
     * A chat message. [groupId] is `null` for a plain 1:1 message (today's
     * unchanged behavior, sent/received exactly as before this type existed)
     * or a locally-generated group id (see [GroupInvite.groupId]'s doc) when
     * this text is one member's fan-out copy of a group message.
     */
    data class Text(val groupId: String?, val text: String) : MessagePayload()

    /**
     * Announces a new group to one prospective member, sent once per member as
     * part of the creator's invite fan-out
     * (`com.hop.repository.MessageRepository.createGroup`).
     *
     * [groupId] is locally generated (random hex) by the creator, not
     * wire-negotiated -- it has no cryptographic meaning of its own and is not
     * secret (every member sees it in plaintext), so it must never be treated
     * as an access-control token. Membership trust is established by the
     * receiving device pinning this invite's *sender* as the group's
     * `creatorPeerId` the first time it ever sees this [groupId] (trust-on-
     * first-use), and rejecting any later invite for the same [groupId] from a
     * different sender -- see `com.hop.repository.MessageRepository.onEnvelopeReceived`'s
     * `GroupInvite` handling for the exact check. Without that pin, any current
     * member could unilaterally redefine another member's view of who's in the
     * group, or an unrelated party who merely observed this [groupId] in
     * plaintext could mint a colliding "group" under the same id.
     *
     * [memberPeerIds] is every *other* member besides the sender (the sender
     * identifies itself as the creator via the envelope's own `senderPeerId`,
     * not via this list) -- a receiving device filters its own peer id back
     * out of this list before recording it, since a device never lists itself
     * as one of its own fan-out targets.
     */
    data class GroupInvite(val groupId: String, val name: String, val memberPeerIds: List<String>) : MessagePayload()

    /**
     * Encodes as `[1-byte type tag][type-specific fields]`, mirroring
     * `protocol/`'s own length-prefixed-field codec style (see
     * `com.hop.protocol.MessageCiphertextEnvelope.encode`) even though this
     * type never crosses the wire in its own right -- it's always wrapped by
     * [DoubleRatchetSession.encrypt] first.
     */
    fun encode(): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { data ->
            when (this) {
                is Text -> {
                    data.writeByte(TYPE_TEXT)
                    data.writeBoolean(groupId != null)
                    groupId?.let { data.writeLengthPrefixedUtf8(it) }
                    data.writeLengthPrefixedUtf8(text)
                }

                is GroupInvite -> {
                    data.writeByte(TYPE_GROUP_INVITE)
                    data.writeLengthPrefixedUtf8(groupId)
                    data.writeLengthPrefixedUtf8(name)
                    data.writeInt(memberPeerIds.size)
                    memberPeerIds.forEach { data.writeLengthPrefixedUtf8(it) }
                }
            }
        }
        return out.toByteArray()
    }

    companion object {
        private const val TYPE_TEXT = 0
        private const val TYPE_GROUP_INVITE = 1

        /**
         * Decodes [bytes] (the output of a previous [encode] call, already
         * recovered from a [DoubleRatchetSession.decrypt] call) back into a
         * [MessagePayload]. Throws (an [java.io.EOFException] from the
         * underlying stream, or [IllegalArgumentException] for an unknown type
         * tag) on malformed input rather than silently misparsing -- matching
         * every other codec in this codebase (`com.hop.protocol.MessageCiphertextEnvelope.decode`,
         * [PreKeyBundleCodec.decode]). Callers (`com.hop.repository.MessageRepository.onEnvelopeReceived`)
         * are expected to catch broadly and log-and-drop, the same posture
         * already applied to a [DoubleRatchetSession.decrypt] failure.
         */
        fun decode(bytes: ByteArray): MessagePayload {
            val data = DataInputStream(ByteArrayInputStream(bytes))
            return when (val type = data.readUnsignedByte()) {
                TYPE_TEXT -> {
                    val groupId = if (data.readBoolean()) data.readLengthPrefixedUtf8() else null
                    val text = data.readLengthPrefixedUtf8()
                    Text(groupId, text)
                }

                TYPE_GROUP_INVITE -> {
                    val groupId = data.readLengthPrefixedUtf8()
                    val name = data.readLengthPrefixedUtf8()
                    val count = data.readInt()
                    val memberPeerIds = (0 until count).map { data.readLengthPrefixedUtf8() }
                    GroupInvite(groupId, name, memberPeerIds)
                }

                else -> throw IllegalArgumentException("Unknown MessagePayload type tag: $type")
            }
        }

        private fun DataOutputStream.writeLengthPrefixedUtf8(value: String) {
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            writeInt(bytes.size)
            write(bytes)
        }

        private fun DataInputStream.readLengthPrefixedUtf8(): String {
            val length = readInt()
            val bytes = ByteArray(length).also { readFully(it) }
            return String(bytes, StandardCharsets.UTF_8)
        }
    }
}
