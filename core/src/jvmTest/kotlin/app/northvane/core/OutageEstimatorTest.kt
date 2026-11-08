package app.northvane.core

import app.northvane.core.model.AlertCategory
import app.northvane.core.model.AlertSeverity
import app.northvane.core.model.DailyForecast
import app.northvane.core.model.OutageFactor.Kind
import app.northvane.core.model.OutageLevel
import app.northvane.core.model.Units
import app.northvane.core.model.WeatherAlert
import app.northvane.core.model.WeatherCondition
import app.northvane.core.outage.OutageEstimator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import app.northvane.core.time.*

class OutageEstimatorTest {
    private val zone = ZoneId.of("America/New_York")

    private fun day(
        date: String, highF: Double = 55.0, lowF: Double = 40.0, gustMph: Double? = null, windMph: Double? = null,
        rainIn: Double? = null, snowIn: Double? = null, iceIn: Double? = null, thunder: Int? = null,
    ) = DailyForecast(
        date = LocalDate.parse(date), name = "Day", highC = Units.fToC(highF), lowC = Units.fToC(lowF),
        condition = WeatherCondition.CLEAR, isDaytimeCondition = true, shortForecast = null, precipitationChance = null,
        gustMaxKph = gustMph?.let(Units::mphToKph), windMaxKph = windMph?.let(Units::mphToKph),
        precipitationMm = rainIn?.let(Units::inchesToMm), snowMm = snowIn?.let(Units::inchesToMm),
        iceMm = iceIn?.let(Units::inchesToMm), thunderChance = thunder,
    )

    private fun risk(d: DailyForecast, alerts: List<WeatherAlert> = emptyList()) = OutageEstimator.estimate(d, alerts, zone)

    @Test
    fun calmDayIsLow() = assertEquals(OutageLevel.LOW, risk(day("2026-07-10", gustMph = 15.0)).level)

    @Test
    fun sameGustsHurtMoreWithLeavesOn() {
        val october = risk(day("2026-10-15", gustMph = 45.0))
        val january = risk(day("2027-01-15", gustMph = 45.0))
        assertTrue(october.score > january.score + 15, "leaf-on ${october.score} vs leaf-off ${january.score}")
        assertEquals(OutageLevel.HIGH, october.level)
        assertTrue(october.factors.any { it.kind == Kind.LEAVES_ON })
    }

    @Test
    fun damagingWindIsVeryHigh() = assertEquals(OutageLevel.VERY_HIGH, risk(day("2026-11-03", gustMph = 65.0)).level)

    @Test
    fun iceStormThresholds() {
        assertEquals(OutageLevel.ELEVATED, risk(day("2027-01-10", highF = 31.0, lowF = 25.0, iceIn = 0.1)).level)
        assertEquals(OutageLevel.HIGH, risk(day("2027-01-10", highF = 31.0, lowF = 25.0, iceIn = 0.3)).level)
        val major = risk(day("2027-01-10", highF = 31.0, lowF = 25.0, iceIn = 0.5, gustMph = 30.0))
        assertEquals(OutageLevel.VERY_HIGH, major.level)
        assertEquals(Kind.ICE, major.factors.first().kind)
    }

    @Test
    fun wetSnowOutweighsDrySnow() {
        val wet = risk(day("2027-03-14", highF = 33.0, lowF = 30.0, snowIn = 10.0, rainIn = 1.4))
        val dry = risk(day("2027-01-20", highF = 18.0, lowF = 5.0, snowIn = 10.0, rainIn = 0.5))
        assertEquals(Kind.WET_SNOW, wet.factors.first().kind)
        assertEquals(Kind.SNOW, dry.factors.first().kind)
        assertTrue(wet.level >= OutageLevel.HIGH, "wet ${wet.level}")
        assertTrue(dry.score < wet.score)
    }

    @Test
    fun earlySeasonSnowOnLeavesIsSevere() {
        // Like the late-October 2011 nor'easter: wet snow on trees still in leaf.
        val oct = risk(day("2026-10-29", highF = 34.0, lowF = 30.0, snowIn = 8.0))
        assertEquals(OutageLevel.VERY_HIGH, oct.level)
    }

    @Test
    fun saturatedGroundAndThunderAddUp() {
        val r = risk(day("2026-08-12", gustMph = 38.0, rainIn = 2.0, thunder = 60))
        assertTrue(r.factors.any { it.kind == Kind.SATURATED_GROUND })
        assertTrue(r.factors.any { it.kind == Kind.THUNDER })
        assertTrue(r.level >= OutageLevel.HIGH, "${r.score}")
    }

    @Test
    fun hazardAlertsSetAFloor() {
        val alert = WeatherAlert(
            id = "a", threadId = "a", event = "High Wind Warning", headline = null, description = null, instruction = null,
            severity = AlertSeverity.SEVERE, urgency = null, certainty = null, category = AlertCategory.WARNING,
            onset = Instant.parse("2027-02-02T15:00:00Z"), ends = Instant.parse("2027-02-03T03:00:00Z"),
        )
        val r = risk(day("2027-02-02", gustMph = 20.0), listOf(alert))
        assertEquals(OutageLevel.HIGH, r.level)
        assertEquals("High Wind Warning", r.factors.first().detail)
        // An alert for another day doesn't count.
        assertEquals(OutageLevel.LOW, risk(day("2027-02-05", gustMph = 20.0), listOf(alert)).level)
    }

    @Test
    fun foliageCalendar() {
        assertEquals(0.0, OutageEstimator.foliage(LocalDate.of(2027, 2, 1)))
        assertEquals(1.0, OutageEstimator.foliage(LocalDate.of(2026, 7, 1)))
        assertTrue(OutageEstimator.foliage(LocalDate.of(2026, 10, 30)) in 0.1..0.9)
    }
}
