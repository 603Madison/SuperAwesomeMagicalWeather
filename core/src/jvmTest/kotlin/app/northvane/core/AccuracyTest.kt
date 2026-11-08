package app.northvane.core

import app.northvane.core.model.DailyForecast
import app.northvane.core.model.ObservedDay
import app.northvane.core.model.UnitSystem
import app.northvane.core.model.WeatherCondition
import app.northvane.core.model.WeatherHistory
import app.northvane.core.model.WeatherSnapshot
import app.northvane.core.outlook.ForecastAccuracy
import app.northvane.core.outlook.ForecastRecord
import app.northvane.core.util.UnitFormatter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import app.northvane.core.time.*

class AccuracyTest {
    private val today = LocalDate.of(2026, 11, 6)
    private val zone = ZoneId.of("America/New_York")

    @Test
    fun recordsTomorrowAndScoresThePast() {
        val snap = WeatherSnapshot(
            locationId = "p", fetchedAt = kotlin.time.Clock.System.now(), timeZoneId = zone.id,
            daily = listOf(DailyForecast(today.plusDays(1), "Sat", 12.0, 2.0, WeatherCondition.CLEAR, true, null, 20)),
        )
        val log = ForecastAccuracy.record(emptyList(), snap, zone, today)
        assertEquals(listOf(ForecastRecord(today.plusDays(1), 12.0, 2.0, 20)), log)
        // A week of forecasts that ran 2 °C warm on highs, right on lows, and called rain right 5 of 6 days.
        val past = (1..6).map { d -> ForecastRecord(today.minusDays(d.toLong()), 12.0, 2.0, if (d <= 2) 80 else 10) }
        val history = WeatherHistory(
            days = (1..6).map { d -> ObservedDay(today.minusDays(d.toLong()), 10.0, 2.2, if (d <= 3) 5.0 else 0.0) },
            fetchedAt = kotlin.time.Clock.System.now(),
        )
        val s = assertNotNull(ForecastAccuracy.summarize(past, history, today))
        assertEquals(2.0, s.highBiasC!!, 0.01)
        assertEquals(5, s.rainRight)
        assertEquals(
            "Forecast, last 6 days: highs ran about 4° warm, lows on target; rain called right 5 of 6 days.",
            ForecastAccuracy.describe(s, UnitFormatter(UnitSystem.IMPERIAL), imperial = true),
        )
        // Too little to say anything yet.
        assertNull(ForecastAccuracy.summarize(past.take(2), history, today))
    }
}
