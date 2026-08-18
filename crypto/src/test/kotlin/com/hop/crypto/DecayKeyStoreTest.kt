package com.hop.crypto

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * A [Clock] whose instant can be advanced under test control, so decay-window
 * expiry can be tested deterministically instead of via a real sleep.
 */
private class MutableClock(private var current: Instant) : Clock() {
    override fun getZone(): java.time.ZoneId = ZoneOffset.UTC
    override fun withZone(zone: java.time.ZoneId?): Clock = this
    override fun instant(): Instant = current
    fun advanceBy(duration: Duration) {
        current = current.plus(duration)
    }
}

class DecayKeyStoreTest {

    private val random = SecureRandom()

    private fun randomBytes(size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }

    @Test
    fun `a key is retrievable before its decay window expires`() {
        val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
        val store = DecayKeyStore(clock)
        val cek = randomBytes(32)

        store.store("clip-1", cek, Duration.ofMinutes(10))
        clock.advanceBy(Duration.ofMinutes(5))

        assertContentEquals(cek, store.retrieve("clip-1"))
    }

    @Test
    fun `a key is not retrievable after its decay window expires, and is gone from the store`() {
        val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
        val store = DecayKeyStore(clock)
        val cek = randomBytes(32)

        store.store("clip-1", cek, Duration.ofMinutes(10))
        clock.advanceBy(Duration.ofMinutes(10).plusSeconds(1))

        assertNull(store.retrieve("clip-1"))
        // Confirm it's actually gone, not just "expired but still there" -- a
        // second retrieve (even if clock somehow rewound) must still miss,
        // proving deletion actually happened rather than a stale expiry check.
        clock.advanceBy(Duration.ofSeconds(-3600)) // hypothetically rewind clock
        assertNull(store.retrieve("clip-1"))
    }

    @Test
    fun `retrieving an unknown contentId returns null`() {
        val store = DecayKeyStore()
        assertNull(store.retrieve("never-stored"))
    }

    @Test
    fun `re-storing (a re-share) extends the expiry`() {
        val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
        val store = DecayKeyStore(clock)
        val cek = randomBytes(32)

        store.store("clip-1", cek, Duration.ofMinutes(10))
        clock.advanceBy(Duration.ofMinutes(9))
        // Re-share: extend the window before it would have expired.
        store.store("clip-1", cek, Duration.ofMinutes(10))
        clock.advanceBy(Duration.ofMinutes(9))

        // 18 minutes have passed total, which is past the *original* 10-minute
        // window but well within the re-extended one.
        assertContentEquals(cek, store.retrieve("clip-1"))
    }

    @Test
    fun `a zero-length decay window expires immediately`() {
        val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
        val store = DecayKeyStore(clock)

        store.store("clip-1", randomBytes(32), Duration.ZERO)

        assertNull(store.retrieve("clip-1"))
    }

    @Test
    fun `a negative decay window is rejected`() {
        val store = DecayKeyStore()
        assertFailsWith<IllegalArgumentException> {
            store.store("clip-1", randomBytes(32), Duration.ofSeconds(-1))
        }
    }

    // --- Integration: ContentEncryption + DecayKeyStore ---

    @Test
    fun `content is decryptable before decay, and undecryptable after because the key is gone`() {
        val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
        val store = DecayKeyStore(clock)

        val cek = ContentEncryption.generateKey()
        val plaintext = "a post's worth of bytes".toByteArray()
        val ciphertext = ContentEncryption.encrypt(cek, plaintext)

        store.store("clip-42", cek.encoded, Duration.ofHours(1))

        // Before expiry: a client can retrieve the key and decrypt normally.
        val liveKeyBytes = store.retrieve("clip-42")
        checkNotNull(liveKeyBytes) { "key should still be live" }
        val decrypted = ContentEncryption.decrypt(ContentEncryption.keyFromBytes(liveKeyBytes), ciphertext)
        assertContentEquals(plaintext, decrypted)

        // Past the decay window: the key is gone from this store. The
        // ciphertext itself is untouched (per ADR 0003, it may still exist
        // elsewhere) -- what's gone is this client's ability to unwrap it.
        clock.advanceBy(Duration.ofHours(1).plusSeconds(1))
        val expiredKeyBytes = store.retrieve("clip-42")
        assertNull(expiredKeyBytes)

        // Reconfirm: the ciphertext is still there and still opaque without a key.
        val wrongKey = ContentEncryption.generateKey()
        assertFailsWith<AEADBadTagException> { ContentEncryption.decrypt(wrongKey, ciphertext) }
    }
}
