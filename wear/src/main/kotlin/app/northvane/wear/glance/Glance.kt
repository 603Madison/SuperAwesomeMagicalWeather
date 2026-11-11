package app.northvane.wear.glance

import app.northvane.core.model.AlertCategory
import app.northvane.core.model.DailyForecast
import app.northvane.core.model.HourlyForecast
import app.northvane.core.model.SavedLocation
import app.northvane.core.model.UnitSystem
import app.northvane.core.model.WeatherAlert
import app.northvane.core.model.WeatherCondition
import app.northvane.core.outlook.MinutePrecip
import app.northvane.core.outlook.TodayHeadline
import app.northvane.core.time.*
import app.northvane.core.util.UnitFormatter
import app.northvane.wear.data.WearForecast

/** Times as the watch shows them, in the forecast place's time zone. */
class WatchClock(val zone: ZoneId, is24: Boolean) {
    private val hour = DateTimeFormatter.ofPattern(if (is24) "HH:mm" else "h a")
    private val time = DateTimeFormatter.ofPattern(if (is24) "HH:mm" else "h:mm a")
    /** Within the next hour AM/PM goes without saying: "3:40". */
    private val soon = DateTimeFormatter.ofPattern(if (is24) "HH:mm" else "h:mm")
    private val dayTime = DateTimeFormatter.ofPattern(if (is24) "EEE HH:mm" else "EEE h:mm a")
    fun hour(t: Instant): String = hour.format(t.atZone(zone))
    fun time(t: Instant): String = time.format(t.atZone(zone))
    fun soon(t: Instant): String = soon.format(t.atZone(zone))
    fun dayTime(t: Instant): String = dayTime.format(t.atZone(zone))
    fun weekday(d: LocalDate, today: LocalDate): String = when (d) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        else -> d.dayOfWeek.getDisplayName(TextStyle.FULL)
    }

    companion object {
        fun forSnapshotZone(timeZoneId: String?, is24: Boolean) =
            WatchClock(timeZoneId?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault(), is24)
    }
}

/**
 * Rain or snow in the next hour from Open-Meteo's 15-minute forecast, as one
 * short line: "Rain starting around 3:40", "Snow stopping around 4:15",
 * "Dry for the next hour". Null when the steps don't cover the whole hour
 * (too old, or none downloaded), so a stale forecast never claims it's dry.
 */
object NextHour {
    /** 0.1 mm in 15 minutes counts as wet (the same as the main app's headline). */
    private const val WET_MM = 0.1
    /** 2.5 mm in 15 minutes (10 mm an hour) is heavy. */
    private const val HEAVY_MM = 2.5
    private val STEP = Duration.ofMinutes(15)
    private val HOUR = Duration.ofMinutes(60)

    fun text(steps: List<MinutePrecip>, now: Instant, time: (Instant) -> String): String? {
        val upcoming = steps.filter { it.time.plus(STEP) > now && it.time < now.plus(HOUR) }.sortedBy { it.time }
        if (upcoming.isEmpty() || upcoming.first().time.isAfter(now) || upcoming.last().time.plus(STEP) < now.plus(HOUR)) return null
        if (upcoming.first().precipitationMm >= WET_MM) {
            val falling = upcoming.takeWhile { it.precipitationMm >= WET_MM }
            val stop = upcoming.getOrNull(falling.size) ?: return "${noun(falling)} for the next hour"
            return "${noun(falling)} stopping around ${time(stop.time)}"
        }
        val start = upcoming.firstOrNull { it.precipitationMm >= WET_MM } ?: return "Dry for the next hour"
        val wet = upcoming.filter { !it.time.isBefore(start.time) }
        val noun = noun(wet)
        val heavy = noun != MIX && wet.any { it.precipitationMm >= HEAVY_MM }
        return "${if (heavy) "Heavy ${noun.lowercase()}" else noun} starting around ${time(start.time)}"
    }

    private const val MIX = "Wintry mix"

    private fun noun(steps: List<MinutePrecip>): String {
        val snow = steps.sumOf { it.snowfallCm }
        val liquid = steps.sumOf { it.precipitationMm }
        // A centimetre of fresh snow holds about a millimetre of water: mostly snow means snow.
        return when {
            snow > 0 && snow >= liquid * 0.8 -> "Snow"
            snow > 0 -> MIX
            else -> "Rain"
        }
    }
}

