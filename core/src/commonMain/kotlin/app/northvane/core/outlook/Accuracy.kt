package app.northvane.core.outlook

import app.northvane.core.json.LocalDateSerializer
import app.northvane.core.model.DailyForecast
import app.northvane.core.model.WeatherHistory
import app.northvane.core.model.WeatherSnapshot
import app.northvane.core.util.UnitFormatter
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.roundToInt
import app.northvane.core.time.*

/** What the forecast said about a day, the day before. */
@Serializable
data class ForecastRecord(
    @Serializable(LocalDateSerializer::class) val date: LocalDate,
    val highC: Double?,
    val lowC: Double?,
    val chance: Int?,
)

/**
 * How the forecast has been doing at this place: each refresh records the
 * forecast for tomorrow, and once a day has passed it is compared with what
 * NOAA observed there. Honest feedback no other app gives: "Forecast highs
 * ran 3° warm this week."
 */
object ForecastAccuracy {
    private const val KEEP_DAYS = 21L

    /** [log] with tomorrow's forecast from [snapshot] recorded (the latest refresh before the day wins). */
    fun record(log: List<ForecastRecord>, snapshot: WeatherSnapshot, zone: ZoneId, today: LocalDate): List<ForecastRecord> {
        val tomorrow = snapshot.daily.firstOrNull { it.date == today.plusDays(1) } ?: return log.prune(today)
        val entry = ForecastRecord(tomorrow.date, tomorrow.highC, tomorrow.lowC, tomorrow.precipitationChance)
        return (log.filter { it.date != entry.date } + entry).prune(today)
    }

    private fun List<ForecastRecord>.prune(today: LocalDate) = filter { !it.date.isBefore(today.minusDays(KEEP_DAYS)) }.sortedBy { it.date }

    data class Summary(
        val days: Int,
        /** Average forecast minus observed, °C; positive means the forecast ran warm. */
        val highBiasC: Double?,
        val lowBiasC: Double?,
        /** Days where the forecast had rain likely (50%+) or not, and it did or didn't rain (1 mm+). */
        val rainRight: Int,
        val rainDays: Int,
    )

    /** The last [window] days that have both a forecast and an observation. */
    fun summarize(log: List<ForecastRecord>, history: WeatherHistory?, today: LocalDate, window: Int = 7): Summary? {
        val observed = history?.days.orEmpty().associateBy { it.date }
        val pairs = log.filter { it.date.isBefore(today) && !it.date.isBefore(today.minusDays(window.toLong())) }
            .mapNotNull { r -> observed[r.date]?.let { r to it } }
        if (pairs.size < 3) return null
        val highs = pairs.mapNotNull { (r, o) -> if (r.highC != null && o.highC != null) r.highC - o.highC else null }
        val lows = pairs.mapNotNull { (r, o) -> if (r.lowC != null && o.lowC != null) r.lowC - o.lowC else null }
        val rain = pairs.filter { (r, o) -> r.chance != null && o.precipMm != null }
        val right = rain.count { (r, o) -> ((r.chance ?: 0) >= 50) == ((o.precipMm ?: 0.0) >= 1.0) }
        return Summary(pairs.size, highs.takeIf { it.size >= 3 }?.average(), lows.takeIf { it.size >= 3 }?.average(), right, rain.size)
    }

    /** "Last 7 days: highs ran about 3° warm, lows on target; rain called right 6 of 7 days." */
    fun describe(s: Summary, f: UnitFormatter, imperial: Boolean): String {
        fun bias(c: Double?, what: String): String? {
            c ?: return null
            val shown = if (imperial) c * 9 / 5 else c
            val size = abs(shown).roundToInt()
            return when {
                size < 2 -> "$what on target"
                c > 0 -> "$what ran about $size° warm"
                else -> "$what ran about $size° cool"
            }
        }
        val parts = listOfNotNull(bias(s.highBiasC, "highs"), bias(s.lowBiasC, "lows")).joinToString(", ")
        val rain = if (s.rainDays >= 3) "rain called right ${s.rainRight} of ${s.rainDays} days" else null
        return "Forecast, last ${s.days} days: " + listOfNotNull(parts.ifEmpty { null }, rain).joinToString("; ") + "."
    }
}

/** Questions worth asking right now, for the chips under the question box. */
object AskSuggestions {
    fun pick(s: WeatherSnapshot, today: LocalDate, zone: ZoneId, auroraTonight: Boolean, frostTonight: Boolean, max: Int = 2): List<String> {
        val now = kotlin.time.Clock.System.now()
        val day = s.daily.firstOrNull { it.date == today }
        val list = mutableListOf<String>()
        StormTotals.find(s.hourly, s.daily, s.alerts, now, zone)?.let { storm ->
            val noun = storm.kind.noun.lowercase()
            list += "When does the $noun start?"
            list += if (storm.snowMm >= 25) "How much snow will we get?" else "When will it end?"
        }
        if (s.alerts.isNotEmpty()) list += "What does the ${s.alerts.first().event.lowercase()} mean for me?"
        NextRain.find(s.hourly, now, app.northvane.core.time.Duration.ofHours(12))?.let { r ->
            list += if (r.ongoing) "When will the ${r.kind.noun.lowercase()} stop?" else "Will I need an umbrella today?"
        }
        if (auroraTonight) list += "Will I see the northern lights tonight?"
        if (frostTonight) list += "Do I need to cover my plants tonight?"
        if ((day?.highC ?: 0.0) >= 29) list += "When is it coolest for a run?"
        if (s.daily.any { (it.mud?.level?.ordinal ?: 0) >= app.northvane.core.model.MudLevel.MUDDY.ordinal }) list += "How muddy are the trails?"
        val weekend = today.dayOfWeek.value >= 4
        list += if (weekend) "Best day to hike this weekend?" else "Good day this week for yard work?"
        list += "Will I need an umbrella today?"
        return list.distinct().take(max)
    }
}
