package app.northvane.core

import app.northvane.core.model.DailyForecast
import app.northvane.core.model.DetailSource
import app.northvane.core.model.HourlyForecast
import app.northvane.core.model.WeatherCondition
import app.northvane.core.nws.ForecastDetails
import app.northvane.core.nws.GridSeries
import app.northvane.core.nws.NwsGridData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import app.northvane.core.time.*

class ForecastDetailsTest {
    private val eastern = ZoneId.of("America/New_York")
    private fun live() = NwsGridData.parse(requireNotNull(javaClass.getResource("/live/nws_grid_gyx.json")).readText())

    private fun day(date: LocalDate, detail: String? = null, night: String? = null, wind: String? = null) = DailyForecast(
        date = date, name = date.dayOfWeek.name, highC = 10.0, lowC = 2.0, condition = WeatherCondition.CLEAR,
        isDaytimeCondition = true, shortForecast = null, precipitationChance = null,
        dayDetail = detail, nightDetail = night, windSummary = wind,
    )

    @Test
    fun parsesValidTimeIntervals() {
        val (start, end) = NwsGridData.parseValidTime("2026-10-29T04:00:00+00:00/P1DT2H")!!
        assertEquals(Instant.parse("2026-10-29T04:00:00Z"), start)
        assertEquals(Instant.parse("2026-10-30T06:00:00Z"), end)
        assertNull(NwsGridData.parseValidTime("garbage"))
    }

    @Test
    fun accumulationsAreSplitByOverlap() {
        val t0 = Instant.parse("2026-10-29T00:00:00Z")
        val series = GridSeries(listOf(GridSeries.Entry(t0, t0.plusSeconds(6 * 3600), 12.0)))
        assertEquals(2.0, series.sum(t0, t0.plusSeconds(3600))!!, 1e-9)
        assertEquals(6.0, series.sum(t0.plusSeconds(3 * 3600), t0.plusSeconds(9 * 3600))!!, 1e-9)
        assertNull(series.sum(t0.minusSeconds(7200), t0))
    }

    @Test
    fun parsesLiveGridpointResponse() {
        val grid = live()
        val t = Instant.parse("2026-10-29T16:00:00Z")
        assertNotNull(grid.temperature.at(t))
        assertNotNull(grid.windGust.at(t))
        assertNotNull(grid.skyCover.at(t))
        assertEquals(0.0, grid.precipitation.sum(t, t.plusSeconds(86400))!!, 1e-9)
        assertTrue(grid.windGust.entries.all { it.value in 0.0..200.0 }, "gusts are km/h")
    }

    @Test
    fun enrichesHourlyAndDailyFromLiveGrid() {
        val grid = live()
        val t = Instant.parse("2026-10-29T18:00:00Z")
        val hour = HourlyForecast(t, 12.0, WeatherCondition.CLEAR, true, "Sunny", 5, 10.0, "SW", null)
        val enriched = ForecastDetails.enrichHourly(listOf(hour), grid).single()
        assertNotNull(enriched.windGustKph)
        assertNotNull(enriched.windDirectionDeg)
        assertNotNull(enriched.skyCoverPercent)
        assertNotNull(enriched.humidityPercent, "filled from grid when the hourly forecast lacks it")
        assertEquals(0.0, enriched.precipitationMm!!, 1e-9)

        val d = ForecastDetails.enrichDaily(listOf(day(LocalDate.of(2026, 10, 30))), grid, eastern).single()
        assertEquals(DetailSource.GRID, d.detailSource)
        assertNotNull(d.gustMaxKph)
        assertTrue(d.gustMaxKph >= (d.windMaxKph ?: 0.0))
        assertNotNull(d.humidityMin)
        assertNotNull(d.skyCoverPercent)
        // A day beyond the grid's range is left alone.
        assertNull(ForecastDetails.enrichDaily(listOf(day(LocalDate.of(2026, 11, 20))), grid, eastern).single().detailSource)
    }

    @Test
    fun readsNumbersFromForecastTextAsFallback() {
        val stormy = day(
            LocalDate.of(2026, 12, 5),
            detail = "Snow. High near 31. Northeast wind 15 to 25 mph, with gusts as high as 45 mph. " +
                "New snow accumulation of 6 to 10 inches possible.",
            night = "Snow and freezing rain. New ice accumulation of around 0.1 of an inch possible. " +
                "New snow accumulation of around one inch possible.",
            wind = "NE 15 to 25 mph",
        )
        val d = ForecastDetails.enrichFromText(listOf(stormy)).single()
        assertEquals(DetailSource.TEXT, d.detailSource)
        assertEquals(72.4, d.gustMaxKph!!, 0.1) // 45 mph
        assertEquals(40.2, d.windMaxKph!!, 0.1) // 25 mph
        assertEquals(279.4, d.snowMm!!, 0.1) // 10 + 1 inches
        assertEquals(2.54, d.iceMm!!, 0.01)

        val rain = day(LocalDate.of(2026, 7, 5), detail = "Showers likely. New rainfall amounts between a quarter and half of an inch possible.")
        assertEquals(12.7, ForecastDetails.enrichFromText(listOf(rain)).single().precipitationMm!!, 0.01)

        val quiet = day(LocalDate.of(2026, 7, 6), detail = "Sunny, with a high near 80.")
        assertNull(ForecastDetails.enrichFromText(listOf(quiet)).single().detailSource)
    }
}
