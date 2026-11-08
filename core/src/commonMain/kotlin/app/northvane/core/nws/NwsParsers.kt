package app.northvane.core.nws

import app.northvane.core.alerts.AlertClassifier
import app.northvane.core.json.NorthvaneJson
import app.northvane.core.json.arr
import app.northvane.core.json.asObj
import app.northvane.core.json.bool
import app.northvane.core.json.dbl
import app.northvane.core.json.int
import app.northvane.core.json.obj
import app.northvane.core.json.quant
import app.northvane.core.json.str
import app.northvane.core.model.AlertSeverity
import app.northvane.core.model.CurrentConditions
import app.northvane.core.model.DailyForecast
import app.northvane.core.model.DataSource
import app.northvane.core.model.HourlyForecast
import app.northvane.core.model.Units
import app.northvane.core.model.WeatherAlert
import app.northvane.core.alerts.AlertAreas
import app.northvane.core.model.WeatherCondition
import app.northvane.core.net.ParseException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import app.northvane.core.time.*

/** Grid metadata for a coordinate, from `/points/{lat},{lon}`. Rarely changes, so it is cached. */
@Serializable
data class NwsPoint(
    val gridId: String,
    val gridX: Int,
    val gridY: Int,
    val forecastUrl: String,
    val hourlyUrl: String,
    val stationsUrl: String,
    val timeZone: String,
    val city: String? = null,
    val state: String? = null,
    val radarStation: String? = null,
    val gridDataUrl: String? = null,
) {
    val placeName: String?
        get() = when {
            city != null && state != null -> "$city, $state"
            else -> city
        }
}

@Serializable
data class NwsStation(val id: String, val name: String? = null)

object NwsParsers {

    private fun root(json: String): JsonObject =
        runCatching { NorthvaneJson.parseToJsonElement(json).asObj() }.getOrNull()
            ?: throw ParseException("Unexpected response from weather.gov")

