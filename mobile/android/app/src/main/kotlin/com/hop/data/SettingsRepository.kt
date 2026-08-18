package com.hop.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.hop.protocol.Frame
import com.hop.protocol.ReachTier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.SecureRandom
import java.util.Base64

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "hop_settings")

/**
 * Small-scalar local settings, Jetpack Preferences DataStore-backed (not
 * SharedPreferences -- DataStore avoids its torn-write issues; not a new Room
 * table -- disproportionate machinery for ~3 scalar values). No account, no
 * server sync: every value here is local-device-only, consistent with the
 * repo-wide "no accounts" invariant.
 *
 * Reach tier is a single value here on purpose (hop-dev skill, PRD §4.2): it
 * is both the default reach for a user's own posts and the browse-feed zoom
 * level. Don't add a second "browse radius" preference key -- a per-post
 * override at post time is ephemeral UI state, not a second persisted
 * setting, and isn't stored here.
 */
class SettingsRepository(private val context: Context) {

    private val deviceIdMutex = Mutex()

    private object Keys {
        val HAS_COMPLETED_FIRST_RUN = booleanPreferencesKey("has_completed_first_run")
        val DEFAULT_REACH_TIER = stringPreferencesKey("default_reach_tier")
        val SENDER_DEVICE_ID = stringPreferencesKey("sender_device_id_b64")
    }

    val hasCompletedFirstRun: Flow<Boolean> =
        context.dataStore.data.map { prefs -> prefs[Keys.HAS_COMPLETED_FIRST_RUN] ?: false }

    val defaultReachTier: Flow<ReachTier?> =
        context.dataStore.data.map { prefs ->
            prefs[Keys.DEFAULT_REACH_TIER]?.let { stored ->
                // Ignore/treat-as-unset rather than crash if a future build ever
                // stores a tier name this build doesn't recognize -- forward
                // compatibility for a purely local preference value.
                runCatching { ReachTier.valueOf(stored) }.getOrNull()
            }
        }

    suspend fun setHasCompletedFirstRun(completed: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.HAS_COMPLETED_FIRST_RUN] = completed }
    }

    suspend fun setDefaultReachTier(tier: ReachTier) {
        context.dataStore.edit { prefs -> prefs[Keys.DEFAULT_REACH_TIER] = tier.name }
    }

    /**
     * Returns this install's stable per-install sender device id, generating
     * and persisting one (16 random bytes, matching
     * [com.hop.protocol.Frame.SENDER_DEVICE_ID_SIZE]) on first call if none
     * exists yet.
     *
     * This is the fix for a real bug in the Phase 0 spike
     * (`com.hop.spike.WifiDirectSpike`, untouched by this change): it
     * generates a fresh random `senderDeviceId` on every single send, which
     * contradicts `WIRE_FORMAT.md`'s documented "ephemeral per-install
     * identifier" contract -- one phone's posts would otherwise appear to
     * come from a different sender on every transfer. This function is only
     * the correct primitive; nothing in this task wires it into transport
     * (that's a later slice).
     *
     * Not a substitute for the ADR-0004-grade attested device identity used
     * by faucets/"don't relay" counting/rate-limiting/blocking -- this is a
     * plain per-install random id with no hardware-attestation backing.
     */
    suspend fun getOrCreateStableSenderDeviceId(): ByteArray {
        // Guards against two concurrent first-callers each generating and
        // persisting a different id (last write would silently win, and the
        // caller that lost the race would go on using a device id that no
        // longer matches what's stored) -- unlikely in practice (this is
        // normally called once at startup) but cheap to make correct.
        return deviceIdMutex.withLock {
            val existing = context.dataStore.data.first()[Keys.SENDER_DEVICE_ID]
            if (existing != null) {
                return@withLock Base64.getDecoder().decode(existing)
            }
            val generated = ByteArray(Frame.SENDER_DEVICE_ID_SIZE).also { SecureRandom().nextBytes(it) }
            val encoded = Base64.getEncoder().encodeToString(generated)
            context.dataStore.edit { prefs -> prefs[Keys.SENDER_DEVICE_ID] = encoded }
            generated
        }
    }

    /**
     * Test-only: clears every persisted setting. Not called by any production
     * code path -- exists so instrumented tests (real DataStore file I/O,
     * shared per-process singleton keyed by context) can start from a known
     * clean state instead of leaking state between test methods.
     */
    suspend fun clearAllForTesting() {
        context.dataStore.edit { prefs -> prefs.clear() }
    }
}
