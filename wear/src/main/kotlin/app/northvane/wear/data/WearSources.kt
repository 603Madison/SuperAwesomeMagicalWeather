package app.northvane.wear.data

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import app.northvane.core.json.NorthvaneJson
import app.northvane.core.model.SavedLocation
import app.northvane.core.net.fetch
import app.northvane.core.time.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Place search by name or ZIP/postal code through Open-Meteo's free geocoder
 * (the same as Mini's), which needs no Google services.
 */
class PlaceSearch(private val http: OkHttpClient) {
    data class Result(val name: String, val detail: String, val latitude: Double, val longitude: Double)

    suspend fun search(query: String): List<Result> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        val url = "https://geocoding-api.open-meteo.com/v1/search".toHttpUrl().newBuilder()
            .addQueryParameter("name", q)
            .addQueryParameter("count", "10")
            .addQueryParameter("language", Locale.getDefault().language.ifBlank { "en" })
            .addQueryParameter("format", "json")
            .build()
        val response = http.fetch(app.northvane.core.net.Request.Builder().url(url.toString()).build(), maxBytes = 256L * 1024)
        if (response.code !in 200..299) return emptyList()
        return parse(response.body)
    }

    companion object {
        fun parse(json: String): List<Result> {
            val results = ((runCatching { NorthvaneJson.parseToJsonElement(json) }.getOrNull() as? JsonObject)?.get("results") as? JsonArray).orEmpty()
            fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
            fun JsonObject.number(key: String) = (this[key] as? JsonPrimitive)?.doubleOrNull
            return results.mapNotNull { it as? JsonObject }.mapNotNull { o ->
                val lat = o.number("latitude")?.takeIf { it in -90.0..90.0 } ?: return@mapNotNull null
                val lon = o.number("longitude")?.takeIf { it in -180.0..180.0 } ?: return@mapNotNull null
                val name = o.text("name") ?: return@mapNotNull null
                Result(name, listOfNotNull(o.text("admin1"), o.text("country_code")).joinToString(", "), lat, lon)
            }
                // NWS and Environment Canada cover the US and Canada best: list those first.
                .sortedBy { if (it.detail.endsWith("US") || it.detail.endsWith("CA")) 0 else 1 }
        }

        fun toPlace(result: Result) = SavedLocation(
            id = UUID.randomUUID().toString(),
            name = listOf(result.name, result.detail.substringBefore(",").takeIf { it.isNotBlank() && it != result.name })
                .filterNotNull().joinToString(", "),
            latitude = result.latitude,
            longitude = result.longitude,
        )
    }
}

/** The watch's position from Android's own location service (no Google Play services needed). */
class DeviceLocation(context: Context) {
    private val manager = context.applicationContext.getSystemService(LocationManager::class.java)

    /** A recent fix if the watch has one, otherwise waits up to [timeout] for a new one. */
    @SuppressLint("MissingPermission") // callers check the permission first
    suspend fun current(timeout: Duration = Duration.ofSeconds(30)): Location? {
        val m = manager ?: return null
        val candidates = buildList {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
        }
        val providers = candidates.filter { runCatching { m.isProviderEnabled(it) }.getOrDefault(false) }
        val recent = providers.mapNotNull { runCatching { m.getLastKnownLocation(it) }.getOrNull() }
            .filter { System.currentTimeMillis() - it.time < Duration.ofMinutes(30).toMillis() }
            .minByOrNull { it.accuracy }
        if (recent != null) return recent
        if (providers.isEmpty()) return null
        // Ask every enabled provider at once and take the first answer.
        return withTimeoutOrNull(timeout.toMillis()) {
            suspendCancellableCoroutine { cont ->
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        m.removeUpdates(this)
                        if (cont.isActive) cont.resume(location)
                    }
                    override fun onProviderDisabled(provider: String) {}
                    override fun onProviderEnabled(provider: String) {}
                }
                providers.forEach { provider ->
                    runCatching { m.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper()) }
                }
                cont.invokeOnCancellation { m.removeUpdates(listener) }
            }
        }
    }
}
