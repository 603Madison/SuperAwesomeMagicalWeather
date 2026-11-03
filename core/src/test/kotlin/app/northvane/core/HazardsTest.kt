package app.northvane.core

import app.northvane.core.aurora.AuroraAdvisor
import app.northvane.core.aurora.AuroraClient
import app.northvane.core.model.AlertCategory
import app.northvane.core.model.AlertSeverity
import app.northvane.core.model.AuroraView
import app.northvane.core.model.BurnRating
import app.northvane.core.model.BurnReason
import app.northvane.core.model.DailyForecast
import app.northvane.core.model.HourlyForecast
import app.northvane.core.model.KpForecast
import app.northvane.core.model.KpPoint
import app.northvane.core.model.Likelihood
import app.northvane.core.model.MorningIce
import app.northvane.core.model.ObservedDay
import app.northvane.core.model.OutageLevel
import app.northvane.core.model.OutageRisk
import app.northvane.core.model.PeriodStats
import app.northvane.core.model.PipeFactor
import app.northvane.core.model.SapRating
import app.northvane.core.model.SapReport
import app.northvane.core.model.UnitSystem
import app.northvane.core.model.Units
import app.northvane.core.model.WeatherAlert
import app.northvane.core.model.WeatherCondition
import app.northvane.core.model.WeatherHistory
import app.northvane.core.net.HttpClients
import app.northvane.core.outlook.BurnAdvisor
import app.northvane.core.outlook.HeatType
import app.northvane.core.outlook.MorningIceEstimator
import app.northvane.core.outlook.OutlookText
import app.northvane.core.outlook.PipeFreeze
import app.northvane.core.outlook.SapAdvisor
import app.northvane.core.util.UnitFormatter
import kotlinx.coroutines.test.runTest
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

class HazardsTest {
    private val zone = ZoneId.of("America/New_York")
    private val portland = 43.66 to -70.26
    private fun resource(name: String) = requireNotNull(javaClass.getResource(name)) { "missing $name" }.readText()

    private fun day(
        date: String,
        highF: Double,
        lowF: Double,
        chance: Int = 10,
        daytime: PeriodStats? = null,
        overnight: PeriodStats? = null,
        humidityMin: Double? = null,
        outage: OutageLevel? = null,
        precipIn: Double? = null,
    ) = DailyForecast(
        LocalDate.parse(date), "d", Units.fToC(highF), Units.fToC(lowF), WeatherCondition.CLEAR, true, null, chance,
        daytime = daytime, overnight = overnight, humidityMin = humidityMin,
        outageRisk = outage?.let { OutageRisk(it, 70, emptyList()) },
        precipitationMm = precipIn?.let(Units::inchesToMm),
    )

    /** Hourly forecast from [start] (local time) with one temperature per hour. */
    private fun hours(
        start: String,
        tempsF: List<Double>,
        sky: Int = 10,
        windMph: Double = 3.0,
        humidity: Double = 85.0,
        rainIn: (Int) -> Double = { 0.0 },
        iceIn: (Int) -> Double = { 0.0 },
    ): List<HourlyForecast> {
        val t0 = java.time.LocalDateTime.parse(start).atZone(zone).toInstant()
        return tempsF.mapIndexed { i, f ->
            HourlyForecast(
                t0.plus(Duration.ofHours(i.toLong())), Units.fToC(f), WeatherCondition.CLEAR, false, null, 0,
                Units.mphToKph(windMph), "N", humidity, skyCoverPercent = sky,
                precipitationMm = Units.inchesToMm(rainIn(i)), snowMm = 0.0, iceMm = Units.inchesToMm(iceIn(i)),
            )
        }
    }

    private fun alert(event: String, start: String, end: String) = WeatherAlert(
        id = event, threadId = event, event = event, headline = null, description = null, instruction = null,
        severity = AlertSeverity.SEVERE, urgency = null, certainty = null, category = AlertCategory.WARNING,
        onset = Instant.parse(start), ends = Instant.parse(end),
    )

    // --- Frozen pipes ---

    @Test
    fun mildNightsHaveNoPipeRisk() {
        assertNull(PipeFreeze.estimate(day("2026-12-10", 38.0, 24.0), emptyList(), zone))
    }

