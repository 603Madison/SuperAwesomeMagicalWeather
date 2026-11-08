package app.northvane.core

import app.northvane.core.model.AlertCategory
import app.northvane.core.model.GeoPoint
import app.northvane.core.net.HttpStatusException
import app.northvane.core.nws.NwsClient
import app.northvane.core.model.WeatherCondition
import app.northvane.core.nws.NwsParsers
import kotlinx.coroutines.test.runTest
import app.northvane.core.net.HttpEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import app.northvane.core.time.*

/** Parsers run against real api.weather.gov responses captured on 2026-10-29 (trimmed). */
class LiveResponsesTest {
    private fun resource(name: String): String =
        requireNotNull(javaClass.getResource("/live/$name")) { "missing $name" }.readText()

    @Test
    fun point() {
        val p = NwsParsers.parsePoint(resource("nws_point.json"))
        assertEquals("TOP", p.gridId)
        assertEquals("Linn, KS", p.placeName)
        assertTrue(p.hourlyUrl.startsWith("https://api.weather.gov/gridpoints/TOP/"))
    }

    @Test
    fun stations() {
        assertEquals("KMYZ", NwsParsers.parseStations(resource("nws_stations.json")).first().id)
    }

    @Test
    fun observation() {
        val c = NwsParsers.parseObservation(resource("nws_observation.json"), null)
        assertEquals(13.0, c.temperatureC!!, 1e-9)
        assertEquals(WeatherCondition.FOG, c.condition)
        assertEquals("Fog/Mist", c.description)
        assertEquals(4.83, c.visibilityKm!!, 1e-9)
        assertEquals(1021.0, c.pressureHpa!!, 1e-9)
        assertNotNull(c.observedAt)
    }

    @Test
    fun forecasts() {
        val hourly = NwsParsers.parseHourly(resource("nws_hourly.json"))
        assertEquals(12, hourly.size)
        assertTrue(hourly.all { it.temperatureC != null && it.humidityPercent != null && it.dewpointC != null })
        val daily = NwsParsers.parseDaily(resource("nws_daily.json"), ZoneId.of("America/Chicago"))
        assertTrue(daily.size in 7..8, "got ${daily.size} days")
        assertEquals("Today", daily.first().name)
        assertNotNull(daily.first().highC)
        assertTrue(daily.drop(1).dropLast(1).all { it.highC != null && it.lowC != null })
    }

    @Test
    fun alerts() {
        val alerts = NwsParsers.parseAlerts(resource("nws_alerts_tx.json"))
        assertEquals(4, alerts.size)
        assertEquals(AlertCategory.WATCH, alerts.first().category)
        assertTrue(alerts.any { it.category == AlertCategory.ADVISORY })
        // Live data has messages that expire well before the event ends; "until" must use ends.
        assertTrue(alerts.any { a -> a.ends != null && a.expires != null && a.ends.isAfter(a.expires) })
        assertTrue(alerts.filter { it.isUpdate }.all { it.threadId != it.id })
    }

    @Test
    fun alertsOutsideCoverageAreA4xx() = runTest {
        val fake = FakeNetwork().apply { on("/alerts/active", code = 400, body = resource("nws_alerts_out_of_bounds.json")) }
        val client = NwsClient(okhttp3.OkHttpClient.Builder().addInterceptor(fake).build(), { "test" })
        val error = assertFailsWith<HttpStatusException> { client.alerts(GeoPoint(51.5085, -0.1257)) }
        assertEquals(400, error.code)
    }
}
