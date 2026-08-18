package com.hop.crypto

import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * ADR 0003's crude Phase-1 decay primitive: maps a content id to its wrapped
 * content-encryption key (CEK, see [ContentEncryption]) and an expiry timestamp,
 * and enforces decay by making the key permanently unrecoverable from this store
 * once the decay window has passed -- not by any cooperation from whoever is
 * still holding the ciphertext.
 *
 * This is deliberately a placeholder shape, not the target design (ADR 0003
 * consequences section): a real implementation needs per-reach-tier key
 * wrapping, re-share window extension, and clock/hop-count sync across a
 * partitioned mesh. What Phase 1 needs to get right now is the enforcement
 * *mechanism* -- deletion on expiry read, not a feed-ranking flag -- so the wire
 * format carrying wrapped keys doesn't need a breaking change later.
 *
 * Limit (state plainly, per ADR 0003): deleting the key from *this* store means
 * an honest client using this store can no longer decrypt the content after
 * expiry. It does not remove the ciphertext from wherever else it's stored (a
 * peer's local cache, or the DHT once Phase 4 lands), and it does not stop a
 * client that already read the key out of this store before expiry from having
 * kept a copy elsewhere. This is a deterrent against casual/default-client
 * access after the decay window, not a cryptographic guarantee against a
 * determined actor who captured the key while it was live.
 *
 * Backing storage is pluggable ([DecayKeyStorage], mirroring [Identity.kt]'s
 * `IdentityKeyStorage`/`InMemoryIdentityKeyStorage` split): without real
 * persistence, every key -- and therefore every received post -- becomes
 * permanently undecryptable the moment the app process dies, which is not the
 * real ADR 0003 decay window, just an Android process-death artifact. This
 * class keeps owning the expiry-check-and-delete-on-read *policy* regardless of
 * which storage backs it -- [DecayKeyStorage] itself is a dumb key/expiry store,
 * not a decision-maker about decay.
 */
class DecayKeyStore(
    private val clock: Clock = Clock.systemUTC(),
    private val storage: DecayKeyStorage = InMemoryDecayKeyStorage(),
) {

    /**
     * Stores [wrappedCek] for [contentId], recoverable via [retrieve] only until
     * [decayWindow] elapses from now. Overwrites any existing entry for the same
     * [contentId] -- e.g. a re-share within the window should call this again
     * with a fresh expiry, per the "decay unless re-shared" model (ADR 0003).
     */
    @Synchronized
    fun store(contentId: String, wrappedCek: ByteArray, decayWindow: Duration) {
        require(!decayWindow.isNegative) { "decayWindow must not be negative" }
        storage.put(contentId, wrappedCek.copyOf(), clock.instant().plus(decayWindow))
    }

    /**
     * Returns the wrapped CEK for [contentId], or null if it was never stored or
     * has expired. Expiry is enforced here, not by a background sweep: reading
     * past expiry deletes the entry as a side effect, so the key becomes
     * permanently unrecoverable from this store from this point on -- this
     * deletion *is* the decay enforcement mechanism (ADR 0003), not a courtesy
     * check on top of some other cleanup process.
     */
    @Synchronized
    fun retrieve(contentId: String): ByteArray? {
        val entry = storage.get(contentId) ?: return null
        if (!clock.instant().isBefore(entry.expiresAt)) {
            storage.remove(contentId)
            return null
        }
        return entry.wrappedCek.copyOf()
    }
}

/**
 * Where [DecayKeyStore] persists its content-id -> wrapped-CEK-and-expiry
 * entries, as an interface so [DecayKeyStore] doesn't need to know whether
 * storage is in-memory (tests / headless use) or durable (Room-backed on
 * Android -- see `com.hop.data.RoomDecayKeyStorage` in `mobile/android/app/`).
 *
 * Deliberately dumb: this interface has no opinion about decay. It does not
 * check expiry and does not delete on read -- [DecayKeyStore] owns that policy
 * on top of whatever [DecayKeyStorage] implementation it's given, so every
 * implementation of this interface behaves identically from a decay-semantics
 * standpoint.
 */
interface DecayKeyStorage {
    /** Unconditionally stores/overwrites the entry for [contentId]. */
    fun put(contentId: String, wrappedCek: ByteArray, expiresAt: Instant)

    /** Returns the raw stored entry for [contentId], or null if none exists -- expired or not. */
    fun get(contentId: String): DecayKeyStorageEntry?

    /** Deletes the entry for [contentId], if any. No-op if none exists. */
    fun remove(contentId: String)
}

/** A single [DecayKeyStorage] entry: the wrapped CEK and when it expires. */
data class DecayKeyStorageEntry(val wrappedCek: ByteArray, val expiresAt: Instant) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DecayKeyStorageEntry) return false
        return wrappedCek.contentEquals(other.wrappedCek) && expiresAt == other.expiresAt
    }

    override fun hashCode(): Int = 31 * wrappedCek.contentHashCode() + expiresAt.hashCode()
}

/**
 * In-memory reference [DecayKeyStorage]. Not durable across process restarts --
 * fine for tests and headless composition, not a substitute for the real
 * Room-backed implementation this interface exists to allow. This is the
 * default backing store for [DecayKeyStore] when no [DecayKeyStorage] is
 * supplied, matching this store's pre-refactor in-memory-only behavior.
 */
class InMemoryDecayKeyStorage : DecayKeyStorage {
    private val entries = mutableMapOf<String, DecayKeyStorageEntry>()

    @Synchronized
    override fun put(contentId: String, wrappedCek: ByteArray, expiresAt: Instant) {
        entries[contentId] = DecayKeyStorageEntry(wrappedCek.copyOf(), expiresAt)
    }

    @Synchronized
    override fun get(contentId: String): DecayKeyStorageEntry? = entries[contentId]

    @Synchronized
    override fun remove(contentId: String) {
        entries.remove(contentId)
    }
}
