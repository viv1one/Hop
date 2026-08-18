package com.hop.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A locally blocked identity, persisted so a block survives an app restart.
 * Room table `blocked_identities`.
 *
 * Keyed off the hardware-attested device key (`AttestationResult.Passed.attestedPublicKey`
 * from `crypto/`'s `AttestationProvider`), per ADR 0004 -- never a soft local
 * id, never a phone number/email. This is what makes a block mean something
 * against a free/resettable identity: it survives an app reinstall on the same
 * hardware (where the platform re-attests related state), but not a new
 * physical device. That's an explicit, acceptable limit (ADR 0004 Limits), not
 * an oversold guarantee -- state it plainly in any UI copy that surfaces
 * blocking, rather than implying it's unbreakable.
 */
@Entity(tableName = "blocked_identities")
data class BlockedIdentityEntity(
    /** Hex-encoded `AttestationResult.Passed.attestedPublicKey`. */
    @PrimaryKey
    val attestedDeviceKey: String,
    val blockedAtMs: Long,
)
