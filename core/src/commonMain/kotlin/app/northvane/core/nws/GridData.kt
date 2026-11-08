package app.northvane.core.nws

import app.northvane.core.json.NorthvaneJson
import app.northvane.core.json.QuantValue
import app.northvane.core.json.arr
import app.northvane.core.json.asObj
import app.northvane.core.json.dbl
import app.northvane.core.json.obj
import app.northvane.core.json.str
import app.northvane.core.model.DailyForecast
import app.northvane.core.model.DetailSource
import app.northvane.core.model.HourlyForecast
import app.northvane.core.model.Units
import app.northvane.core.net.ParseException
import kotlin.math.max
import kotlin.math.roundToInt
import app.northvane.core.time.*

/** One quantity from the NWS gridpoint forecast: values over time intervals, in metric units. */
class GridSeries(val entries: List<Entry>) {
    data class Entry(val start: Instant, val end: Instant, val value: Double)

    fun at(t: Instant): Double? = entries.firstOrNull { !t.isBefore(it.start) && t.isBefore(it.end) }?.value

    private fun overlapping(from: Instant, to: Instant) = entries.filter { it.end.isAfter(from) && it.start.isBefore(to) }

    /**
     * Total of an accumulated quantity (precipitation, snow, ice) over [from, to),
     * counting each forecast period in proportion to its overlap.
     */
    fun sum(from: Instant, to: Instant): Double? {
        val parts = overlapping(from, to)
        if (parts.isEmpty()) return null
        return parts.sumOf { e ->
            val overlap = Duration.between(maxOf(e.start, from), minOf(e.end, to)).toMillis().toDouble()
            val length = Duration.between(e.start, e.end).toMillis().toDouble()
            if (length <= 0) 0.0 else e.value * overlap / length
        }
    }

    fun max(from: Instant, to: Instant): Double? = overlapping(from, to).maxOfOrNull { it.value }
    fun min(from: Instant, to: Instant): Double? = overlapping(from, to).minOfOrNull { it.value }

    /** Time-weighted mean over [from, to). */
    fun mean(from: Instant, to: Instant): Double? {
        val parts = overlapping(from, to)
        if (parts.isEmpty()) return null
        var weight = 0.0
        var total = 0.0
        for (e in parts) {
            val w = Duration.between(maxOf(e.start, from), minOf(e.end, to)).toMillis().toDouble()
            weight += w
            total += e.value * w
        }
        return if (weight > 0) total / weight else null
    }

    /** Start of the period holding the maximum value in [from, to). */
    fun timeOfMax(from: Instant, to: Instant): Instant? = overlapping(from, to).maxByOrNull { it.value }?.start

    companion object {
        val EMPTY = GridSeries(emptyList())
    }
}

