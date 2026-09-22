package com.hop.crypto

import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.kem.KEMPublicKey
import org.signal.libsignal.protocol.state.PreKeyBundle
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Serializes/deserializes a libsignal-client [PreKeyBundle] to/from a plain
 * byte array for wire transmission.
 *
 * [PreKeyBundle] itself has no `serialize()`/byte-array constructor pair --
 * confirmed via `javap` against the compiled 0.86.5 jar, unlike
 * `SessionRecord`/`IdentityKeyPair`/`SignedPreKeyRecord`/etc., which all do.
 * Each of its *component* fields does, though ([ECPublicKey], [IdentityKey],
 * [KEMPublicKey] all have `serialize()` + a `byte[]` constructor), so this
 * manually length-prefixes and concatenates each field instead.
 *
 * This is the one piece of libsignal-aware bundle (de)serialization
 * `com.hop.transport.WifiDirectTransport` (an app-layer class, not
 * `protocol/`) is allowed to call directly, per the Phase 1 messaging plan's
 * architecture decision: `protocol/`'s own `PreKeyBundleEnvelope` treats
 * [PreKeyBundle] bytes as fully opaque and never imports this class or any
 * libsignal-client type, preserving ADR 0001's one-way `protocol/` ->
 * `crypto/` dependency rule. This class lives in `crypto/` specifically so
 * that rule holds -- it's the libsignal-aware counterpart to
 * `PreKeyBundleEnvelope`'s deliberately opaque `bundleBytes` field.
 *
 * [PreKeyBundle.getPreKey] is nullable in libsignal-client's own Kotlin
 * source (a bundle *can* omit its one-time prekey, `NULL_PRE_KEY_ID`) --
 * this codec assumes it never is, matching every bundle this app actually
 * produces ([DoubleRatchetSession.publishPreKeyBundle] always includes one),
 * and fails loudly via [requireNotNull] rather than silently encoding a
 * bundle with a missing one-time prekey.
 */
object PreKeyBundleCodec {

    fun encode(bundle: PreKeyBundle): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { data ->
            data.writeInt(bundle.registrationId)
            data.writeInt(bundle.deviceId)
            data.writeInt(bundle.preKeyId)
            val preKey = requireNotNull(bundle.preKey) {
                "PreKeyBundleCodec only supports bundles with a one-time prekey (preKeyId=${bundle.preKeyId})"
            }
            data.writeLengthPrefixed(preKey.serialize())
            data.writeInt(bundle.signedPreKeyId)
            data.writeLengthPrefixed(bundle.signedPreKey.serialize())
            data.writeLengthPrefixed(bundle.signedPreKeySignature)
            data.writeLengthPrefixed(bundle.identityKey.serialize())
            data.writeInt(bundle.kyberPreKeyId)
            data.writeLengthPrefixed(bundle.kyberPreKey.serialize())
            data.writeLengthPrefixed(bundle.kyberPreKeySignature)
        }
        return out.toByteArray()
    }

    fun decode(bytes: ByteArray): PreKeyBundle {
        val data = DataInputStream(ByteArrayInputStream(bytes))
        val registrationId = data.readInt()
        val deviceId = data.readInt()
        val preKeyId = data.readInt()
        val preKeyPublic = ECPublicKey(data.readLengthPrefixed())
        val signedPreKeyId = data.readInt()
        val signedPreKeyPublic = ECPublicKey(data.readLengthPrefixed())
        val signedPreKeySignature = data.readLengthPrefixed()
        val identityKey = IdentityKey(data.readLengthPrefixed())
        val kyberPreKeyId = data.readInt()
        val kyberPreKeyPublic = KEMPublicKey(data.readLengthPrefixed())
        val kyberPreKeySignature = data.readLengthPrefixed()

        return PreKeyBundle(
            registrationId,
            deviceId,
            preKeyId,
            preKeyPublic,
            signedPreKeyId,
            signedPreKeyPublic,
            signedPreKeySignature,
            identityKey,
            kyberPreKeyId,
            kyberPreKeyPublic,
            kyberPreKeySignature,
        )
    }

    private fun DataOutputStream.writeLengthPrefixed(bytes: ByteArray) {
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readLengthPrefixed(): ByteArray {
        val length = readInt()
        return ByteArray(length).also { readFully(it) }
    }
}
