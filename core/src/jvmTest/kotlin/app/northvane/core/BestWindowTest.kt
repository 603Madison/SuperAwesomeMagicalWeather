package app.northvane.core

import app.northvane.core.model.Activity
import app.northvane.core.model.HourlyForecast
import app.northvane.core.model.WeatherCondition
import app.northvane.core.outlook.BestWindow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import app.northvane.core.time.*

class BestWindowTest {
    private val zone = ZoneId.of("America/New_York")
    private val date = LocalDate.of(2026, 11, 7)
    private val midnight = date.atStartOfDay(zone).toInstant()

    /** 36 hours from midnight: rain in the morning, mild and dry 1–5 PM, cloudy evening clearing after 11 PM. */
    private val hours = (0 until 36).map { i ->
        HourlyForecast(
            time = midnight.plus(Duration.ofHours(i.toLong())), temperatureC = if (i in 12..17) 15.0 else 6.0,
            condition = WeatherCondition.CLEAR, isDaytime = i in 7..17, shortForecast = null,
            precipitationChance = if (i in 7..11) 80 else 5, windSpeedKph = 10.0, windDirection = "W", humidityPercent = 60.0,
            skyCoverPercent = if (i in 23..26) 5 else 90,
        )
    }

    @Test
    fun picksTheDryMildAfternoon() {
        val w = assertNotNull(BestWindow.find(Activity.HIKING, hours, date, zone, midnight))
        assertEquals(midnight.plus(Duration.ofHours(12)), w.start)
        assertEquals(midnight.plus(Duration.ofHours(15)), w.end)
        assertEquals(5, w.chance)
    }

    @Test
    fun stargazingWantsClearSky() {
        val w = assertNotNull(BestWindow.find(Activity.STARGAZING, hours, date, zone, midnight))
        assertEquals(midnight.plus(Duration.ofHours(23)), w.start)
        assertEquals(5, w.cloudPercent)
    }

    @Test
    fun noWindowWhenTheDayIsOverOrForCamping() {
        assertNull(BestWindow.find(Activity.HIKING, hours, date, zone, midnight.plus(Duration.ofHours(17))))
        assertNull(BestWindow.find(Activity.CAMPING, hours, date, zone, midnight))
        // Beyond the hourly forecast.
        assertNull(BestWindow.find(Activity.HIKING, hours, date.plusDays(3), zone, midnight))
    }
}
