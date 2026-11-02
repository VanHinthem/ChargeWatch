package nl.vanhinthem.chargewatch.data.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

data class DevicePosition(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val approximate: Boolean
)

sealed interface PositionResult {
    data class Found(val position: DevicePosition) : PositionResult
    data object PermissionMissing : PositionResult
    data object Disabled : PositionResult
    data object Unavailable : PositionResult
}

fun locationPermissionStatus(context: Context): String {
    val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    return when {
        fine -> "Precieze locatie"
        coarse -> "Geschatte locatie"
        else -> "Niet toegestaan"
    }
}

/** One-shot foreground fix. Never return an unverified cached Android last-known location. */
class DeviceLocationProvider(private val context: Context) {
    suspend fun acquire(): PositionResult = withContext(Dispatchers.Main) {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return@withContext PositionResult.PermissionMissing
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val gpsEnabled = fine && runCatching { manager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)
        val networkEnabled = runCatching { manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false)
        if (!gpsEnabled && !networkEnabled) return@withContext PositionResult.Disabled
        try {
            // Prefer GPS for fine permission, but allow a fresh network fix if GPS cannot lock.
            val fromGps = if (gpsEnabled) acquireOnce(manager, LocationManager.GPS_PROVIDER, 8_000) else null
            val found = fromGps ?: if (networkEnabled)
                acquireOnce(manager, LocationManager.NETWORK_PROVIDER, 5_000) else null
            val ageMs = found?.let { (SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) / 1_000_000L }
            if (found == null || ageMs == null || ageMs !in 0L..120_000L ||
                !found.latitude.isFinite() || !found.longitude.isFinite() ||
                found.latitude !in -90.0..90.0 || found.longitude !in -180.0..180.0
            ) PositionResult.Unavailable
            else PositionResult.Found(DevicePosition(found.latitude, found.longitude, found.accuracy, !fine))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            PositionResult.PermissionMissing
        } catch (_: Exception) {
            PositionResult.Unavailable
        }
    }

    private suspend fun acquireOnce(manager: LocationManager, provider: String, timeoutMs: Long): Location? =
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Location?> { continuation ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val signal = CancellationSignal()
                    continuation.invokeOnCancellation { signal.cancel() }
                    manager.getCurrentLocation(provider, signal, ContextCompat.getMainExecutor(context)) { result ->
                        if (continuation.isActive) continuation.resume(result)
                    }
                } else {
                    val listener = object : LocationListener {
                        override fun onLocationChanged(location: Location) {
                            if (continuation.isActive) continuation.resume(location)
                        }
                        override fun onProviderDisabled(provider: String) {
                            if (continuation.isActive) continuation.resume(null)
                        }
                    }
                    manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                    continuation.invokeOnCancellation { manager.removeUpdates(listener) }
                }
            }
        }
}
