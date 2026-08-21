package com.hop.app.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * A single lat/long reading, local to this device only. This is deliberately
 * NEVER put on the wire anywhere in this codebase --
 * [com.hop.protocol.Geohash]/[com.hop.protocol.ReachTierGeohash] turn a
 * [DeviceLocation] into a geohash-prefix string locally, and only that
 * derived (coarser, non-reversible-to-exact-coordinates) prefix is ever used
 * as a DHT topic key ([com.hop.topics.GeohashTopicKey]). See [LocationProvider]'s
 * own doc for the full reasoning.
 */
data class DeviceLocation(val latitude: Double, val longitude: Double)

/**
 * Swappable location-read seam, matching this codebase's existing
 * [com.hop.crypto.AttestationProvider]/`StubAttestationProvider` pattern: an
 * interface real production code depends on, a real platform-backed
 * implementation ([FusedLocationProvider]) below, and a trivially fake-able
 * seam for plain-JVM unit tests. In practice, callers in this app (see
 * `PostComposerScreen`/`FeedScreen`) close over this behind a narrow suspend
 * lambda passed into their view model, the same "swap a whole
 * `Context`-backed class for a plain fake lambda" pattern already used for
 * `TransportManager`/`SettingsRepository` capabilities elsewhere -- so
 * [FusedLocationProvider] itself never needs to be faked in a ViewModel unit
 * test.
 *
 * **What this is for, and the one thing it must never be used for:** a real
 * on-device location read, used ONLY to compute this device's current
 * geohash-prefix cell locally ([com.hop.protocol.ReachTierGeohash]) before
 * calling [com.hop.topics.TopicSubscription.publish]/`browse`. Raw
 * latitude/longitude never gets encoded onto the wire anywhere in this
 * codebase -- only the derived geohash-prefix string does. Do not confuse
 * this with BLE's `neverForLocation` posture
 * (`com.hop.transport.TransportManager`'s manifest note) -- that's a
 * *different* permission surface (BLE presence scanning, which genuinely
 * needs no location) declaring it derives no location from scan results.
 * This class does the opposite on purpose: it is a real location read,
 * gated on the exact same ACCESS_FINE_LOCATION/ACCESS_COARSE_LOCATION grant
 * `TransportManager.REQUIRED_PERMISSIONS` already requests for WiFi Direct
 * peer discovery -- reused here, never requested a second time.
 */
interface LocationProvider {
    /**
     * Returns this device's best current location, or `null` if it's
     * unavailable for any reason (permission not granted, location services
     * off, no fix within a bounded wait, a transient Play Services failure,
     * etc.). Every caller in this app treats `null` as "skip the DHT
     * publish/browse for this attempt," never as an error surfaced to the
     * user -- mesh mechanics stay invisible (PRD §5), and reach radius is
     * the only mesh concept the user ever sees, only at post time.
     */
    suspend fun currentLocation(): DeviceLocation?
}

/**
 * [LocationProvider] backed by Play Services'
 * `FusedLocationProviderClient` -- the standard choice for this. This is the
 * first direct Play Services API dependency `:app` takes on (see
 * `app/build.gradle.kts`'s added `play-services-location` coordinate).
 */
class FusedLocationProvider(context: Context) : LocationProvider {

    private val appContext = context.applicationContext
    private val client by lazy { LocationServices.getFusedLocationProviderClient(appContext) }

    @SuppressLint("MissingPermission") // guarded by hasLocationPermission() below
    override suspend fun currentLocation(): DeviceLocation? {
        if (!hasLocationPermission()) {
            Log.d(TAG, "Skipping location read -- location permission not granted yet")
            return null
        }
        return try {
            suspendCancellableCoroutine { continuation ->
                val cancellationTokenSource = CancellationTokenSource()
                continuation.invokeOnCancellation { cancellationTokenSource.cancel() }
                val request = CurrentLocationRequest.Builder()
                    .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
                    .build()
                client.getCurrentLocation(request, cancellationTokenSource.token)
                    .addOnSuccessListener { location ->
                        if (continuation.isActive) {
                            continuation.resume(location?.let { DeviceLocation(it.latitude, it.longitude) })
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "getCurrentLocation failed", e)
                        if (continuation.isActive) continuation.resume(null)
                    }
            }
        } catch (e: SecurityException) {
            // Real-hardware edge case: permission can be revoked between the
            // hasLocationPermission() check above and this call actually
            // reaching the platform (e.g. the user revokes it from system
            // settings mid-call) -- never let that crash the caller.
            Log.e(TAG, "Location permission revoked mid-call", e)
            null
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TAG = "FusedLocationProvider"
    }
}
