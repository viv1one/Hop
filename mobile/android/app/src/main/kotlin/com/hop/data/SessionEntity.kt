package com.hop.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The live Double Ratchet session/ratchet state for one remote device
 * address -- backs libsignal-client's `SessionStore` contract inside
 * [RoomSignalProtocolStore]. Room table `signal_sessions`. This is the
 * concrete reason this slice exists: without it, every 1:1 conversation's
 * ratchet state vanishes the moment the app process dies (message
 * *history* already survives via [MessageEntity], but the live session that
 * produced it did not, until this table).
 *
 * [addressKey] is `"$name:$deviceId"`, matching
 * [org.signal.libsignal.protocol.SignalProtocolAddress]'s two-part identity
 * (a peer, `name`, may have more than one `deviceId` in general libsignal
 * usage, even though this app currently pins every peer to a single fixed
 * `deviceId = 1` -- see the Phase 1 messaging plan). [name]/[deviceId] are
 * kept as their own indexed-by-query columns (not just baked into
 * [addressKey] and parsed back out) so [SignalSessionDao]'s
 * `getSubDeviceSessions`/`deleteAllSessions` queries (which operate by name
 * only, per libsignal-client's `SessionStore` contract) don't need string
 * splitting/prefix-`LIKE` matching.
 *
 * [name] is [org.signal.libsignal.protocol.SignalProtocolAddress.name]'s
 * string form, which per the Phase 1 messaging plan is the hex-encoded
 * per-install `senderDeviceId` (`SettingsRepository.getOrCreateStableSenderDeviceId()`)
 * -- not a new identity concept.
 *
 * [sessionRecordBytes] is the session's own `.serialize()` output,
 * reconstructed later via `SessionRecord(bytes)` -- never re-derived from
 * any other representation. This is load-bearing, not a style choice:
 * `DoubleRatchetSessionTest`'s forward-secrecy case captures/restores a
 * `SessionRecord` via exactly this serialize/deserialize round trip, and
 * this store's persistence tests extend that same pattern against Room.
 */
@Entity(tableName = "signal_sessions")
data class SessionEntity(
    @PrimaryKey
    val addressKey: String,
    val name: String,
    val deviceId: Int,
    val sessionRecordBytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SessionEntity) return false
        return addressKey == other.addressKey &&
            name == other.name &&
            deviceId == other.deviceId &&
            sessionRecordBytes.contentEquals(other.sessionRecordBytes)
    }

    override fun hashCode(): Int {
        var result = addressKey.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + deviceId
        result = 31 * result + sessionRecordBytes.contentHashCode()
        return result
    }

    companion object {
        fun addressKey(name: String, deviceId: Int): String = "$name:$deviceId"
    }
}
