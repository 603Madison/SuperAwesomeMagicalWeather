package app.northvane.core.outlook

import app.northvane.core.model.AlertCategory
import app.northvane.core.model.DailyForecast
import app.northvane.core.model.HourlyForecast
import app.northvane.core.model.WeatherAlert
import app.northvane.core.model.WeatherCondition
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class PrecipKind(val noun: String) { RAIN("Rain"), SNOW("Snow"), MIX("Wintry mix") }

private val SNOWY = setOf(WeatherCondition.SNOW, WeatherCondition.BLIZZARD)
private val MIXED = setOf(WeatherCondition.RAIN_SNOW, WeatherCondition.SLEET, WeatherCondition.FREEZING_RAIN)

internal fun kindOf(conditions: Collection<WeatherCondition>, snowMm: Double = 0.0, liquidMm: Double = 0.0): PrecipKind = when {
    conditions.any { it in MIXED } -> PrecipKind.MIX
    conditions.any { it in SNOWY } && conditions.any { it.isPrecipitation && it !in SNOWY } -> PrecipKind.MIX
    conditions.any { it in SNOWY } -> PrecipKind.SNOW
    snowMm > 0 && liquidMm > 0 && snowMm < liquidMm * 5 -> PrecipKind.MIX
    snowMm > 0 -> PrecipKind.SNOW
    else -> PrecipKind.RAIN
}

/** The next stretch of likely rain or snow in the hourly forecast. */
data class RainWindow(
    val start: Instant,
    /** When it's likely to stop; null if it runs past the end of the look-ahead. */
    val end: Instant?,
    val chance: Int,
    val kind: PrecipKind,
    /** Already falling (or likely) in the current hour. */
    val ongoing: Boolean,
)

object NextRain {
    /** A chance from which an hour counts as wet. */
    const val LIKELY = 50
    /** Below this, a wet stretch is over. */
    private const val ENDS_BELOW = 30

    fun find(hourly: List<HourlyForecast>, now: Instant, lookAhead: Duration = Duration.ofHours(24)): RainWindow? {
        val hours = hourly.filter { it.time.plusSeconds(3600) > now && it.time < now.plus(lookAhead) }
        val first = hours.indexOfFirst { (it.precipitationChance ?: 0) >= LIKELY }
        if (first < 0) return null
        val run = hours.drop(first).takeWhile { (it.precipitationChance ?: 0) >= ENDS_BELOW }
        return RainWindow(
            start = hours[first].time,
            end = hours.getOrNull(first + run.size)?.time,
            chance = run.maxOf { it.precipitationChance ?: 0 },
            kind = kindOf(run.map { it.condition }, run.sumOf { it.snowMm ?: 0.0 }, run.sumOf { it.precipitationMm ?: 0.0 }),
            ongoing = !hours[first].time.isAfter(now),
        )
    }
}

/** Precipitation in one 15-minute step (Open-Meteo's HRRR-based nowcast in the US). */
data class MinutePrecip(val time: Instant, val precipitationMm: Double, val snowfallCm: Double)

/** Rain or snow about to start where it's dry now. */
data class RainSoon(val start: Instant, val kind: PrecipKind, val heavy: Boolean)

object RainSoonDetector {
    /** 0.1 mm in 15 minutes: enough to notice, more than model drizzle noise. */
    private const val WET_MM = 0.1
    /** 2.5 mm in 15 minutes (10 mm an hour) is heavy. */
    private const val HEAVY_MM = 2.5

    /** The start of rain or snow within [lead], if the current 15 minutes are dry. */
    fun detect(steps: List<MinutePrecip>, now: Instant, lead: Duration = Duration.ofMinutes(60)): RainSoon? {
        val upcoming = steps.filter { it.time.plus(Duration.ofMinutes(15)) > now && it.time <= now.plus(lead) }.sortedBy { it.time }
        if (upcoming.isEmpty() || upcoming.first().precipitationMm >= WET_MM) return null
        val firstWet = upcoming.firstOrNull { it.precipitationMm >= WET_MM } ?: return null
        val wet = upcoming.filter { !it.time.isBefore(firstWet.time) }
        val snow = wet.sumOf { it.snowfallCm }
        val liquid = wet.sumOf { it.precipitationMm }
        // A centimetre of fresh snow holds about a millimetre of water: mostly snow means snow.
        val kind = when {
            snow > 0 && snow >= liquid * 0.8 -> PrecipKind.SNOW
            snow > 0 -> PrecipKind.MIX
            else -> PrecipKind.RAIN
        }
        return RainSoon(firstWet.time, kind, heavy = wet.any { it.precipitationMm >= HEAVY_MM })
    }

    /** Whether a start at [start] is a new event after the one last notified at [lastNotified]. */
    fun isNew(start: Instant, lastNotified: Instant?): Boolean =
        lastNotified == null || Duration.between(lastNotified, start) >= Duration.ofHours(3)
}