/** What a glance at the watch shows for one place: the app's top card, the tile and the complication. */
data class Glance(
    val place: String,
    val isCurrentLocation: Boolean,
    val hasForecast: Boolean,
    val temperature: String,
    val condition: WeatherCondition,
    val conditionText: String,
    val isDay: Boolean,
    /** "H 58° L 41°", or one of them. */
    val highLow: String?,
    /** The same one-line headline as Mini and the main app. */
    val headline: String?,
    val nextHour: String?,
    /** Alerts in effect, warnings first. */
    val alerts: List<WeatherAlert>,
    val updatedAt: Instant?,
    /** Older than [STALE_AFTER]: the tile says when it was updated. */
    val stale: Boolean,
    val clock: WatchClock,
) {
    /** "Updated 3:10 PM" for old data, else null. */
    val updatedLine: String? get() = if (stale && updatedAt != null) "Updated ${clock.time(updatedAt)}" else null

    companion object {
        val STALE_AFTER: Duration = Duration.ofHours(2)
        const val CURRENT_LOCATION = "Current location"

        fun of(place: SavedLocation, forecast: WearForecast?, units: UnitSystem, showRain: Boolean, now: Instant, is24: Boolean): Glance {
            val snapshot = forecast?.snapshot
            val clock = WatchClock.forSnapshotZone(snapshot?.timeZoneId, is24)
            val f = UnitFormatter(units)
            val name = if (place.isDeviceLocation) snapshot?.placeName ?: CURRENT_LOCATION else place.displayName
            if (snapshot == null) {
                return Glance(name, place.isDeviceLocation, false, "--", WeatherCondition.UNKNOWN, "No forecast yet", true, null, null, null, emptyList(), null, false, clock)
            }
            val hour = currentHour(snapshot.hourly, now)
            // An observation more than two hours old says less than the forecast for this hour.
            val current = snapshot.current?.takeIf { c ->
                val at = c.observedAt ?: snapshot.fetchedAt
                Duration.between(at, now) < Duration.ofHours(2)
            }
            val temperatureC = current?.temperatureC ?: hour?.temperatureC
            val condition = current?.condition?.takeIf { it != WeatherCondition.UNKNOWN } ?: hour?.condition ?: WeatherCondition.UNKNOWN
            val conditionText = condition.takeIf { it != WeatherCondition.UNKNOWN }?.label
                ?: current?.description ?: hour?.shortForecast ?: ""
            val today = today(snapshot.daily, now, clock.zone)
            val highLow = today?.let { d ->
                listOfNotNull(d.highC?.let { "H ${f.temperature(it)}" }, d.lowC?.let { "L ${f.temperature(it)}" }).joinToString(" ").ifBlank { null }
            }
            val headline = TodayHeadline.write(snapshot.hourly, snapshot.daily, now, clock.zone, f, clock::time, clock::hour, maxSentences = 1)
                .firstOrNull()?.trimEnd('.')
            val nextHour = if (showRain) NextHour.text(forecast.rainSteps, now, clock::soon) else null
            val alerts = activeAlerts(snapshot.alerts, now)
            return Glance(
                place = name,
                isCurrentLocation = place.isDeviceLocation,
                hasForecast = true,
                temperature = f.temperature(temperatureC),
                condition = condition,
                conditionText = conditionText,
                isDay = current?.isDaytime ?: hour?.isDaytime ?: true,
                highLow = highLow,
                headline = headline,
                nextHour = nextHour,
                alerts = alerts,
                updatedAt = snapshot.fetchedAt,
                stale = Duration.between(snapshot.fetchedAt, now) > STALE_AFTER,
                clock = clock,
            )
        }

        fun currentHour(hourly: List<HourlyForecast>, now: Instant): HourlyForecast? =
            hourly.firstOrNull { it.time.plusSeconds(3600) > now }

        fun today(daily: List<DailyForecast>, now: Instant, zone: ZoneId): DailyForecast? {
            val date = now.atZone(zone).toLocalDate()
            return daily.firstOrNull { !it.date.isBefore(date) && (it.highC != null || it.lowC != null) }
        }

        fun activeAlerts(alerts: List<WeatherAlert>, now: Instant): List<WeatherAlert> =
            alerts.filter { (it.ends ?: it.expires)?.isAfter(now) != false }.sortedBy { it.category.ordinal }
    }
}

/** The complication's text: SHORT_TEXT is the temperature, LONG_TEXT adds the next hour or the headline. */
data class ComplicationTexts(val shortText: String, val shortTitle: String?, val longTitle: String, val longText: String, val description: String) {
    companion object {
        fun of(g: Glance): ComplicationTexts {
            if (!g.hasForecast) return ComplicationTexts("--", null, g.place, "Open to update", "${g.place}: no forecast yet")
            val warning = g.alerts.firstOrNull { it.category == AlertCategory.WARNING }
            val detail = warning?.event ?: g.nextHour ?: g.headline ?: g.conditionText
            return ComplicationTexts(
                shortText = g.temperature,
                // A warning shows even on the smallest complication.
                shortTitle = if (warning != null) "Alert" else null,
                longTitle = listOf(g.temperature, g.conditionText).filter { it.isNotBlank() }.joinToString(" "),
                longText = detail,
                description = listOfNotNull("${g.place}: ${g.temperature}", g.conditionText.ifBlank { null }, warning?.event, g.nextHour).joinToString(", "),
            )
        }
    }
}

/** The tile's lines, top to bottom; null lines are left out. */
data class TileTexts(
    val title: String,
    val temperature: String,
    val summary: String?,
    val alert: String?,
    val headline: String?,
    val nextHour: String?,
    val updated: String?,
) {
    companion object {
        fun of(g: Glance?): TileTexts {
            if (g == null) return TileTexts("Weather", "--", "Tap to add a place", null, null, null, null)
            if (!g.hasForecast) return TileTexts(g.place, "--", "Tap to update", null, null, null, null)
            val alert = g.alerts.firstOrNull()?.let { a -> if (g.alerts.size > 1) "${a.event} +${g.alerts.size - 1}" else a.event }
            return TileTexts(
                title = g.place,
                temperature = g.temperature,
                summary = listOfNotNull(g.conditionText.ifBlank { null }, g.highLow).joinToString(" · ").ifBlank { null },
                alert = alert,
                headline = g.headline,
                nextHour = g.nextHour,
                updated = g.updatedLine,
            )
        }
    }
}
