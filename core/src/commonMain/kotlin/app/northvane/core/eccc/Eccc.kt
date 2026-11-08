package app.northvane.core.eccc

import app.northvane.core.json.NorthvaneJson
import app.northvane.core.json.arr
import app.northvane.core.json.asObj
import app.northvane.core.json.obj
import app.northvane.core.json.str
import app.northvane.core.model.AlertCategory
import app.northvane.core.model.AlertSeverity
import app.northvane.core.model.CurrentConditions
import app.northvane.core.model.DailyForecast
import app.northvane.core.model.DataSource
import app.northvane.core.model.GeoPoint
import app.northvane.core.model.HourlyForecast
import app.northvane.core.model.Units
import app.northvane.core.model.WeatherAlert
import app.northvane.core.model.WeatherCondition
import app.northvane.core.net.HttpStatusException
import app.northvane.core.net.ParseException
import app.northvane.core.net.WeatherApiException
import app.northvane.core.net.fetch
import app.northvane.core.util.Solar
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import app.northvane.core.net.HttpEngine
import app.northvane.core.net.Request
import kotlin.math.max
import app.northvane.core.time.*
import app.northvane.core.util.fmt

/** Everything the app needs from one Environment Canada city page. */
data class EcccForecast(
    val siteId: String,
    val placeName: String,
    val zone: ZoneId,
    val current: CurrentConditions?,
    val hourly: List<HourlyForecast>,
    val daily: List<DailyForecast>,
    val alerts: List<WeatherAlert>,
    val sunrise: Instant?,
    val sunset: Instant?,
)

class OutsideEcccCoverageException : WeatherApiException("No Environment Canada forecast point near this location")

/**
 * Environment and Climate Change Canada city-page weather (MSC GeoMet OGC
 * API): current conditions, 24-hour hourly and 6-day forecasts, and public
 * alerts for about 840 Canadian places. Free and keyless. A forecast is the
 * nearest city page within ~150 km.
 */
class EcccClient(private val http: HttpEngine, private val baseUrl: String = DEFAULT_BASE_URL) {

    /** Nearest city-page id, searched in widening boxes with a tiny id-only response. */
    suspend fun nearestSite(point: GeoPoint): String {
        for (span in listOf(0.6, 1.5, 3.0)) {
            val bbox = "%.3f,%.3f,%.3f,%.3f".fmt(point.longitude - span * 1.5, point.latitude - span, point.longitude + span * 1.5, point.latitude + span)
            val json = get("$baseUrl/collections/$COLLECTION/items?f=json&limit=500&properties=identifier&bbox=$bbox", 512L * 1024)
            val nearest = parseSites(json).minByOrNull { (_, p) -> point.distanceKm(p) }
            if (nearest != null && point.distanceKm(nearest.second) <= MAX_DISTANCE_KM) return nearest.first
        }
        throw OutsideEcccCoverageException()
    }

    suspend fun forecast(siteId: String, now: Instant): EcccForecast {
        require(siteId.matches(Regex("^[a-z]{2}-\\d{1,4}$"))) { "Unexpected Environment Canada site id" }
        return parse(get("$baseUrl/collections/$COLLECTION/items/$siteId?f=json", 2L * 1024 * 1024), now)
    }

    private suspend fun get(url: String, maxBytes: Long): String {
        val response = http.fetch(Request.Builder().url(url).build(), maxBytes)
        if (response.code !in 200..299) throw HttpStatusException(response.code, "Environment Canada")
        return response.body
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.weather.gc.ca"
        private const val COLLECTION = "citypageweather-realtime"
        private const val MAX_DISTANCE_KM = 150.0

        /** Rough bounding box for Canada, to try ECCC before giving up on a non-US place. */
        fun mayBeInCanada(p: GeoPoint) = p.latitude in 41.5..83.5 && p.longitude in -141.5..-52.0

        fun parseSites(json: String): List<Pair<String, GeoPoint>> {
            val root = runCatching { NorthvaneJson.parseToJsonElement(json).asObj() }.getOrNull()
                ?: throw ParseException("Unexpected response from Environment Canada")
            return root.arr("features").orEmpty().mapNotNull { f ->
                val o = f.asObj() ?: return@mapNotNull null
                val id = o.str("id") ?: o.obj("properties")?.str("identifier") ?: return@mapNotNull null
                val c = o.obj("geometry")?.arr("coordinates")?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull } ?: return@mapNotNull null
                if (c.size < 2) null else id to GeoPoint(c[1], c[0])
            }
        }

