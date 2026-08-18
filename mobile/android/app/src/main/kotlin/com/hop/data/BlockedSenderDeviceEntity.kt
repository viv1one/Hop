package com.hop.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A Phase-1 stopgap block record, persisted so a block survives an app
 * restart. Room table `blocked_sender_devices`.
 *
 * Keyed on the plain per-install `senderDeviceId`
 * ([SettingsRepository.getOrCreateStableSenderDeviceId], `Frame.senderDeviceId`
 * in `protocol/`) -- deliberately NOT the ADR-0004-grade hardware-attested
 * identity that [BlockedIdentityEntity] already provides for. This table is
 * explicitly separate from that one, not a replacement: a block recorded
 * here does NOT survive a reinstall (a fresh install generates a fresh
 * `senderDeviceId`), unlike the real attested-key block, and it has no
 * defense against a determined actor cycling installs to evade a block.
 *
 * This is a confirmed, deliberate scope call for this slice (Feed tab), not
 * an oversight: real ADR-0004-grade attested-key blocking needs a persisted
 * attested identity plus a peer identity-exchange handshake, neither of
 * which exists yet -- both are deferred to the messaging slice, which needs
 * that same identity-exchange machinery anyway. Don't let any UI copy
 * surfacing this table's effect imply it survives a reinstall.
 */
@Entity(tableName = "blocked_sender_devices")
data class BlockedSenderDeviceEntity(
    @PrimaryKey
    val senderDeviceId: String,
    val blockedAtMs: Long,
)
