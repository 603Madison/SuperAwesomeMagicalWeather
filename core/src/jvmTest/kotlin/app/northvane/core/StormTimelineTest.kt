package app.northvane.core

import app.northvane.core.alerts.StormTimeline
import app.northvane.core.alerts.StormTimeline.Hazard
import app.northvane.core.model.AlertCategory
import app.northvane.core.model.AlertSeverity
import app.northvane.core.model.HourlyForecast
import app.northvane.core.model.WeatherAlert
import app.northvane.core.model.WeatherCondition
import app.northvane.core.time.*
import app.northvane.core.water.TideEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StormTimelineTest {
    private val now = Instant.fromEpochSeconds(1_794_009_600L) // on the hour
    private fun h(n: Int) = now.plusSeconds(n * 3600L)

    private fun alert(event: String, category: AlertCategory, onset: Int, ends: Int?, severity: AlertSeverity = AlertSeverity.SEVERE, expires: Int? = null) =
        WeatherAlert(
            id = event, threadId = event, event = event, headline = null, description = null, instruction = null,
            severity = severity, urgency = null, certainty = null, category = category,
            onset = h(onset), ends = ends?.let(::h), expires = expires?.let(::h),
        )

    private fun hour(n: Int, gust: Double? = null, snow: Double? = null, temp: Double = -2.0, feels: Double? = null) = HourlyForecast(
        time = h(n), temperatureC = temp, condition = WeatherCondition.OVERCAST, isDaytime = true, shortForecast = null,
        precipitationChance = null, windSpeedKph = 10.0, windDirection = null, humidityPercent = null,
        windGustKph = gust, snowMm = snow, apparentTemperatureC = feels,
    )

    @Test
    fun hazardsFromEventNames() {
        assertEquals(Hazard.SNOW, Hazard.of("Winter Storm Warning"))
        assertEquals(Hazard.SNOW, Hazard.of("Blizzard Warning"))
        assertEquals(Hazard.ICE, Hazard.of("Ice Storm Warning"))
        assertEquals(Hazard.WIND, Hazard.of("High Wind Warning"))
        assertEquals(Hazard.COLD, Hazard.of("Wind Chill Advisory"))
        assertEquals(Hazard.COLD, Hazard.of("Extreme Cold Warning"))
        assertEquals(Hazard.COASTAL, Hazard.of("Coastal Flood Advisory"))
        assertEquals(Hazard.RAIN, Hazard.of("Flash Flood Warning"))
        assertEquals(Hazard.THUNDER, Hazard.of("Severe Thunderstorm Watch"))
        assertEquals(Hazard.HEAT, Hazard.of("Heat Advisory"))
        assertEquals(Hazard.OTHER, Hazard.of("Dense Fog Advisory"))
    }

    @Test
    fun picksWarningsBeforeWatchesAndSkipsWhatsOver() {
        val over = alert("Wind Advisory", AlertCategory.ADVISORY, -10, -1)
        val watch = alert("Winter Storm Watch", AlertCategory.WATCH, 20, 40)
        val warning = alert("High Wind Warning", AlertCategory.WARNING, 2, 12)
        val statement = alert("Special Weather Statement", AlertCategory.STATEMENT, 0, 5)
        assertEquals(warning, StormTimeline.pick(listOf(over, watch, statement, warning), now))
        assertEquals(watch, StormTimeline.pick(listOf(over, watch, statement), now))
        assertNull(StormTimeline.pick(listOf(over, statement), now))
    }

    @Test
    fun worstHoursAndEasing() {
        val gusts = listOf(20.0, 25.0, 40.0, 70.0, 85.0, 90.0, 80.0, 50.0, 30.0, 20.0, 20.0, 20.0)
        val hourly = gusts.mapIndexed { i, g -> hour(i, gust = g) }
        val t = StormTimeline.build(alert("High Wind Warning", AlertCategory.WARNING, 2, 11), hourly, emptyList(), now)
        val worst = assertNotNull(t.worst)
        assertEquals(90.0, worst.value)
        assertEquals(h(4), worst.from) // 85 and 80 are within a fifth of 90; 70 isn't
        assertEquals(h(6), worst.to)
        assertEquals(h(8), t.tapering) // first hour under 30% of the peak
        assertTrue(t.hours.first().time <= h(0))
    }

    @Test
    fun coldIsWorstAtTheLowest() {
        val feels = listOf(-10.0, -18.0, -25.0, -27.0, -24.0, -15.0)
        val hourly = feels.mapIndexed { i, f -> hour(i, feels = f) }
        val t = StormTimeline.build(alert("Wind Chill Warning", AlertCategory.WARNING, 0, 6), hourly, emptyList(), now)
        val worst = assertNotNull(t.worst)
        assertEquals(-27.0, worst.value)
        assertEquals(h(2), worst.from)
        assertEquals(h(4), worst.to)
        assertNull(t.tapering)
    }

    @Test
    fun coastalFloodingUsesTheHighTides() {
        val tides = listOf(TideEvent(h(3), true, 3.4), TideEvent(h(9), false, 0.3), TideEvent(h(15), true, 3.9), TideEvent(h(40), true, 3.1))
        val t = StormTimeline.build(alert("Coastal Flood Warning", AlertCategory.WARNING, 1, 18), emptyList(), tides, now)
        assertEquals(listOf(h(3), h(15)), t.hours.map { it.time })
        assertEquals(h(15), t.worst!!.at)
    }

    @Test
    fun anAlertWithoutAnEndShowsItsExpiry() {
        val t = StormTimeline.build(alert("Winter Storm Warning", AlertCategory.WARNING, 1, null, expires = 9), listOf(hour(2, snow = 5.0)), emptyList(), now)
        assertEquals(h(9), t.end)
        assertTrue(t.endIsExpiry)
    }
}
