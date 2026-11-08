package app.northvane.core.ai

import app.northvane.core.model.UnitSystem
import app.northvane.core.aurora.AuroraAdvisor
import app.northvane.core.model.AuroraView
import app.northvane.core.model.OutageLevel
import app.northvane.core.outlook.BestWindow
import app.northvane.core.outlook.OutlookText
import app.northvane.core.outlook.StormTotals
import app.northvane.core.radar.SmokeLevel
import app.northvane.core.model.WeatherAlert
import app.northvane.core.model.WeatherSnapshot
import app.northvane.core.nws.ForecastDiscussion
import app.northvane.core.util.UnitFormatter
import app.northvane.core.time.*
import app.northvane.core.util.fmt

/**
 * The three jobs the app gives a language model. Each is one that the app's
 * own rules can't do: reading forecasters' prose, rewriting an alert for one
 * place, and answering a question nobody anticipated. Restating forecast
 * numbers the screen already shows is deliberately not one of them.
 *
 * Every request carries only weather text and the place's name.
 */
object AiPrompts {

    private const val PLAIN = "Write plain text for a phone screen: no markdown, headings or emoji."

    /** The forecasters' discussion, boiled down for one place in the office's area. */
    fun discussion(d: ForecastDiscussion, place: String, zone: ZoneId): AiRequest = AiRequest(
        system = "You boil National Weather Service forecast discussions down for people at a glance. A discussion is the " +
            "forecasters' reasoning for a large area; keep only what matters at the reader's place over the next few days, " +
            "how sure the forecasters are, and what could change. Where the discussion treats parts of the area differently " +
            "(coast, foothills, mountains), use the part that fits the reader's place and drop the rest. Use everyday words, no jargon. " +
            "Reply in exactly this format and nothing else:\n" +
            "HEADLINE: the one thing to know, under 14 words\n" +
            "- a supporting point, under 12 words\n" +
            "- (one to three of these)\n" +
            "CONFIDENCE: high, medium or low | the reason in under 8 words",
        user = "The reader is in $place. Discussion from NWS ${d.office}, issued ${stamp(d.issued, zone)} local time:\n\n" +
            "<discussion>\n${d.text}\n</discussion>",
        replyTokens = 200,
        effort = AiEffort.LOW,
    )

    /** One alert, rewritten for someone at [place]. */
    fun alert(a: WeatherAlert, place: String, zone: ZoneId, now: Instant): AiRequest = AiRequest(
        system = "You rewrite National Weather Service alerts so they are quick to understand. " +
            "Say what is expected, when it matters (in the reader's local time), and the one or two things worth doing about it. " +
            "If the alert says it applies only to part of its area (the coast, higher elevations, a county) and the reader's place " +
            "may be outside that part, say so. Keep the alert's numbers and times; add none of your own. " +
            "Reply in exactly this format and nothing else:\n" +
            "HEADLINE: what happens and when at the reader's place, under 16 words\n" +
            "- one thing to do, under 10 words\n" +
            "- (at most two of these)",
        user = buildString {
            appendLine("The reader is in $place. It is now ${stamp(now, zone)} local time.")
            appendLine("<alert>")
            appendLine("Event: ${a.event}")
            a.headline?.let { appendLine("Headline: $it") }
            a.onset?.let { appendLine("Starts: ${stamp(it, zone)}") }
            (a.ends ?: a.expires)?.let { appendLine("Ends: ${stamp(it, zone)}") }
            a.areaDesc?.let { appendLine("Area: ${it.take(600)}") }
            a.description?.let { appendLine("Details:\n${it.trim().take(4000)}") }
            a.instruction?.let { appendLine("Instructions:\n${it.trim().take(1500)}") }
            append("</alert>")
        },
        replyTokens = 160,
        effort = AiEffort.LOW,
    )

