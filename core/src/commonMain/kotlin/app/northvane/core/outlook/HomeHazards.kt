package app.northvane.core.outlook

import app.northvane.core.model.DailyForecast
import app.northvane.core.model.HourlyForecast
import app.northvane.core.model.Likelihood
import app.northvane.core.model.MorningIce
import app.northvane.core.model.OutageLevel
import app.northvane.core.model.PipeFactor
import app.northvane.core.model.PipeRisk
import app.northvane.core.model.Units
import kotlin.math.roundToInt
import app.northvane.core.time.*

/**
 * Frozen pipe risk. Plumbers and insurers use 20°F (−6.7 °C) as the point
 * where pipes in unheated spaces and outside walls start to freeze; risk grows
 * with how cold, how long, wind, and the chance of losing heat in an outage.
 */
object PipeFreeze {
    const val THRESHOLD_C = -6.7 // 20°F
    private const val HARD_C = -12.2 // 10°F
    private const val SEVERE_C = -17.8 // 0°F
    private const val WINDY_KPH = 24.0 // 15 mph

    /** The day and the night after it, 7 AM to 8 AM the next morning. */
    fun window(date: LocalDate, zone: ZoneId): Pair<Instant, Instant> =
        date.atTime(7, 0).atZone(zone).toInstant() to date.plusDays(1).atTime(8, 0).atZone(zone).toInstant()

    fun estimate(day: DailyForecast, hourly: List<HourlyForecast>, zone: ZoneId): PipeRisk? {
        val (from, to) = window(day.date, zone)
        val hours = covering(hourly, from, to)
        val low: Double
        val high: Double?
        val hoursBelow: Int?
        val coldWindKph: Double?
        if (hours != null) {
            val temps = hours.mapNotNull { it.temperatureC }
            low = temps.min()
            high = temps.max()
            val step = stepHours(hourly)
            val cold = hours.filter { (it.temperatureC ?: 99.0) <= THRESHOLD_C }
            hoursBelow = cold.size * step
            coldWindKph = cold.mapNotNull { it.windSpeedKph }.takeIf { it.isNotEmpty() }?.average()
        } else {
            low = listOfNotNull(day.lowC, day.overnight?.tempMinC).minOrNull() ?: return null
            high = day.highC ?: day.daytime?.tempMaxC
            hoursBelow = estimateHoursBelow(low, high)
            coldWindKph = day.overnight?.windMeanKph
        }
        if (low > THRESHOLD_C) return null

        val factors = mutableListOf(PipeFactor(PipeFactor.Kind.LOW, low))
        var level = when {
            low <= SEVERE_C -> OutageLevel.VERY_HIGH
            low <= HARD_C -> OutageLevel.HIGH
            else -> OutageLevel.ELEVATED
        }
        if (high != null && high <= 0.0) {
            factors += PipeFactor(PipeFactor.Kind.NO_THAW, high)
            if (high <= THRESHOLD_C) level = maxOf(level, OutageLevel.HIGH)
            if (high <= -9.4 && low <= HARD_C) level = OutageLevel.VERY_HIGH
        }
        if (hoursBelow != null && hoursBelow >= 8) {
            factors += PipeFactor(PipeFactor.Kind.LONG_COLD, hoursBelow.toDouble())
            if (hoursBelow >= 12) level = maxOf(level, OutageLevel.HIGH)
        }
        if (coldWindKph != null && coldWindKph >= WINDY_KPH) {
            factors += PipeFactor(PipeFactor.Kind.WIND, coldWindKph)
            level = bump(level)
        }
        if ((day.outageRisk?.level ?: OutageLevel.LOW) >= OutageLevel.HIGH) {
            factors += PipeFactor(PipeFactor.Kind.OUTAGE)
            level = bump(level)
        }
        return PipeRisk(level, low, hoursBelow?.takeIf { hours != null }, factors)
    }

