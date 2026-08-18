package com.hop.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hop.crypto.ContentEncryption
import com.hop.crypto.DecayKeyStore
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import javax.crypto.AEADBadTagException
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * End-to-end: a real `crypto/` [DecayKeyStore] backed by [RoomDecayKeyStorage]
 * (Room, not the in-memory default) -- proving Part 1's [com.hop.crypto.DecayKeyStorage]
 * interface actually gives ADR 0003's decay-by-key-deletion primitive a real,
 * cross-restart-capable persistence path. Mirrors
 * `crypto/src/test/kotlin/com/hop/crypto/DecayKeyStoreTest.kt`'s
 * "content is decryptable before decay, and undecryptable after" case, just
 * backed by Room here instead of the default in-memory store.
 */
@RunWith(AndroidJUnit4::class)
class RoomDecayKeyStorageTest {

    private lateinit var db: HopDatabase

    /** A [Clock] whose instant can be advanced under test control. */
    private class MutableClock(private var current: Instant) : Clock() {
        override fun getZone(): java.time.ZoneId = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?): Clock = this
        override fun instant(): Instant = current
        fun advanceBy(duration: Duration) {
            current = current.plus(duration)
        }
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            HopDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun keyStoredViaRoomIsRetrievableBeforeExpiryAndGoneAfter() {
        val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
        val storage = RoomDecayKeyStorage(db.decayKeyDao())
        val store = DecayKeyStore(clock, storage)

        val cek = ContentEncryption.generateKey()
        val plaintext = "a post's worth of bytes".toByteArray()
        val ciphertext = ContentEncryption.encrypt(cek, plaintext)

        store.store("clip-42", cek.encoded, Duration.ofHours(1))

        // Before expiry: the key round-trips through Room and decrypts normally.
        clock.advanceBy(Duration.ofMinutes(30))
        val liveKeyBytes = store.retrieve("clip-42")
        checkNotNull(liveKeyBytes) { "key should still be live" }
        val decrypted = ContentEncryption.decrypt(ContentEncryption.keyFromBytes(liveKeyBytes), ciphertext)
        assertContentEquals(plaintext, decrypted)

        // Past the decay window: gone from the Room-backed store, same as the
        // in-memory default -- this is the actual persistence guarantee this
        // slice exists to add (survives what the in-memory version couldn't:
        // simulate this by re-wrapping a *new* DecayKeyStore instance over the
        // same underlying Room database, i.e. what happens across an app
        // restart).
        clock.advanceBy(Duration.ofHours(1))
        val restartedStore = DecayKeyStore(clock, RoomDecayKeyStorage(db.decayKeyDao()))
        val expiredKeyBytes = restartedStore.retrieve("clip-42")
        assertNull(expiredKeyBytes)

        // Ciphertext itself is untouched (ADR 0003: decay makes the key
        // unrecoverable, it doesn't destroy the ciphertext) -- it's just opaque
        // without a live key now.
        val wrongKey = ContentEncryption.generateKey()
        assertFailsWith<AEADBadTagException> { ContentEncryption.decrypt(wrongKey, ciphertext) }
    }

    @Test
    fun keyPersistsAcrossANewDecayKeyStoreInstanceBeforeExpiry() {
        // This is the concrete case this slice exists for: a fresh
        // `DecayKeyStore` instance (simulating a post-restart re-wiring of the
        // Room database into a new in-memory `DecayKeyStore`) can still see a
        // key stored by a previous instance, unlike the in-memory-only default.
        val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
        val cek = byteArrayOf(9, 8, 7, 6, 5, 4, 3, 2, 1, 0)

        val firstStore = DecayKeyStore(clock, RoomDecayKeyStorage(db.decayKeyDao()))
        firstStore.store("clip-restart", cek, Duration.ofHours(1))

        clock.advanceBy(Duration.ofMinutes(1))
        val secondStore = DecayKeyStore(clock, RoomDecayKeyStorage(db.decayKeyDao()))
        assertContentEquals(cek, secondStore.retrieve("clip-restart"))
    }
}
