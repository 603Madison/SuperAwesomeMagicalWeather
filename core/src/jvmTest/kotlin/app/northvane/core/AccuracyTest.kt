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
    @Test
    fun recordFillsInTheRainThatFell() {
        val log = listOf(
            ForecastRecord(today.minusDays(2), 10.0, 1.0, 70),
            ForecastRecord(today, 10.0, 1.0, 40),
        )
        val snap = WeatherSnapshot(
            locationId = "p", fetchedAt = kotlin.time.Clock.System.now(), timeZoneId = zone.id,
            current = app.northvane.core.model.CurrentConditions(observedAt = null, source = app.northvane.core.model.DataSource.WEEWX, rainTodayMm = 3.2),
            history = WeatherHistory(days = listOf(ObservedDay(today.minusDays(2), 9.0, 1.0, 0.0)), fetchedAt = kotlin.time.Clock.System.now()),
        )
        val updated = ForecastAccuracy.record(log, snap, zone, today)
        assertEquals(0.0, updated.first { it.date == today.minusDays(2) }.observedMm)
        assertEquals(3.2, updated.first { it.date == today }.stationMm)
        // A station's total never goes back down within the day.
        val later = ForecastAccuracy.record(updated, snap.copy(current = snap.current!!.copy(rainTodayMm = 1.0)), zone, today)
        assertEquals(3.2, later.first { it.date == today }.stationMm)
    }

    @Test
    fun rainCheckSaysHowTheChancesWorkOutHere() {
        // 20 days at 40%: it rained on only 2 of them. 12 days at 80%: rained on 11.
        val middle = (1..20).map { d -> ForecastRecord(today.minusDays(d.toLong()), null, null, 40, observedMm = if (d <= 2) 4.0 else 0.0) }
        val high = (21..32).map { d -> ForecastRecord(today.minusDays(d.toLong()), null, null, 80, observedMm = if (d <= 31) 6.0 else 0.1) }
        val report = assertNotNull(app.northvane.core.outlook.RainCheck.report(middle + high, today))
        assertEquals(today.minusDays(1), report.latest!!.date)
        val bandMiddle = report.bands.first { it.band == app.northvane.core.outlook.RainCheck.Band.MIDDLE }
        assertEquals(20, bandMiddle.days)
        assertEquals(10, bandMiddle.rainPercent)
        assertEquals("Around here lately, chances like that have meant rain about 1 day in 10.", report.note(40))
        // 80% chances rained 11 times in 12 (92%): close enough to the forecast to say nothing.
        assertNull(report.note(80))
        assertNull(report.note(10))
        assertNull(app.northvane.core.outlook.RainCheck.report(listOf(ForecastRecord(today, null, null, 30)), today))
    }

    @Test
    fun aStationBeatsTheGriddedAnalysis() {
        val r = ForecastRecord(today.minusDays(1), null, null, 60, observedMm = 0.0, stationMm = 2.0)
        val day = app.northvane.core.outlook.RainCheck.report(listOf(r), today)!!.latest!!
        assertEquals(true, day.rained)
        assertEquals(true, day.fromStation)
    }
}