        /** Province (site id prefix) to time zone; good enough for forecast day boundaries. */
        fun zoneFor(siteId: String): ZoneId = ZoneId.of(
            when (siteId.substringBefore('-')) {
                "nl" -> "America/St_Johns"
                "ns", "pe" -> "America/Halifax"
                "nb" -> "America/Moncton"
                "qc", "on" -> "America/Toronto"
                "mb" -> "America/Winnipeg"
                "sk" -> "America/Regina"
                "ab" -> "America/Edmonton"
                "bc" -> "America/Vancouver"
                "yt" -> "America/Whitehorse"
                "nt" -> "America/Yellowknife"
                "nu" -> "America/Iqaluit"
                else -> "America/Toronto"
            },
        )

        // Bilingual values look like {"en": ..., "fr": ...}.
        private fun JsonObject.en(key: String): JsonElement? = (this[key] as? JsonObject)?.get("en")
        private fun JsonObject.enText(key: String): String? = (en(key) as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
        private fun JsonObject.enNumber(key: String): Double? = (en(key) as? JsonPrimitive)?.doubleOrNull
        private fun JsonObject.value(key: String): Double? = obj(key)?.enNumber("value")

        fun parse(json: String, now: Instant): EcccForecast {
            val root = runCatching { NorthvaneJson.parseToJsonElement(json).asObj() }.getOrNull()
                ?: throw ParseException("Unexpected response from Environment Canada")
            val p = root.obj("properties") ?: throw ParseException("Unexpected response from Environment Canada")
            val siteId = root.str("id") ?: p.str("identifier") ?: "ca"
            val zone = zoneFor(siteId)
            val coords = root.obj("geometry")?.arr("coordinates")?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull }
            val site = coords?.takeIf { it.size >= 2 }?.let { GeoPoint(it[1], it[0]) }
            val rise = p.obj("riseSet")?.enText("sunrise")?.let { runCatching { Instant.parse(it) }.getOrNull() }
            val set = p.obj("riseSet")?.enText("sunset")?.let { runCatching { Instant.parse(it) }.getOrNull() }
            val name = listOfNotNull(p.enText("name"), provinceCode(siteId)).joinToString(", ")
            return EcccForecast(
                siteId = siteId,
                placeName = name,
                zone = zone,
                current = p.obj("currentConditions")?.let { parseCurrent(it, site, now) },
                hourly = parseHourly(p.obj("hourlyForecastGroup"), site),
                daily = parseDaily(p.obj("forecastGroup"), zone, now),
                alerts = parseWarnings(p.arr("warnings"), siteId),
                sunrise = rise,
                sunset = set,
            )
        }

        /**
         * ECCC sometimes reports a wind chill on mild days; wind chill only
         * applies at 10°C or colder and humidex at 20°C or warmer.
         */
        internal fun feelsLike(tempC: Double, windChill: Double?, humidex: Double?): Double? = when {
            windChill != null && tempC <= 10 -> windChill
            humidex != null && tempC >= 20 -> humidex
            else -> null
        }

        private fun provinceCode(siteId: String) = siteId.substringBefore('-').uppercase().takeIf { it.length == 2 }

        private fun parseCurrent(c: JsonObject, site: GeoPoint?, now: Instant): CurrentConditions? {
            val temp = c.value("temperature") ?: return null
            val observed = c.obj("timestamp")?.let { (it["en"] as? JsonPrimitive)?.content }?.let { runCatching { Instant.parse(it) }.getOrNull() }
            val wind = c.obj("wind")
            val speed = wind?.value("speed")
            val icon = c.obj("iconCode")?.let { (it["value"] as? JsonPrimitive)?.doubleOrNull?.toInt() }
            val text = c.enText("condition")
            val humidity = c.value("relativeHumidity")
            val feels = feelsLike(temp, c.value("windChill"), c.value("humidex"))
            return CurrentConditions(
                observedAt = observed,
                source = DataSource.ECCC,
                stationName = c.obj("station")?.enText("value"),
                description = text,
                condition = icon?.let(::condition) ?: WeatherCondition.fromText(text),
                isDaytime = site?.let { Solar.isDaylight(it.latitude, it.longitude, observed ?: now) } ?: true,
                temperatureC = temp,
                feelsLikeC = feels ?: Units.feelsLikeC(temp, humidity, speed),
                dewpointC = c.value("dewpoint"),
                humidityPercent = humidity,
                windSpeedKph = speed,
                windGustKph = wind?.value("gust")?.takeIf { it > 0 },
                windDirectionDeg = wind?.value("bearing"),
                pressureHpa = c.value("pressure")?.let { it * 10 },
                visibilityKm = c.value("visibility"),
            )
        }

