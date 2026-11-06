package app.northvane.core.air

import app.northvane.core.repo.attempt
import app.northvane.core.json.NorthvaneJson
import app.northvane.core.json.arr
import app.northvane.core.json.asObj
import app.northvane.core.json.bool
import app.northvane.core.json.dbl
import app.northvane.core.json.int
import app.northvane.core.json.obj
import app.northvane.core.json.str
import app.northvane.core.model.AirQuality
import app.northvane.core.model.AirSource
import app.northvane.core.model.AqiCategory
import app.northvane.core.model.AqiDay
import app.northvane.core.model.AqiReading
import app.northvane.core.model.GeoPoint
import app.northvane.core.model.UvForecast
import app.northvane.core.model.UvHour
import app.northvane.core.net.HttpStatusException
import app.northvane.core.net.InvalidCredentialsException
import app.northvane.core.net.ParseException
import app.northvane.core.net.RateLimitedException
import app.northvane.core.net.WeatherApiException
import app.northvane.core.net.fetch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * EPA AirNow: official AQI from monitoring stations plus state/local
 * forecasts. Free, but needs a key (register at docs.airnowapi.org). The API
 * only accepts the key as a query parameter, so requests are never cached.
 */
class AirNowClient(private val http: OkHttpClient, private val baseUrl: String = DEFAULT_BASE_URL) {

    suspend fun airQuality(point: GeoPoint, apiKey: String, today: LocalDate, zone: ZoneId, now: Instant): AirQuality {
        val p = point.rounded()
        fun url(path: String, extra: Map<String, String>) = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments(path)
            .addQueryParameter("format", "application/json")
            .addQueryParameter("latitude", GeoPoint.format(p.latitude))
            .addQueryParameter("longitude", GeoPoint.format(p.longitude))
            .addQueryParameter("distance", "50")
            .apply { extra.forEach { (k, v) -> addQueryParameter(k, v) } }
            .addQueryParameter("API_KEY", apiKey)
            .build()
        val current = parseCurrent(get(url("aq/observation/latLong/current/", emptyMap()).toString()), zone)
        val days = attempt { parseForecast(get(url("aq/forecast/latLong/", mapOf("date" to today.toString())).toString())) }
            .getOrDefault(emptyList())
            .filter { !it.date.isBefore(today) }
        if (current == null && days.isEmpty()) throw WeatherApiException("No AirNow monitors near this location")
        return AirQuality(current, days, AirSource.AIRNOW, now)
    }

