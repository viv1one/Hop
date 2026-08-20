package com.hop.data

import androidx.room.Entity

/**
 * One attested device's "don't relay" flag for a clipHash (Phase 2 Slice 2,
 * PRD §4.6, ADR 0004). Room table `dont_relay_flags`, composite primary key
 * `(clipHash, attestedDeviceKey)` -- the same distinct-device dedup the
 * distinct-flagger-count query relies on, enforced by the DB itself rather
 * than an application-level check.
 *
 * Deliberately independent of [RelayQueueEntity]: a row here can legitimately
 * exist for a clipHash this device has never received a post for yet (mesh
 * delivery order isn't guaranteed -- see `com.hop.repository.DontRelayRepository`'s
 * doc for the order-independence design this closes). [originatedAtMs]/
 * [ttlSeconds] are carried on the flag itself, denormalized off the post it
 * refers to, for exactly that reason -- there is no other local source for a
 * TTL to bound this row's own propagation against when no [PostEntity]/
 * [RelayQueueEntity] row exists yet.
 *
 * [attestedDeviceKey] is hex-encoded, matching [PostEntity.senderDeviceId]'s
 * existing hex-encoding convention for wire-derived identity bytes.
 *
 * **Sybil-resistance limit** (state separately from [RelayPolicy]'s existing
 * `hopCount`-spoofing caveat, which requires a *modified* client): under
 * `com.hop.crypto.StubAttestationProvider`, minting N distinct
 * `attestedDeviceKey` values costs nothing -- not even for a stock,
 * unmodified client, since the "key" is just the caller-supplied nonce
 * echoed back (see that class's own doc). This table's distinct-key counting
 * logic is real; the Sybil-resistance property it's meant to provide is not,
 * until real Play Integrity/App Attest replaces the stub.
 *
 * **Eventual-consistency limit**: different devices cross the distinct-flag
 * threshold at different times depending on mesh partition/propagation
 * order -- some devices flip their local `RelayQueueEntity.dontRelay` to
 * `true` while others (who haven't yet seen enough flags) keep relaying for
 * a while longer. This is expected behavior, not a bug, consistent with ADR
 * 0003/0004's "deterrent, not a guarantee" framing throughout.
 */
@Entity(tableName = "dont_relay_flags", primaryKeys = ["clipHash", "attestedDeviceKey"])
data class DontRelayFlagEntity(
    /** Hex-encoded `Frame.clipHash` -- same encoding [PostEntity.clipHash] uses. */
    val clipHash: String,
    /** Hex-encoded attested public key (ADR 0004) -- see class doc for the Sybil-resistance limit under the current stub. */
    val attestedDeviceKey: String,
    /** Local wall-clock time this device recorded the flag -- diagnostic only, not read by any eligibility decision. */
    val flaggedAtMs: Long,
    /** Denormalized off the post this flag refers to -- see class doc for why. */
    val originatedAtMs: Long,
    /** Denormalized off the post this flag refers to -- see class doc for why. */
    val ttlSeconds: Long,
)
