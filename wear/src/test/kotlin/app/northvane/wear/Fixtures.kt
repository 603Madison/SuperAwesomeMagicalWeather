package app.northvane.wear

import app.northvane.core.model.AlertCategory
import app.northvane.core.model.AlertSeverity
import app.northvane.core.model.CurrentConditions
import app.northvane.core.model.DailyForecast
import app.northvane.core.model.DataSource
import app.northvane.core.model.HourlyForecast
import app.northvane.core.model.SavedLocation
import app.northvane.core.model.WeatherAlert
import app.northvane.core.model.WeatherCondition
import app.northvane.core.model.WeatherSnapshot
import app.northvane.core.time.*
import app.northvane.wear.data.RainStep
import app.northvane.wear.data.WearForecast

/** A made-up but realistic forecast for Portland, Maine, on a November afternoon. */
object Fixtures {
    val zone: ZoneId = ZoneId.of("America/New_York")
    /** 3:05 PM in Portland. */
    val now: Instant = Instant.parse("2026-11-11T20:05:00Z")
    val place = SavedLocation("portland", "Portland, Maine", 43.66, -70.26)

    fun rain(vararg mm: Double, snowCm: Double = 0.0, start: Instant = Instant.parse("2026-11-11T20:00:00Z")): List<RainStep> =
        mm.mapIndexed { i, v -> RainStep(start.plus(Duration.ofMinutes(15L * i)).epochSecond, v, if (v > 0) snowCm else 0.0) }

    fun hourly(chance: (Int) -> Int = { 10 }): List<HourlyForecast> = (0 until 24).map { i ->
        HourlyForecast(
            time = Instant.parse("2026-11-11T20:00:00Z").plus(Duration.ofHours(i.toLong())),
            temperatureC = 9.0 - i * 0.3,
            condition = if (chance(i) >= 50) WeatherCondition.RAIN else WeatherCondition.PARTLY_CLOUDY,
            isDaytime = i < 2,
            shortForecast = "Partly Cloudy",
            precipitationChance = chance(i),
            windSpeedKph = 12.0,
            windDirection = "SW",
            humidityPercent = 70.0,
        )
    }

    fun daily(): List<DailyForecast> = (0 until 7).map { i ->
        DailyForecast(
            date = LocalDate(2026, 11, 11).plusDays(i),
            name = if (i == 0) "This Afternoon" else "Day $i",
            highC = 11.0 - i,
            lowC = 2.0 - i,
            condition = if (i == 2) WeatherCondition.SNOW else WeatherCondition.PARTLY_CLOUDY,
            isDaytimeCondition = true,
            shortForecast = "Partly Sunny",
            precipitationChance = if (i == 2) 60 else 10,
            dayDetail = "Partly sunny, with a high near 52. Southwest wind 5 to 10 mph.",
        )
    }

    val warning = WeatherAlert(
        id = "urn:oid:2.49.0.1.840.0.test.1", threadId = "t1", event = "Winter Storm Warning",
        headline = "Winter Storm Warning issued November 11 at 2:10PM EST until November 13 at 7:00AM EST",
        description = "* WHAT...Heavy snow expected. Total snow\naccumulations of 8 to 12 inches.\n\n* WHERE...Coastal Cumberland County.",
        instruction = "Travel could be very difficult.",
        severity = AlertSeverity.SEVERE, urgency = "Expected", certainty = "Likely", category = AlertCategory.WARNING,
        ends = Instant.parse("2026-11-13T12:00:00Z"),
    )

    fun forecast(
        rain: List<RainStep> = rain(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
        alerts: List<WeatherAlert> = emptyList(),
        fetchedAt: Instant = now.minus(Duration.ofMinutes(10)),
        hourly: List<HourlyForecast> = hourly(),
    ) = WearForecast(
        WeatherSnapshot(
            locationId = place.id,
            fetchedAt = fetchedAt,
            timeZoneId = "America/New_York",
            placeName = "Portland",
            current = CurrentConditions(
                observedAt = fetchedAt.minus(Duration.ofMinutes(20)), source = DataSource.NWS,
                condition = WeatherCondition.PARTLY_CLOUDY, temperatureC = 10.0, description = "Partly Cloudy",
            ),
            hourly = hourly,
            daily = daily(),
            alerts = alerts,
        ),
        rain,
    )
}
