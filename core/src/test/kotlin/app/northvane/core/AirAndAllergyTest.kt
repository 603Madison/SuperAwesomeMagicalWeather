package app.northvane.core

import app.northvane.core.air.AirNowClient
import app.northvane.core.air.EpaUvClient
import app.northvane.core.air.OpenMeteoAirClient
import app.northvane.core.history.AcisClient
import app.northvane.core.model.Activity
import app.northvane.core.model.AirQuality
import app.northvane.core.model.AirSource
import app.northvane.core.model.Allergen
import app.northvane.core.model.AllergyLevel
import app.northvane.core.model.AqiCategory
import app.northvane.core.model.AqiDay
import app.northvane.core.model.AqiReading
import app.northvane.core.model.DailyForecast
import app.northvane.core.model.MudLevel
import app.northvane.core.model.MudOutlook
import app.northvane.core.model.ObservedDay
import app.northvane.core.model.PeriodStats
import app.northvane.core.model.PollenForecast
import app.northvane.core.model.Sensitivity
import app.northvane.core.model.Tip
import app.northvane.core.model.Units
import app.northvane.core.model.UvLevel
import app.northvane.core.model.WeatherCondition
import app.northvane.core.model.WeatherHistory
import app.northvane.core.outlook.ActivityScorer
import app.northvane.core.outlook.AllergyEstimator
import app.northvane.core.outlook.WaterIce
import app.northvane.core.pollen.PollenClient
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AirAndAllergyTest {
    private val zone = ZoneId.of("America/New_York")
    private fun resource(name: String) = requireNotNull(javaClass.getResource(name)) { "missing $name" }.readText()

    // --- UV ---

    @Test
    fun parsesEpaUvForecast() {
        val hours = EpaUvClient.parseHourly(resource("/air/epa_uv_hourly.json"), zone)
        assertTrue(hours.size >= 10)
        assertEquals(Instant.parse("2026-10-29T11:00:00Z"), hours.first().time) // 7 AM EDT
        val (max, alert) = assertNotNull(EpaUvClient.parseDaily(resource("/air/epa_uv_daily.json")))
        assertEquals(3.0, max)
        assertFalse(alert)
        assertEquals(UvLevel.MODERATE, UvLevel.of(max))
        assertEquals(UvLevel.LOW, UvLevel.of(2.0))
        assertEquals(UvLevel.EXTREME, UvLevel.of(11.0))
    }

    // --- AQI ---

    @Test
    fun parsesAirNow() {
        val current = assertNotNull(AirNowClient.parseCurrent(resource("/air/airnow_current.json"), zone))
        assertEquals(54, current.aqi)
        assertEquals("PM2.5", current.pollutant)
        assertEquals(AqiCategory.MODERATE, current.category)
        assertEquals("Portland, ME", current.area)
        // AirNow gives the hour in local standard time (EST here, although Maine was on EDT).
        assertEquals(Instant.parse("2026-10-29T19:00:00Z"), current.observedAt)
        val days = AirNowClient.parseForecast(resource("/air/airnow_forecast.json"))
        assertEquals(2, days.size)
        assertEquals(48, days[0].aqi)
        assertNull(days[1].aqi)
        assertEquals(AqiCategory.SENSITIVE, days[1].category)
        assertTrue(days[1].actionDay)
        assertEquals("Clean air continues.", days[0].discussion)
    }

    @Test
    fun parsesOpenMeteoAirQuality() {
        val (air, uv) = OpenMeteoAirClient.parse(resource("/air/openmeteo_aq.json"), Instant.parse("2026-10-29T18:00:00Z"))
        assertEquals(AirSource.OPEN_METEO, air.source)
        assertEquals(32, air.current!!.aqi)
        assertEquals("PM2.5", air.current!!.pollutant)
        assertTrue(air.days.size in 5..6)
        assertTrue(uv.hourly.size in 20..24)
        assertNotNull(uv.todayMax)
    }

    // --- Allergies ---

    private fun day(date: String, highF: Double = 68.0, chance: Int = 10, rainMm: Double = 0.0, gustMph: Double = 10.0, humidity: Double = 55.0) =
        DailyForecast(
            LocalDate.parse(date), "Day", Units.fToC(highF), Units.fToC(highF - 20), WeatherCondition.CLEAR, true, null, chance,
            precipitationMm = rainMm, gustMaxKph = Units.mphToKph(gustMph), humidityMin = humidity - 10, humidityMax = humidity + 10,
            daytime = PeriodStats(precipChanceMax = chance, precipMm = rainMm, gustMaxKph = Units.mphToKph(gustMph), humidityMean = humidity),
        )

    private fun pollen(): PollenForecast = PollenForecast(PollenClient.parse(resource("/pollen/google_forecast.json")), Instant.now())

    @Test
    fun birchAllergyFollowsBirchPollenAndSensitivity() {
        val d = day("2027-05-12")
        val severe = AllergyEstimator.score(mapOf(Allergen.BIRCH to Sensitivity.SEVERE), d, pollen().days[0], null)
        val mild = AllergyEstimator.score(mapOf(Allergen.BIRCH to Sensitivity.MILD), d, pollen().days[0], null)
        assertEquals(80, severe.score) // birch index 4 × 20
        assertEquals(AllergyLevel.VERY_HIGH, severe.level)
        assertTrue(mild.score < severe.score)
        val kinds = severe.tips.map { it.kind }
        assertTrue(Tip.Kind.ALLERGY_MEDICATE in kinds && Tip.Kind.ALLERGY_WINDOWS in kinds, "$kinds")
        assertEquals(Allergen.BIRCH, severe.drivers.first().allergen)
        assertFalse(severe.drivers.first().estimated)
    }

    @Test
    fun grassAllergyIsQuietWhenOnlyTreesAreHigh() {
        val grass = AllergyEstimator.score(mapOf(Allergen.GRASS to Sensitivity.SEVERE), day("2027-05-12"), pollen().days[0], null)
        assertTrue(grass.score <= 20, "${grass.score}")
        assertEquals(AllergyLevel.LOW, grass.level)
        assertEquals(Tip.Kind.ALLERGY_QUIET, grass.tips.first().kind)
    }

    @Test
    fun unreportedPlantFallsBackToItsPollenType() {
        // Elm isn't in the fixture's plant list, so it's estimated from tree pollen (index 4) at a discount.
        val elm = AllergyEstimator.score(mapOf(Allergen.ELM to Sensitivity.SEVERE), day("2027-05-12"), pollen().days[0], null)
        assertTrue(elm.drivers.single().estimated)
        assertEquals(64, elm.score)
    }

    @Test
    fun rainWashesPollenOutAndWindSpreadsIt() {
        val profile = mapOf(Allergen.BIRCH to Sensitivity.SEVERE)
        val p = pollen().days[0]
        val rainy = AllergyEstimator.score(profile, day("2027-05-12", chance = 90, rainMm = 12.0), p, null)
        val windy = AllergyEstimator.score(profile, day("2027-05-12", gustMph = 35.0), p, null)
        assertTrue(rainy.score < 80 && Tip.Kind.ALLERGY_RAIN_HELPS in rainy.tips.map { it.kind })
        assertTrue(windy.score > 80 && Tip.Kind.ALLERGY_WIND in windy.tips.map { it.kind })
    }

    @Test
    fun moldAndAirPollutionWorkWithoutPollenData() {
        val profile = mapOf(Allergen.MOLD to Sensitivity.SEVERE, Allergen.AIR_POLLUTION to Sensitivity.MODERATE, Allergen.BIRCH to Sensitivity.SEVERE)
        val fall = day("2026-10-15", highF = 64.0, chance = 70, humidity = 85.0).copy(mud = MudOutlook(MudLevel.MUDDY, 50))
        val air = AirQuality(AqiReading(160), listOf(AqiDay(LocalDate.parse("2026-10-15"), 160)), AirSource.AIRNOW, Instant.now())
        val result = AllergyEstimator.forecast(profile, listOf(fall), pollen = null, air = air, today = LocalDate.parse("2026-10-15")).single()
        assertFalse(result.pollenKnown)
        assertEquals(Allergen.MOLD, result.drivers.first().allergen)
        assertTrue(AllergyEstimator.moldIndex(fall) >= 4.0)
        assertTrue(Tip.Kind.ALLERGY_AIR in result.tips.map { it.kind })
        assertTrue(result.level >= AllergyLevel.HIGH, "${result.score}")
        val winter = day("2027-01-20", highF = 25.0).copy(mud = MudOutlook(MudLevel.SNOW_COVERED, 10))
        assertTrue(AllergyEstimator.moldIndex(winter) < 0.5)
    }

    @Test
    fun noProfileNoScore() = assertTrue(AllergyEstimator.forecast(emptyMap(), listOf(day("2027-05-12")), pollen(), null, LocalDate.parse("2027-05-12")).isEmpty())

    // --- Ice on the water ---

    @Test
    fun sustainedFreezeMakesPaddlingZero() {
        val cold = (10..12).map { DailyForecast(LocalDate.of(2026, 12, it), "x", Units.fToC(22.0), Units.fToC(8.0), WeatherCondition.CLEAR, true, null, 0) }
        val ice = WaterIce.forecast(null, cold, LocalDate.of(2026, 12, 10))
        assertTrue(ice.getValue(LocalDate.of(2026, 12, 10)) < WaterIce.FROZEN)
        assertTrue(ice.getValue(LocalDate.of(2026, 12, 12)) >= WaterIce.FROZEN)
        val ctx = ActivityScorer.Context(43.66, -70.26, zone, emptyList(), ice)
        val calm = PeriodStats(tempMaxC = Units.fToC(22.0), tempMinC = Units.fToC(15.0), windMeanKph = 3.0, gustMaxKph = 5.0, skyCoverMean = 10)
        val third = ActivityScorer.score(cold[2].copy(daytime = calm), null, ctx, listOf(Activity.PADDLING)).single()
        assertEquals(0, third.score)
        assertEquals(Tip.Kind.ICE_ON_WATER, third.tips.first().kind)
    }

    @Test
    fun lakesStayIcedUntilSpringIceOut() {
        val history = AcisClient.parseGrid(resource("/live/acis_grid_concord_spring.json")).first
        fun iceOn(date: String): Double {
            val d = LocalDate.parse(date)
            val past = WeatherHistory(history.filter { it.date.isBefore(d) }, fetchedAt = Instant.now())
            val today = history.first { it.date == d }
            val f = DailyForecast(d, "x", today.highC, today.lowC, WeatherCondition.CLEAR, true, null, 0)
            return WaterIce.forecast(past, listOf(f), d).getValue(d)
        }
        assertTrue(iceOn("2026-02-15") >= WaterIce.FROZEN)
        assertTrue(iceOn("2026-03-20") >= WaterIce.FROZEN, "lakes still frozen in March")
        assertTrue(iceOn("2026-06-01") < WaterIce.SHORE_ICE, "ice out by June")
    }

    @Test
    fun summerHasNoIce() {
        val warm = listOf(DailyForecast(LocalDate.of(2026, 7, 1), "x", 28.0, 17.0, WeatherCondition.CLEAR, true, null, 0))
        val history = WeatherHistory((1..30).map { ObservedDay(LocalDate.of(2026, 6, it), 25.0, 14.0, 1.0) }, fetchedAt = Instant.now())
        assertEquals(0.0, WaterIce.forecast(history, warm, LocalDate.of(2026, 7, 1)).values.single())
    }
}
