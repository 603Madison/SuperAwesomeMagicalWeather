package app.northvane.weather.ui

import app.northvane.core.garden.FrostClimate
import app.northvane.core.garden.GardenAdvisor
import app.northvane.core.model.WeatherSnapshot
import app.northvane.core.outlook.AllergyEstimator
import app.northvane.core.outlook.BurnAdvisor
import app.northvane.core.outlook.DryingAdvisor
import app.northvane.core.outlook.HeatingAdvisor
import app.northvane.core.outlook.MinutePrecip
import app.northvane.core.outlook.OutlookText
import app.northvane.core.outlook.SapAdvisor
import app.northvane.core.outlook.TodayHeadline
import app.northvane.core.outlook.MyTimes
import app.northvane.core.outlook.WhatToWear
import app.northvane.core.util.UnitFormatter
import app.northvane.weather.data.AppSettings
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The app's outlooks that depend on the user's own setup (garden, heating,
 * allergies, laundry, frost dates) or on data held outside the forecast
 * download (the 15-minute rain forecast), written out for the AI. Together
 * with [app.northvane.core.ai.AiPrompts.outlooks] it covers every card the
 * app can show, so an answer never claims the app doesn't know something it does.
 */
object AiContext {

    fun extras(
        snapshot: WeatherSnapshot,
        settings: AppSettings,
        climate: FrostClimate?,
        nowcast: List<MinutePrecip>,
        latitude: Double?,
        now: Instant,
    ): String {
        val zone = runCatching { ZoneId.of(snapshot.timeZoneId) }.getOrDefault(ZoneId.systemDefault())
        val f = UnitFormatter(settings.units)
        val hour = DateTimeFormatter.ofPattern("EEE h a", Locale.US).withZone(zone)
        val clock = DateTimeFormatter.ofPattern("h:mm a", Locale.US).withZone(zone)
        val day = DateTimeFormatter.ofPattern("EEE", Locale.US)
        val date = DateTimeFormatter.ofPattern("MMM d", Locale.US)
        val today = now.atZone(zone).toLocalDate()
        fun words(e: Enum<*>) = e.name.lowercase().replace('_', ' ')
        return buildString {
            val slots = MyTimes.upcoming(settings.myTimes, now, zone)
            val headline = TodayHeadline.write(snapshot.hourly, snapshot.daily, now, zone, f, clock::format, hour::format, nowcast, times = slots)
            if (headline.isNotEmpty()) appendLine("The app's headline: ${headline.joinToString(" ")}")
            if (settings.myTimes.isNotEmpty()) {
                appendLine("The reader is usually out at: " + settings.myTimes.joinToString("; ") { t ->
                    "${t.label} ${"%d:%02d".format(t.minutes / 60, t.minutes % 60)} for ${t.durationMinutes} min" + if (t.weekdaysOnly) " on weekdays" else ""
                } + ".")
            }
            WhatToWear.advise(snapshot.hourly, now, settings.comfort, hour::format, snapshot.uv?.todayMax)?.let {
                appendLine("What to wear (the app's suggestion; the reader says: ${settings.comfort.label.lowercase()}): $it.")
            }
            if (nowcast.isNotEmpty()) {
                val wet = nowcast.filter { it.time.isAfter(now.minusSeconds(900)) && it.precipitationMm >= 0.1 }
                appendLine(
                    "Next two hours, 15-minute rain forecast: " +
                        if (wet.isEmpty()) "dry." else wet.joinToString(", ") { "${clock.format(it.time)} ${f.precipitation(it.precipitationMm)}" } + ".",
                )
            }
            climate?.let { c ->
                appendLine(
                    "Frost dates here (30 years of NOAA data): last spring frost usually ${date.format(c.lastFrost(today.year))}, " +
                        "first fall frost ${date.format(c.firstFrost(today.year))}, first hard freeze ${date.format(c.hardFreeze(today.year))}.",
                )
            }
            if (settings.allergies.isNotEmpty()) {
                val days = AllergyEstimator.forecast(settings.allergies, snapshot.daily, snapshot.pollen, snapshot.airQuality, today).take(3)
                if (days.isNotEmpty()) {
                    appendLine(
                        "The reader's allergy score (they react to ${settings.allergies.keys.joinToString(", ") { it.label.lowercase() }}): " +
                            days.joinToString("; ") { d ->
                                "${day.format(d.date)} ${d.score}/100 ${d.level.label.lowercase()}" +
                                    d.drivers.take(2).takeIf { it.isNotEmpty() }?.let { dr -> " (${dr.joinToString(", ") { it.allergen.label.lowercase() }})" }.orEmpty()
                            } + ".",
                    )
                }
            }
            if (climate != null && settings.gardenPlan.isNotEmpty()) {
                val report = GardenAdvisor.report(settings.gardenPlan, climate, snapshot.daily, snapshot.history, today)
                val plants = GardenAdvisor.plants(report, today)
                if (plants.isNotEmpty()) {
                    appendLine("The reader's garden:")
                    plants.forEach { p ->
                        val parts = listOfNotNull(
                            p.planting?.let { "${it.step.label.lowercase()}: ${words(it.status)}, window ${date.format(it.windowStart)} to ${date.format(it.windowEnd)}" },
                            p.harvest?.let { "harvest: ${words(it.status)}, ${date.format(it.start)} to ${date.format(it.end)}" },
                        )
                        appendLine("- ${p.crop.label}: ${parts.joinToString("; ")}")
                    }
                }
                report.frostNights.takeIf { it.isNotEmpty() }?.let { n -> appendLine("Garden frost nights: " + n.joinToString(", ") { "${day.format(it.date)} ${f.temperature(it.lowC)}" } + ".") }
                report.watering?.let { w -> appendLine("Watering: ${words(w.action)} (past week ${f.precipitation(w.pastWeekMm)}, coming ${f.precipitation(w.comingMm)}).") }
                report.soil?.let { appendLine("Garden soil: ${it.label}" + (report.soilTempF?.let { t -> ", about ${t.roundToInt()}°F" } ?: "") + ".") }
            }
            val drying = DryingAdvisor.report(snapshot.hourly, snapshot.daily, now, zone)
            appendLine(
                "Clothes drying outdoors: " + (drying.best?.let { "best window ${hour.format(it.start)} to ${hour.format(it.end)}, about ${it.hoursToDry.roundToInt()} hours to dry" }
                    ?: "no good window in the next two days") +
                    (if (settings.laundryOutSince != null) "; the reader has laundry out now" else "") + ".",
            )
            if (settings.heatSources.isNotEmpty()) {
                val heating = HeatingAdvisor.report(settings.heatSources, snapshot.daily, snapshot.history, climate, today, settings.heatingAggressive)
                appendLine(
                    "Heating (the reader has ${settings.heatSources.joinToString(", ") { it.label.lowercase() }}): ${heating.phase.label.lowercase()}" +
                        (if (heating.recommended.isNotEmpty()) "; run ${heating.recommended.joinToString(" and ")}" else "") +
                        "; ${HeatingAdvisor.demandText(heating.weekHdd, heating.recentHdd).trimEnd('.').lowercase()}" +
                        heating.tasks.takeIf { it.isNotEmpty() }?.let { t -> "; to do: " + t.joinToString(", ") { OutlookText.heatingTask(it, f) } }.orEmpty() + ".",
                )
            }
            if (latitude != null && SapAdvisor.inSeason(today, latitude)) {
                val sap = SapAdvisor.report(snapshot.daily, snapshot.hourly, snapshot.history, today, zone)
                appendLine("Maple sap: " + sap.days.take(7).joinToString("; ") { "${day.format(it.date)} ${it.rating.label.lowercase()}" } + ".")
            }
            val burn = BurnAdvisor.report(snapshot.daily, snapshot.history, snapshot.alerts, today, zone)
            if (burn.days.isNotEmpty()) {
                appendLine(
                    "Burning brush: " + burn.days.take(5).joinToString("; ") { "${day.format(it.date)} ${it.rating.label.lowercase()}" } +
                        (burn.daysSinceRain?.let { "; $it days since real rain" } ?: "") + ".",
                )
            }
        }.trim()
    }
}