        private fun parseHourly(group: JsonObject?, site: GeoPoint?): List<HourlyForecast> =
            group?.arr("hourlyForecasts").orEmpty().mapNotNull { e ->
                val h = e.asObj() ?: return@mapNotNull null
                val time = h.str("timestamp")?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return@mapNotNull null
                val wind = h.obj("wind")
                val icon = h.obj("iconCode")?.let { (it["value"] as? JsonPrimitive)?.doubleOrNull?.toInt() }
                val text = h.enText("condition")
                HourlyForecast(
                    time = time,
                    temperatureC = h.value("temperature"),
                    condition = icon?.let(::condition) ?: WeatherCondition.fromText(text),
                    isDaytime = site?.let { Solar.isDaylight(it.latitude, it.longitude, time) } ?: true,
                    shortForecast = text,
                    precipitationChance = h.value("lop")?.toInt(),
                    windSpeedKph = wind?.value("speed"),
                    windDirection = wind?.obj("direction")?.enText("value")?.takeIf { it != "VR" },
                    humidityPercent = null,
                    windGustKph = wind?.value("gust")?.takeIf { it > 0 },
                    apparentTemperatureC = h.value("temperature")?.let { t -> feelsLike(t, h.value("windChill"), h.value("humidex")) },
                )
            }

        /** Pairs each day period with the following night, like the NWS daily forecast. */
        private fun parseDaily(group: JsonObject?, zone: ZoneId, now: Instant): List<DailyForecast> {
            val periods = group?.arr("forecasts").orEmpty().mapNotNull { it.asObj() }
            if (periods.isEmpty()) return emptyList()
            val today = now.atZone(zone).toLocalDate()
            // Dates count from when the forecast was issued, not from now: the afternoon issue is
            // still current after midnight, when its "Tonight" is the evening before.
            val issued = group?.enText("timestamp")?.let { runCatching { Instant.parse(it) }.getOrNull() }
                ?.atZone(zone)?.toLocalDate() ?: today
            val result = mutableListOf<DailyForecast>()
            var date = issued
            var i = 0
            while (i < periods.size) {
                val first = periods[i]
                val firstName = first.obj("period")?.enText("textForecastName").orEmpty()
                val isNight = firstName.contains("night", ignoreCase = true) || firstName.equals("Tonight", ignoreCase = true)
                // "value" names the real day ("Thursday night") where the label says "Tonight".
                val dayName = first.obj("period")?.enText("value") ?: firstName
                date = dateFor(dayName, date, issued)
                val day = if (isNight) null else first
                val night = if (isNight) first else periods.getOrNull(i + 1)?.takeIf {
                    it.obj("period")?.enText("textForecastName").orEmpty().contains("night", ignoreCase = true)
                }
                i += if (isNight || night == null) 1 else 2
                fun temp(o: JsonObject?, cls: String) = o?.obj("temperatures")?.arr("temperature").orEmpty().mapNotNull { it.asObj() }
                    .firstOrNull { it.enText("class") == cls }
                    ?.enNumber("value")
                fun pop(o: JsonObject?) = o?.obj("abbreviatedForecast")?.obj("pop")?.enNumber("value")?.toInt()
                    ?: o?.obj("abbreviatedForecast")?.value("pop")?.toInt()
                    ?: textPop(o?.obj("abbreviatedForecast")?.obj("textSummary")?.enText("value") ?: o?.obj("abbreviatedForecast")?.enText("textSummary"))
                val main = day ?: night!!
                val abbreviated = main.obj("abbreviatedForecast")
                val icon = abbreviated?.obj("icon")?.let { (it["value"] as? JsonPrimitive)?.doubleOrNull?.toInt() }
                val summary = abbreviated?.enText("textSummary")
                val gust = listOfNotNull(day, night).flatMap { o ->
                    o.obj("winds")?.arr("periods").orEmpty().mapNotNull { w -> w.asObj()?.value("gust") }
                }.maxOrNull()?.takeIf { it > 0 }
                val wind = listOfNotNull(day, night).flatMap { o ->
                    o.obj("winds")?.arr("periods").orEmpty().mapNotNull { w -> w.asObj()?.value("speed") }
                }.maxOrNull()
                val humidity = listOfNotNull(day, night).mapNotNull { it.value("relativeHumidity") }
                result += DailyForecast(
                    date = date,
                    // After midnight, last evening's "Tonight" is named for its day instead.
                    name = if (isNight && date == today) "Tonight" else if (firstName.equals("Tonight", true)) dayName else firstName,
                    highC = temp(day, "high"),
                    lowC = temp(night, "low"),
                    condition = icon?.let(::condition) ?: WeatherCondition.fromText(summary),
                    isDaytimeCondition = day != null,
                    shortForecast = summary,
                    precipitationChance = listOfNotNull(pop(day), pop(night)).maxOrNull(),
                    dayDetail = day?.enText("textSummary"),
                    nightDetail = night?.enText("textSummary"),
                    windMaxKph = wind,
                    gustMaxKph = gust?.let { max(it, wind ?: 0.0) },
                    humidityMin = humidity.minOrNull(),
                    humidityMax = humidity.maxOrNull(),
                )
                date = date.plusDays(1)
            }
            return result
        }

