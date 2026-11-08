package app.northvane.core.outlook

import app.northvane.core.model.DailyForecast
import app.northvane.core.model.HourlyForecast
import app.northvane.core.model.PeriodStats
import app.northvane.core.model.Units
import app.northvane.core.nws.NwsGridData
import kotlin.math.roundToInt
import app.northvane.core.time.*

/** Daytime and overnight summaries of a forecast day, the windows activity scores look at. */
object Periods {
    fun daytime(date: LocalDate, zone: ZoneId): Pair<Instant, Instant> =
        date.atTime(8, 0).atZone(zone).toInstant() to date.atTime(18, 0).atZone(zone).toInstant()

    fun overnight(date: LocalDate, zone: ZoneId): Pair<Instant, Instant> =
        date.atTime(20, 0).atZone(zone).toInstant() to date.plusDays(1).atTime(7, 0).atZone(zone).toInstant()

    /** From NWS gridpoint numbers, which cover the whole 7-day forecast hour by hour. */
    fun fromGrid(grid: NwsGridData, window: Pair<Instant, Instant>): PeriodStats? {
        val (from, to) = window
        val tMax = grid.temperature.max(from, to) ?: return null
        val windMax = grid.windSpeed.max(from, to)
        return PeriodStats(
            tempMaxC = tMax,
            tempMinC = grid.temperature.min(from, to),
            feelsMaxC = grid.apparentTemperature.max(from, to),
            feelsMinC = grid.apparentTemperature.min(from, to),
            dewpointMaxC = grid.dewpoint.max(from, to),
            humidityMean = grid.humidity.mean(from, to),
            precipChanceMax = grid.precipitationChance.max(from, to)?.roundToInt(),
            precipMm = grid.precipitation.sum(from, to),
            snowMm = grid.snow.sum(from, to),
            iceMm = grid.ice.sum(from, to),
            windMeanKph = grid.windSpeed.mean(from, to),
            windMaxKph = windMax,
            gustMaxKph = grid.windGust.max(from, to)?.let { maxOf(it, windMax ?: 0.0) },
            thunderChanceMax = grid.thunderChance.max(from, to)?.roundToInt(),
            skyCoverMean = grid.skyCover.mean(from, to)?.roundToInt(),
        )
    }

    /** From the hourly (or 3-hourly) forecast, when it covers at least part of the window. */
    fun fromHourly(hours: List<HourlyForecast>, window: Pair<Instant, Instant>): PeriodStats? {
        val (from, to) = window
        val inside = hours.filter { !it.time.isBefore(from) && it.time.isBefore(to) }
        val span = Duration.between(from, to).toHours()
        val step = if (hours.size >= 2) Duration.between(hours[0].time, hours[1].time).toHours().coerceAtLeast(1) else 1
        if (inside.isEmpty() || inside.size * step < span * 0.6) return null
        val temps = inside.mapNotNull { it.temperatureC }
        if (temps.isEmpty()) return null
        val feels = inside.mapNotNull { h -> h.apparentTemperatureC ?: h.temperatureC?.let { Units.feelsLikeC(it, h.humidityPercent, h.windSpeedKph) } }
        val winds = inside.mapNotNull { it.windSpeedKph }
        val windMax = winds.maxOrNull()
        val dew = inside.mapNotNull { h -> h.dewpointC ?: if (h.temperatureC != null && h.humidityPercent != null) Units.dewpointC(h.temperatureC, h.humidityPercent) else null }
        return PeriodStats(
            tempMaxC = temps.max(),
            tempMinC = temps.min(),
            feelsMaxC = feels.maxOrNull(),
            feelsMinC = feels.minOrNull(),
            dewpointMaxC = dew.maxOrNull(),
            humidityMean = inside.mapNotNull { it.humidityPercent }.average().takeIf { !it.isNaN() },
            precipChanceMax = inside.mapNotNull { it.precipitationChance }.maxOrNull(),
            precipMm = inside.mapNotNull { it.precipitationMm }.takeIf { it.isNotEmpty() }?.sum(),
            snowMm = inside.mapNotNull { it.snowMm }.takeIf { it.isNotEmpty() }?.sum(),
            iceMm = inside.mapNotNull { it.iceMm }.takeIf { it.isNotEmpty() }?.sum(),
            windMeanKph = winds.average().takeIf { !it.isNaN() },
            windMaxKph = windMax,
            gustMaxKph = inside.mapNotNull { it.windGustKph }.maxOrNull()?.let { maxOf(it, windMax ?: 0.0) },
            thunderChanceMax = inside.mapNotNull { it.thunderChance }.maxOrNull(),
            skyCoverMean = inside.mapNotNull { it.skyCoverPercent }.average().takeIf { !it.isNaN() }?.roundToInt(),
        )
    }

    /** Coarse stand-in from the day's totals when nothing finer was downloaded. */
    fun fromDaily(day: DailyForecast, overnight: Boolean): PeriodStats {
        val high = day.highC
        val low = day.lowC
        val mid = if (high != null && low != null) (high + low) / 2 else null
        val (max, min) = if (overnight) (mid ?: low) to low else high to (mid ?: high)
        val wind = day.windMaxKph?.let { if (overnight) it * 0.7 else it }
        val humidity = listOfNotNull(day.humidityMin, day.humidityMax).takeIf { it.isNotEmpty() }?.average()
        return PeriodStats(
            tempMaxC = max,
            tempMinC = min,
            feelsMaxC = max?.let { Units.feelsLikeC(it, humidity, wind) },
            feelsMinC = min?.let { Units.feelsLikeC(it, humidity, wind) },
            dewpointMaxC = if (max != null && day.humidityMax != null) Units.dewpointC(max, day.humidityMin ?: day.humidityMax) else null,
            humidityMean = humidity,
            precipChanceMax = day.precipitationChance,
            precipMm = day.precipitationMm?.let { it / 2 },
            snowMm = day.snowMm?.let { it / 2 },
            iceMm = day.iceMm?.let { it / 2 },
            windMeanKph = wind?.let { it * 0.75 },
            windMaxKph = wind,
            gustMaxKph = day.gustMaxKph,
            thunderChanceMax = day.thunderChance,
            skyCoverMean = day.skyCoverPercent,
        )
    }

    /** Fills in missing daytime/overnight summaries: grid numbers first, then hourly, then daily totals. */
    fun fill(days: List<DailyForecast>, hourly: List<HourlyForecast>, grid: NwsGridData?, zone: ZoneId): List<DailyForecast> =
        days.map { d ->
            val dayWindow = daytime(d.date, zone)
            val nightWindow = overnight(d.date, zone)
            d.copy(
                daytime = d.daytime
                    ?: grid?.let { fromGrid(it, dayWindow) }
                    ?: fromHourly(hourly, dayWindow)
                    ?: fromDaily(d, overnight = false),
                overnight = d.overnight
                    ?: grid?.let { fromGrid(it, nightWindow) }
                    ?: fromHourly(hourly, nightWindow)
                    ?: fromDaily(d, overnight = true),
            )
        }
}