    /** Rough hours below 20°F from the high and low alone, assuming a steady daily swing. */
    internal fun estimateHoursBelow(low: Double, high: Double?): Int? {
        if (high == null) return null
        if (high <= THRESHOLD_C) return 24
        return (24 * (THRESHOLD_C - low) / (high - low)).roundToInt().coerceIn(0, 24)
    }

    private fun bump(level: OutageLevel) = OutageLevel.entries[(level.ordinal + 1).coerceAtMost(OutageLevel.entries.lastIndex)]
}

/**
 * Frost on windshields and black ice on roads for each morning. Frost forms
 * when clear, calm nights let surfaces cool below freezing in damp air. Black
 * ice forms when wet roads freeze: freezing rain, rain followed by a freeze,
 * or snowmelt refreezing overnight.
 */
object MorningIceEstimator {
    /** Evening before through 9 AM: the cooling that sets up the morning. */
    fun night(date: LocalDate, zone: ZoneId): Pair<Instant, Instant> =
        date.minusDays(1).atTime(18, 0).atZone(zone).toInstant() to date.atTime(9, 0).atZone(zone).toInstant()

    /** Earlier rain counts: from the morning before until dawn. */
    private fun wetWindow(date: LocalDate, zone: ZoneId): Pair<Instant, Instant> =
        date.minusDays(1).atTime(6, 0).atZone(zone).toInstant() to date.atTime(8, 0).atZone(zone).toInstant()

    private data class Night(
        val lowC: Double,
        val coldestAt: Instant?,
        val dewAtLowC: Double?,
        val humidity: Double?,
        val skyCover: Int?,
        val windKph: Double?,
        val rainMm: Double,
        val iceMm: Double,
        val warmestBeforeC: Double?,
    )

    fun estimate(
        day: DailyForecast,
        previous: DailyForecast?,
        hourly: List<HourlyForecast>,
        zone: ZoneId,
        snowOnGround: Boolean,
        now: Instant,
    ): MorningIce? {
        // Once the morning is over, there's nothing left to warn about.
        if (now.isAfter(day.date.atTime(10, 0).atZone(zone).toInstant())) return null
        val n = fromHourly(day.date, hourly, zone) ?: fromDaily(previous) ?: return null
        if (n.lowC > 2.5) return null

        val reasons = mutableListOf<MorningIce.Reason>()
        val damp = when {
            n.dewAtLowC != null -> n.dewAtLowC >= n.lowC - 4
            n.humidity != null -> n.humidity >= 70
            else -> true
        }
        val clear = (n.skyCover ?: 100) <= 40
        val partly = (n.skyCover ?: 100) <= 65
        val calm = (n.windKph ?: 99.0) <= 11
        val light = (n.windKph ?: 99.0) <= 16
        val frost = when {
            n.lowC <= 0.5 && clear && calm && damp -> Likelihood.LIKELY
            n.lowC <= 2.0 && partly && light && damp -> Likelihood.POSSIBLE
            n.lowC <= -1.0 && light && damp && (n.humidity ?: 0.0) >= 80 -> Likelihood.POSSIBLE
            else -> null
        }
        if (frost != null && (clear || partly)) reasons += MorningIce.Reason.CLEAR_CALM

        var blackIce: Likelihood? = null
        if (n.iceMm >= 0.25 && n.lowC <= 0.5) {
            blackIce = Likelihood.LIKELY
            reasons += MorningIce.Reason.FREEZING_RAIN
        }
        if (n.rainMm >= 0.2 && n.lowC <= 0.5) {
            val likely = n.rainMm >= 1.0 && n.lowC <= -1.0
            blackIce = maxOf(blackIce ?: Likelihood.POSSIBLE, if (likely) Likelihood.LIKELY else Likelihood.POSSIBLE)
            reasons += MorningIce.Reason.RAIN_THEN_FREEZE
        }
        if (snowOnGround && (n.warmestBeforeC ?: -99.0) >= 3.0 && n.lowC <= -2.0) {
            blackIce = maxOf(blackIce ?: Likelihood.POSSIBLE, Likelihood.POSSIBLE)
            reasons += MorningIce.Reason.MELT_REFREEZE
        }
        if (frost == null && blackIce == null) return null
        return MorningIce(frost, blackIce, reasons.distinct(), n.lowC, n.coldestAt)
    }