    @Test
    fun pipeRiskRisesWithTheCold() {
        assertEquals(OutageLevel.ELEVATED, PipeFreeze.estimate(day("2026-12-10", 30.0, 18.0), emptyList(), zone)!!.level)
        assertEquals(OutageLevel.HIGH, PipeFreeze.estimate(day("2026-12-10", 30.0, 6.0), emptyList(), zone)!!.level)
        assertEquals(OutageLevel.VERY_HIGH, PipeFreeze.estimate(day("2026-12-10", 12.0, -6.0), emptyList(), zone)!!.level)
    }

    @Test
    fun allDayColdWindAndOutagesRaisePipeRisk() {
        val noThaw = PipeFreeze.estimate(day("2026-12-10", 17.0, 14.0), emptyList(), zone)!!
        assertEquals(OutageLevel.HIGH, noThaw.level)
        assertTrue(noThaw.factors.any { it.kind == PipeFactor.Kind.NO_THAW })

        val windy = day("2026-12-10", 30.0, 18.0, overnight = PeriodStats(tempMinC = Units.fToC(18.0), windMeanKph = Units.mphToKph(20.0)))
        assertEquals(OutageLevel.HIGH, PipeFreeze.estimate(windy, emptyList(), zone)!!.level)

        val outage = PipeFreeze.estimate(day("2026-12-10", 30.0, 18.0, outage = OutageLevel.HIGH), emptyList(), zone)!!
        assertEquals(OutageLevel.HIGH, outage.level)
        assertTrue(outage.factors.any { it.kind == PipeFactor.Kind.OUTAGE })
    }

    @Test
    fun hourlyForecastCountsHoursBelowTwentyDegrees() {
        // 7 AM to 8 AM next day: 25 hours, 14 of them at 15°F.
        val temps = List(11) { 25.0 } + List(14) { 15.0 }
        val risk = PipeFreeze.estimate(day("2026-12-10", 25.0, 15.0), hours("2026-12-10T07:00", temps), zone)!!
        assertEquals(14, risk.hoursBelow)
        assertEquals(OutageLevel.HIGH, risk.level) // 12+ hours below 20°F
        assertEquals(24, PipeFreeze.estimateHoursBelow(Units.fToC(5.0), Units.fToC(15.0)))
    }

    @Test
    fun pipePrepAddsStepsForHighRiskAndOutages() {
        val f = UnitFormatter(UnitSystem.IMPERIAL)
        val elevated = OutlookText.pipePrep(PipeFreeze.estimate(day("2026-12-10", 30.0, 18.0), emptyList(), zone)!!, emptyList(), f)
        assertTrue(elevated.any { "garden hoses" in it })
        assertTrue(elevated.any { "55°F" in it }, "$elevated")
        assertFalse(elevated.any { "drip" in it })
        val severe = PipeFreeze.estimate(day("2026-12-10", 10.0, -8.0, outage = OutageLevel.HIGH), emptyList(), zone)!!
        val steps = OutlookText.pipePrep(severe, listOf(HeatType.OIL, HeatType.WOOD), f)
        assertTrue(steps.any { "drip" in it })
        assertTrue(steps.any { "wood stove" in it }, "$steps")
        assertTrue(steps.any { "never an open flame" in it })
    }

    // --- Frost and black ice ---

    private val now = Instant.parse("2026-12-09T20:00:00Z")

    @Test
    fun clearCalmFreezingNightMeansFrost() {
        // 6 PM to 9 AM, cooling to 28°F under clear, calm skies.
        val temps = (0 until 15).map { 40.0 - it * 0.9 }
        val ice = MorningIceEstimator.estimate(day("2026-12-10", 45.0, 28.0), null, hours("2026-12-09T18:00", temps), zone, snowOnGround = false, now)!!
        assertEquals(Likelihood.LIKELY, ice.frost)
        assertNull(ice.blackIce)
        assertTrue(MorningIce.Reason.CLEAR_CALM in ice.reasons)
        assertTrue(OutlookText.iceTips(ice).any { "windshield" in it })
    }