    /** A question about the forecast, answered from the forecast itself. */
    fun question(
        question: String,
        s: WeatherSnapshot,
        place: String,
        units: UnitSystem,
        now: Instant,
        longitude: Double? = null,
        /** The app's outlooks that depend on the user's own settings (garden, heating, allergies…), already written out. */
        extras: String = "",
    ): AiRequest = AiRequest(
        system = "You answer questions about the weather in a phone weather app. Answer from the data given: the forecast and the " +
            "app's own outlooks: northern lights, wildfire smoke, air quality, UV, pollen and allergies, trail mud, fall foliage, rivers " +
            "and tides, storm totals, power outage and frozen pipe risk, frost and black ice, outdoor activities with their best " +
            "hours, recent rain, and whatever the reader set up (garden, heating, clothes drying, maple sap, burn days). It is the " +
            "only information you have; when one of these answers the question, use it, and never say the app doesn't track " +
            "something that appears below. Be direct: lead with the answer, give the times and numbers that " +
            "support it, and suggest a better time if the one asked about looks poor. If the forecast doesn't cover what was asked " +
            "(too far ahead, or something it doesn't measure), say so plainly. If the question isn't about the weather or " +
            "planning around it, say that you can only help with the forecast. " +
            "Answer in one or two short sentences, under 40 words. " + PLAIN,
        user = "<forecast>\n${forecastData(s, place, units, now)}\n${outlooks(s, units, now, longitude).ifEmpty { "No other outlooks." }}\n${extras.trim()}\n</forecast>\n\n" +
            "Question: ${question.trim().take(MAX_QUESTION)}",
        replyTokens = 200,
        effort = AiEffort.MEDIUM,
    )

    const val MAX_QUESTION = 300

    /**
     * The forecast as compact lines: conditions now, 48 hours hour by hour,
     * the days ahead, and active alerts. About 4 KB.
     */
    fun forecastData(s: WeatherSnapshot, place: String, units: UnitSystem, now: Instant): String {
        val zone = runCatching { ZoneId.of(s.timeZoneId) }.getOrDefault(ZoneId.systemDefault())
        val f = UnitFormatter(units)
        val hour = DateTimeFormatter.ofPattern("EEE h a").withZone(zone)
        return buildString {
            appendLine("Place: $place. Now: ${stamp(now, zone)}. Units: ${if (units == UnitSystem.IMPERIAL) "°F, mph, inches" else "°C, km/h, mm"}.")
            s.current?.let { c ->
                appendLine(
                    "Current: ${f.temperatureWithUnit(c.temperatureC)}, feels like ${f.temperatureWithUnit(c.feelsLikeC ?: c.temperatureC)}, " +
                        "${c.description ?: c.condition.label}, wind ${f.wind(c.windSpeedKph, c.windDirectionDeg)}" +
                        (c.windGustKph?.let { " gusting ${f.windSpeed(it)}" } ?: "") +
                        (c.humidityPercent?.let { ", humidity ${f.percent(it)}" } ?: "") + ".",
                )
            }
            val hours = s.hourly.filter { it.time.isAfter(now.minus(Duration.ofMinutes(59))) }.take(48)
            if (hours.isNotEmpty()) {
                appendLine("Hourly (time: temperature, sky, chance of precipitation, amount, wind):")
                hours.forEach { h ->
                    append(hour.format(h.time)).append(": ").append(f.temperatureWithUnit(h.temperatureC))
                    append(", ").append(h.shortForecast ?: h.condition.label)
                    h.precipitationChance?.let { append(", ").append(it).append("%") }
                    h.precipitationMm?.takeIf { it >= 0.1 }?.let { append(", ").append(f.precipitation(it)) }
                    append(", wind ").append(f.wind(h.windSpeedKph, h.windDirectionDeg))
                    h.windGustKph?.let { append(" gust ").append(f.windSpeed(it)) }
                    appendLine()
                }
            }
            if (s.daily.isNotEmpty()) {
                appendLine("Daily:")
                s.daily.take(7).forEach { d ->
                    append("${d.name}: high ${f.temperatureWithUnit(d.highC)}, low ${f.temperatureWithUnit(d.lowC)}, ${d.shortForecast ?: d.condition.label}")
                    d.precipitationChance?.let { append(", ${it}% precipitation") }
                    d.snowMm?.takeIf { it >= 5 }?.let { append(", snow ${f.snow(it)}") }
                    d.gustMaxKph?.let { append(", gusts ${f.windSpeed(it)}") }
                    appendLine()
                }
            }
            s.alerts.take(4).forEach { a ->
                appendLine("Alert: ${a.event}" + ((a.ends ?: a.expires)?.let { " until ${hour.format(it)}" } ?: "") + (a.headline?.let { ". $it" } ?: ""))
            }
            s.airQuality?.current?.let { appendLine("Air quality index ${it.aqi} (${it.category.label}).") }
            s.uv?.todayMax?.let { appendLine("UV index today up to ${it.toInt()}.") }
        }.trim()
    }

