package app.northvane.core.outlook

import app.northvane.core.aurora.AuroraAdvisor
import app.northvane.core.model.AuroraView
import app.northvane.core.model.OutageLevel
import app.northvane.core.model.WeatherSnapshot
import app.northvane.core.util.UnitFormatter
import app.northvane.core.time.*

/**
 * Everything worth doing before bed, in one evening notification instead of
 * several: frost (cover plants), frozen pipes, black ice by morning, a storm
 * starting overnight, rain on laundry still out, and the northern lights.
 * Nothing worth saying, no notification.
 */
object TonightDigest {
    data class Options(
        val garden: Boolean = false,
        val laundryOut: Boolean = false,
        val pipes: Boolean = true,
        val ice: Boolean = true,
        val aurora: Boolean = true,
        val longitude: Double? = null,
    )

    fun write(s: WeatherSnapshot, now: Instant, zone: ZoneId, f: UnitFormatter, hour: (Instant) -> String, o: Options): List<String> {
        val local = now.atZone(zone)
        val today = local.toLocalDate()
        val morning = today.plusDays(1).atTime(9, 0).atZone(zone).toInstant()
        val night = s.hourly.filter { it.time.isAfter(now) && it.time.isBefore(morning) }
        if (night.isEmpty()) return emptyList()
        val lines = mutableListOf<String>()

        StormTotals.find(s.hourly, s.daily, s.alerts, now, zone)?.let { storm ->
            if (storm.start != null && storm.start.isBefore(morning)) lines += "${storm.title} starts around ${hour(storm.start)}."
        }
        val low = night.mapNotNull { it.temperatureC }.minOrNull()
        if (low != null && low <= 0.5) {
            lines += if (o.garden) "Frost tonight (low ${f.temperature(low)}): cover tender plants." else "Frost tonight, low ${f.temperature(low)}."
        }
        val todayForecast = s.daily.firstOrNull { it.date == today }
        val tomorrow = s.daily.firstOrNull { it.date == today.plusDays(1) }
        if (o.pipes) todayForecast?.pipeRisk?.takeIf { it.level >= OutageLevel.HIGH }?.let {
            lines += "Frozen pipe risk is ${it.level.label.lowercase()}: drip faucets and open sink cabinets."
        }
        if (o.ice) tomorrow?.morningIce?.blackIce?.let { lines += "Black ice ${it.label} by morning." }
        if (o.laundryOut && night.any { (it.precipitationChance ?: 0) >= 40 }) {
            lines += "Your laundry is still out, and rain is likely overnight."
        }
        val lat = s.latitude
        val aurora = s.aurora
        if (o.aurora && aurora != null && lat != null && o.longitude != null) {
            AuroraAdvisor.report(aurora, lat, o.longitude, s.hourly, zone, now).nights.firstOrNull { it.date == today }
                ?.takeIf { it.view >= AuroraView.CAMERA && (it.cloudPercent ?: 0) < 85 }
                ?.let { n -> lines += "Northern lights possible" + (n.peakAt?.let { " around ${hour(it)}" } ?: "") + " (${n.view.label.lowercase()})." }
        }
        return lines
    }
}
