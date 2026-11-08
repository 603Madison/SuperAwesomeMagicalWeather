package app.northvane.core.outlook

import app.northvane.core.model.HourlyForecast
import app.northvane.core.util.UnitFormatter
import kotlinx.serialization.Serializable
import app.northvane.core.time.*

/** A time the user is usually out: "Drive home" at 5 PM on weekdays, for an hour. */
@Serializable
data class MyTime(
    val label: String,
    /** Minutes after midnight, local time. */
    val minutes: Int,
    val durationMinutes: Int = 60,
    val weekdaysOnly: Boolean = false,
) {
    fun occursOn(date: LocalDate): Boolean = !weekdaysOnly || date.dayOfWeek !in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
}

/** One upcoming occurrence of a [MyTime]. */
data class TimeSlot(val label: String, val start: Instant, val end: Instant)

object MyTimes {
    /** The occurrences that haven't ended yet, in the next [ahead]. */
    fun upcoming(times: List<MyTime>, now: Instant, zone: ZoneId, ahead: Duration = Duration.ofHours(20)): List<TimeSlot> {
        val today = now.atZone(zone).toLocalDate()
        return listOf(today, today.plusDays(1)).flatMap { date ->
            times.filter { it.occursOn(date) }.map { t ->
                val start = date.atStartOfDay(zone).plusMinutes(t.minutes.toLong()).toInstant()
                TimeSlot(t.label, start, start.plus(Duration.ofMinutes(t.durationMinutes.toLong().coerceIn(15, 240))))
            }
        }.filter { it.end.isAfter(now) && it.start.isBefore(now.plus(ahead)) }.sortedBy { it.start }
    }

    /**
     * A sentence about the next of the user's times that the weather touches:
     * rain or snow during it, bitter cold, or strong wind; otherwise, for the
     * very next one, that it looks fine.
     */
    fun sentence(slots: List<TimeSlot>, hourly: List<HourlyForecast>, f: UnitFormatter, hour: (Instant) -> String, now: Instant): String? {
        fun hoursOf(s: TimeSlot) = hourly.filter { it.time.plusSeconds(3600).isAfter(s.start) && it.time.isBefore(s.end) }
        for (slot in slots) {
            val hours = hoursOf(slot).takeIf { it.isNotEmpty() } ?: continue
            val name = slot.label.lowercase()
            val at = if (slot.start.isAfter(now)) " (${hour(slot.start)})" else " (now)"
            val chance = hours.maxOf { it.precipitationChance ?: 0 }
            if (chance >= 40) {
                val kind = kindOf(hours.map { it.condition }, hours.sumOf { it.snowMm ?: 0.0 }, hours.sumOf { it.precipitationMm ?: 0.0 })
                return "${kind.noun} likely for your $name$at."
            }
            val feels = hours.mapNotNull { it.apparentTemperatureC ?: it.temperatureC }.minOrNull()
            if (feels != null && feels <= -12.0) return "Bitter cold for your $name$at: feels like ${f.temperature(feels)}."
            val gust = hours.mapNotNull { it.windGustKph }.maxOrNull()
            if (gust != null && gust >= 56.0) return "Gusts to ${f.windSpeed(gust)} for your $name$at."
        }
        val next = slots.firstOrNull() ?: return null
        if (hoursOf(next).isEmpty()) return null
        return "Dry for your ${next.label.lowercase()}" + (if (next.start.isAfter(now)) " (${hour(next.start)})" else "") + "."
    }
}

enum class Comfort(val label: String, val offsetC: Double) { COLD("I run cold", 3.0), NORMAL("About average", 0.0), WARM("I run warm", -3.0) }

/**
 * What to wear for the next several hours out: one short line such as
 * "Light jacket · umbrella after 3 PM", from the feels-like temperature
 * (shifted for people who run cold or warm), rain, snow, wind and UV.
 */
object WhatToWear {
    fun advise(hourly: List<HourlyForecast>, now: Instant, comfort: Comfort, hour: (Instant) -> String, uvMax: Double? = null, hours: Int = 10): String? {
        val window = hourly.filter { it.time.plusSeconds(3600).isAfter(now) }.take(hours)
        if (window.isEmpty()) return null
        val feels = window.mapNotNull { it.apparentTemperatureC ?: it.temperatureC }
        if (feels.isEmpty()) return null
        // Someone who runs cold feels it 3 °C colder.
        val low = feels.min() - comfort.offsetC
        val high = feels.max() - comfort.offsetC
        val base = when {
            low >= 24 -> "T-shirt weather"
            low >= 18 -> "Light layers"
            low >= 12 -> "Light jacket"
            low >= 5 -> "Jacket"
            low >= -2 -> "Warm coat"
            low >= -15 -> "Winter coat, hat and gloves"
            else -> "Heavy coat, hat, gloves and a face cover"
        }
        val extras = mutableListOf<String>()
        if (high - low >= 10 && low < 18) extras += "layers you can shed"
        val wet = window.firstOrNull { (it.precipitationChance ?: 0) >= 50 }
        if (wet != null) {
            val snowy = window.sumOf { it.snowMm ?: 0.0 } >= 10
            val item = if (snowy) "boots" else "umbrella"
            extras += if (wet.time.isAfter(now.plusSeconds(3600))) "$item after ${hour(wet.time)}" else item
        }
        if (window.any { (it.windGustKph ?: 0.0) >= 50 } && low >= -2) extras += "something windproof"
        if ((uvMax ?: 0.0) >= 6) extras += "sunscreen"
        return (listOf(base) + extras.take(2)).joinToString(" · ")
    }
}
