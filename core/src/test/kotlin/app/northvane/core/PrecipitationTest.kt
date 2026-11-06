package app.northvane.core

import app.northvane.core.model.AlertCategory
import app.northvane.core.model.AlertSeverity
import app.northvane.core.model.DailyForecast
import app.northvane.core.model.HourlyForecast
import app.northvane.core.model.WeatherAlert
import app.northvane.core.model.WeatherCondition
import app.northvane.core.outlook.BriefingWriter
import app.northvane.core.outlook.MinutePrecip
import app.northvane.core.outlook.NextRain
import app.northvane.core.outlook.PrecipKind
import app.northvane.core.outlook.RainSoonDetector
import app.northvane.core.outlook.StormTotals
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PrecipitationTest {
    private val zone = ZoneId.of("America/New_York")
    private val now = Instant.parse("2026-11-06T12:00:00Z") // 7 AM in New York

    private fun hour(
        offset: Int,
        chance: Int = 0,
        condition: WeatherCondition = WeatherCondition.CLEAR,
        rainMm: Double? = null,
        snowMm: Double? = null,
        iceMm: Double? = null,
        gustKph: Double? = null,
    ) = HourlyForecast(
        now.plus(Duration.ofHours(offset.toLong())), 5.0, condition, true, condition.label, chance, 10.0, "N", 80.0,
        precipitationMm = rainMm, snowMm = snowMm, iceMm = iceMm, windGustKph = gustKph,
    )

    @Test
    fun findsTheNextRainWindow() {
        val hours = (0 until 24).map { h -> if (h in 6..9) hour(h, chance = 70, condition = WeatherCondition.RAIN) else hour(h, chance = 10) }
        val r = assertNotNull(NextRain.find(hours, now))
        assertEquals(now.plus(Duration.ofHours(6)), r.start)
        assertEquals(now.plus(Duration.ofHours(10)), r.end)
        assertEquals(70, r.chance)
        assertEquals(PrecipKind.RAIN, r.kind)
        assertFalse(r.ongoing)
        assertNull(NextRain.find((0 until 24).map { hour(it, chance = 20) }, now))
    }

    @Test
    fun rainSoonOnlyWhenItsDryNow() {
        fun step(min: Long, mm: Double, snowCm: Double = 0.0) = MinutePrecip(now.plus(Duration.ofMinutes(min)), mm, snowCm)
        val dryThenRain = listOf(step(0, 0.0), step(15, 0.0), step(30, 0.4), step(45, 3.0))
        val soon = assertNotNull(RainSoonDetector.detect(dryThenRain, now))
        assertEquals(now.plus(Duration.ofMinutes(30)), soon.start)
        assertEquals(PrecipKind.RAIN, soon.kind)
        assertTrue(soon.heavy)
        // Already raining: nothing to announce.
        assertNull(RainSoonDetector.detect(listOf(step(0, 0.5), step(15, 0.5)), now))
        // Snow: snowfall (cm) about matching its water (mm).
        assertEquals(PrecipKind.SNOW, RainSoonDetector.detect(listOf(step(0, 0.0), step(15, 0.3, 0.4)), now)?.kind)
        // Beyond the lead time: not yet.
        assertNull(RainSoonDetector.detect(listOf(step(0, 0.0), step(90, 1.0)), now))
        // One notification per event.
        assertFalse(RainSoonDetector.isNew(now.plus(Duration.ofMinutes(30)), now))
        assertTrue(RainSoonDetector.isNew(now.plus(Duration.ofHours(4)), now))
    }

    @Test
    fun findsANoreasterWithTotalsAndPeak() {
        val hours = (0 until 48).map { h ->
            when (h) {
                in 10..15 -> hour(h, 90, WeatherCondition.SNOW, rainMm = 2.0, snowMm = 20.0)
                in 16..19 -> hour(h, 90, WeatherCondition.SNOW, rainMm = 1.0, snowMm = 8.0)
                in 22..23 -> hour(h, 60, WeatherCondition.SNOW, rainMm = 0.3, snowMm = 3.0) // after a short lull
                else -> hour(h, 10)
            }
        }
        val s = assertNotNull(StormTotals.find(hours, emptyList(), emptyList(), now, zone))
        assertEquals(PrecipKind.SNOW, s.kind)
        assertEquals("Major snowstorm", s.title)
        assertEquals(20.0 * 6 + 8.0 * 4 + 3.0 * 2, s.snowMm, 0.01)
        assertEquals(now.plus(Duration.ofHours(10)), s.start)
        assertEquals(now.plus(Duration.ofHours(24)), s.end)
        assertEquals(now.plus(Duration.ofHours(10)), s.peakStart)
        assertEquals(listOf(LocalDate.of(2026, 11, 6), LocalDate.of(2026, 11, 7)), s.days)
    }

    @Test
    fun usesTheStormAlertAndIgnoresLightRain() {
        val alert = WeatherAlert(
            id = "a", threadId = "a", event = "Winter Storm Warning", headline = null, description = null, instruction = null,
            severity = AlertSeverity.SEVERE, urgency = null, certainty = null, category = AlertCategory.WARNING,
            ends = now.plus(Duration.ofDays(1)), expires = now.plus(Duration.ofDays(1)),
        )
        val icy = (0 until 12).map { h -> if (h in 2..8) hour(h, 80, WeatherCondition.FREEZING_RAIN, rainMm = 1.5, iceMm = 1.2) else hour(h) }
        val s = assertNotNull(StormTotals.find(icy, emptyList(), listOf(alert), now, zone))
        assertEquals("Ice storm", s.title)
        assertEquals("Winter Storm Warning", s.alert)
        // A wet day under an inch isn't a storm.
        assertNull(StormTotals.find((0 until 24).map { hour(it, 60, WeatherCondition.RAIN, rainMm = 0.5) }, emptyList(), emptyList(), now, zone))
    }

    @Test
    fun fallsBackToDailyTotals() {
        fun day(offset: Long, rain: Double, snow: Double) = DailyForecast(
            LocalDate.of(2026, 11, 6).plusDays(offset), "d", 2.0, -3.0, WeatherCondition.SNOW, true, null, 80,
            precipitationMm = rain, snowMm = snow,
        )
        val s = assertNotNull(StormTotals.find(emptyList(), listOf(day(0, 0.0, 0.0), day(1, 12.0, 120.0), day(2, 4.0, 40.0)), emptyList(), now, zone))
        assertEquals(listOf(LocalDate.of(2026, 11, 7), LocalDate.of(2026, 11, 8)), s.days)
        assertEquals(160.0, s.snowMm, 0.01)
        assertNull(s.start)
    }

    @Test
    fun writesTheMorningBriefing() {
        val today = DailyForecast(LocalDate.of(2026, 11, 6), "Today", 11.0, 3.0, WeatherCondition.RAIN, true, "Rain Likely", 70)
        val hours = (0 until 18).map { h -> if (h in 6..9) hour(h, 70, WeatherCondition.RAIN, gustKph = 60.0) else hour(h, 10) }
        val b = BriefingWriter.write(
            "Portland", listOf(today), hours, emptyList(), zone, now,
            temperature = { c -> "${c?.toInt()}°" }, wind = { k -> "${k?.toInt()} km/h" }, hour = { t -> t.atZone(zone).hour.toString() },
        )
        assertEquals("Portland today: 11°", b.title)
        assertEquals("Rain Likely, high 11°, low 3°. Rain likely from 13 to 17 (70%). Gusts up to 60 km/h.", b.text)
        // Quiet day.
        val quiet = BriefingWriter.write(
            "Portland", listOf(today.copy(shortForecast = "Sunny")), (0 until 18).map { hour(it) }, emptyList(), zone, now,
            temperature = { c -> "${c?.toInt()}°" }, wind = { "" }, hour = { "" },
        )
        assertEquals("Sunny, high 11°, low 3°. Staying dry.", quiet.text)
    }
}