/** Raw numeric forecast from `/gridpoints/{wfo}/{x},{y}`. All values metric. */
class NwsGridData(
    val temperature: GridSeries,
    val dewpoint: GridSeries,
    val humidity: GridSeries,
    val apparentTemperature: GridSeries,
    val skyCover: GridSeries,
    val windDirection: GridSeries,
    val windSpeed: GridSeries,
    val windGust: GridSeries,
    val precipitationChance: GridSeries,
    val precipitation: GridSeries,
    val snow: GridSeries,
    val ice: GridSeries,
    val thunderChance: GridSeries,
) {
    companion object {
        fun parse(json: String): NwsGridData {
            val p = runCatching { NorthvaneJson.parseToJsonElement(json).asObj()?.obj("properties") }.getOrNull()
                ?: throw ParseException("Unexpected gridpoint response from weather.gov")

            fun series(name: String, convert: (QuantValue) -> Double?): GridSeries {
                val o = p.obj(name) ?: return GridSeries.EMPTY
                val unit = o.str("uom")?.substringAfter(':') ?: ""
                val entries = o.arr("values").orEmpty().mapNotNull { element ->
                    val v = element.asObj() ?: return@mapNotNull null
                    val raw = v.dbl("value") ?: return@mapNotNull null
                    val (start, end) = parseValidTime(v.str("validTime")) ?: return@mapNotNull null
                    convert(QuantValue(raw, unit))?.let { GridSeries.Entry(start, end, it) }
                }
                return GridSeries(entries)
            }
            val percent: (QuantValue) -> Double? = { it.value }
            return NwsGridData(
                temperature = series("temperature") { it.toCelsius() },
                dewpoint = series("dewpoint") { it.toCelsius() },
                humidity = series("relativeHumidity", percent),
                apparentTemperature = series("apparentTemperature") { it.toCelsius() },
                skyCover = series("skyCover", percent),
                windDirection = series("windDirection") { it.value },
                windSpeed = series("windSpeed") { it.toKph() },
                windGust = series("windGust") { it.toKph() },
                precipitationChance = series("probabilityOfPrecipitation", percent),
                precipitation = series("quantitativePrecipitation") { it.toMm() },
                snow = series("snowfallAmount") { it.toMm() },
                ice = series("iceAccumulation") { it.toMm() },
                thunderChance = series("probabilityOfThunder", percent),
            )
        }

        /** "2026-10-29T04:00:00+00:00/PT2H" -> [start, end). */
        internal fun parseValidTime(value: String?): Pair<Instant, Instant>? {
            val (startText, durationText) = value?.split('/')?.takeIf { it.size == 2 } ?: return null
            val start = runCatching { OffsetDateTime.parse(startText).toInstant() }.getOrNull() ?: return null
            val duration = runCatching { Duration.parse(durationText) }.getOrNull() ?: return null
            return start to start.plus(duration)
        }
    }
}

/** Adds gridpoint numbers to the NWS hourly and daily forecasts. */
object ForecastDetails {
    private val HOUR = Duration.ofHours(1)

    fun enrichHourly(hours: List<HourlyForecast>, grid: NwsGridData): List<HourlyForecast> = hours.map { h ->
        val t = h.time
        val end = t.plus(HOUR)
        val gust = grid.windGust.at(t)
        h.copy(
            windGustKph = gust?.let { g -> max(g, h.windSpeedKph ?: 0.0) },
            windDirectionDeg = grid.windDirection.at(t) ?: Units.compassToDegrees(h.windDirection),
            apparentTemperatureC = grid.apparentTemperature.at(t),
            skyCoverPercent = grid.skyCover.at(t)?.roundToInt(),
            precipitationMm = grid.precipitation.sum(t, end),
            snowMm = grid.snow.sum(t, end),
            iceMm = grid.ice.sum(t, end),
            thunderChance = grid.thunderChance.at(t)?.roundToInt(),
            humidityPercent = h.humidityPercent ?: grid.humidity.at(t),
            dewpointC = h.dewpointC ?: grid.dewpoint.at(t),
        )
    }

    /** NWS pairs a day with the following night, so each day covers 06:00 to 06:00 local time. */
    fun dayWindow(day: DailyForecast, zone: ZoneId): Pair<Instant, Instant> {
        val from = day.date.atTime(6, 0).atZone(zone).toInstant()
        return from to from.plus(Duration.ofHours(24))
    }

    fun enrichDaily(days: List<DailyForecast>, grid: NwsGridData, zone: ZoneId): List<DailyForecast> = days.map { d ->
        val (from, to) = dayWindow(d, zone)
        val windMax = grid.windSpeed.max(from, to)
        if (windMax == null && grid.temperature.max(from, to) == null) return@map d
        val daytimeEnd = from.plus(Duration.ofHours(12))
        d.copy(
            windMaxKph = windMax,
            gustMaxKph = grid.windGust.max(from, to)?.let { g -> max(g, windMax ?: 0.0) },
            windDirectionDeg = grid.windSpeed.timeOfMax(from, to)?.let { grid.windDirection.at(it) },
            precipitationMm = grid.precipitation.sum(from, to),
            snowMm = grid.snow.sum(from, to),
            iceMm = grid.ice.sum(from, to),
            humidityMin = grid.humidity.min(from, to),
            humidityMax = grid.humidity.max(from, to),
            thunderChance = grid.thunderChance.max(from, to)?.roundToInt(),
            skyCoverPercent = (grid.skyCover.mean(from, daytimeEnd) ?: grid.skyCover.mean(from, to))?.roundToInt(),
            detailSource = DetailSource.GRID,
        )
    }

