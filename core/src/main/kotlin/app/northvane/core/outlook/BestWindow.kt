package app.northvane.core.outlook

import app.northvane.core.model.Activity
import app.northvane.core.model.HourlyForecast
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

/**
 * The best few hours of a day for an activity, from the hourly forecast
 * (which reaches about two days ahead): dry, calm and close to the
 * activity's ideal temperature; for stargazing, the clearest dark hours.
 */
object BestWindow {

    data class Window(
        val start: Instant,
        /** The end of the last hour in the window. */
        val end: Instant,
        val tempC: Double?,
        /** The highest chance of precipitation in the window. */
        val chance: Int,
        /** Average cloud cover, for stargazing. */
        val cloudPercent: Int?,
    )

    /** Comfortable temperature for each daytime activity, °C. */
    private fun idealC(activity: Activity): Double? = when (activity) {
        Activity.HIKING, Activity.OFF_ROADING -> 15.0
        Activity.MOUNTAIN_BIKING -> 16.0
        Activity.PADDLING -> 22.0
        Activity.SWIMMING -> 28.0
        Activity.HUNTING -> 5.0
        Activity.SKIING, Activity.SNOWSHOEING -> -4.0
        Activity.XC_SKIING -> -6.0
        Activity.SNOWMOBILING, Activity.ICE_FISHING -> -8.0
        Activity.CAMPING, Activity.STARGAZING -> null
    }

    private fun length(activity: Activity): Int = when (activity) {
        Activity.SWIMMING, Activity.STARGAZING -> 2
        Activity.SKIING, Activity.XC_SKIING, Activity.SNOWMOBILING -> 4
        else -> 3
    }

    /**
     * The best window on [date] that hasn't started before [now], or null when
     * the hourly forecast doesn't cover enough of the day (or for camping,
     * which is judged by the whole night).
     */
    fun find(activity: Activity, hourly: List<HourlyForecast>, date: LocalDate, zone: ZoneId, now: Instant): Window? {
        val hours = if (activity == Activity.STARGAZING) {
            val from = date.atTime(20, 0).atZone(zone).toInstant()
            val to = date.plusDays(1).atTime(5, 0).atZone(zone).toInstant()
            hourly.filter { !it.time.isBefore(from) && it.time.isBefore(to) }
        } else {
            val from = date.atTime(7, 0).atZone(zone).toInstant()
            val to = date.atTime(19, 0).atZone(zone).toInstant()
            hourly.filter { !it.time.isBefore(from) && it.time.isBefore(to) && it.isDaytime }
        }.filter { !it.time.plusSeconds(3600).isBefore(now.plusSeconds(1)) }.sortedBy { it.time }
        val n = length(activity)
        if (activity == Activity.CAMPING || hours.size < n) return null
        val ideal = idealC(activity)
        return hours.windowed(n).filter { w -> w.zipWithNext().all { (a, b) -> b.time.epochSecond - a.time.epochSecond == 3600L } }
            .minByOrNull { w -> w.sumOf { penalty(it, activity, ideal) } }
            ?.let { w ->
                Window(
                    start = w.first().time,
                    end = w.last().time.plusSeconds(3600),
                    tempC = w.mapNotNull { it.temperatureC }.takeIf { it.isNotEmpty() }?.average(),
                    chance = w.maxOf { it.precipitationChance ?: 0 },
                    cloudPercent = w.mapNotNull { it.skyCoverPercent }.takeIf { it.isNotEmpty() }?.average()?.toInt(),
                )
            }
    }

    private fun penalty(h: HourlyForecast, activity: Activity, ideal: Double?): Double {
        if (activity == Activity.STARGAZING) return (h.skyCoverPercent ?: 50).toDouble() + (h.precipitationChance ?: 0)
        var p = 0.0
        val chance = h.precipitationChance ?: 0
        if (chance >= 20) p += chance
        p += (h.thunderChance ?: 0) * 2.0
        val temp = h.apparentTemperatureC ?: h.temperatureC
        if (ideal != null && temp != null) p += abs(temp - ideal) * 2
        val gust = h.windGustKph ?: h.windSpeedKph ?: 0.0
        val windLimit = if (activity == Activity.PADDLING) 25.0 else 40.0
        if (gust > windLimit) p += (gust - windLimit) * 1.5
        return p
    }
}
