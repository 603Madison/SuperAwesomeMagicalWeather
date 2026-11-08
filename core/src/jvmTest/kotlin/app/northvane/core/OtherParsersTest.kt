package app.northvane.core

import app.northvane.core.model.DataSource
import app.northvane.core.model.WeatherCondition
import app.northvane.core.net.ParseException
import app.northvane.core.owm.OwmParsers
import app.northvane.core.weewx.WeeWxFormat
import app.northvane.core.weewx.WeeWxParsers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import app.northvane.core.time.*

class OtherParsersTest {
    private val chicago = ZoneId.of("America/Chicago")

    @Test
    fun weewxNorthvaneJson() {
        val c = WeeWxParsers.parse(WeeWx.northvaneJson, WeeWxFormat.AUTO, "Mine", chicago, null, NOW)
        assertEquals(DataSource.WEEWX, c.source)
        assertEquals("Backyard", c.stationName)
        assertEquals(10.5, c.temperatureC!!, 1e-9)
        assertEquals(9.0, c.feelsLikeC!!, 1e-9, "wind chill below temperature is used")
        assertEquals(1015.2, c.pressureHpa!!, 1e-9)
        assertEquals(Instant.ofEpochSecond(NOW.epochSecond - 120), c.observedAt)
    }

    @Test
    fun weewxRealtimeTxtConvertsDeclaredUnits() {
        assertEquals(WeeWxFormat.CUMULUS_REALTIME, WeeWxParsers.detect(WeeWx.realtime()))
        val c = WeeWxParsers.parse(WeeWx.realtime(), WeeWxFormat.AUTO, "Mine", chicago, null, NOW)
        assertEquals(11.28, c.temperatureC!!, 0.01) // 52.3°F
        assertEquals(8.05, c.windSpeedKph!!, 0.01) // 5 mph
        assertEquals(19.31, c.windGustKph!!, 0.01) // 12 mph 10-min gust
        assertEquals(1016.27, c.pressureHpa!!, 0.05) // 30.01 inHg
        assertEquals(1.27, c.rainTodayMm!!, 0.01) // 0.05 in
        assertEquals(225.0, c.windDirectionDeg!!, 1e-9)
        assertEquals(Instant.parse("2026-10-29T14:58:00Z"), c.observedAt) // 09:58 CDT
        assertEquals("Mine", c.stationName)
    }

    @Test
    fun weewxClientRaw() {
        val lastModified = Instant.parse("2026-10-29T14:59:00Z")
        val c = WeeWxParsers.parse(WeeWx.clientraw(), WeeWxFormat.AUTO, "Mine", chicago, lastModified, NOW)
        assertEquals("Backyard Station", c.stationName)
        assertEquals(7.96, c.windSpeedKph!!, 0.01) // 4.3 kt
        assertEquals(0.6, c.rainRateMmPerHour!!, 1e-9) // 0.01 mm/min
        assertEquals(4.2, c.dewpointC!!, 1e-9)
        assertEquals(lastModified, c.observedAt)
    }

    @Test
    fun weewxRejectsUnrecognizedJson() {
        assertFailsWith<ParseException> {
            WeeWxParsers.parse("""{"current":{}}""", WeeWxFormat.AUTO, "Mine", chicago, null, NOW)
        }
    }

    @Test
    fun weewxDerivesDewpointWhenMissing() {
        val json = """{"northvane_format":1,"time":${NOW.epochSecond},"outTemp_C":20.0,"outHumidity":50}"""
        val c = WeeWxParsers.parse(json, WeeWxFormat.NORTHVANE_JSON, "Mine", chicago, null, NOW)
        assertEquals(9.3, c.dewpointC!!, 0.1)
        assertNotNull(c.feelsLikeC)
    }

    @Test
    fun owmCurrent() {
        val c = OwmParsers.parseCurrent(Owm.current)
        assertEquals(DataSource.OWM, c.source)
        assertEquals(WeatherCondition.MOSTLY_CLOUDY, c.condition)
        assertEquals("Broken clouds", c.description)
        assertEquals(18.0, c.windSpeedKph!!, 1e-9)
        assertEquals(10.0, c.visibilityKm!!, 1e-9)
    }

    @Test
    fun owmForecastAggregatesDays() {
        val f = OwmParsers.parseForecast(Owm.forecast)
        assertEquals(4, f.hourly.size)
        assertFalse(f.hourly[1].isDaytime)
        assertEquals(65, f.hourly[0].precipitationChance)
        assertEquals(2, f.daily.size)
        val first = f.daily.first { it.date == LocalDate.of(2026, 10, 29) }
        assertEquals(15.0, first.highC!!, 1e-9)
        assertEquals(9.0, first.lowC!!, 1e-9)
        assertEquals(WeatherCondition.RAIN, first.condition)
        assertEquals(65, first.precipitationChance)
    }
}