    @Test
    fun rainThenFreezeMeansBlackIce() {
        // Rain through the evening at 38°F, then a drop to 26°F by dawn, cloudy and breezy (no frost).
        val temps = (0 until 15).map { if (it < 5) 38.0 else 38.0 - (it - 4) * 1.2 }
        val hourly = hours("2026-12-09T18:00", temps, sky = 90, windMph = 12.0, rainIn = { if (it < 4) 0.08 else 0.0 })
        val ice = MorningIceEstimator.estimate(day("2026-12-10", 34.0, 26.0), null, hourly, zone, snowOnGround = false, now)!!
        assertEquals(Likelihood.LIKELY, ice.blackIce)
        assertNull(ice.frost)
        assertTrue(MorningIce.Reason.RAIN_THEN_FREEZE in ice.reasons)
        assertTrue(OutlookText.iceTips(ice).any { "Bridges" in it })
    }

    @Test
    fun freezingRainAndSnowmeltRefreeze() {
        val glaze = hours("2026-12-09T18:00", List(15) { 30.0 }, sky = 100, windMph = 10.0, iceIn = { if (it in 2..5) 0.05 else 0.0 })
        val ice = MorningIceEstimator.estimate(day("2026-12-10", 33.0, 30.0), null, glaze, zone, snowOnGround = false, now)!!
        assertEquals(Likelihood.LIKELY, ice.blackIce)
        assertTrue(MorningIce.Reason.FREEZING_RAIN in ice.reasons)

        // A 42°F afternoon melting snow, then 24°F overnight, from the daily summaries alone.
        val before = day("2026-12-12", 42.0, 24.0, daytime = PeriodStats(tempMaxC = Units.fToC(42.0)),
            overnight = PeriodStats(tempMinC = Units.fToC(24.0), skyCoverMean = 90, windMeanKph = 15.0, humidityMean = 60.0))
        val melt = MorningIceEstimator.estimate(day("2026-12-13", 35.0, 22.0), before, emptyList(), zone, snowOnGround = true, now)!!
        assertEquals(Likelihood.POSSIBLE, melt.blackIce)
        assertTrue(MorningIce.Reason.MELT_REFREEZE in melt.reasons)
        assertNull(MorningIceEstimator.estimate(day("2026-12-13", 35.0, 22.0), before, emptyList(), zone, snowOnGround = false, now))
    }

    @Test
    fun noIceOnMildNightsOrPastMornings() {
        assertNull(MorningIceEstimator.estimate(day("2026-12-10", 55.0, 45.0), null, hours("2026-12-09T18:00", List(15) { 45.0 }), zone, false, now))
        val cold = hours("2026-12-09T18:00", List(15) { 25.0 })
        val afternoon = Instant.parse("2026-12-10T17:00:00Z")
        assertNull(MorningIceEstimator.estimate(day("2026-12-10", 30.0, 25.0), null, cold, zone, false, afternoon))
    }

    // --- Maple sap ---

    @Test
    fun sapRunsAfterFreezingNightsAndThawingDays() {
        assertEquals(SapRating.GREAT, SapAdvisor.rate(Units.fToC(24.0), Units.fToC(42.0), skyCover = 20).first)
        assertEquals(SapRating.GOOD, SapAdvisor.rate(Units.fToC(29.0), Units.fToC(45.0), skyCover = 80).first)
        assertEquals(SapRating.NONE, SapAdvisor.rate(Units.fToC(15.0), Units.fToC(30.0), skyCover = 20).first) // never thaws
        assertEquals(SapRating.NONE, SapAdvisor.rate(Units.fToC(40.0), Units.fToC(55.0), skyCover = 20).first) // no freeze
        assertEquals(SapRating.LIGHT, SapAdvisor.rate(Units.fToC(33.0), Units.fToC(42.0), skyCover = 20).first)
        assertTrue(SapAdvisor.inSeason(LocalDate.parse("2027-03-05"), 44.0))
        assertFalse(SapAdvisor.inSeason(LocalDate.parse("2027-01-05"), 44.0))
        assertFalse(SapAdvisor.inSeason(LocalDate.parse("2027-03-05"), 28.0))
    }