/** Totals for the next significant storm. Amounts are metric: liquid rain and ice in mm, snow depth in mm. */
data class StormSummary(
    val title: String,
    val kind: PrecipKind,
    /** Start and end, when the hourly forecast reaches the storm; otherwise only [days] are known. */
    val start: Instant?,
    val end: Instant?,
    val days: List<LocalDate>,
    val rainMm: Double,
    val snowMm: Double,
    val iceMm: Double,
    /** The 6 hours with the most precipitation. */
    val peakStart: Instant?,
    val peakEnd: Instant?,
    /** The NWS alert covering it, if any (e.g. "Winter Storm Warning"). */
    val alert: String? = null,
)

object StormTotals {
    /** Thresholds for a storm worth a card: 2" of snow, 0.1" of ice, or 1" of rain. */
    const val SNOW_MM = 50.0
    const val ICE_MM = 2.5
    const val RAIN_MM = 25.0
    /** Dry hours that still count as the same storm. */
    private const val GAP_HOURS = 6

    fun find(hourly: List<HourlyForecast>, daily: List<DailyForecast>, alerts: List<WeatherAlert>, now: Instant, zone: ZoneId): StormSummary? {
        val hours = hourly.filter { it.time.plusSeconds(3600) > now }.sortedBy { it.time }
        val hasAmounts = hours.any { it.precipitationMm != null || it.snowMm != null }
        val alert = stormAlert(alerts, now)
        return if (hasAmounts) fromHours(hours, alert, zone) else fromDays(daily, alert, now, zone)
    }

    private fun wet(h: HourlyForecast) = (h.precipitationMm ?: 0.0) > 0.05 || (h.snowMm ?: 0.0) > 0.5 || (h.iceMm ?: 0.0) > 0.05

    private fun significant(rain: Double, snow: Double, ice: Double) = snow >= SNOW_MM || ice >= ICE_MM || rain >= RAIN_MM

    private fun fromHours(hours: List<HourlyForecast>, alert: String?, zone: ZoneId): StormSummary? {
        var i = 0
        while (i < hours.size) {
            if (!wet(hours[i])) { i++; continue }
            // Grow the event while the dry gaps stay short.
            var last = i
            var j = i + 1
            while (j < hours.size && Duration.between(hours[last].time, hours[j].time).toHours() <= GAP_HOURS) {
                if (wet(hours[j])) last = j
                j++
            }
            val event = hours.subList(i, last + 1)
            val snow = event.sumOf { it.snowMm ?: 0.0 }
            val ice = event.sumOf { it.iceMm ?: 0.0 }
            // Rain is the liquid that fell in hours without snow or ice.
            val rain = event.filter { (it.snowMm ?: 0.0) < 0.5 && (it.iceMm ?: 0.0) < 0.05 }.sumOf { it.precipitationMm ?: 0.0 }
            val liquid = event.sumOf { it.precipitationMm ?: 0.0 }
            if (significant(rain, snow, ice)) {
                val start = event.first().time
                val end = event.last().time.plusSeconds(3600)
                // The heaviest 6 hours, by liquid water (snow counted as its water).
                val peakIndex = event.indices.maxBy { k ->
                    event.filter { !it.time.isBefore(event[k].time) && it.time.isBefore(event[k].time.plus(Duration.ofHours(6))) }
                        .sumOf { (it.precipitationMm ?: 0.0) + (it.snowMm ?: 0.0) / 10 }
                }
                val peakStart = event[peakIndex].time
                val kind = when {
                    ice >= ICE_MM && snow < SNOW_MM -> PrecipKind.MIX
                    snow > 0 && (rain >= 5.0 || ice > 0) -> PrecipKind.MIX
                    snow >= SNOW_MM / 2 -> PrecipKind.SNOW
                    else -> kindOf(event.map { it.condition }, snow, liquid)
                }
                val days = generateSequence(start.atZone(zone).toLocalDate()) { it.plusDays(1) }
                    .takeWhile { !it.isAfter(end.atZone(zone).toLocalDate()) }.toList()
                return StormSummary(titleFor(kind, snow, ice, rain), kind, start, end, days, rain, snow, ice, peakStart, minOf(peakStart.plus(Duration.ofHours(6)), end), alert)
            }
            i = last + 1
        }
        return null
    }