        /** The date of a named period, the first matching weekday from the day before [expected] on. */
        private fun dateFor(name: String, expected: LocalDate, issued: LocalDate): LocalDate {
            if (name.equals("Today", true) || name.equals("Tonight", true)) return issued
            val dayName = name.substringBefore(' ').lowercase()
            val dow = DayOfWeek.entries.firstOrNull { it.getDisplayName(TextStyle.FULL).lowercase() == dayName } ?: return expected
            var d = expected.minusDays(1)
            while (d.dayOfWeek != dow) d = d.plusDays(1)
            return d
        }

        /** ECCC states a probability only when it's significant; fall back on the wording. */
        private fun textPop(summary: String?): Int? {
            val t = summary?.lowercase() ?: return null
            val wet = listOf("rain", "shower", "snow", "flurr", "drizzle", "thunder", "precipitation", "ice pellet")
            return when {
                wet.none { it in t } -> null
                "chance" in t -> 40
                "few" in t || "periods of" in t -> 60
                else -> 70
            }
        }

        /** Public alerts: "YELLOW WARNING - WIND" becomes "Wind Warning". */
        private fun parseWarnings(warnings: JsonArray?, siteId: String): List<WeatherAlert> =
            warnings.orEmpty().mapNotNull { e ->
                val w = e.asObj() ?: return@mapNotNull null
                val type = w.enText("type")?.lowercase() ?: return@mapNotNull null
                if (type == "ended") return@mapNotNull null
                val description = w.enText("description").orEmpty()
                val colour = w.enText("alertColourLevel")?.lowercase()
                val hazard = description.substringAfter(" - ", description).lowercase().split(' ')
                    .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
                val category = when (type) {
                    "warning" -> AlertCategory.WARNING
                    "watch" -> AlertCategory.WATCH
                    "advisory" -> AlertCategory.ADVISORY
                    "statement" -> AlertCategory.STATEMENT
                    else -> AlertCategory.OTHER
                }
                val issued = w.enText("eventIssue")?.let { runCatching { Instant.parse(it) }.getOrNull() }
                val expires = w.enText("expiryTime")?.let { runCatching { Instant.parse(it) }.getOrNull() }
                val event = "$hazard ${type.replaceFirstChar { it.uppercase() }}".trim()
                val id = "eccc:$siteId:$event:${issued?.epochSecond}"
                WeatherAlert(
                    id = id,
                    threadId = "eccc:$siteId:$event",
                    event = event,
                    headline = description.lowercase().replaceFirstChar { it.uppercase() },
                    description = w.enText("url")?.let { "Details: $it" },
                    instruction = null,
                    severity = when (colour) {
                        "red" -> AlertSeverity.EXTREME
                        "orange" -> AlertSeverity.SEVERE
                        "yellow" -> AlertSeverity.MODERATE
                        else -> AlertSeverity.UNKNOWN
                    },
                    urgency = null,
                    certainty = null,
                    category = category,
                    sent = issued,
                    effective = issued,
                    expires = expires,
                    senderName = "Environment and Climate Change Canada",
                    messageType = "Alert",
                )
            }

        /** ECCC weather icon codes (weather.gc.ca/weathericons). */
        fun condition(code: Int): WeatherCondition = when (code) {
            0, 30 -> WeatherCondition.CLEAR
            1, 31 -> WeatherCondition.MOSTLY_CLEAR
            2, 4, 5, 32, 34, 35 -> WeatherCondition.PARTLY_CLOUDY
            3, 33 -> WeatherCondition.MOSTLY_CLOUDY
            10 -> WeatherCondition.OVERCAST
            6, 12, 36 -> WeatherCondition.SHOWERS
            11, 13 -> WeatherCondition.RAIN
            28 -> WeatherCondition.DRIZZLE
            14 -> WeatherCondition.FREEZING_RAIN
            7, 15, 37 -> WeatherCondition.RAIN_SNOW
            8, 16, 17, 18, 25, 38 -> WeatherCondition.SNOW
            26, 27 -> WeatherCondition.SLEET
            40 -> WeatherCondition.BLIZZARD
            9, 19, 39, 46, 47 -> WeatherCondition.THUNDERSTORM
            23 -> WeatherCondition.HAZE
            24 -> WeatherCondition.FOG
            41, 42, 48 -> WeatherCondition.TORNADO
            43 -> WeatherCondition.WINDY
            44 -> WeatherCondition.SMOKE
            45 -> WeatherCondition.DUST
            else -> WeatherCondition.UNKNOWN
        }
    }
}
