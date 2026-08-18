package com.hop.data

import com.hop.crypto.DecayKeyStorage
import com.hop.crypto.DecayKeyStorageEntry
import java.time.Instant

/**
 * Room-backed [DecayKeyStorage] (`crypto/`'s pluggable decay-key persistence
 * interface -- see `crypto/`'s `DecayKeyStore.kt`). This is the concrete
 * reason that interface exists: without this class, `crypto/`'s
 * `DecayKeyStore` has no path to real, cross-restart persistence, and every
 * post received via `EncryptedFrameCodec.decode` becomes permanently
 * undecryptable the moment the app process dies -- not because the real ADR
 * 0003 decay window elapsed, but just because Android killed the app in the
 * background.
 *
 * Dependency direction note (ADR 0001): this class lives in `com.hop.data`
 * (`mobile/android/`, Room/Android-specific) and depends on `crypto/`'s
 * `DecayKeyStorage` interface. That's fine -- `mobile/android/` depending on
 * `crypto/` is already established (see `mobile/android/app/build.gradle.kts`).
 * The only forbidden direction is `crypto/` depending back on `mobile/android/`
 * or `protocol/`, which this class does not introduce (`crypto/` has zero
 * knowledge this class exists).
 *
 * Deliberately synchronous, matching [DecayKeyStorage]'s contract -- see
 * [DecayKeyDao]'s doc for why its backing queries are blocking rather than
 * `suspend`. Callers (ultimately `DecayKeyStore.store`/`retrieve`) must not
 * invoke this from the main thread.
 */
class RoomDecayKeyStorage(private val dao: DecayKeyDao) : DecayKeyStorage {

    override fun put(contentId: String, wrappedCek: ByteArray, expiresAt: Instant) {
        dao.insertOrReplace(
            DecayKeyEntity(
                contentId = contentId,
                wrappedCek = wrappedCek,
                expiresAtMs = expiresAt.toEpochMilli(),
            )
        )
    }

    override fun get(contentId: String): DecayKeyStorageEntry? {
        val entity = dao.getById(contentId) ?: return null
        return DecayKeyStorageEntry(
            wrappedCek = entity.wrappedCek,
            expiresAt = Instant.ofEpochMilli(entity.expiresAtMs),
        )
    }

    override fun remove(contentId: String) {
        dao.deleteById(contentId)
    }
}