    private suspend fun get(url: String): String {
        val response = http.fetch(Request.Builder().url(url).header("Cache-Control", "no-store").build(), maxBytes = 512L * 1024)
        return when (response.code) {
            in 200..299 -> response.body
            401, 403 -> throw InvalidCredentialsException("EPA AirNow")
            429 -> throw RateLimitedException("EPA AirNow")
            else -> throw HttpStatusException(response.code, "EPA AirNow")
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://www.airnowapi.org"

        fun looksLikeApiKey(key: String): Boolean = key.trim().matches(Regex("^[A-Fa-f0-9-]{30,40}$"))

        private fun items(json: String): JsonArray =
            runCatching { NorthvaneJson.parseToJsonElement(json) as? JsonArray }.getOrNull()
                ?: throw ParseException("Unexpected response from EPA AirNow")

        /** The highest pollutant AQI at the nearest reporting area is the area's AQI. */
        fun parseCurrent(json: String, zone: ZoneId): AqiReading? {
            val best = items(json).mapNotNull { it.asObj() }
                .filter { (it.int("AQI") ?: -1) >= 0 }
                .maxByOrNull { it.int("AQI")!! } ?: return null
            val date = best.str("DateObserved")?.trim()?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            val hour = best.int("HourObserved")
            return AqiReading(
                aqi = best.int("AQI")!!,
                pollutant = best.str("ParameterName"),
                // AirNow reports the hour in local *standard* time ("EST" even in summer).
                observedAt = if (date != null && hour != null) {
                    date.atTime(hour.coerceIn(0, 23), 0).atZone(standardZone(best.str("LocalTimeZone")) ?: zone).toInstant()
                } else {
                    null
                },
                area = listOfNotNull(best.str("ReportingArea"), best.str("StateCode")).joinToString(", ").ifEmpty { null },
            )
        }

        /** Fixed standard-time offsets for AirNow's zone abbreviations. */
        private fun standardZone(abbreviation: String?): ZoneId? = when (abbreviation?.trim()?.uppercase()) {
            "AST" -> ZoneOffset.ofHours(-4)
            "EST" -> ZoneOffset.ofHours(-5)
            "CST" -> ZoneOffset.ofHours(-6)
            "MST" -> ZoneOffset.ofHours(-7)
            "PST" -> ZoneOffset.ofHours(-8)
            "AKST" -> ZoneOffset.ofHours(-9)
            "HST" -> ZoneOffset.ofHours(-10)
            else -> null
        }

        fun parseForecast(json: String): List<AqiDay> =
            items(json).mapNotNull { it.asObj() }
                .groupBy { it.str("DateForecast")?.trim() }
                .mapNotNull { (dateText, entries) ->
                    val date = dateText?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return@mapNotNull null
                    val worst = entries.maxByOrNull { e ->
                        e.int("AQI")?.takeIf { it >= 0 } ?: categoryFromNumber(e.obj("Category")?.int("Number"))?.max?.coerceAtMost(500) ?: -1
                    } ?: return@mapNotNull null
                    val aqi = worst.int("AQI")?.takeIf { it >= 0 }
                    AqiDay(
                        date = date,
                        aqi = aqi,
                        pollutant = worst.str("ParameterName"),
                        category = aqi?.let(AqiCategory::of) ?: categoryFromNumber(worst.obj("Category")?.int("Number")),
                        actionDay = entries.any { it.bool("ActionDay") == true },
                        discussion = worst.str("Discussion")?.takeIf { it.isNotBlank() }?.take(600),
                    )
                }
                .sortedBy { it.date }

        private fun categoryFromNumber(n: Int?): AqiCategory? = n?.takeIf { it in 1..6 }?.let { AqiCategory.entries[it - 1] }
    }
}

/**
 * EPA's UV Index forecast (Envirofacts), by ZIP code: hourly values for today
 * and the daily maximum with EPA's UV Alert flag. Free and keyless.
 */
class EpaUvClient(private val http: OkHttpClient, private val baseUrl: String = DEFAULT_BASE_URL) {

    suspend fun forecast(zip: String, zone: ZoneId, now: Instant): UvForecast {
        require(zip.matches(Regex("^\\d{5}$"))) { "Not a US ZIP code" }
        val hourly = parseHourly(get("$baseUrl/efservice/getEnvirofactsUVHOURLY/ZIP/$zip/JSON"), zone)
        val daily = attempt { parseDaily(get("$baseUrl/efservice/getEnvirofactsUVDAILY/ZIP/$zip/JSON")) }.getOrNull()
        if (hourly.isEmpty() && daily == null) throw WeatherApiException("No EPA UV forecast for this ZIP code")
        return UvForecast(
            hourly = hourly,
            todayMax = daily?.first ?: hourly.maxOfOrNull { it.index },
            alert = daily?.second ?: false,
            source = "EPA UV Index",
            fetchedAt = now,
        )
    }

    private suspend fun get(url: String): String {
        val response = http.fetch(Request.Builder().url(url).build(), maxBytes = 256L * 1024)
        if (response.code !in 200..299) throw HttpStatusException(response.code, "EPA UV Index")
        return response.body
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://data.epa.gov"
        private val HOUR_FORMAT = DateTimeFormatter.ofPattern("MMM/dd/yyyy hh a", Locale.US)
        private val DAY_FORMAT = DateTimeFormatter.ofPattern("MMM/dd/yyyy", Locale.US)

        private fun items(json: String): List<kotlinx.serialization.json.JsonObject> =
            (runCatching { NorthvaneJson.parseToJsonElement(json) as? JsonArray }.getOrNull()
                ?: throw ParseException("Unexpected response from EPA UV Index")).mapNotNull { it.asObj() }

        /** "Oct/29/2026 07 AM" in the ZIP's local time. */
        fun parseHourly(json: String, zone: ZoneId): List<UvHour> = items(json).mapNotNull { o ->
            val time = o.str("DATE_TIME")?.let { runCatching { LocalDateTime.parse(it, HOUR_FORMAT) }.getOrNull() } ?: return@mapNotNull null
            val value = o.dbl("UV_VALUE") ?: o.str("UV_VALUE")?.toDoubleOrNull() ?: return@mapNotNull null
            UvHour(time.atZone(zone).toInstant(), value)
        }.sortedBy { it.time }

        /** (today's max, UV alert). */
        fun parseDaily(json: String): Pair<Double, Boolean>? {
            val o = items(json).firstOrNull() ?: return null
            val index = o.str("UV_INDEX")?.toDoubleOrNull() ?: o.dbl("UV_INDEX") ?: return null
            runCatching { LocalDate.parse(o.str("DATE"), DAY_FORMAT) }.getOrNull() ?: return null
            return index to (o.str("UV_ALERT") == "1")
        }
    }
}

/**
 * Open-Meteo air quality (CAMS global model): US AQI and UV index anywhere,
 * keyless, free for non-commercial use (CC BY 4.0). Used when there's no
 * AirNow key, no monitor nearby, or no ZIP code for EPA's UV forecast.
 */
class OpenMeteoAirClient(private val http: OkHttpClient, private val baseUrl: String = DEFAULT_BASE_URL) {

