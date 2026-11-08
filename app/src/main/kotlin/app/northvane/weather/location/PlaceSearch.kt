package app.northvane.weather.location

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import app.northvane.core.model.GeoPoint
import app.northvane.core.owm.OwmClient
import app.northvane.core.owm.PlaceResult
import app.northvane.weather.SecretStore
import app.northvane.weather.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Turns a typed place name into coordinates. Uses the platform Geocoder (no API
 * key; on most devices backed by the system's location services), then
 * OpenWeatherMap's geocoder if the user has configured a key. Plain
 * "lat, lon" input is accepted directly.
 */
class AndroidPlaceSearch(
    private val context: Context,
    private val owm: OwmClient,
    private val secrets: SecretStore,
    private val settings: SettingsRepository,
) : app.northvane.weather.PlaceSearch {
    override suspend fun search(query: String): List<PlaceResult> {
        val q = query.trim().take(100)
        if (q.length < 2) return emptyList()
        parseCoordinates(q)?.let { p ->
            return listOf(PlaceResult("${GeoPoint.format(p.latitude)}, ${GeoPoint.format(p.longitude)}", p.latitude, p.longitude))
        }
        val platform = if (Geocoder.isPresent()) runCatching { geocode(q) }.getOrDefault(emptyList()) else emptyList()
        if (platform.isNotEmpty()) return platform

        val key = if (settings.settings.first().owmEnabled) {
            withContext(Dispatchers.IO) { secrets.get(SecretStore.OWM_API_KEY) }
        } else {
            null
        }
        return if (key != null) runCatching { owm.geocode(q, key) }.getOrDefault(emptyList()) else emptyList()
    }

    private suspend fun geocode(query: String): List<PlaceResult> {
        val geocoder = Geocoder(context, Locale.getDefault())
        val addresses: List<Address> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            withTimeoutOrNull(10_000) {
                suspendCancellableCoroutine { cont ->
                    geocoder.getFromLocationName(query, 5, object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) {
                            if (cont.isActive) cont.resume(addresses.toList())
                        }

                        override fun onError(errorMessage: String?) {
                            if (cont.isActive) cont.resume(emptyList())
                        }
                    })
                }
            }.orEmpty()
        } else {
            withContext(Dispatchers.IO) {
                @Suppress("DEPRECATION")
                geocoder.getFromLocationName(query, 5).orEmpty()
            }
        }
        return addresses.filter { it.hasLatitude() && it.hasLongitude() }.map { a ->
            val label = listOfNotNull(a.locality ?: a.featureName, a.adminArea, a.countryCode)
                .distinct()
                .joinToString(", ")
            PlaceResult(label.ifEmpty { query }, a.latitude, a.longitude)
        }.distinctBy { it.name }
    }

    /** US ZIP code for a point, from the device's geocoder (used for EPA's UV forecast). */
    override suspend fun postalCode(latitude: Double, longitude: Double): String? {
        if (!Geocoder.isPresent()) return null
        val geocoder = Geocoder(context, Locale.US)
        val addresses: List<Address> = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                withTimeoutOrNull(10_000) {
                    suspendCancellableCoroutine { cont ->
                        geocoder.getFromLocation(latitude, longitude, 1, object : Geocoder.GeocodeListener {
                            override fun onGeocode(addresses: MutableList<Address>) {
                                if (cont.isActive) cont.resume(addresses.toList())
                            }

                            override fun onError(errorMessage: String?) {
                                if (cont.isActive) cont.resume(emptyList())
                            }
                        })
                    }
                }.orEmpty()
            } else {
                withContext(Dispatchers.IO) {
                    @Suppress("DEPRECATION")
                    geocoder.getFromLocation(latitude, longitude, 1).orEmpty()
                }
            }
        }.getOrDefault(emptyList())
        val address = addresses.firstOrNull { it.countryCode == "US" } ?: return null
        return address.postalCode?.take(5)?.takeIf { it.matches(Regex("^\\d{5}$")) }
    }

    companion object {
        private val COORDINATES = Regex("""^\s*(-?\d{1,2}(?:\.\d+)?)\s*[, ]\s*(-?\d{1,3}(?:\.\d+)?)\s*$""")

        fun parseCoordinates(text: String): GeoPoint? {
            val match = COORDINATES.matchEntire(text) ?: return null
            val lat = match.groupValues[1].toDoubleOrNull() ?: return null
            val lon = match.groupValues[2].toDoubleOrNull() ?: return null
            return if (lat in -90.0..90.0 && lon in -180.0..180.0) GeoPoint(lat, lon) else null
        }
    }
}
