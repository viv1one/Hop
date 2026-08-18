package com.hop.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room-backed row for a single `crypto/`'s `DecayKeyStorage` entry. Table
 * `decay_keys`. Backs [RoomDecayKeyStorage], which is the concrete reason
 * `DecayKeyStorage` exists as a pluggable interface at all -- without this,
 * `crypto/`'s `DecayKeyStore` has no path to real persistence, and every
 * decay key (and therefore every received post) becomes permanently
 * undecryptable the moment the app process dies, regardless of whether the
 * real ADR 0003 decay window has actually elapsed.
 *
 * This table stores wrapped keys only, never plaintext content-encryption
 * keys in a form usable without going through `DecayKeyStore`'s
 * expiry-check-and-delete-on-read policy -- that policy lives in `crypto/`,
 * not here (this table is a dumb key/expiry store, per `DecayKeyStorage`'s
 * contract).
 */
@Entity(tableName = "decay_keys")
data class DecayKeyEntity(
    @PrimaryKey
    val contentId: String,
    val wrappedCek: ByteArray,
    val expiresAtMs: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DecayKeyEntity) return false
        return contentId == other.contentId &&
            wrappedCek.contentEquals(other.wrappedCek) &&
            expiresAtMs == other.expiresAtMs
    }

    override fun hashCode(): Int {
        var result = contentId.hashCode()
        result = 31 * result + wrappedCek.contentHashCode()
        result = 31 * result + expiresAtMs.hashCode()
        return result
    }
}