    /**
     * The app's own outlooks, as compact lines: everything a card on the
     * Today or Outlook tab could tell the reader, so an answer never says
     * "the forecast doesn't cover that" when the app does.
     */
    fun outlooks(s: WeatherSnapshot, units: UnitSystem, now: Instant, longitude: Double? = null): String {
        val zone = runCatching { ZoneId.of(s.timeZoneId) }.getOrDefault(ZoneId.systemDefault())
        val f = UnitFormatter(units)
        val hour = DateTimeFormatter.ofPattern("EEE h a").withZone(zone)
        val day = DateTimeFormatter.ofPattern("EEE")
        val today = now.atZone(zone).toLocalDate()
        return buildString {
            val lat = s.latitude
            val aurora = s.aurora
            if (aurora != null && lat != null && longitude != null) {
                val report = AuroraAdvisor.report(aurora, lat, longitude, s.hourly, zone, now)
                if (report.nights.isNotEmpty()) {
                    appendLine("Northern lights (NOAA Kp forecast):")
                    if (report.nowView != AuroraView.NONE) appendLine("Right now: ${report.nowView.label.lowercase()} (Kp ${report.nowKp}).")
                    report.nights.forEach { n ->
                        val name = if (n.date == today) "Tonight" else "${day.format(n.date)} night"
                        append("$name: Kp ${"%.1f".fmt(n.maxKp)}, ${n.view.label.lowercase()}")
                        n.peakAt?.let { append(", best around ${hour.format(it)}") }
                        n.cloudPercent?.let { append(", cloud cover about $it%" + if (it >= 85) " (likely hidden)" else "") }
                        appendLine(".")
                    }
                }
            }
            StormTotals.find(s.hourly, s.daily, s.alerts, now, zone)?.let { st ->
                append("Storm: ${st.title}")
                if (st.start != null && st.end != null) append(" from ${hour.format(st.start)} to ${hour.format(st.end)}")
                if (st.snowMm >= 5) append(", snow about ${f.snow(st.snowMm)}")
                if (st.iceMm >= 0.5) append(", ice about ${f.precipitation(st.iceMm)}")
                if (st.rainMm >= 2) append(", rain about ${f.precipitation(st.rainMm)}")
                appendLine(".")
            }
            s.smoke?.peak?.let { p ->
                val level = SmokeLevel.of(p.microgramsPerM3)
                if (level != SmokeLevel.NONE) appendLine("Wildfire smoke: ${level.label.lowercase()}, worst around ${hour.format(p.time)}.")
            }
            s.airQuality?.days?.filter { !it.date.isBefore(today) }?.take(3)?.takeIf { it.isNotEmpty() }?.let { days ->
                appendLine("Air quality forecast: " + days.joinToString("; ") { d -> "${day.format(d.date)} " + (d.aqi?.let { "AQI $it " } ?: "") + (d.category?.label ?: "") })
            }
            s.pollen?.days?.take(3)?.takeIf { it.isNotEmpty() }?.let { days ->
                appendLine("Pollen: " + days.joinToString("; ") { d -> "${day.format(d.date)} " + (d.worst?.let { "${it.name.lowercase()} ${OutlookText.pollenCategory(it.index).lowercase()}" } ?: "none") })
            }
            s.water?.let { w ->
                w.river?.let { r ->
                    append("River: ${r.name}")
                    r.gageHeightFt?.let { append(", height ${f.height(it * 0.3048)}") }
                    r.dischargeCfs?.let { append(", flow ${f.discharge(it)}") }
                    append(if (r.rising) ", rising" else ", steady or falling")
                    appendLine(".")
                }
                w.tide?.let { t ->
                    val next = t.events.filter { it.time.isAfter(now) }.take(4)
                    if (next.isNotEmpty()) appendLine("Tides at ${t.name}: " + next.joinToString(", ") { e -> "${if (e.high) "high" else "low"} ${hour.format(e.time)}" } + ".")
                }
                w.waterTempC?.let { appendLine("Water temperature ${f.temperatureWithUnit(it)}.") }
            }
            s.current?.let { c ->
                val extra = listOfNotNull(
                    c.dewpointC?.let { "dew point ${f.temperatureWithUnit(it)}" },
                    c.pressureHpa?.let { "pressure ${f.pressure(it)}" },
                    c.visibilityKm?.let { "visibility ${f.visibility(it)}" },
                    c.rainTodayMm?.let { "rain so far today ${f.precipitation(it)}" },
                    c.stationName?.let { "station $it" },
                )
                if (extra.isNotEmpty()) appendLine("Conditions now also: ${extra.joinToString(", ")}.")
            }
            if (s.sunrise != null || s.sunset != null) {
                appendLine("Sun today: " + listOfNotNull(s.sunrise?.let { "rises ${hour.format(it)}" }, s.sunset?.let { "sets ${hour.format(it)}" }).joinToString(", ") + ".")
            }
            s.airQuality?.current?.let { appendLine("Air quality now: AQI ${it.aqi} (${it.category.label})" + (it.pollutant?.let { p -> ", mainly $p" } ?: "") + ".") }
            s.uv?.let { uv ->
                val peak = uv.peak()
                appendLine("UV index: now ${uv.at(now)?.let { "%.0f".fmt(it) } ?: "unknown"}" +
                    (uv.todayMax?.let { ", today's peak ${"%.0f".fmt(it)}" } ?: "") +
                    (peak?.let { " around ${hour.format(it.time)}" } ?: "") + ".")
            }
            s.history?.let { h ->
                val recent = h.days.sortedBy { it.date }.takeLast(7)
                val rain = recent.mapNotNull { it.precipMm }
                if (rain.isNotEmpty()) appendLine("Observed rain and melted snow over the last ${recent.size} days: ${f.precipitation(rain.sum())}.")
                h.snowDepthMm?.takeIf { it > 0 }?.let { appendLine("Snow on the ground: about ${f.snow(it)}" + (h.snowDepthStation?.let { st -> " (reported at $st)" } ?: "") + ".") }
            }
            val mudDays = s.daily.filter { !it.date.isBefore(today) && it.mud != null }.take(5)
            if (mudDays.isNotEmpty()) {
                appendLine(
                    "Trail mud (the app's estimate from observed and forecast rain, snowmelt and thaw): " +
                        mudDays.joinToString("; ") { d ->
                            val m = d.mud!!
                            val why = m.reasons.take(2).joinToString(", ") { r -> r.kind.name.lowercase().replace('_', ' ') }
                            "${day.format(d.date)} ${m.level.label.lowercase()}" + (if (why.isNotEmpty()) " ($why)" else "")
                        } + if (mudDays.any { it.mud!!.mudSeason }) "; spring mud season." else ".",
                )
            }
            s.foliage?.let { fo ->
                fo.today?.let { t ->
                    appendLine(
                        "Fall foliage (the app's estimate): ${t.stage.label.lowercase()}, about ${t.colorPercent}% color; estimated peak ${day.format(fo.estimatedPeak)} ${fo.estimatedPeak}" +
                            (fo.days.filter { it.leafDrop }.takeIf { it.isNotEmpty() }?.let { d -> "; wind or rain may strip leaves ${d.joinToString(", ") { day.format(it.date) }}" } ?: "") + ".",
                    )
                }
            }
            s.daily.filter { !it.date.isBefore(today) }.take(7).forEach { d ->
                val notes = buildList {
                    d.outageRisk?.let { add("power outage risk ${it.level.label.lowercase()}") }
                    d.pipeRisk?.let { add("frozen pipe risk ${it.level.label.lowercase()}") }
                    d.morningIce?.frost?.let { add("morning frost ${it.label}") }
                    d.morningIce?.blackIce?.let { add("black ice ${it.label}") }
                    d.activities.takeIf { it.isNotEmpty() }?.let { list ->
                        add(
                            "activities: " + list.joinToString(", ") { a ->
                                val window = BestWindow.find(a.activity, s.hourly, d.date, zone, now)
                                    ?.let { w -> " best ${hour.format(w.start)} to ${hour.format(w.end)}" }.orEmpty()
                                val why = a.tips.firstOrNull()?.let { " (${OutlookText.tip(it, f)})" }.orEmpty()
                                "${a.label} ${a.score}/100$window$why"
                            },
                        )
                    }
                }
                if (notes.isNotEmpty()) appendLine("${day.format(d.date)}: ${notes.joinToString("; ")}.")
            }
            // The National Weather Service's own words for the next few periods.
            s.daily.filter { !it.date.isBefore(today) }.take(4).forEach { d ->
                listOfNotNull(d.dayDetail?.let { "day" to it }, d.nightDetail?.let { "night" to it }).forEach { (part, text) ->
                    appendLine("NWS ${day.format(d.date)} $part: ${text.trim().take(300)}")
                }
            }
        }.trim()
    }

    private fun stamp(t: Instant, zone: ZoneId): String =
        DateTimeFormatter.ofPattern("EEE MMM d, h:mm a").withZone(zone).format(t)
}