    // --- Fallback: numbers stated in the NWS forecast text, used when gridpoint data isn't downloaded ---

    private val GUST = Regex("""gusts? as high as (\d+) mph""", RegexOption.IGNORE_CASE)
    private val WIND_NUMBERS = Regex("""(\d+)""")
    private val SNOW = Regex(
        """snow accumulation of (?:around |up to |less than |)(?:(\w+(?:\.\d+)?) to )?(\w+(?:\.\d+)?|half an|a half) inch""",
        RegexOption.IGNORE_CASE,
    )
    private val ICE = Regex("""ice accumulation of (?:around |up to |less than |)(\d*\.?\d+) of an inch""", RegexOption.IGNORE_CASE)
    private val RAIN = Regex("""rainfall amounts? (?:between |of |)(?:around |up to |less than |)([a-z0-9. ]+?)(?: and ([a-z0-9. ]+?))? (?:of an )?inch""", RegexOption.IGNORE_CASE)

    private val WORDS = mapOf(
        "a tenth" to 0.1, "tenth" to 0.1, "a quarter" to 0.25, "quarter" to 0.25, "half an" to 0.5, "a half" to 0.5,
        "half" to 0.5, "three quarters" to 0.75, "one" to 1.0, "two" to 2.0, "three" to 3.0, "four" to 4.0,
        "five" to 5.0, "six" to 6.0, "seven" to 7.0, "eight" to 8.0, "nine" to 9.0, "ten" to 10.0,
    )

    internal fun amount(text: String?): Double? {
        val t = text?.trim()?.lowercase() ?: return null
        return t.toDoubleOrNull() ?: WORDS[t] ?: WORDS.entries.firstOrNull { t.endsWith(it.key) }?.value
    }

    fun enrichFromText(days: List<DailyForecast>): List<DailyForecast> = days.map { d ->
        if (d.detailSource != null) return@map d
        val text = listOfNotNull(d.dayDetail, d.nightDetail).joinToString(" ")
        if (text.isBlank()) return@map d
        val gustMph = GUST.findAll(text).mapNotNull { it.groupValues[1].toDoubleOrNull() }.maxOrNull()
        val windMph = d.windSummary?.let { s -> WIND_NUMBERS.findAll(s).mapNotNull { it.value.toDoubleOrNull() }.maxOrNull() }
        val snowIn = SNOW.findAll(text).mapNotNull { m -> amount(m.groupValues[2]) ?: amount(m.groupValues[1]) }.sum()
            .takeIf { SNOW.containsMatchIn(text) }
        val iceIn = ICE.findAll(text).mapNotNull { it.groupValues[1].toDoubleOrNull() }.sum().takeIf { ICE.containsMatchIn(text) }
        val rainIn = RAIN.findAll(text).mapNotNull { m -> amount(m.groupValues[2]) ?: amount(m.groupValues[1]) }.sum()
            .takeIf { RAIN.containsMatchIn(text) }
        d.copy(
            windMaxKph = windMph?.let(Units::mphToKph),
            gustMaxKph = gustMph?.let(Units::mphToKph),
            snowMm = snowIn?.let(Units::inchesToMm),
            iceMm = iceIn?.let(Units::inchesToMm),
            precipitationMm = rainIn?.let(Units::inchesToMm),
            detailSource = if (gustMph != null || snowIn != null || iceIn != null || rainIn != null || windMph != null) DetailSource.TEXT else null,
        )
    }
}