    @Test
    fun sapReportUsesThePreviousNightAndFlagsWarmNights() {
        val days = listOf(
            day("2027-03-10", 42.0, 24.0), day("2027-03-11", 44.0, 26.0),
            day("2027-03-12", 60.0, 40.0), day("2027-03-13", 62.0, 42.0), day("2027-03-14", 64.0, 44.0),
        )
        val history = WeatherHistory(listOf(ObservedDay(LocalDate.parse("2027-03-10"), Units.fToC(40.0), Units.fToC(22.0), 0.0)), fetchedAt = Instant.now())
        val report = SapAdvisor.report(days, emptyList(), history, LocalDate.parse("2027-03-10"), zone)
        assertEquals(SapRating.GREAT, report.days[0].rating) // last night 22°F (observed), 42°F today
        assertEquals(Units.fToC(24.0), report.days[1].nightLowC!!, 0.01) // the 10th's low
        assertEquals(SapRating.NONE, report.days[3].rating) // 40°F night: no freeze
        assertEquals(SapReport.Note.WARM_NIGHTS, report.note)
    }

    // --- Burn days ---

    private val calm = PeriodStats(windMaxKph = 8.0, gustMaxKph = 15.0)

    @Test
    fun windAndDryAirStopBurning() {
        val gusty = BurnAdvisor.rate(day("2027-04-20", 60.0, 40.0, daytime = PeriodStats(windMaxKph = 20.0, gustMaxKph = 48.0), humidityMin = 45.0), 1, false, emptyList(), zone)
        assertEquals(BurnRating.NO_BURN, gusty.rating)
        assertTrue(gusty.reasons.any { it.kind == BurnReason.Kind.GUSTS })
        val dry = BurnAdvisor.rate(day("2027-04-20", 60.0, 40.0, daytime = calm, humidityMin = 22.0), 1, false, emptyList(), zone)
        assertEquals(BurnRating.NO_BURN, dry.rating)
        val breezy = BurnAdvisor.rate(day("2027-04-20", 60.0, 40.0, daytime = PeriodStats(windMaxKph = 18.0, gustMaxKph = 25.0), humidityMin = 45.0), 1, false, emptyList(), zone)
        assertEquals(BurnRating.CAUTION, breezy.rating)
    }

    @Test
    fun redFlagWarningMeansNoBurn() {
        val redFlag = alert("Red Flag Warning", "2027-04-20T15:00:00Z", "2027-04-21T00:00:00Z")
        val day = BurnAdvisor.rate(day("2027-04-20", 60.0, 40.0, daytime = calm, humidityMin = 45.0), 1, false, listOf(redFlag), zone)
        assertEquals(BurnRating.NO_BURN, day.rating)
        assertEquals("Red Flag Warning", day.reasons.first { it.kind == BurnReason.Kind.FIRE_ALERT }.detail)
        val nextDay = BurnAdvisor.rate(day("2027-04-22", 60.0, 40.0, daytime = calm, humidityMin = 45.0), 1, false, listOf(redFlag), zone)
        assertEquals(BurnRating.GOOD, nextDay.rating)
    }

    @Test
    fun dryAndSnowyStretches() {
        val history = WeatherHistory(
            (1..12).map { ObservedDay(LocalDate.parse("2027-04-20").minusDays(it.toLong()), 15.0, 2.0, if (it == 8) 6.0 else 0.0) },
            fetchedAt = Instant.now(),
        )
        val days = listOf(
            day("2027-04-20", 60.0, 40.0, daytime = calm, humidityMin = 45.0),
            day("2027-04-21", 55.0, 40.0, chance = 90, daytime = calm, humidityMin = 70.0, precipIn = 0.6),
            day("2027-04-22", 58.0, 40.0, daytime = calm, humidityMin = 50.0),
        )
        val report = BurnAdvisor.report(days, history, emptyList(), LocalDate.parse("2027-04-20"), zone)
        assertEquals(8, report.daysSinceRain)
        assertEquals(BurnRating.CAUTION, report.days[0].rating) // 8 dry days
        assertTrue(report.days[0].reasons.any { it.kind == BurnReason.Kind.DRY_SPELL })
        assertEquals(BurnRating.GOOD, report.days[2].rating) // after the 21st's rain
        assertTrue(report.days[2].reasons.any { it.kind == BurnReason.Kind.RECENT_RAIN })

        val snowy = history.copy(snowDepthMm = 150.0)
        val winter = BurnAdvisor.report(listOf(day("2027-01-20", 28.0, 15.0, daytime = calm, humidityMin = 30.0)), snowy, emptyList(), LocalDate.parse("2027-01-20"), zone)
        assertEquals(BurnRating.GOOD, winter.days[0].rating)
        assertTrue(winter.days[0].reasons.any { it.kind == BurnReason.Kind.SNOW_COVER })
    }