    suspend fun fetch(point: GeoPoint, now: Instant): Pair<AirQuality, UvForecast> {
        val p = point.rounded()
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("v1/air-quality")
            .addQueryParameter("latitude", GeoPoint.format(p.latitude))
            .addQueryParameter("longitude", GeoPoint.format(p.longitude))
            .addQueryParameter("current", "us_aqi,us_aqi_pm2_5,us_aqi_ozone,uv_index")
            .addQueryParameter("hourly", "us_aqi,uv_index")
            .addQueryParameter("forecast_days", "5")
            .addQueryParameter("timezone", "auto")
            .build()
        val response = http.fetch(Request.Builder().url(url).build(), maxBytes = 512L * 1024)
        if (response.code !in 200..299) throw HttpStatusException(response.code, "Open-Meteo")
        return parse(response.body, now)
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://air-quality-api.open-meteo.com"

        fun parse(json: String, now: Instant): Pair<AirQuality, UvForecast> {
            val root = runCatching { NorthvaneJson.parseToJsonElement(json).asObj() }.getOrNull()
                ?: throw ParseException("Unexpected response from Open-Meteo")
            root.str("reason")?.let { throw WeatherApiException("Open-Meteo: $it") }
            val offset = ZoneOffset.ofTotalSeconds(root.int("utc_offset_seconds") ?: 0)
            val zone = root.str("timezone")?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: offset
            // Local times in the place's zone, so hours after a daylight-saving change aren't off by one.
            fun time(text: String?) = text?.let { runCatching { LocalDateTime.parse(it).atZone(zone).toInstant() }.getOrNull() }

            val current = root.obj("current")?.let { c ->
                val aqi = c.dbl("us_aqi")?.toInt() ?: return@let null
                val pm = c.dbl("us_aqi_pm2_5")
                val ozone = c.dbl("us_aqi_ozone")
                AqiReading(aqi, pollutant = if ((ozone ?: -1.0) > (pm ?: -1.0)) "O3" else "PM2.5", observedAt = time(c.str("time")))
            }
            val hourly = root.obj("hourly")
            val times = hourly?.arr("time").orEmpty().map { time((it as? JsonPrimitive)?.content) }
            fun series(name: String) = hourly?.arr(name).orEmpty().map { (it as? JsonPrimitive)?.content?.toDoubleOrNull() }
            val aqiSeries = series("us_aqi")
            val uvSeries = series("uv_index")
            val days = times.indices.mapNotNull { i -> times[i]?.let { t -> aqiSeries.getOrNull(i)?.let { t to it } } }
                .groupBy { (t, _) -> t.atZone(zone).toLocalDate() }
                .map { (date, values) -> AqiDay(date, values.maxOf { it.second }.toInt()) }
                .sortedBy { it.date }
            val uvHours = times.indices.mapNotNull { i -> times[i]?.let { t -> uvSeries.getOrNull(i)?.let { UvHour(t, it) } } }
            val today = now.atZone(zone).toLocalDate()
            val todayUv = uvHours.filter { it.time.atZone(zone).toLocalDate() == today }
            return AirQuality(current, days, AirSource.OPEN_METEO, now) to
                UvForecast(todayUv.ifEmpty { uvHours.take(24) }, todayUv.maxOfOrNull { it.index }, source = "Open-Meteo (CAMS)", fetchedAt = now)
        }
    }
}
