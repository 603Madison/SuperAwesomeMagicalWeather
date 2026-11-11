package app.northvane.core.alerts

import app.northvane.core.model.AlertCategory
import app.northvane.core.model.AlertSeverity
import app.northvane.core.model.HourlyForecast
import app.northvane.core.model.WeatherAlert
import app.northvane.core.time.*
import app.northvane.core.water.TideEvent

/**
 * One alert laid out in time for the place: when it starts, its worst hours
 * and when it ends, from the alert's own times and the hourly forecast's
 * numbers for the hazard it's about (snow for a winter storm, gusts for wind,
 * high tides for coastal flooding...).
 */
data class StormTimeline(
    val alert: WeatherAlert,
    val hazard: Hazard,
    val start: Instant,
    /** The alert's end, or its expiry when it gives no end (it may be extended). */
    val end: Instant?,
    val endIsExpiry: Boolean,
    /** Hourly values for [hazard] from a little before [start] to a little after [end]. */
    val hours: List<HourValue>,
    val worst: Worst?,
    /** When the hazard dies down in the forecast, if before the alert's end. */
    val tapering: Instant?,
) {
    data class HourValue(val time: Instant, val value: Double)

    /** The worst stretch: [from] to [to] (inclusive hour starts), and its worst value. */
    data class Worst(val from: Instant, val to: Instant, val value: Double, val at: Instant)

    enum class Hazard(
        /** Lower is worse (cold), rather than higher. */
        val lowerIsWorse: Boolean = false,
    ) {
        SNOW, ICE, RAIN, WIND, THUNDER, HEAT, COLD(lowerIsWorse = true), COASTAL, OTHER;

        companion object {
            /** The hazard an alert is about, from its event name. */
            fun of(event: String): Hazard {
                val e = event.lowercase()
                return when {
                    "coastal flood" in e || "storm surge" in e -> COASTAL
                    "ice storm" in e || "freezing rain" in e || "freezing drizzle" in e -> ICE
                    "winter" in e || "blizzard" in e || "snow" in e || "lake effect" in e -> SNOW
                    "tornado" in e || "thunderstorm" in e -> THUNDER
                    "flood" in e || "rainfall" in e -> RAIN
                    "wind chill" in e || "cold" in e || "freeze" in e || "frost" in e -> COLD
                    "heat" in e -> HEAT
                    "wind" in e || "hurricane" in e || "tropical storm" in e || "gale" in e || "typhoon" in e -> WIND
                    else -> OTHER
                }
            }
        }
    }

    companion object {
        /** Lead-in and tail shown around the alert, so its start and end are in view. */
        private const val PAD_HOURS = 3L
        /** Never draw more than two days. */
        private const val MAX_HOURS = 48L

        /**
         * The alert to lay out: the most serious warning, else watch, else
         * advisory, that isn't over. Statements and the like aren't timed
         * events worth a timeline.
         */
        fun pick(alerts: List<WeatherAlert>, now: Instant): WeatherAlert? = alerts
            .filter { it.category in setOf(AlertCategory.WARNING, AlertCategory.WATCH, AlertCategory.ADVISORY) }
            .filter { (it.ends ?: it.expires)?.let { end -> end > now } ?: true }
            .sortedWith(
                compareBy<WeatherAlert> { it.category.ordinal }
                    .thenBy { severityRank(it.severity) }
                    .thenBy { it.onset ?: it.effective ?: now },
            )
            .firstOrNull()

        private fun severityRank(s: AlertSeverity) = when (s) {
            AlertSeverity.EXTREME -> 0
            AlertSeverity.SEVERE -> 1
            AlertSeverity.MODERATE -> 2
            AlertSeverity.MINOR -> 3
            else -> 4
        }

        fun build(alert: WeatherAlert, hourly: List<HourlyForecast>, tides: List<TideEvent>, now: Instant): StormTimeline {
            val hazard = Hazard.of(alert.event)
            val start = alert.onset ?: alert.effective ?: alert.sent ?: now
            val end = alert.ends ?: alert.expires
            val from = minOf(start, now).truncatedTo(ChronoUnit.HOURS).minusSeconds(PAD_HOURS * 3600)
            val lastHour = (end ?: start.plusSeconds(12 * 3600)).plusSeconds(PAD_HOURS * 3600)
            val to = minOf(lastHour, from.plusSeconds(MAX_HOURS * 3600))
            val hours = when (hazard) {
                Hazard.COASTAL -> tides.filter { it.high && it.time >= from && it.time <= to }.map { HourValue(it.time, it.heightM) }
                Hazard.OTHER -> emptyList()
                else -> hourly.filter { it.time >= from && it.time <= to }.mapNotNull { h -> value(hazard, h)?.let { HourValue(h.time, it) } }
            }
            // The worst hours count only inside the alert (and from now on).
            val inside = hours.filter { it.time >= maxOf(start, now).truncatedTo(ChronoUnit.HOURS) && (end == null || it.time < end) }
            val worst = worst(hazard, inside)
            val tapering = if (worst != null && hazard != Hazard.COASTAL && hazard != Hazard.COLD && hazard != Hazard.HEAT) {
                hours.firstOrNull { it.time > worst.to && it.value < worst.value * 0.3 }?.time?.takeIf { end == null || it < end }
            } else {
                null
            }
            return StormTimeline(alert, hazard, start, end, endIsExpiry = alert.ends == null && end != null, hours, worst, tapering)
        }

        /** The hourly number that matters for [hazard], or null when the forecast doesn't say. */
        internal fun value(hazard: Hazard, h: HourlyForecast): Double? = when (hazard) {
            Hazard.SNOW -> h.snowMm ?: h.precipitationMm?.takeIf { (h.temperatureC ?: 5.0) <= 0.5 }?.times(10)
            Hazard.ICE -> h.iceMm
            Hazard.RAIN -> h.precipitationMm
            Hazard.WIND -> h.windGustKph ?: h.windSpeedKph
            Hazard.THUNDER -> h.thunderChance?.toDouble()
            Hazard.HEAT -> h.apparentTemperatureC ?: h.temperatureC
            Hazard.COLD -> h.apparentTemperatureC ?: h.temperatureC
            Hazard.COASTAL, Hazard.OTHER -> null
        }

        /**
         * The worst hour, and the run of hours around it that come close to it
         * (within a fifth of it, or 3 °C for heat and cold).
         */
        internal fun worst(hazard: Hazard, hours: List<HourValue>): Worst? {
            if (hours.isEmpty()) return null
            val peak = if (hazard.lowerIsWorse) hours.minBy { it.value } else hours.maxBy { it.value }
            if (!hazard.lowerIsWorse && peak.value <= 0.0) return null
            if (hazard == Hazard.COASTAL) return Worst(peak.time, peak.time, peak.value, peak.time)
            fun close(v: Double) = when (hazard) {
                Hazard.COLD -> v <= peak.value + 3
                Hazard.HEAT -> v >= peak.value - 3
                else -> v >= peak.value * 0.8
            }
            val i = hours.indexOf(peak)
            var a = i
            while (a > 0 && close(hours[a - 1].value)) a--
            var b = i
            while (b < hours.lastIndex && close(hours[b + 1].value)) b++
            return Worst(hours[a].time, hours[b].time, peak.value, peak.time)
        }
    }
}
