package app.northvane.core.outlook

import app.northvane.core.model.DailyForecast
import app.northvane.core.model.HourlyForecast
import app.northvane.core.util.UnitFormatter
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * The one or two things worth knowing about the next day, as short sentences
 * for the top of the Today screen: when rain or snow starts or stops (from the
 * 15-minute forecast when there is one), strong gusts, a frost after a mild
 * day, and a big change in tomorrow's high. Quiet weather gives a quiet line.
 */
object TodayHeadline {
    /** 0.1 mm in 15 minutes counts as wet (same as the rain-starting notification). */
    private const val WET_MM = 0.1
    private const val HEAVY_MM = 2.5
    /** About 35 mph. */
    private const val GUSTY_KPH = 56.0
    /** A change in the high this big (about 11 °F) is worth a sentence. */
    private const val BIG_CHANGE_C = 6.0
    /** Frost only matters after a mild day; in midwinter every night freezes. */
    private const val MILD_HIGH_C = 4.0

    fun write(
        hourly: List<HourlyForecast>,
        daily: List<DailyForecast>,
        now: Instant,
        zone: ZoneId,
        f: UnitFormatter,
        clock: (Instant) -> String,
        hour: (Instant) -> String,
        nowcast: List<MinutePrecip> = emptyList(),
        maxSentences: Int = 3,
        /** The user's own times ("Drive home" at 5 PM), which come right after anything in the next two hours. */
        times: List<TimeSlot> = emptyList(),
    ): List<String> {
        val sentences = mutableListOf<String>()
        val soon = nearTerm(nowcast, now, clock)
        soon?.let { sentences += it }
        MyTimes.sentence(times, hourly, f, hour, now)?.let { sentences += it }
        val rain = NextRain.find(hourly, now, Duration.ofHours(18))
        // The 15-minute line already covers rain that starts or stops in the next two hours.
        val coveredBySoon = soon != null && rain != null && rain.start.isBefore(now.plus(Duration.ofHours(2)))
        when {
            rain != null && !coveredBySoon -> sentences += rainSentence(rain, hourly, f, hour)
            rain == null && soon == null && hourly.any { it.time.isAfter(now) } -> sentences += "Dry for the next 18 hours."
        }
        gusts(hourly, now, f, hour)?.let { sentences += it }
        frost(hourly, daily, now, zone, f)?.let { sentences += it }
        change(daily, now, zone, f)?.let { sentences += it }
        return sentences.take(maxSentences)
    }

    /** Rain starting or stopping within two hours, from the 15-minute forecast. */
    internal fun nearTerm(steps: List<MinutePrecip>, now: Instant, clock: (Instant) -> String): String? {
        val upcoming = steps.filter { it.time.plus(Duration.ofMinutes(15)) > now && it.time <= now.plus(Duration.ofHours(2)) }.sortedBy { it.time }
        if (upcoming.size < 2) return null
        val wetNow = upcoming.first().precipitationMm >= WET_MM
        if (!wetNow) {
            val start = upcoming.firstOrNull { it.precipitationMm >= WET_MM } ?: return null
            val after = upcoming.filter { !it.time.isBefore(start.time) }
            val noun = noun(after)
            val minutes = Duration.between(now, start.time).toMinutes().coerceAtLeast(0)
            val heavy = if (after.any { it.precipitationMm >= HEAVY_MM }) ", heavy at times" else ""
            return if (minutes <= 50) "$noun in about ${roundTo5(minutes)} min$heavy." else "$noun from about ${clock(start.time)}$heavy."
        }
        val stop = upcoming.firstOrNull { it.precipitationMm < WET_MM } ?: return "${noun(upcoming)} continuing for the next two hours."
        return "${noun(upcoming.filter { it.time.isBefore(stop.time) })} ending around ${clock(stop.time)}."
    }

    private fun noun(steps: List<MinutePrecip>): String {
        val snow = steps.sumOf { it.snowfallCm }
        val liquid = steps.sumOf { it.precipitationMm }
        return when {
            snow > 0 && snow >= liquid * 0.8 -> "Snow"
            snow > 0 -> "Wintry mix"
            else -> "Rain"
        }
    }

    private fun roundTo5(minutes: Long): Long = ((minutes + 2) / 5 * 5).coerceAtLeast(5)

    private fun rainSentence(r: RainWindow, hourly: List<HourlyForecast>, f: UnitFormatter, hour: (Instant) -> String): String {
        val end = r.end ?: r.start.plus(Duration.ofHours(18))
        val window = hourly.filter { !it.time.isBefore(r.start) && it.time.isBefore(end) }
        val snow = window.sumOf { it.snowMm ?: 0.0 }
        val liquid = window.sumOf { it.precipitationMm ?: 0.0 }
        val amount = when {
            r.kind == PrecipKind.SNOW && snow >= 5 -> ", about ${f.snow(snow)}"
            r.kind != PrecipKind.SNOW && liquid >= 1 -> ", about ${f.precipitation(liquid)}"
            else -> ""
        }
        return when {
            r.ongoing && r.end != null -> "${r.kind.noun} until about ${hour(r.end)}$amount."
            r.ongoing -> "${r.kind.noun} through the next 18 hours$amount."
            else -> "${r.kind.noun} from ${hour(r.start)}$amount."
        }
    }

    private fun gusts(hourly: List<HourlyForecast>, now: Instant, f: UnitFormatter, hour: (Instant) -> String): String? {
        val strongest = hourly.filter { it.time.plusSeconds(3600) > now && it.time < now.plus(Duration.ofHours(12)) }
            .filter { (it.windGustKph ?: 0.0) >= GUSTY_KPH }
            .maxByOrNull { it.windGustKph ?: 0.0 } ?: return null
        return "Gusts to ${f.windSpeed(strongest.windGustKph)} around ${hour(strongest.time)}."
    }

    /** Freezing tonight after a day above about 39 °F. */
    private fun frost(hourly: List<HourlyForecast>, daily: List<DailyForecast>, now: Instant, zone: ZoneId, f: UnitFormatter): String? {
        val local = now.atZone(zone)
        val morning = local.toLocalDate().plusDays(if (local.hour < 5) 0 else 1).atTime(9, 0).atZone(zone).toInstant()
        val night = hourly.filter { it.time.isAfter(now) && it.time.isBefore(morning) && it.temperatureC != null }
        val low = night.minOfOrNull { it.temperatureC!! } ?: return null
        val todayHigh = daily.firstOrNull { it.date == local.toLocalDate() }?.highC
            ?: hourly.filter { it.time.isBefore(now.plus(Duration.ofHours(6))) }.mapNotNull { it.temperatureC }.maxOrNull()
            ?: return null
        if (low > 0.0 || todayHigh < MILD_HIGH_C) return null
        val word = if (low <= -2.0) "A freeze" else "Frost"
        return "$word tonight, low ${f.temperature(low)}."
    }

    private fun change(daily: List<DailyForecast>, now: Instant, zone: ZoneId, f: UnitFormatter): String? {
        val today = now.atZone(zone).toLocalDate()
        val todayHigh = daily.firstOrNull { it.date == today }?.highC ?: return null
        val tomorrowHigh = daily.firstOrNull { it.date == today.plusDays(1) }?.highC ?: return null
        val diff = tomorrowHigh - todayHigh
        return when {
            diff <= -BIG_CHANGE_C -> "Much colder tomorrow, high ${f.temperature(tomorrowHigh)}."
            diff >= BIG_CHANGE_C -> "Much warmer tomorrow, high ${f.temperature(tomorrowHigh)}."
            else -> null
        }
    }
}