    private fun parseTime(value: String?): Instant? =
        value?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() }

    fun parsePoint(json: String): NwsPoint {
        val p = root(json).obj("properties") ?: throw ParseException("weather.gov point has no properties")
        val relative = p.obj("relativeLocation")?.obj("properties")
        return NwsPoint(
            gridId = p.str("gridId") ?: throw ParseException("weather.gov point missing gridId"),
            gridX = p.int("gridX") ?: 0,
            gridY = p.int("gridY") ?: 0,
            forecastUrl = p.str("forecast") ?: throw ParseException("No forecast available for this point"),
            hourlyUrl = p.str("forecastHourly") ?: throw ParseException("No hourly forecast for this point"),
            stationsUrl = p.str("observationStations") ?: throw ParseException("No observation stations for this point"),
            timeZone = p.str("timeZone") ?: "UTC",
            city = relative?.str("city"),
            state = relative?.str("state"),
            radarStation = p.str("radarStation"),
            gridDataUrl = p.str("forecastGridData"),
        )
    }

    fun parseStations(json: String, limit: Int = 5): List<NwsStation> =
        root(json).arr("features").orEmpty().mapNotNull { feature ->
            val props = feature.asObj()?.obj("properties") ?: return@mapNotNull null
            val id = props.str("stationIdentifier") ?: return@mapNotNull null
            NwsStation(id = id, name = props.str("name"))
        }.take(limit)

    fun parseObservation(json: String, fallbackStationName: String?): CurrentConditions {
        val p = root(json).obj("properties") ?: throw ParseException("Observation has no properties")
        val description = p.str("textDescription")?.takeIf { it.isNotBlank() }
        val icon = NwsIcons.parse(p.str("icon"))
        val temperature = p.quant("temperature")?.toCelsius()
        val humidity = p.quant("relativeHumidity")?.toPercent()
        val wind = p.quant("windSpeed")?.toKph()
        val heatIndex = p.quant("heatIndex")?.toCelsius()
        val windChill = p.quant("windChill")?.toCelsius()
        return CurrentConditions(
            observedAt = parseTime(p.str("timestamp")),
            source = DataSource.NWS,
            stationName = p.str("stationName") ?: fallbackStationName,
            description = description,
            condition = icon?.condition?.takeIf { it != WeatherCondition.UNKNOWN }
                ?: WeatherCondition.fromText(description),
            isDaytime = icon?.isDaytime ?: true,
            temperatureC = temperature,
            feelsLikeC = heatIndex ?: windChill
                ?: temperature?.let { Units.feelsLikeC(it, humidity, wind) },
            dewpointC = p.quant("dewpoint")?.toCelsius(),
            humidityPercent = humidity,
            windSpeedKph = wind,
            windGustKph = p.quant("windGust")?.toKph(),
            windDirectionDeg = p.quant("windDirection")?.toDegrees(),
            pressureHpa = (p.quant("barometricPressure") ?: p.quant("seaLevelPressure"))?.toHpa(),
            visibilityKm = p.quant("visibility")?.toKm(),
            rainRateMmPerHour = p.quant("precipitationLastHour")?.toMm(),
        )
    }

    private class Period(
        val name: String,
        val start: ZonedDateTime,
        val isDaytime: Boolean,
        val temperatureC: Double?,
        val condition: WeatherCondition,
        val iconIsDay: Boolean,
        val shortForecast: String?,
        val detailedForecast: String?,
        val precipitationChance: Int?,
        val windText: String?,
        val windKph: Double?,
        val windDirection: String?,
        val humidity: Double?,
        val dewpointC: Double?,
    )

    private fun parsePeriods(json: String): List<Period> {
        val periods = root(json).obj("properties")?.arr("periods")
            ?: throw ParseException("Forecast has no periods")
        return periods.mapNotNull { element ->
            val o = element.asObj() ?: return@mapNotNull null
            val start = o.str("startTime")?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }
                ?: return@mapNotNull null
            val isDay = o.bool("isDaytime") ?: true
            val icon = NwsIcons.parse(o.str("icon"))
            val short = o.str("shortForecast")
            val (windKph, windText) = parseWind(o["windSpeed"])
            Period(
                name = o.str("name").orEmpty(),
                start = start,
                isDaytime = isDay,
                temperatureC = parseTemperature(o),
                condition = icon?.condition?.takeIf { it != WeatherCondition.UNKNOWN }
                    ?: WeatherCondition.fromText(short),
                iconIsDay = icon?.isDaytime ?: isDay,
                shortForecast = short,
                detailedForecast = o.str("detailedForecast"),
                precipitationChance = o.quant("probabilityOfPrecipitation")?.value?.toInt(),
                windText = windText?.let { w -> o.str("windDirection")?.let { "$it $w" } ?: w },
                windKph = windKph,
                windDirection = o.str("windDirection"),
                humidity = o.quant("relativeHumidity")?.value,
                dewpointC = o.quant("dewpoint")?.toCelsius(),
            )
        }
    }

    /** `temperature` is normally an integer in `temperatureUnit`, but may be a quantitative value. */
    private fun parseTemperature(period: JsonObject): Double? = when (val t = period["temperature"]) {
        is JsonObject -> period.quant("temperature")?.toCelsius()
        is JsonPrimitive -> t.doubleOrNull?.let { v ->
            if (period.str("temperatureUnit") == "C") v else Units.fToC(v)
        }
        else -> null
    }

    private val NUMBER = Regex("""\d+(?:\.\d+)?""")

    /** Handles "10 mph", "10 to 15 mph", "20 km/h", or a quantitative value. Returns (max kph, original text). */
    internal fun parseWind(element: JsonElement?): Pair<Double?, String?> = when (element) {
        is JsonObject -> {
            val unit = element.str("unitCode")?.substringAfter(':') ?: ""
            val v = element.dbl("maxValue") ?: element.dbl("value")
            val kph = v?.let {
                when (unit) {
                    "km_h-1" -> it
                    "m_s-1" -> it * 3.6
                    else -> Units.mphToKph(it)
                }
            }
            kph to null
        }
        is JsonPrimitive -> {
            val text = element.content
            val max = NUMBER.findAll(text).mapNotNull { it.value.toDoubleOrNull() }.maxOrNull()
            val kph = max?.let { if ("km" in text) it else Units.mphToKph(it) }
            kph to text.takeIf { it.isNotBlank() }
        }
        else -> null to null
    }

    fun parseHourly(json: String): List<HourlyForecast> = parsePeriods(json).map { p ->
        HourlyForecast(
            time = p.start.toInstant(),
            temperatureC = p.temperatureC,
            condition = p.condition,
            isDaytime = p.iconIsDay,
            shortForecast = p.shortForecast,
            precipitationChance = p.precipitationChance,
            windSpeedKph = p.windKph,
            windDirection = p.windDirection,
            humidityPercent = p.humidity,
            dewpointC = p.dewpointC,
        )
    }

    /**
     * NWS issues alternating 12-hour day/night periods. Pair them into calendar
     * days in the location's zone; a night period that starts before noon
     * ("Overnight") belongs to the previous evening.
     */
    fun parseDaily(json: String, zone: ZoneId): List<DailyForecast> {
        class Day(val date: LocalDate) {
            var day: Period? = null
            var night: Period? = null
        }

        val days = LinkedHashMap<LocalDate, Day>()
        for (p in parsePeriods(json)) {
            val local = p.start.withZoneSameInstant(zone)
            val date = if (!p.isDaytime && local.hour < 12) local.toLocalDate().minusDays(1) else local.toLocalDate()
            val entry = days.getOrPut(date) { Day(date) }
            if (p.isDaytime) entry.day = entry.day ?: p else entry.night = entry.night ?: p
        }
        return days.values.mapNotNull { d ->
            val primary = d.day ?: d.night ?: return@mapNotNull null
            DailyForecast(
                date = d.date,
                name = primary.name,
                highC = d.day?.temperatureC,
                lowC = d.night?.temperatureC,
                condition = primary.condition,
                isDaytimeCondition = d.day != null,
                shortForecast = primary.shortForecast,
                precipitationChance = listOfNotNull(d.day?.precipitationChance, d.night?.precipitationChance).maxOrNull(),
                dayDetail = d.day?.detailedForecast,
                nightDetail = d.night?.detailedForecast,
                windSummary = primary.windText,
            )
        }
    }

    fun parseAlerts(json: String): List<WeatherAlert> {
        val features = root(json).arr("features").orEmpty()
        return features.mapNotNull { feature ->
            val p = feature.asObj()?.obj("properties") ?: return@mapNotNull null
            // Skip test and exercise messages.
            val status = p.str("status")
            if (status != null && !status.equals("Actual", ignoreCase = true)) return@mapNotNull null
            if (p.str("messageType").equals("Cancel", ignoreCase = true)) return@mapNotNull null
            val id = p.str("id") ?: feature.asObj()?.str("id") ?: return@mapNotNull null
            val event = p.str("event") ?: "Weather alert"
            val oldestReference = p.arr("references").orEmpty()
                .mapNotNull { ref ->
                    val r = ref.asObj() ?: return@mapNotNull null
                    val identifier = r.str("identifier") ?: return@mapNotNull null
                    identifier to (parseTime(r.str("sent")) ?: Instant.DISTANT_FUTURE)
                }
                .minByOrNull { it.second }?.first
            WeatherAlert(
                id = id,
                threadId = oldestReference ?: id,
                event = event,
                headline = p.str("headline"),
                description = p.str("description"),
                instruction = p.str("instruction"),
                severity = when (p.str("severity")?.lowercase()) {
                    "extreme" -> AlertSeverity.EXTREME
                    "severe" -> AlertSeverity.SEVERE
                    "moderate" -> AlertSeverity.MODERATE
                    "minor" -> AlertSeverity.MINOR
                    else -> AlertSeverity.UNKNOWN
                },
                urgency = p.str("urgency"),
                certainty = p.str("certainty"),
                category = AlertClassifier.categorize(event),
                sent = parseTime(p.str("sent")),
                effective = parseTime(p.str("effective")),
                onset = parseTime(p.str("onset")),
                expires = parseTime(p.str("expires")),
                ends = parseTime(p.str("ends")),
                senderName = p.str("senderName"),
                areaDesc = p.str("areaDesc"),
                messageType = p.str("messageType"),
                area = AlertAreas.parse(feature.asObj()?.get("geometry")),
            )
        }.sortedWith(AlertClassifier.displayOrder)
    }
}
