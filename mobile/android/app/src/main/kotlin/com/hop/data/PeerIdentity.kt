package com.hop.data

import org.signal.libsignal.protocol.SignalProtocolAddress

/**
 * Every Double Ratchet [SignalProtocolAddress] this app constructs uses this
 * fixed device id -- there is no multi-device concept anywhere in Phase 1,
 * only ever one physical device per install. Matches the `deviceId` every
 * [com.hop.crypto.DoubleRatchetSession.publishPreKeyBundle] call and every
 * `RoomSignalProtocolStoreTest`/`DoubleRatchetSessionTest` party already uses.
 */
const val PEER_DEVICE_ID: Int = 1

/**
 * Maps this app's existing per-install sender-device identity onto a Double
 * Ratchet [SignalProtocolAddress]. [peerIdHex] is expected to already be in
 * the same hex-string form [ByteArray.toPeerIdHex] produces -- the same
 * string [SettingsRepository.getOrCreateStableSenderDeviceId] persists (once
 * hex-encoded), the same string every post `com.hop.protocol.Frame`'s
 * `senderDeviceId` hex-encodes to on receipt (see
 * `WifiDirectTransport.ReceivedFrameStore.handle`), and the same string
 * [PostEntity.senderDeviceId] / [BlockedSenderDeviceEntity.senderDeviceId]
 * already store.
 *
 * This is the Phase 1 messaging plan's architecture decision #1: one
 * identity, reused everywhere, not a new identity concept layered on top --
 * so blocking a sender in the Feed also blocks messaging from them for free,
 * with zero new plumbing. This is *not* yet the ADR-0004-grade
 * hardware-attested identity (see [BlockRepository]'s own doc for that same
 * caveat applied to blocking) -- it is a plain per-install random id with no
 * attestation backing, reused here rather than duplicated.
 */
fun peerSignalAddress(peerIdHex: String): SignalProtocolAddress = SignalProtocolAddress(peerIdHex, PEER_DEVICE_ID)

/**
 * Hex-encodes this byte array using the exact same per-byte format already
 * used throughout this codebase for `senderDeviceId` (see
 * `WifiDirectTransport.ReceivedFrameStore`'s and
 * `PostComposerViewModel`'s own private `toHexString()` extensions, which
 * this does not replace -- they're small enough not to be worth a shared-file
 * refactor for this slice, but any *new* hex-encoding of a peer/sender device
 * id, including this messaging slice's own, must produce byte-identical
 * output to those, since [BlockedSenderDeviceEntity]/[PostEntity] block/key
 * on this exact string form).
 */
fun ByteArray.toPeerIdHex(): String = joinToString(separator = "") { "%02x".format(it) }
