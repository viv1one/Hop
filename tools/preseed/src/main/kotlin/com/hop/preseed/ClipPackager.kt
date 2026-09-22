package com.hop.preseed

import com.hop.crypto.DecayKeyStore
import com.hop.protocol.ContentType
import com.hop.protocol.EncryptedFrameCodec
import com.hop.protocol.Geohash
import com.hop.protocol.ReachTier
import com.hop.protocol.ReachTierGeohash
import com.hop.protocol.ReachTierKeyDistribution
import java.security.MessageDigest
import java.time.Duration

/**
 * Packages one source media file's plaintext bytes into a [SeedClip] -- the
 * exact same [com.hop.protocol.Frame]/[com.hop.protocol.EncryptedFrameCodec]
 * shape the real app's posting path produces (see
 * `PostComposerViewModel.post` in `mobile/android/app/`, which this function
 * deliberately mirrors step-for-step: SHA-256 the plaintext for `clipHash`,
 * derive `originGeohashPrefix` from the target coordinates at this tier's own
 * geohash precision, call [EncryptedFrameCodec.encode], then store the real
 * content-encryption key under [ReachTierKeyDistribution]'s per-tier storage
 * key convention) -- so a real client that later discovers and dials this
 * tool cannot tell the difference between a pre-seeded clip and one an
 * ordinary user posted.
 *
 * **Locality is refused outright, not just discouraged.** Per ADR 0003,
 * Locality resolves entirely offline via BLE/WiFi Direct and never touches
 * the DHT -- there is structurally no way for an internet-mode tool like this
 * one to pre-seed it, so [reachTier] `== LOCALITY` is an [IllegalArgumentException],
 * not a silently-accepted no-op.
 *
 * **Content type is an explicit caller-supplied parameter, never inferred
 * from [sourceFile]'s extension or sniffed from its bytes.** BUILD_PLAN.md's
 * open decision #4 and this codebase's wire format both treat photo-vs-video
 * as an explicit [ContentType] flag, not something inferred -- see
 * `Frame.kt`'s own `ContentType` doc. The real app never needs to infer it
 * either: a picked file's MIME type comes from `ContentResolver`, a
 * framework fact this plain-JVM tool has no access to and must not
 * approximate by guessing from a file name. See [PreseedCli]'s own doc for
 * why this pushes the manifest format to name each source file's content
 * type explicitly, rather than trying to glob a directory and guess.
 *
 * **Decay is real, not waived.** [ttlSeconds] is passed straight through to
 * [EncryptedFrameCodec.encode]/[DecayKeyStore.store] -- a pre-seeded clip
 * decays on exactly the same schedule any other post would, per ADR 0003 and
 * this module's own instruction not to special-case seeded content as a
 * permanent exemption. See [PreseedCli]'s own doc for the CLI-configurable
 * default this project chose and the tradeoff behind it.
 */
object ClipPackager {

    /**
     * Packages [plaintext] as a [contentType] clip targeted at
     * ([latitude], [longitude]) and tier [reachTier], stores its real
     * content-encryption key into [decayKeyStore] under
     * [ReachTierKeyDistribution]'s per-tier storage key (the exact
     * composition a later [com.hop.protocol.TierKeyRequestEnvelope] lookup
     * MUST agree with -- see that function's own doc), and returns the
     * resulting [SeedClip], ready to hand to [PreseedContentServer]/
     * [PreseedNode].
     *
     * [senderDeviceId] is this seeding run's own nominal sender identity
     * (16 bytes, matching [com.hop.protocol.Frame.SENDER_DEVICE_ID_SIZE]) --
     * a plain per-run value, not a persistent cross-run identity and not an
     * attested one (see [PreseedNode]'s own class doc for why this tool
     * needs no device attestation to do its job).
     *
     * Throws [IllegalArgumentException] if [reachTier] is
     * [ReachTier.LOCALITY] -- see this object's own class doc.
     */
    fun packageClip(
        plaintext: ByteArray,
        contentType: ContentType,
        reachTier: ReachTier,
        latitude: Double,
        longitude: Double,
        ttlSeconds: Long,
        senderDeviceId: ByteArray,
        decayKeyStore: DecayKeyStore,
        originatedAtMs: Long = System.currentTimeMillis(),
    ): SeedClip {
        require(reachTier != ReachTier.LOCALITY) {
            "Locality is BLE/WiFi-Direct-only and structurally cannot be pre-seeded over the internet -- ADR 0003"
        }

        val clipHash = MessageDigest.getInstance("SHA-256").digest(plaintext)
        val originGeohashPrefix = Geohash.encode(latitude, longitude, ReachTierGeohash.precisionFor(reachTier))

        val encodeResult = EncryptedFrameCodec.encode(
            plaintext = plaintext,
            clipHash = clipHash,
            senderDeviceId = senderDeviceId,
            contentType = contentType,
            hopCount = 0,
            originatedAtMs = originatedAtMs,
            ttlSeconds = ttlSeconds,
            reachTier = reachTier,
            dontRelay = false,
            originGeohashPrefix = originGeohashPrefix,
        )

        val contentIdHex = clipHash.toHexString()
        // decayKeyStoreKeyFor encapsulates the LOCALITY special-case itself
        // (see its own doc) -- reachTier can never be LOCALITY by this
        // point (guarded above), so this call is equivalent to
        // decayKeyStorageKey directly, but using the same wrapper every
        // other real call site (PostComposerViewModel, ReceivedFrameStore,
        // EnvelopeDispatcher) uses keeps this file structurally consistent
        // with them rather than silently relying on a duplicated LOCALITY
        // check nobody else's storage-key composition needs to make either.
        val storageKey = ReachTierKeyDistribution.decayKeyStoreKeyFor(contentIdHex, reachTier)
        decayKeyStore.store(
            contentId = storageKey,
            wrappedCek = encodeResult.contentEncryptionKey,
            decayWindow = Duration.ofSeconds(ttlSeconds),
        )

        return SeedClip(
            contentId = contentIdHex,
            reachTier = reachTier,
            originGeohashPrefix = originGeohashPrefix,
            latitude = latitude,
            longitude = longitude,
            encodedFrame = encodeResult.encoded,
        )
    }

    private fun ByteArray.toHexString(): String = joinToString(separator = "") { byte -> "%02x".format(byte) }
}
