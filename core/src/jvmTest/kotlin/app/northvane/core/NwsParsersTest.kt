package app.northvane.core

import app.northvane.core.model.AlertCategory
import app.northvane.core.model.AlertSeverity
import app.northvane.core.model.WeatherCondition
import app.northvane.core.nws.NwsIcons
import app.northvane.core.nws.NwsParsers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import app.northvane.core.time.*

class NwsParsersTest {
    private val chicago = ZoneId.of("America/Chicago")

    @Test
    fun parsesPointMetadata() {
        val p = NwsParsers.parsePoint(Nws.point)
        assertEquals("TOP", p.gridId)
        assertEquals("${Nws.BASE}/gridpoints/TOP/32,81/forecast/hourly", p.hourlyUrl)
        assertEquals("America/Chicago", p.timeZone)
        assertEquals("Linn, KS", p.placeName)
        assertEquals("KTWX", p.radarStation)
    }

    @Test
    fun parsesStationsInOrder() {
        val stations = NwsParsers.parseStations(Nws.stations)
        assertEquals(listOf("KMYZ", "KCNK"), stations.map { it.id })
    }

    @Test
    fun parsesObservationWithUnitConversion() {
        val c = NwsParsers.parseObservation(Nws.observation(), "fallback")
        assertEquals(12.2, c.temperatureC!!, 1e-9)
        assertEquals(1016.6, c.pressureHpa!!, 1e-6)
        assertEquals(16.09, c.visibilityKm!!, 1e-6)
        assertEquals(18.36, c.windSpeedKph!!, 1e-9)
        assertEquals(200.0, c.windDirectionDeg!!, 1e-9)
        assertNull(c.windGustKph)
        assertEquals(10.1, c.feelsLikeC!!, 1e-9, "wind chill is used as feels-like")
        assertEquals(WeatherCondition.PARTLY_CLOUDY, c.condition)
        assertTrue(c.isDaytime)
        assertEquals("Marysville Municipal Airport", c.stationName)
    }

    @Test
    fun parsesHourlyForecast() {
        val hours = NwsParsers.parseHourly(Nws.hourly)
        assertEquals(3, hours.size)
        assertEquals(12.222, hours[0].temperatureC!!, 0.01) // 54°F
        assertEquals(3, hours[0].precipitationChance)
        assertEquals(19.31, hours[0].windSpeedKph!!, 0.01) // 12 mph
        assertEquals(56.0, hours[0].humidityPercent!!, 1e-9)
        assertEquals(WeatherCondition.SHOWERS, hours[1].condition)
        assertEquals(WeatherCondition.THUNDERSTORM, hours[2].condition)
    }

    @Test
    fun pairsDayAndNightPeriodsIntoDays() {
        val days = NwsParsers.parseDaily(Nws.daily, chicago)
        assertEquals(2, days.size)
        val today = days[0]
        assertEquals(LocalDate.of(2026, 10, 29), today.date)
        assertEquals("Today", today.name)
        assertEquals(16.11, today.highC!!, 0.01) // 61°F
        assertEquals(6.67, today.lowC!!, 0.01) // 44°F
        assertEquals(60, today.precipitationChance, "max of day and night chance")
        assertEquals(WeatherCondition.THUNDERSTORM, today.condition, "more significant of sct/tsra_hi")
        assertEquals("S 10 to 15 mph", today.windSummary)
        assertNull(days[1].precipitationChance)
    }

    @Test
    fun overnightPeriodBelongsToPreviousEvening() {
        val days = NwsParsers.parseDaily(Nws.dailyStartingOvernight, chicago)
        assertEquals(LocalDate.of(2026, 10, 28), days[0].date)
        assertNull(days[0].highC)
        assertNotNull(days[0].lowC)
        assertEquals(LocalDate.of(2026, 10, 29), days[1].date)
        assertNotNull(days[1].highC)
        assertNotNull(days[1].lowC)
    }

    @Test
    fun parsesAlertsSortedAndFiltered() {
        val alerts = NwsParsers.parseAlerts(Nws.alerts)
        assertEquals(
            listOf("Severe Thunderstorm Warning", "Tornado Watch", "Wind Advisory"),
            alerts.map { it.event },
            "test messages are dropped; warnings before watches before advisories",
        )
        val warning = alerts[0]
        assertEquals(AlertCategory.WARNING, warning.category)
        assertEquals(AlertSeverity.SEVERE, warning.severity)
        assertEquals("urn:oid:2.49.0.1.840.0.aaa.0", warning.threadId, "thread is the oldest referenced message")
        assertTrue(warning.isUpdate)
        assertEquals("urn:oid:2.49.0.1.840.0.bbb", alerts[2].threadId)
        assertFalse(alerts[2].isUpdate)
    }

    @Test
    fun iconParsingHandlesCompoundAndNight() {
        val parsed = NwsIcons.parse("https://api.weather.gov/icons/land/night/rain_showers,30/tsra,60?size=medium")!!
        assertEquals(WeatherCondition.THUNDERSTORM, parsed.condition)
        assertFalse(parsed.isDaytime)
        assertEquals(WeatherCondition.WINDY, NwsIcons.parse("https://api.weather.gov/icons/land/day/wind_bkn")!!.condition)
        assertNull(NwsIcons.parse(null))
        assertNull(NwsIcons.parse("https://example.com/nope.png"))
    }

    @Test
    fun textFallbackRecognizesCommonPhrases() {
        assertEquals(WeatherCondition.RAIN, WeatherCondition.fromText("Light Rain and Fog/Mist"))
        assertEquals(WeatherCondition.FOG, WeatherCondition.fromText("Fog/Mist"))
        assertEquals(WeatherCondition.MOSTLY_CLOUDY, WeatherCondition.fromText("Mostly Cloudy"))
        assertEquals(WeatherCondition.OVERCAST, WeatherCondition.fromText("Cloudy"))
        assertEquals(WeatherCondition.CLEAR, WeatherCondition.fromText("Sunny"))
        assertEquals(WeatherCondition.RAIN_SNOW, WeatherCondition.fromText("Rain and Snow Likely"))
        assertEquals(WeatherCondition.UNKNOWN, WeatherCondition.fromText(""))
    }
}
