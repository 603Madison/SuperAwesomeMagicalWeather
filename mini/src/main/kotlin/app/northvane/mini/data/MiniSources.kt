package app.northvane.mini.data

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import app.northvane.core.json.NorthvaneJson
import app.northvane.core.model.SavedLocation
import app.northvane.core.net.fetch
import app.northvane.core.repo.attempt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.math.cos

/**
 * Place search by name or ZIP/postal code through Open-Meteo's free geocoder,
 * which works on phones without Google services (common on flip phones).
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
        val response = http.fetch(Request.Builder().url(url).build(), maxBytes = 256L * 1024)
        if (response.code !in 200..299) return emptyList()
        val results = ((NorthvaneJson.parseToJsonElement(response.body) as? JsonObject)?.get("results") as? JsonArray).orEmpty()
        fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
        fun JsonObject.number(key: String) = (this[key] as? JsonPrimitive)?.doubleOrNull
        return results.mapNotNull { it as? JsonObject }.mapNotNull { o ->
            val lat = o.number("latitude") ?: return@mapNotNull null
            val lon = o.number("longitude") ?: return@mapNotNull null
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

/** The phone's position from Android's own location service (no Google Play services needed). */
class DeviceLocation(context: Context) {
    private val manager = context.applicationContext.getSystemService(LocationManager::class.java)

    /** A recent fix if the phone has one, otherwise waits up to [timeout] for a new one. */
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
        // Ask every enabled provider at once and take the first answer: on phones without Google
        // services the network provider can be "enabled" yet never answer, while GPS would.
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

/**
 * Radar pictures from the Iowa Environmental Mesonet: NEXRAD radar already
 * drawn over counties, highways and city names, one small PNG per frame, for
 * any area and time in 5-minute steps. Ideal for a 2.8" screen and no map engine.
 */
class RadarImages(private val http: OkHttpClient) {
    /** Half the height of the picture, in degrees of latitude, for each zoom step. */
    val zooms = listOf(0.6, 1.2, 2.4, 4.8)

    data class View(val latitude: Double, val longitude: Double, val zoom: Int)

    /** Recent pictures, up to [CACHE_BYTES]: flip phones have little memory. */
    private val cache = object : android.util.LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** Downloads in progress, so the loop prefetch and the frame on screen never fetch the same picture twice. */
    private val inFlight = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<Bitmap?>>()

    /** Frees the pictures, e.g. when Android is short of memory. */
    fun clear() = cache.evictAll()

    /** Frame times every 10 minutes over the past hour, oldest first, a few minutes behind for processing. */
    fun frameTimes(now: Instant): List<Instant> {
        val latest = now.minus(Duration.ofMinutes(5)).truncatedTo(ChronoUnit.MINUTES).let {
            it.minus(Duration.ofMinutes((it.atOffset(ZoneOffset.UTC).minute % 5).toLong()))
        }
        return (6 downTo 0).map { latest.minus(Duration.ofMinutes(10L * it)) }
    }

    fun url(view: View, time: Instant, width: Int, height: Int): String {
        val halfLat = zooms[view.zoom.coerceIn(zooms.indices)]
        val halfLon = halfLat * width / height / cos(Math.toRadians(view.latitude)).coerceAtLeast(0.2)
        return "https://mesonet.agron.iastate.edu/GIS/radmap.php".toHttpUrl().newBuilder()
            .addQueryParameter("width", width.toString())
            .addQueryParameter("height", height.toString())
            .addQueryParameter("bbox", "%.3f,%.3f,%.3f,%.3f".format(Locale.ROOT, view.longitude - halfLon, view.latitude - halfLat, view.longitude + halfLon, view.latitude + halfLat))
            .addQueryParameter("ts", STAMP.format(time))
            .addQueryParameter("layers[]", "nexrad")
            .addQueryParameter("layers[]", "uscounties")
            .addQueryParameter("layers[]", "interstates")
            .addQueryParameter("layers[]", "cities")
            .build()
            .toString()
    }

    suspend fun image(view: View, time: Instant, width: Int, height: Int): Bitmap? {
        val url = url(view, time, width, height)
        cache[url]?.let { return it }
        val mine = kotlinx.coroutines.CompletableDeferred<Bitmap?>()
        inFlight.putIfAbsent(url, mine)?.let { other ->
            return try {
                other.await()
            } catch (e: kotlinx.coroutines.CancellationException) {
                // The other download was cancelled; this caller wasn't, so it just gets nothing this time.
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                null
            }
        }
        try {
            val bitmap = download(url)
            mine.complete(bitmap)
            return bitmap
        } catch (e: Throwable) {
            mine.completeExceptionally(e)
            throw e
        } finally {
            inFlight.remove(url, mine)
        }
    }

    private suspend fun download(url: String): Bitmap? {
        val bitmap = attempt {
            runInterruptible(Dispatchers.IO) {
                http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val source = response.body.source()
                    if (source.request(MAX_BYTES + 1)) return@use null
                    val bytes = source.buffer.readByteArray()
                    // Check the size before decoding: a small file can claim a huge picture.
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    if (bounds.outWidth !in 1..MAX_EDGE || bounds.outHeight !in 1..MAX_EDGE) return@use null
                    // Radar maps have no transparency: 16-bit pixels halve the memory.
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 })
                }
            }
        }.getOrNull() ?: return null
        cache.put(url, bitmap)
        return bitmap
    }

    private companion object {
        const val MAX_BYTES = 1024L * 1024
        const val MAX_EDGE = 2048
        const val CACHE_BYTES = 8 * 1024 * 1024
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmm").withZone(ZoneOffset.UTC)
    }
}