    private fun fromDays(daily: List<DailyForecast>, alert: String?, now: Instant, zone: ZoneId): StormSummary? {
        val today = now.atZone(zone).toLocalDate()
        val days = daily.filter { !it.date.isBefore(today) }.sortedBy { it.date }
        var i = 0
        while (i < days.size) {
            val d = days[i]
            if ((d.precipitationMm ?: 0.0) <= 0 && (d.snowMm ?: 0.0) <= 0) { i++; continue }
            var j = i
            while (j + 1 < days.size && days[j + 1].date == days[j].date.plusDays(1) &&
                ((days[j + 1].precipitationMm ?: 0.0) > 0 || (days[j + 1].snowMm ?: 0.0) > 0)
            ) j++
            val event = days.subList(i, j + 1)
            val snow = event.sumOf { it.snowMm ?: 0.0 }
            val ice = event.sumOf { it.iceMm ?: 0.0 }
            val rain = event.filter { (it.snowMm ?: 0.0) < 2.5 }.sumOf { it.precipitationMm ?: 0.0 }
            if (significant(rain, snow, ice)) {
                val kind = when {
                    snow >= SNOW_MM && (rain >= 5.0 || ice > 0) -> PrecipKind.MIX
                    snow >= SNOW_MM / 2 -> PrecipKind.SNOW
                    ice >= ICE_MM -> PrecipKind.MIX
                    else -> PrecipKind.RAIN
                }
                return StormSummary(titleFor(kind, snow, ice, rain), kind, null, null, event.map { it.date }, rain, snow, ice, null, null, alert)
            }
            i = j + 1
        }
        return null
    }

    private fun titleFor(kind: PrecipKind, snowMm: Double, iceMm: Double, rainMm: Double): String = when {
        // A quarter inch of ice brings down limbs and lines (Sperry-Piltz).
        iceMm >= 6.35 -> "Ice storm"
        kind == PrecipKind.MIX -> "Winter storm"
        kind == PrecipKind.SNOW && snowMm >= 150 -> "Major snowstorm"
        kind == PrecipKind.SNOW -> "Snowstorm"
        rainMm >= RAIN_MM * 2 -> "Heavy rain storm"
        else -> "Soaking rain"
    }

    private val STORM_WORDS = listOf("Winter Storm", "Blizzard", "Ice Storm", "Snow Squall", "Flood", "Heavy Snow", "Lake Effect", "Hurricane", "Tropical Storm", "Winter Weather")

    private fun stormAlert(alerts: List<WeatherAlert>, now: Instant): String? = alerts
        .filter { a -> (a.ends ?: a.expires)?.isAfter(now) != false && STORM_WORDS.any { a.event.contains(it, ignoreCase = true) } }
        .minByOrNull { it.category.ordinal }
        ?.event
}

/** The morning briefing: a title and a few spoken-style sentences. */
data class Briefing(val title: String, val text: String)

object BriefingWriter {
    /**
     * Today's forecast in a few sentences that read well in a notification and
     * aloud: conditions, high and low, rain timing, wind, alerts and any storm.
     */
    fun write(
        place: String,
        daily: List<DailyForecast>,
        hourly: List<HourlyForecast>,
        alerts: List<WeatherAlert>,
        zone: ZoneId,
        now: Instant,
        temperature: (Double?) -> String,
        wind: (Double?) -> String,
        hour: (Instant) -> String,
    ): Briefing {
        val today = now.atZone(zone).toLocalDate()
        val day = daily.firstOrNull { it.date == today } ?: daily.firstOrNull { !it.date.isBefore(today) }
        val sentences = mutableListOf<String>()
        if (day != null) {
            val temps = listOfNotNull(day.highC?.let { "high ${temperature(it)}" }, day.lowC?.let { "low ${temperature(it)}" })
            val outlook = (day.shortForecast ?: day.condition.label).trimEnd('.')
            sentences += (listOf(outlook) + temps).joinToString(", ")
        }
        NextRain.find(hourly, now, Duration.ofHours(18))?.let { r ->
            val noun = if (r.kind == PrecipKind.MIX) "A wintry mix" else r.kind.noun
            sentences += when {
                r.ongoing && r.end != null -> "$noun until about ${hour(r.end)}"
                r.ongoing -> "$noun through the day"
                r.end != null -> "$noun likely from ${hour(r.start)} to ${hour(r.end)} (${r.chance}%)"
                else -> "$noun likely from ${hour(r.start)} (${r.chance}%)"
            }
        } ?: run { if (hourly.any { it.time.isAfter(now) }) sentences += "Staying dry" }
        val gust = hourly.filter { it.time.isAfter(now) && it.time.isBefore(now.plus(Duration.ofHours(18))) }.mapNotNull { it.windGustKph }.maxOrNull()
            ?: day?.gustMaxKph
        if (gust != null && gust >= 48) sentences += "Gusts up to ${wind(gust)}"
        val active = alerts.filter { (it.ends ?: it.expires)?.isAfter(now) != false && it.category != AlertCategory.STATEMENT }
            .sortedBy { it.category.ordinal }
        when (active.size) {
            0 -> Unit
            1 -> sentences += active.first().event
            else -> sentences += "${active.first().event} and ${active.size - 1} more alert${if (active.size > 2) "s" else ""}"
        }
        val text = sentences.joinToString(". ", postfix = ".").takeIf { sentences.isNotEmpty() } ?: "No forecast available."
        val title = if (day?.highC != null) "$place today: ${temperature(day.highC)}" else "$place today"
        return Briefing(title, text)
    }
}
