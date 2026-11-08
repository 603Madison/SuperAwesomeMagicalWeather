package app.northvane.weather.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import androidx.core.content.ContextCompat
import app.northvane.core.model.GeoPoint
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * One-shot location using the platform LocationManager (no Google Play
 * services dependency), only while the app is in use. Uses a precise fix when
 * the user granted precise location, otherwise the approximate one. Results
 * are rounded to ~11 m before leaving this class.
 */
class DeviceLocationProvider(private val context: Context) : app.northvane.weather.DeviceLocation {

    override fun hasPermission(): Boolean = granted(Manifest.permission.ACCESS_COARSE_LOCATION) || hasPrecise()

    override fun hasPrecise(): Boolean = granted(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // checked by hasPermission()
    override suspend fun current(): GeoPoint? {
        if (!hasPermission()) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val fused = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) listOf(LocationManager.FUSED_PROVIDER) else emptyList()
        val providers = (
            if (hasPrecise()) fused + LocationManager.GPS_PROVIDER + LocationManager.NETWORK_PROVIDER
            else listOf(LocationManager.NETWORK_PROVIDER) + fused + LocationManager.GPS_PROVIDER
            ).filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }

        val fresh = providers.firstOrNull()?.let { provider ->
            withTimeoutOrNull(20_000) { requestOnce(manager, provider) }
        }
        val best = fresh ?: providers.mapNotNull { provider ->
            try {
                manager.getLastKnownLocation(provider)
            } catch (e: SecurityException) {
                null
            }
        }.maxByOrNull { it.time }
        return best?.let { GeoPoint(it.latitude, it.longitude).rounded() }
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestOnce(manager: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { cont ->
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val signal = CancellationSignal()
                    manager.getCurrentLocation(provider, signal, ContextCompat.getMainExecutor(context)) { location ->
                        if (cont.isActive) cont.resume(location)
                    }
                    cont.invokeOnCancellation { signal.cancel() }
                } else {
                    // Older platforms call every listener method, so implement them all.
                    val listener = object : LocationListener {
                        override fun onLocationChanged(location: Location) {
                            manager.removeUpdates(this)
                            if (cont.isActive) cont.resume(location)
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                        override fun onProviderEnabled(provider: String) = Unit
                        override fun onProviderDisabled(provider: String) {
                            manager.removeUpdates(this)
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                    @Suppress("DEPRECATION")
                    manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                    cont.invokeOnCancellation { manager.removeUpdates(listener) }
                }
            } catch (e: SecurityException) {
                if (cont.isActive) cont.resume(null)
            } catch (e: IllegalArgumentException) {
                if (cont.isActive) cont.resume(null)
            }
        }
}
