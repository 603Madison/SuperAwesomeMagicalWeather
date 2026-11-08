package app.northvane.core.nowcast

import app.northvane.core.json.NorthvaneJson
import app.northvane.core.json.arr
import app.northvane.core.json.asObj
import app.northvane.core.json.obj
import app.northvane.core.json.str
import app.northvane.core.model.GeoPoint
import app.northvane.core.net.HttpStatusException
import app.northvane.core.net.ParseException
import app.northvane.core.net.RateLimitedException
import app.northvane.core.net.WeatherApiException
import app.northvane.core.net.fetch
import app.northvane.core.outlook.MinutePrecip
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import app.northvane.core.net.HttpUrl.Companion.toHttpUrl
import app.northvane.core.net.HttpEngine
import app.northvane.core.net.Request
import app.northvane.core.time.*
import app.northvane.core.util.fmt

/**
 * Rain and snow for the next two hours in 15-minute steps, from Open-Meteo
 * (free, keyless; in the US it uses NOAA's HRRR 15-minute output). About
 * 1 KB per check, small enough to poll in the background.
 */
class NowcastClient(private val http: HttpEngine, private val base: String = DEFAULT_BASE) {
    suspend fun next(point: GeoPoint): List<MinutePrecip> {
        val url = "$base/v1/forecast".toHttpUrl().newBuilder()
            .addQueryParameter("latitude", "%.3f".fmt(point.latitude))
            .addQueryParameter("longitude", "%.3f".fmt(point.longitude))
            .addQueryParameter("minutely_15", "precipitation,snowfall")
            .addQueryParameter("forecast_minutely_15", "8")
            .addQueryParameter("timeformat", "unixtime")
            .build()
        val response = http.fetch(Request.Builder().url(url).build(), maxBytes = 64L * 1024)
        if (response.code == 429) throw RateLimitedException("Open-Meteo")
        if (response.code !in 200..299) throw HttpStatusException(response.code, "Open-Meteo")
        return parse(response.body)
    }

    companion object {
        const val DEFAULT_BASE = "https://api.open-meteo.com"

        fun parse(json: String): List<MinutePrecip> {
            val root = runCatching { NorthvaneJson.parseToJsonElement(json).asObj() }.getOrNull()
                ?: throw ParseException("Unexpected response from Open-Meteo")
            root.str("reason")?.let { throw WeatherApiException("Open-Meteo: $it") }
            val m = root.obj("minutely_15") ?: return emptyList()
            val times = m.arr("time").orEmpty().map { (it as? JsonPrimitive)?.longOrNull }
            val precip = m.arr("precipitation").orEmpty().map { (it as? JsonPrimitive)?.doubleOrNull }
            val snow = m.arr("snowfall").orEmpty().map { (it as? JsonPrimitive)?.doubleOrNull }
            return times.indices.mapNotNull { i ->
                val t = times[i] ?: return@mapNotNull null
                MinutePrecip(Instant.ofEpochSecond(t), precip.getOrNull(i) ?: 0.0, snow.getOrNull(i) ?: 0.0)
            }
        }
    }
}
