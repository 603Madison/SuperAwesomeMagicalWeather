package app.northvane.core.outlook

import app.northvane.core.json.InstantSerializer
import app.northvane.core.json.LocalDateSerializer
import app.northvane.core.model.DailyForecast
import app.northvane.core.model.HourlyForecast
import app.northvane.core.model.PeriodStats
import app.northvane.core.model.Units
import kotlinx.serialization.Serializable
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import app.northvane.core.time.*

@Serializable
enum class DryingBlocker { FREEZING, RAIN, DAMP, NIGHT }

@Serializable
data class DryingHour(@Serializable(InstantSerializer::class) val time: Instant, val index: Int, val blocker: DryingBlocker? = null)

@Serializable
data class DryingWindow(
    @Serializable(InstantSerializer::class) val start: Instant,
    @Serializable(InstantSerializer::class) val end: Instant,
    val index: Int,
    /** Rough time for a load of cotton to dry in these conditions. */
    val hoursToDry: Double,
)

@Serializable
data class DryingDay(@Serializable(LocalDateSerializer::class) val date: LocalDate, val score: Int, val blocker: DryingBlocker? = null)

@Serializable
data class DryingReport(
    val hours: List<DryingHour>,
    val best: DryingWindow?,
    /** First likely rain (or thunder) in the next 24 hours: bring laundry in before this. */
    @Serializable(InstantSerializer::class) val nextRain: Instant?,
    val nextRainChance: Int?,
    val days: List<DryingDay>,
)

/**
 * How well laundry dries on a line, hour by hour: evaporation from the
 * vapor-pressure deficit (warm, dry air), a moderate breeze, and sunshine;
 * rain stops it, and wet clothes don't dry below freezing (they freeze).
 */
object DryingAdvisor {

    /** Saturation vapor pressure, kPa. */
    private fun es(c: Double) = 0.6108 * exp(17.27 * c / (c + 237.3))

    fun index(tempC: Double?, humidity: Double?, windKph: Double?, sky: Int?, chance: Int?, precipMm: Double?, daytime: Boolean): Pair<Int, DryingBlocker?> {
        val t = tempC ?: return 0 to null
        if (Units.cToF(t) <= 32.0) return 0 to DryingBlocker.FREEZING
        if ((chance ?: 0) >= 50 || (precipMm ?: 0.0) >= 0.2) return 0 to DryingBlocker.RAIN
        val rh = humidity ?: 65.0
        val vpd = es(t) * (1 - rh / 100.0)
        var score = min(1.0, vpd / 1.6) * 55
        val wind = windKph?.let(Units::kphToMph) ?: 5.0
        score += if (wind <= 22) 25.0 * min(wind, 12.0) / 12.0 else max(0.0, 25 - (wind - 22) * 2)
        if (daytime) score += 20.0 * (1 - (sky ?: 50) / 100.0)
        score -= (chance ?: 0) * 0.4
        if (Units.cToF(t) < 40) score *= 0.6 // near freezing, drying slows to a crawl
        val blocker = when {
            rh >= 90 -> DryingBlocker.DAMP
            !daytime -> DryingBlocker.NIGHT
            else -> null
        }
        if (blocker == DryingBlocker.DAMP) score = min(score, 15.0)
        return score.coerceIn(0.0, 100.0).roundToInt() to blocker
    }

    fun hoursToDry(index: Int): Double = 1.5 + 10 * (1 - index / 100.0).coerceAtLeast(0.0).pow(1.5)

    fun report(hourly: List<HourlyForecast>, daily: List<DailyForecast>, now: Instant, zone: ZoneId): DryingReport {
        val hours = hourly.filter { it.time.isAfter(now.minus(Duration.ofMinutes(59))) }.take(48).map { h ->
            val (i, b) = index(h.temperatureC, h.humidityPercent, h.windSpeedKph, h.skyCoverPercent, h.precipitationChance, h.precipitationMm, h.isDaytime)
            DryingHour(h.time, i, b)
        }
        val nextRain = hourly.filter { it.time.isAfter(now.minus(Duration.ofMinutes(59))) && it.time.isBefore(now.plus(Duration.ofHours(24))) }
            .firstOrNull { (it.precipitationChance ?: 0) >= 40 || (it.precipitationMm ?: 0.0) >= 0.2 || (it.thunderChance ?: 0) >= 20 }
        return DryingReport(
            hours = hours,
            best = bestWindow(hours, now, zone),
            nextRain = nextRain?.time,
            nextRainChance = nextRain?.precipitationChance,
            days = daily.filter { !it.date.isBefore(now.atZone(zone).toLocalDate()) }.take(7).map(::dayScore),
        )
    }

    /** Runs of good daytime hours long enough to dry a load; prefer today if it's nearly as good. */
    private fun bestWindow(hours: List<DryingHour>, now: Instant, zone: ZoneId): DryingWindow? {
        val runs = mutableListOf<List<DryingHour>>()
        var current = mutableListOf<DryingHour>()
        for (h in hours) {
            if (h.index >= 45 && h.blocker == null) current += h else {
                if (current.isNotEmpty()) runs += current
                current = mutableListOf()
            }
        }
        if (current.isNotEmpty()) runs += current
        val windows = runs.mapNotNull { run ->
            val avg = run.map { it.index }.average().roundToInt()
            val need = hoursToDry(avg)
            if (run.size < need) null else DryingWindow(run.first().time, run.last().time.plus(Duration.ofHours(1)), avg, need)
        }
        if (windows.isEmpty()) return null
        val today = now.atZone(zone).toLocalDate()
        val best = windows.maxBy { it.index }
        val todayBest = windows.filter { it.start.atZone(zone).toLocalDate() == today }.maxByOrNull { it.index }
        return if (todayBest != null && todayBest.index >= best.index - 10) todayBest else best
    }

    fun dayScore(day: DailyForecast): DryingDay {
        val p: PeriodStats = day.daytime ?: Periods.fromDaily(day, overnight = false)
        if ((p.tempMaxC ?: day.highC)?.let { Units.cToF(it) <= 32.0 } == true) return DryingDay(day.date, 0, DryingBlocker.FREEZING)
        val mean = listOfNotNull(p.tempMaxC, p.tempMinC).takeIf { it.isNotEmpty() }?.average()
        val (i, b) = index(mean, p.humidityMean, p.windMeanKph, p.skyCoverMean, p.precipChanceMax, p.precipMm?.let { it / 10 }, daytime = true)
        return DryingDay(day.date, i, b)
    }

    /** For the laundry watch: rain or thunder expected within [lead] of [now]? */
    fun rainSoon(hourly: List<HourlyForecast>, now: Instant, lead: Duration = Duration.ofMinutes(120)): HourlyForecast? =
        hourly.filter { it.time.isAfter(now.minus(Duration.ofMinutes(59))) && it.time.isBefore(now.plus(lead)) }
            .firstOrNull { (it.precipitationChance ?: 0) >= 40 || (it.precipitationMm ?: 0.0) >= 0.2 || (it.thunderChance ?: 0) >= 20 }
}
