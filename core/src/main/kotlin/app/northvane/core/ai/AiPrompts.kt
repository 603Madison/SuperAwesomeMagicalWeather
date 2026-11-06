package app.northvane.core.ai

import app.northvane.core.model.UnitSystem
import app.northvane.core.model.WeatherAlert
import app.northvane.core.model.WeatherSnapshot
import app.northvane.core.nws.ForecastDiscussion
import app.northvane.core.util.UnitFormatter
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

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

    /** The forecasters' discussion in plain words, for one place in the office's area. */
    fun discussion(d: ForecastDiscussion, place: String, zone: ZoneId): AiRequest = AiRequest(
        system = "You explain National Weather Service forecast discussions to people without a meteorology background. " +
            "A discussion is the forecasters' own reasoning behind the forecast for a large area. " +
            "Tell the reader what the forecasters expect at their place over the next few days, how confident they are, " +
            "and what could still change. Where the discussion treats parts of the area differently (coast, foothills, " +
            "mountains, north and south), use what applies to the reader's place and leave out the rest; never comment on " +
            "what you know or don't know about their location. Translate jargon (QPF, PTYPE, shortwave, upslope and so on) " +
            "into everyday words, and skip marine and fire-weather detail unless it changes things on land. " +
            "Keep it short: at most 4 sentences and 70 words, then one line that starts with \"Confidence:\" giving high, " +
            "medium or low and the reason in under 15 words. " + PLAIN,
        user = "The reader is in $place. Discussion from NWS ${d.office}, issued ${stamp(d.issued, zone)} local time:\n\n" +
            "<discussion>\n${d.text}\n</discussion>",
        replyTokens = 300,
        effort = AiEffort.LOW,
    )

    /** One alert, rewritten for someone at [place]. */
    fun alert(a: WeatherAlert, place: String, zone: ZoneId, now: Instant): AiRequest = AiRequest(
        system = "You rewrite National Weather Service alerts so they are quick to understand. " +
            "Say what is expected, when it matters (in the reader's local time), and the one or two things worth doing about it. " +
            "If the alert says it applies only to part of its area (the coast, higher elevations, a county) and the reader's place " +
            "may be outside that part, say so. Keep every number and time from the alert; add none of your own. " +
            "Use at most 3 short sentences and 60 words. " + PLAIN,
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
        replyTokens = 300,
        effort = AiEffort.LOW,
    )

    /** A question about the forecast, answered from the forecast itself. */
    fun question(question: String, s: WeatherSnapshot, place: String, units: UnitSystem, now: Instant): AiRequest = AiRequest(
        system = "You answer questions about the weather forecast in a phone weather app. Answer from the forecast data given; " +
            "it is the only weather information you have. Be direct: lead with the answer, give the times and numbers that " +
            "support it, and suggest a better time if the one asked about looks poor. If the forecast doesn't cover what was asked " +
            "(too far ahead, or something it doesn't measure), say so plainly. If the question isn't about the weather or " +
            "planning around it, say that you can only help with the forecast. " +
            "Use at most 4 short sentences and 80 words. " + PLAIN,
        user = "<forecast>\n${forecastData(s, place, units, now)}\n</forecast>\n\nQuestion: ${question.trim().take(MAX_QUESTION)}",
        replyTokens = 450,
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
        val hour = DateTimeFormatter.ofPattern("EEE h a", Locale.US).withZone(zone)
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

    private fun stamp(t: Instant, zone: ZoneId): String =
        DateTimeFormatter.ofPattern("EEE MMM d, h:mm a", Locale.US).withZone(zone).format(t)
}
