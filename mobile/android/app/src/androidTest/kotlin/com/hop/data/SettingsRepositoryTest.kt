package com.hop.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hop.protocol.Frame
import com.hop.protocol.ReachTier
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Instrumented rather than a plain JVM unit test: Jetpack Preferences
 * DataStore does real file I/O against a Context-backed files dir
 * (`preferencesDataStore` delegate), which plain JVM unit tests (Robolectric
 * not set up in this repo) can't exercise -- matching this module's existing
 * pattern of instrumented tests for anything needing real Android I/O (see
 * PostDaoTest, BlockedIdentityDaoTest).
 *
 * [SettingsRepository]'s backing DataStore is a per-process singleton keyed
 * by file name ("hop_settings"), not per-instance -- state persists across
 * test methods within the same instrumentation process unless explicitly
 * cleared, hence [clearBefore].
 */
@RunWith(AndroidJUnit4::class)
class SettingsRepositoryTest {

    private lateinit var repository: SettingsRepository

    @Before
    fun setUp() = runBlocking {
        repository = SettingsRepository(ApplicationProvider.getApplicationContext())
        repository.clearAllForTesting()
    }

    @Test
    fun hasCompletedFirstRunDefaultsToFalse() = runBlocking {
        assertFalse(repository.hasCompletedFirstRun.first())
    }

    @Test
    fun setHasCompletedFirstRunPersists() = runBlocking {
        repository.setHasCompletedFirstRun(true)

        assertTrue(repository.hasCompletedFirstRun.first())
    }

    @Test
    fun defaultReachTierIsNullWhenUnset() = runBlocking {
        assertNull(repository.defaultReachTier.first())
    }

    @Test
    fun setDefaultReachTierPersistsAndRoundTrips() = runBlocking {
        repository.setDefaultReachTier(ReachTier.CITY)

        assertEquals(ReachTier.CITY, repository.defaultReachTier.first())
    }

    @Test
    fun setDefaultReachTierOverwritesPreviousValue() = runBlocking {
        repository.setDefaultReachTier(ReachTier.LOCALITY)
        repository.setDefaultReachTier(ReachTier.COUNTRY)

        assertEquals(ReachTier.COUNTRY, repository.defaultReachTier.first())
    }

    @Test
    fun getOrCreateStableSenderDeviceIdGeneratesCorrectSize() = runBlocking {
        val id = repository.getOrCreateStableSenderDeviceId()

        assertEquals(Frame.SENDER_DEVICE_ID_SIZE, id.size)
    }

    @Test
    fun getOrCreateStableSenderDeviceIdIsStableAcrossCalls() = runBlocking {
        val first = repository.getOrCreateStableSenderDeviceId()
        val second = repository.getOrCreateStableSenderDeviceId()

        assertContentEquals(first, second)
    }

    @Test
    fun getOrCreateStableSenderDeviceIdIsStableAcrossNewRepositoryInstances() = runBlocking {
        // Regression check for the exact bug this function fixes
        // (WifiDirectSpike.kt regenerating a random id per-send instead of
        // once per install): a fresh SettingsRepository instance backed by
        // the same DataStore file must observe the same id, not generate a
        // new one.
        val original = repository.getOrCreateStableSenderDeviceId()

        val secondInstance = SettingsRepository(ApplicationProvider.getApplicationContext())
        val fromSecondInstance = secondInstance.getOrCreateStableSenderDeviceId()

        assertContentEquals(original, fromSecondInstance)
    }
}