    private fun fromHourly(date: LocalDate, hourly: List<HourlyForecast>, zone: ZoneId): Night? {
        val (from, to) = night(date, zone)
        val hours = covering(hourly, from, to) ?: return null
        val coldest = hours.filter { it.temperatureC != null }.minByOrNull { it.temperatureC!! } ?: return null
        val (wetFrom, wetTo) = wetWindow(date, zone)
        val wet = hourly.filter { !it.time.isBefore(wetFrom) && it.time.isBefore(wetTo) }
        // Rain is liquid falling above freezing; snow hours don't wet the road the same way.
        val rain = wet.filter { (it.temperatureC ?: 0.0) > 0.5 && (it.snowMm ?: 0.0) < 1.0 }
            .sumOf { h -> h.precipitationMm ?: if ((h.precipitationChance ?: 0) >= 60) 0.5 else 0.0 }
        val dark = hours.filter { it.time.isBefore(date.atTime(7, 0).atZone(zone).toInstant()) }
        return Night(
            lowC = coldest.temperatureC!!,
            coldestAt = coldest.time,
            dewAtLowC = coldest.dewpointC ?: coldest.humidityPercent?.let { Units.dewpointC(coldest.temperatureC, it) },
            humidity = coldest.humidityPercent,
            skyCover = dark.mapNotNull { it.skyCoverPercent }.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
            windKph = dark.mapNotNull { it.windSpeedKph }.takeIf { it.isNotEmpty() }?.average(),
            rainMm = rain,
            iceMm = wet.sumOf { it.iceMm ?: 0.0 },
            warmestBeforeC = wet.mapNotNull { it.temperatureC }.maxOrNull(),
        )
    }

    /** Beyond the hourly forecast: the previous day's daytime and overnight summaries. */
    private fun fromDaily(previous: DailyForecast?): Night? {
        val night = previous?.overnight ?: return null
        val low = night.tempMinC ?: return null
        val dayStats = previous.daytime
        val precip = (dayStats?.precipMm ?: 0.0) + (night.precipMm ?: 0.0)
        val snowWater = ((dayStats?.snowMm ?: 0.0) + (night.snowMm ?: 0.0)) / 10.0
        val warm = listOfNotNull(dayStats?.tempMaxC, previous.highC).maxOrNull()
        val rain = if ((warm ?: 0.0) > 1.0) (precip - snowWater).coerceAtLeast(0.0) else 0.0
        return Night(
            lowC = low,
            coldestAt = null,
            dewAtLowC = null,
            humidity = night.humidityMean,
            skyCover = night.skyCoverMean,
            windKph = night.windMeanKph,
            rainMm = rain,
            iceMm = (dayStats?.iceMm ?: 0.0) + (night.iceMm ?: 0.0),
            warmestBeforeC = warm,
        )
    }
}

/** Hours inside [from, to), or null if the hourly forecast covers less than 60% of it. */
internal fun covering(hourly: List<HourlyForecast>, from: Instant, to: Instant): List<HourlyForecast>? {
    val inside = hourly.filter { !it.time.isBefore(from) && it.time.isBefore(to) && it.temperatureC != null }
    val span = Duration.between(from, to).toHours()
    return inside.takeIf { it.isNotEmpty() && it.size * stepHours(hourly) >= span * 0.6 }
}

internal fun stepHours(hourly: List<HourlyForecast>): Int =
    if (hourly.size >= 2) Duration.between(hourly[0].time, hourly[1].time).toHours().toInt().coerceAtLeast(1) else 1