    // --- Aurora ---

    @Test
    fun parsesNoaaKpForecast() {
        val points = AuroraClient.parse(resource("/aurora/kp_forecast.json"))
        assertEquals(81, points.size)
        assertEquals(KpPoint.Kind.OBSERVED, points.first().kind)
        assertEquals(KpPoint.Kind.PREDICTED, points.last().kind)
        assertEquals(6.0, points.maxOf { it.kp }, 0.01)
        assertEquals(Instant.parse("2026-10-27T00:00:00Z"), points.first().start)

        val legacy = """[["time_tag","kp","observed","noaa_scale"],["2024-05-10 18:00:00","8.67","observed","G4"],["2024-05-11 00:00:00","9.00","predicted",null]]"""
        val old = AuroraClient.parse(legacy)
        assertEquals(2, old.size)
        assertEquals(9.0, old[1].kp, 0.01)
        assertEquals(KpPoint.Kind.PREDICTED, old[1].kind)
    }

    @Test
    fun auroraVisibilityByGeomagneticLatitude() {
        val maine = AuroraAdvisor.geomagneticLatitude(portland.first, portland.second)
        assertTrue(maine in 52.0..54.0, "$maine")
        val florida = AuroraAdvisor.geomagneticLatitude(27.95, -82.46)
        assertTrue(florida < 39.0, "$florida")
        assertEquals(AuroraView.NONE, AuroraAdvisor.view(3.0, maine))
        assertEquals(AuroraView.CAMERA, AuroraAdvisor.view(4.67, maine))
        assertEquals(AuroraView.HORIZON, AuroraAdvisor.view(5.67, maine))
        assertEquals(AuroraView.OVERHEAD, AuroraAdvisor.view(7.0, maine))
        assertEquals(AuroraView.NONE, AuroraAdvisor.view(9.0, florida))
    }

    @Test
    fun auroraNightsUseDarknessAndClouds() {
        // Quiet afternoon (UTC 18–21), a Kp 7 storm through the night, then quiet again.
        val start = Instant.parse("2026-11-10T18:00:00Z")
        val kps = listOf(2.0, 3.0, 7.0, 7.33, 6.0, 4.0, 2.0, 2.0, 2.0, 2.0, 2.0, 2.0, 2.0, 2.0, 2.0, 2.0)
        val forecast = KpForecast(kps.mapIndexed { i, k -> KpPoint(start.plus(Duration.ofHours(3L * i)), k, KpPoint.Kind.PREDICTED) }, start)
        val clouds = hours("2026-11-10T16:00", List(18) { 30.0 }, sky = 20)
        val report = AuroraAdvisor.report(forecast, portland.first, portland.second, clouds, zone, Instant.parse("2026-11-10T19:00:00Z"))
        val tonight = report.nights.first()
        assertEquals(LocalDate.parse("2026-11-10"), tonight.date)
        assertEquals(7.33, tonight.maxKp, 0.01)
        assertEquals(AuroraView.OVERHEAD, tonight.view)
        assertEquals(20, tonight.cloudPercent)
        assertTrue(report.gpsImpact)
        assertEquals(AuroraView.NONE, report.nowView) // 2 PM: still daylight
        assertEquals(AuroraView.NONE, report.nights[1].view)
    }

    @Test
    fun auroraClientDownloadsTheForecast() = runTest {
        val fake = FakeNetwork().apply { on("services.swpc.noaa.gov/products/noaa-planetary-k-index-forecast.json", body = resource("/aurora/kp_forecast.json")) }
        val http = HttpClients.create("test-agent", cacheDir = null).newBuilder().addInterceptor(fake).build()
        val now = Instant.parse("2026-11-03T12:00:00Z")
        val forecast = AuroraClient(http).forecast(now)
        assertEquals(now, forecast.fetchedAt)
        assertEquals(81, forecast.points.size)
        val report = AuroraAdvisor.report(forecast, portland.first, portland.second, emptyList(), zone, now)
        assertNotNull(report.nights.firstOrNull())
        assertFalse(report.gpsImpact)
    }
}
