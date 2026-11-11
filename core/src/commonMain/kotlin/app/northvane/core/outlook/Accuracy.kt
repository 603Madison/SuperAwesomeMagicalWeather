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

/** What the forecast said about a day, the day before, and how much rain then fell. */
@Serializable
data class ForecastRecord(
    @Serializable(LocalDateSerializer::class) val date: LocalDate,
    val highC: Double?,
    val lowC: Double?,
    val chance: Int?,
    /** Rain and melted snow NOAA's daily analysis found here, once the day is in it. */
    val observedMm: Double? = null,
    /** The day's total from the user's own weather station, when it has one. */
    val stationMm: Double? = null,
)

/**
 * How the forecast has been doing at this place: each refresh records the
 * forecast for tomorrow, and once a day has passed it is compared with what
 * NOAA observed there. Honest feedback no other app gives: "Forecast highs
 * ran 3° warm this week."
 */
object ForecastAccuracy {
    /** Four months: enough days for "did it rain?" to say how the rain chances do here. */
    private const val KEEP_DAYS = 120L

    /**
     * [log] with tomorrow's forecast from [snapshot] recorded (the latest
     * refresh before the day wins), and the rain that fell filled in for past
     * days from NOAA's history and for today from the user's station.
     */
    fun record(log: List<ForecastRecord>, snapshot: WeatherSnapshot, zone: ZoneId, today: LocalDate): List<ForecastRecord> {
        val observed = snapshot.history?.days.orEmpty().associateBy { it.date }
        val stationToday = snapshot.current?.rainTodayMm?.takeIf { it >= 0 }
        var updated = log.map { r ->
            val noaa = observed[r.date]?.precipMm?.takeIf { r.date.isBefore(today) } ?: r.observedMm
            // A station's day total only grows; it resets at midnight, so only today's value is today's.
            val station = if (r.date == today && stationToday != null) maxOf(stationToday, r.stationMm ?: 0.0) else r.stationMm
            if (noaa != r.observedMm || station != r.stationMm) r.copy(observedMm = noaa, stationMm = station) else r
        }
        val tomorrow = snapshot.daily.firstOrNull { it.date == today.plusDays(1) }
        if (tomorrow != null) {
            val entry = ForecastRecord(tomorrow.date, tomorrow.highC, tomorrow.lowC, tomorrow.precipitationChance)
            updated = updated.filter { it.date != entry.date } + entry
        }
        return updated.prune(today)
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

/**
 * Did it rain? Each day's rain chance (as forecast the day before) against
 * whether measurable rain fell here: from the user's own weather station when
 * there is one, otherwise NOAA's daily analysis. Over a few weeks this shows
 * how the chances work out at this spot, which can differ from the region
 * the forecast is written for (a valley, the coast, a rain shadow).
 */
object RainCheck {
    /** Measurable rain, as rain chances mean it: 0.01 inch. */
    const val MEASURABLE_MM = 0.25
    /** Days in a band before saying anything about it. */
    private const val MIN_DAYS = 10
    /** How far the real share must be from the forecast before it's worth saying. */
    private const val MIN_GAP_POINTS = 15

    data class Day(val date: LocalDate, val chance: Int, val rainMm: Double, val fromStation: Boolean) {
        val rained: Boolean get() = rainMm >= MEASURABLE_MM
    }

    enum class Band(val range: IntRange, val label: String) {
        LOW(0..24, "under 25%"),
        MIDDLE(25..64, "25–60%"),
        HIGH(65..100, "65% and up");

        companion object {
            fun of(chance: Int): Band = entries.first { chance.coerceIn(0, 100) in it.range }
        }
    }

    data class BandResult(val band: Band, val days: Int, val rainDays: Int, val meanChance: Int) {
        val rainPercent: Int get() = if (days == 0) 0 else (rainDays * 100 + days / 2) / days
        /** Enough days, and far enough from the forecast's own chance, to mention. */
        val telling: Boolean get() = days >= MIN_DAYS && kotlin.math.abs(rainPercent - meanChance) >= MIN_GAP_POINTS
    }

    data class Report(
        /** Days with both a forecast chance and a measurement, oldest first. */
        val days: List<Day>,
        val bands: List<BandResult>,
    ) {
        val latest: Day? get() = days.lastOrNull()
        val fromStation: Boolean get() = days.takeLast(14).any { it.fromStation }

        /** How chances like [chance] have worked out here, when that says something. */
        fun note(chance: Int): String? {
            val b = bands.firstOrNull { it.band == Band.of(chance) }?.takeIf { it.telling } ?: return null
            val tens = (b.rainPercent + 5) / 10
            return "Around here lately, chances like that have meant rain about $tens day${if (tens == 1) "" else "s"} in 10."
        }
    }

    fun report(log: List<ForecastRecord>, today: LocalDate): Report? {
        val days = log.filter { it.date.isBefore(today) }.mapNotNull { r ->
            val chance = r.chance ?: return@mapNotNull null
            val station = r.stationMm
            when {
                station != null -> Day(r.date, chance, station, fromStation = true)
                r.observedMm != null -> Day(r.date, chance, r.observedMm, fromStation = false)
                else -> null
            }
        }.sortedBy { it.date }
        if (days.isEmpty()) return null
        val bands = Band.entries.map { band ->
            val inBand = days.filter { Band.of(it.chance) == band }
            BandResult(band, inBand.size, inBand.count { it.rained }, if (inBand.isEmpty()) 0 else inBand.map { it.chance }.average().toInt())
        }
        return Report(days, bands)
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
