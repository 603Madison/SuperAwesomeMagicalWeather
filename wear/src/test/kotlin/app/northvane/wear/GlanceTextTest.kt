package app.northvane.wear

import app.northvane.core.model.UnitSystem
import app.northvane.core.time.*
import app.northvane.wear.glance.ComplicationTexts
import app.northvane.wear.glance.Glance
import app.northvane.wear.glance.TileTexts
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GlanceTextTest {
    private fun glance(
        forecast: app.northvane.wear.data.WearForecast? = Fixtures.forecast(),
        units: UnitSystem = UnitSystem.IMPERIAL,
        showRain: Boolean = true,
        now: Instant = Fixtures.now,
        place: app.northvane.core.model.SavedLocation = Fixtures.place,
    ) = Glance.of(place, forecast, units, showRain, now, is24 = false)

    @Test
    fun complicationShowsTemperatureAndNextHour() {
        val t = ComplicationTexts.of(glance(Fixtures.forecast(rain = Fixtures.rain(0.0, 0.0, 0.4, 0.8, 0.5, 0.0, 0.0, 0.0))))
        assertEquals("50°", t.shortText)
        assertNull(t.shortTitle)
        assertEquals("50° Partly cloudy", t.longTitle)
        assertEquals("Rain starting around 3:30", t.longText)
        assertEquals("Portland, Maine: 50°, Partly cloudy, Rain starting around 3:30", t.description)
    }

    @Test
    fun complicationInCelsius() = assertEquals("10°", ComplicationTexts.of(glance(units = UnitSystem.METRIC)).shortText)

    @Test
    fun warningBeatsEverythingOnTheComplication() {
        val t = ComplicationTexts.of(glance(Fixtures.forecast(alerts = listOf(Fixtures.warning))))
        assertEquals("Alert", t.shortTitle)
        assertEquals("Winter Storm Warning", t.longText)
    }

    @Test
    fun withoutRainTheHeadlineFills() {
        val g = glance(showRain = false)
        assertNull(g.nextHour)
        // The same one-line headline as Mini: quiet weather gives a quiet line.
        assertEquals("Dry for the next 18 hours", g.headline)
        assertEquals("Dry for the next 18 hours", ComplicationTexts.of(g).longText)
    }

    @Test
    fun tileLines() {
        val t = TileTexts.of(glance(Fixtures.forecast(alerts = listOf(Fixtures.warning))))
        assertEquals("Portland, Maine", t.title)
        assertEquals("50°", t.temperature)
        assertEquals("Partly cloudy · H 52° L 36°", t.summary)
        assertEquals("Winter Storm Warning", t.alert)
        assertEquals("Dry for the next hour", t.nextHour)
        assertNull(t.updated)
    }

    @Test
    fun oldForecastSaysWhenItWasUpdatedAndUsesTheHourlyTemperature() {
        val then = Fixtures.now.minus(Duration.ofHours(3))
        val g = glance(Fixtures.forecast(fetchedAt = then, rain = Fixtures.rain(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, start = then)))
        assertTrue(g.stale)
        // The observation is over two hours old, so this hour's forecast (9 °C) is shown.
        assertEquals("48°", g.temperature)
        val t = TileTexts.of(g)
        assertNull(t.nextHour)
        assertEquals("Updated 12:05 PM", t.updated)
    }

    @Test
    fun currentLocationTakesTheTownName() {
        val g = glance(place = Fixtures.place.copy(name = Glance.CURRENT_LOCATION, isDeviceLocation = true))
        assertEquals("Portland", g.place)
        assertTrue(g.isCurrentLocation)
    }

    @Test
    fun noForecastYet() {
        val g = glance(forecast = null)
        assertFalse(g.hasForecast)
        assertEquals("--", ComplicationTexts.of(g).shortText)
        assertEquals("Tap to update", TileTexts.of(g).summary)
        assertEquals("Tap to add a place", TileTexts.of(null).summary)
    }

    @Test
    fun expiredAlertsAreLeftOut() {
        val g = glance(Fixtures.forecast(alerts = listOf(Fixtures.warning.copy(ends = Fixtures.now.minus(Duration.ofMinutes(1))))))
        assertTrue(g.alerts.isEmpty())
    }
}
