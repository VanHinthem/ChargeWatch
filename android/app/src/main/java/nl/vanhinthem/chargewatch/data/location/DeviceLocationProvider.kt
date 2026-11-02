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
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

data class DevicePosition(val latitude: Double, val longitude: Double, val accuracyMeters: Float, val approximate: Boolean)

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

class DeviceLocationProvider(private val context: Context) {
    suspend fun acquire(): PositionResult = withContext(Dispatchers.Main) {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return@withContext PositionResult.PermissionMissing
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val provider = when {
            fine && manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> return@withContext PositionResult.Disabled
        }
        try {
            val found = withTimeoutOrNull(12_000) {
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
            if (found == null || !found.latitude.isFinite() || !found.longitude.isFinite() ||
                found.latitude !in -90.0..90.0 || found.longitude !in -180.0..180.0
            ) PositionResult.Unavailable
            else PositionResult.Found(DevicePosition(found.latitude, found.longitude, found.accuracy, !fine))
        } catch (_: SecurityException) {
            PositionResult.PermissionMissing
        } catch (_: Exception) {
            PositionResult.Unavailable
        }
    }
}
