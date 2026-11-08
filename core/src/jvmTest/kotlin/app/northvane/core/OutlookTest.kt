package app.northvane.core

import app.northvane.core.history.AcisClient
import app.northvane.core.history.HistoryUnavailableException
import app.northvane.core.model.Activity
import app.northvane.core.model.AlertCategory
import app.northvane.core.model.AlertSeverity
import app.northvane.core.model.DailyForecast
import app.northvane.core.model.FoliageStage
import app.northvane.core.model.MudLevel
import app.northvane.core.model.MudOutlook
import app.northvane.core.model.MudReason
import app.northvane.core.model.ObservedDay
import app.northvane.core.model.OutageFactor.Kind as OutageKind
import app.northvane.core.model.PeriodStats
import app.northvane.core.model.Tip
import app.northvane.core.model.UnitSystem
import app.northvane.core.model.Units
import app.northvane.core.model.WeatherAlert
import app.northvane.core.model.WeatherCondition
import app.northvane.core.model.WeatherHistory
import app.northvane.core.model.WeatherSnapshot
import app.northvane.core.outage.OutageContext
import app.northvane.core.outage.OutageEstimator
import app.northvane.core.outlook.ActivityScorer
import app.northvane.core.outlook.FoliageEstimator
import app.northvane.core.outlook.MudEstimator
import app.northvane.core.outlook.OutlookText
import app.northvane.core.outlook.Outlooks
import app.northvane.core.util.UnitFormatter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import app.northvane.core.time.*

class OutlookTest {
    private val zone = ZoneId.of("America/New_York")
    private val concordLat = 43.21
    private val ctx = ActivityScorer.Context(concordLat, -71.54, zone, emptyList())

    private fun resource(name: String) = requireNotNull(javaClass.getResource(name)) { "missing $name" }.readText()

    private fun concordHistory(): List<ObservedDay> = AcisClient.parseGrid(resource("/live/acis_grid_concord_spring.json")).first

    private fun day(
        date: String, highF: Double = 60.0, lowF: Double = 45.0, rainIn: Double? = null, chance: Int? = null,
        gustMph: Double? = null, windMph: Double? = null, thunder: Int? = null, sky: Int? = null,
        daytime: PeriodStats? = null, overnight: PeriodStats? = null,
    ) = DailyForecast(
        date = LocalDate.parse(date), name = "Day", highC = Units.fToC(highF), lowC = Units.fToC(lowF),
        condition = WeatherCondition.CLEAR, isDaytimeCondition = true, shortForecast = null, precipitationChance = chance,
        precipitationMm = rainIn?.let(Units::inchesToMm), gustMaxKph = gustMph?.let(Units::mphToKph),
        windMaxKph = windMph?.let(Units::mphToKph), thunderChance = thunder, skyCoverPercent = sky,
        daytime = daytime, overnight = overnight,
    )

    private fun period(
        maxF: Double = 62.0, minF: Double = 48.0, dewF: Double = 40.0, chance: Int = 5, rainIn: Double = 0.0,
        windMph: Double = 6.0, gustMph: Double = 12.0, thunder: Int = 0, sky: Int = 30, humidity: Double = 55.0,
        snowIn: Double = 0.0,
    ) = PeriodStats(
        tempMaxC = Units.fToC(maxF), tempMinC = Units.fToC(minF), feelsMaxC = Units.fToC(maxF), feelsMinC = Units.fToC(minF),
        dewpointMaxC = Units.fToC(dewF), humidityMean = humidity, precipChanceMax = chance, precipMm = Units.inchesToMm(rainIn),
        snowMm = Units.inchesToMm(snowIn), iceMm = 0.0, windMeanKph = Units.mphToKph(windMph), windMaxKph = Units.mphToKph(windMph),
        gustMaxKph = Units.mphToKph(gustMph), thunderChanceMax = thunder, skyCoverMean = sky,
    )

    private fun score(d: DailyForecast, activity: Activity, mud: MudOutlook? = null, c: ActivityScorer.Context = ctx) =
        ActivityScorer.score(d, mud, c, listOf(activity)).single()

    // --- NOAA ACIS ---

    @Test
    fun parsesAcisGridAndElevation() {
        val (days, elevationFt) = AcisClient.parseGrid(resource("/live/acis_grid_portland.json"))
        assertEquals(76, days.size)
        assertEquals(LocalDate.parse("2026-10-28"), days.last().date)
        assertEquals(51.0, Units.cToF(days.last().highC!!), 1e-6)
        assertEquals(25.0, Units.cToF(days.last().lowC!!), 1e-6)
        assertEquals(Units.inchesToMm(1.01), days.first { it.date == LocalDate.parse("2026-10-14") }.precipMm!!, 1e-6)
        assertNull(elevationFt, "Portland's coastal grid cell reports the missing-elevation sentinel")
        assertEquals(331.0, AcisClient.parseGrid(resource("/live/acis_grid_concord_spring.json")).second)
    }

    @Test
    fun acisMissingValuesAndErrors() {
        val (days, _) = AcisClient.parseGrid(
            """{"meta":{"elev":300},"data":[["2026-10-27",50,30,0.10],["2026-10-28",-999,-999,-999]]}""",
        )
        assertEquals(1, days.size)
        assertFailsWith<HistoryUnavailableException> { AcisClient.parseGrid("""{"error":"no data"}""") }
        // Grid cells over water report a missing-value elevation.
        assertNull(AcisClient.parseGrid("""{"meta":{"elev":-32768.1},"data":[["2026-10-27",50,30,0.10]]}""").second)
        assertFailsWith<HistoryUnavailableException> { AcisClient.parseGrid("""{"data":[]}""") }
    }

    @Test
    fun snowDepthFromNearestReportingStation() {
        val near = app.northvane.core.model.GeoPoint(44.27, -71.30)
        val (depthMm, station) = assertNotNull(AcisClient.parseSnowDepth(resource("/live/acis_snwd_whites.json"), near))
        // Mount Washington is closest but reported "M", so the next-nearest plot is used.
        assertEquals("HERMIT LAKE SNOWPLOT", station)
        assertEquals(Units.inchesToMm(50.0), depthMm, 1e-6)
    }

    // --- Mud ---

    private fun mudOn(date: String, history: List<ObservedDay> = concordHistory()): MudOutlook {
        val d = LocalDate.parse(date)
        val forecast = history.filter { !it.date.isBefore(d) }.take(3).map {
            DailyForecast(it.date, "x", it.highC, it.lowC, WeatherCondition.CLEAR, true, null, null, precipitationMm = it.precipMm)
        }
        val past = WeatherHistory(history.filter { it.date.isBefore(d) }.takeLast(150), fetchedAt = NOW)
        return MudEstimator.forecast(past, forecast, d).getValue(d)
    }

    @Test
    fun springThawIsMudSeason() {
        // Concord, NH, 2026 observed: deep winter frost, then a March–April thaw.
        val april = mudOn("2026-04-09")
        assertTrue(april.mudSeason)
        assertTrue(april.level >= MudLevel.MUDDY, "${april.level}")
        assertTrue(april.reasons.any { it.kind == MudReason.Kind.THAW })
        val june = mudOn("2026-06-09")
        assertFalse(june.mudSeason)
        assertEquals(MudLevel.DRY, june.level)
    }

    @Test
    fun midwinterIsSnowOrFrozenNotMudSeason() {
        val january = mudOn("2026-01-30")
        assertFalse(january.mudSeason)
        assertTrue(january.level == MudLevel.SNOW_COVERED || january.level == MudLevel.FROZEN, "${january.level}")
    }

    @Test
    fun summerRainMakesTrailsMuddyAndTheyDryOut() {
        val dry = (1..20).map { ObservedDay(LocalDate.of(2026, 7, it), Units.fToC(82.0), Units.fToC(60.0), 0.0) }
        val history = WeatherHistory(dry, fetchedAt = NOW)
        val forecast = listOf(
            day("2026-07-21", highF = 72.0, lowF = 62.0, rainIn = 1.6),
            day("2026-07-22", highF = 80.0, lowF = 60.0, rainIn = 0.0),
            day("2026-07-23", highF = 85.0, lowF = 62.0, rainIn = 0.0),
            day("2026-07-24", highF = 86.0, lowF = 63.0, rainIn = 0.0),
            day("2026-07-25", highF = 86.0, lowF = 63.0, rainIn = 0.0),
        )
        val mud = MudEstimator.forecast(history, forecast, LocalDate.parse("2026-07-21"))
        assertTrue(mud.getValue(LocalDate.parse("2026-07-21")).level >= MudLevel.MUDDY)
        assertTrue(mud.getValue(LocalDate.parse("2026-07-21")).reasons.any { it.kind == MudReason.Kind.FORECAST_RAIN })
        assertTrue(mud.getValue(LocalDate.parse("2026-07-25")).score < mud.getValue(LocalDate.parse("2026-07-22")).score)
        assertTrue(mud.getValue(LocalDate.parse("2026-07-25")).level <= MudLevel.DAMP)
    }

    @Test
    fun mudWithoutHistorySaysSo() {
        val mud = MudEstimator.forecast(null, listOf(day("2026-08-10")), LocalDate.parse("2026-08-10")).values.single()
        assertTrue(mud.reasons.any { it.kind == MudReason.Kind.NO_HISTORY })
    }

    // --- Foliage ---

    @Test
    fun typicalPeakFollowsLatitudeAndElevation() {
        assertEquals(LocalDate.parse("2026-10-18"), FoliageEstimator.typicalPeak(2026, 43.0, 0.0))
        val northern = FoliageEstimator.typicalPeak(2026, 46.5, 300.0) // Aroostook-ish
        val mountains = FoliageEstimator.typicalPeak(2026, 44.27, 1200.0)
        assertTrue(northern.isBefore(LocalDate.parse("2026-10-01")), "$northern")
        assertTrue(mountains.isBefore(LocalDate.parse("2026-10-01")), "$mountains")
    }

    @Test
    fun foliageReportOnlyInSeasonAndRegion() {
        assertNull(FoliageEstimator.report(43.2, null, emptyList(), LocalDate.parse("2026-07-15")))
        assertNull(FoliageEstimator.report(27.9, null, emptyList(), LocalDate.parse("2026-10-15"))) // Florida
        val early = assertNotNull(FoliageEstimator.report(43.2, null, emptyList(), LocalDate.parse("2026-09-25")))
        assertTrue(early.today!!.stage <= FoliageStage.MODERATE)
        val peak = assertNotNull(FoliageEstimator.report(43.2, null, emptyList(), LocalDate.parse("2026-10-17")))
        assertEquals(FoliageStage.PEAK, peak.today!!.stage)
    }

    @Test
    fun coolNightsBringColorEarlier() {
        fun history(lowF: Double) = WeatherHistory(
            (0 until 40).map { ObservedDay(LocalDate.of(2026, 9, 1).plusDays(it.toLong()), Units.fToC(65.0), Units.fToC(lowF), 2.5) },
            elevationM = 100.0, fetchedAt = NOW,
        )
        val today = LocalDate.parse("2026-10-11")
        val cool = FoliageEstimator.report(43.2, history(36.0), emptyList(), today)!!
        val warm = FoliageEstimator.report(43.2, history(56.0), emptyList(), today)!!
        assertTrue(cool.estimatedPeak.isBefore(warm.estimatedPeak))
        assertTrue(FoliageReport_Note_COOL in cool.notes)
        assertTrue(FoliageReport_Note_WARM in warm.notes)
    }

    @Test
    fun windyDayNearPeakStripsLeaves() {
        val days = listOf(day("2026-10-15"), day("2026-10-16", gustMph = 45.0), day("2026-10-17"))
        val report = FoliageEstimator.report(43.2, null, days, LocalDate.parse("2026-10-15"))!!
        assertTrue(report.days[1].leafDrop)
        assertFalse(report.days[0].leafDrop)
    }

    @Test
    fun leafCoverBySeason() {
        assertEquals(0.0, FoliageEstimator.leafCover(LocalDate.parse("2027-01-15"), 43.2, 100.0))
        assertEquals(0.0, FoliageEstimator.leafCover(LocalDate.parse("2027-03-20"), 43.2, 100.0))
        assertEquals(1.0, FoliageEstimator.leafCover(LocalDate.parse("2026-07-15"), 43.2, 100.0))
        assertEquals(1.0, FoliageEstimator.leafCover(LocalDate.parse("2026-10-01"), 43.2, 100.0))
        assertTrue(FoliageEstimator.leafCover(LocalDate.parse("2026-11-20"), 43.2, 100.0) < 0.2)
        // Northern Maine leafs out later than southern New Hampshire.
        val may = LocalDate.parse("2027-05-15")
        assertTrue(FoliageEstimator.leafCover(may, 46.8, 200.0) < FoliageEstimator.leafCover(may, 42.9, 50.0))
    }

    // --- Activities ---

    @Test
    fun crispFallDayIsGreatForHiking() {
        val d = day("2026-10-05", daytime = period())
        val hike = score(d, Activity.HIKING)
        assertTrue(hike.score >= 85, "${hike.score} ${hike.tips}")
        assertEquals(Tip.Kind.IDEAL, hike.tips.first().kind)
    }

    @Test
    fun hotStormyDayIsPoorForHikingWithEarlyStart() {
        val d = day("2026-07-15", daytime = period(maxF = 92.0, minF = 74.0, dewF = 71.0, chance = 60, thunder = 50, sky = 60))
        val hike = score(d, Activity.HIKING)
        assertTrue(hike.score < 30, "${hike.score}")
        val kinds = hike.tips.map { it.kind }
        assertTrue(Tip.Kind.THUNDER in kinds && Tip.Kind.EARLY_START in kinds && Tip.Kind.HOT in kinds, "$kinds")
    }

    @Test
    fun coldNightCampingRecommendsWarmBag() {
        val d = day("2026-10-20", overnight = period(maxF = 40.0, minF = 24.0, chance = 0, sky = 10))
        val camp = score(d, Activity.CAMPING)
        val kinds = camp.tips.map { it.kind }
        assertTrue(Tip.Kind.SLEEPING_BAG_20F in kinds, "$kinds")
        assertTrue(Tip.Kind.FROST in kinds, "$kinds")
        assertTrue(camp.score in 50..85, "${camp.score}")
        val mild = score(day("2026-08-20", overnight = period(maxF = 66.0, minF = 56.0, humidity = 60.0)), Activity.CAMPING)
        assertTrue(mild.score > camp.score)
        assertTrue(Tip.Kind.SLEEPING_BAG_SUMMER in mild.tips.map { it.kind })
    }

    @Test
    fun mudSinksOffRoadingAndMountainBiking() {
        val d = day("2026-04-12", daytime = period(maxF = 55.0, minF = 38.0))
        val mudSeason = MudOutlook(MudLevel.VERY_MUDDY, 90, mudSeason = true)
        val atv = score(d, Activity.OFF_ROADING, mudSeason)
        val bike = score(d, Activity.MOUNTAIN_BIKING, mudSeason)
        assertTrue(atv.score < 45, "${atv.score}")
        assertTrue(bike.score < 30, "${bike.score}")
        assertTrue(Tip.Kind.TRAIL_CLOSURES in atv.tips.map { it.kind })
        assertTrue(Tip.Kind.RECOVERY_GEAR in atv.tips.map { it.kind })
        val firm = score(d.copy(date = LocalDate.parse("2026-08-12")), Activity.OFF_ROADING, MudOutlook(MudLevel.DRY, 0))
        assertTrue(firm.score > 80, "${firm.score}")
    }

    @Test
    fun windyDayIsRoughForPaddling() {
        val calm = score(day("2026-08-01", daytime = period(maxF = 80.0, minF = 66.0, windMph = 5.0, gustMph = 10.0)), Activity.PADDLING)
        val windy = score(day("2026-08-01", daytime = period(maxF = 80.0, minF = 66.0, windMph = 18.0, gustMph = 30.0)), Activity.PADDLING)
        assertTrue(calm.score >= 80, "${calm.score}")
        assertTrue(windy.score < 50, "${windy.score}")
        assertTrue(Tip.Kind.ROUGH_WATER in windy.tips.map { it.kind })
    }

    @Test
    fun moonPhase() {
        assertTrue(ActivityScorer.moonIllumination(Instant.parse("2024-01-25T17:54:00Z")) > 0.97)
        assertTrue(ActivityScorer.moonIllumination(Instant.parse("2024-01-11T11:57:00Z")) < 0.03)
    }

    @Test
    fun clearMoonlessNightIsBestForStargazing() {
        // 2024-01-10/11: new moon; 2024-01-24/25: full moon.
        val newMoon = score(day("2024-01-10", overnight = period(maxF = 30.0, minF = 26.0, sky = 5, humidity = 50.0)), Activity.STARGAZING)
        val fullMoon = score(day("2024-01-24", overnight = period(maxF = 30.0, minF = 26.0, sky = 5, humidity = 50.0)), Activity.STARGAZING)
        val cloudy = score(day("2024-01-10", overnight = period(maxF = 30.0, minF = 26.0, sky = 95, humidity = 50.0)), Activity.STARGAZING)
        assertTrue(newMoon.score > fullMoon.score + 15, "${newMoon.score} vs ${fullMoon.score}")
        assertTrue(cloudy.score < 30, "${cloudy.score}")
        assertTrue(Tip.Kind.DARK_SKIES in newMoon.tips.map { it.kind })
        assertTrue(Tip.Kind.BRIGHT_MOON in fullMoon.tips.map { it.kind })
    }

    @Test
    fun activeWarningCapsScores() {
        val warning = WeatherAlert(
            id = "w", threadId = "w", event = "Winter Storm Warning", headline = null, description = null, instruction = null,
            severity = AlertSeverity.SEVERE, urgency = null, certainty = null, category = AlertCategory.WARNING,
            onset = Instant.parse("2026-12-10T05:00:00Z"), ends = Instant.parse("2026-12-11T05:00:00Z"),
        )
        val c = ctx.copy(alerts = listOf(warning))
        val hike = score(day("2026-12-10", daytime = period()), Activity.HIKING, c = c)
        assertTrue(hike.score <= 10)
        assertEquals(Tip.Kind.WARNING, hike.tips.first().kind)
        val nextWeek = score(day("2026-12-17", daytime = period()), Activity.HIKING, c = c)
        assertTrue(nextWeek.score > 10)
    }

    @Test
    fun seasonalTips() {
        val blackFlies = score(day("2026-05-25", daytime = period(maxF = 70.0)), Activity.HIKING).tips.map { it.kind }
        assertTrue(Tip.Kind.BLACK_FLIES in blackFlies, "$blackFlies")
        val hunting = score(day("2026-11-14", daytime = period(maxF = 45.0, minF = 30.0)), Activity.HIKING).tips.map { it.kind }
        assertTrue(Tip.Kind.BLAZE_ORANGE in hunting, "$hunting")
        assertTrue(Tip.Kind.SHORT_DAYLIGHT in hunting, "$hunting")
    }

    @Test
    fun everyTipHasWordsInBothUnits() {
        for (system in UnitSystem.entries) {
            val f = UnitFormatter(system)
            for (kind in Tip.Kind.entries) {
                val text = OutlookText.tip(Tip(kind, 10.0), f)
                assertTrue(text.isNotBlank() && "null" !in text, "$kind: $text")
            }
            for (kind in MudReason.Kind.entries) assertTrue(OutlookText.mudReason(MudReason(kind, 12.0), f).isNotBlank())
        }
        assertEquals("Low near -7°C: bring a -7°C bag and an insulated pad (R4+). Don't let your water filter freeze",
            OutlookText.tip(Tip(Tip.Kind.SLEEPING_BAG_20F, -7.0), UnitFormatter(UnitSystem.METRIC)))
    }

    // --- Season-aware outages ---

    @Test
    fun leavesOnlyMatterWhenTreesHaveThem() {
        val gusty = { date: String -> day(date, gustMph = 45.0) }
        val january = OutageEstimator.estimate(gusty("2027-01-15"), emptyList(), zone, OutageContext(leafCover = { 0.0 }))
        assertTrue(january.factors.none { it.kind == OutageKind.LEAVES_ON })
        assertTrue(january.factors.any { it.kind == OutageKind.LEAVES_OFF })
        // Late October in northern Maine, after peak: fewer leaves left than in southern NH on the same day.
        val date = LocalDate.parse("2026-10-30")
        val north = OutageEstimator.estimate(gusty("2026-10-30"), emptyList(), zone,
            OutageContext(leafCover = { FoliageEstimator.leafCover(it, 46.8, 250.0) }))
        val south = OutageEstimator.estimate(gusty("2026-10-30"), emptyList(), zone,
            OutageContext(leafCover = { FoliageEstimator.leafCover(it, 42.8, 30.0) }))
        assertTrue(north.score < south.score, "north ${north.score} vs south ${south.score} on $date")
        assertTrue(north.leafCover!! < south.leafCover!!)
    }

    @Test
    fun groundStateChangesUprootingRisk() {
        val storm = day("2027-03-25", highF = 45.0, lowF = 33.0, rainIn = 1.5, gustMph = 40.0)
        val date = storm.date
        val frozen = OutageEstimator.estimate(storm, emptyList(), zone, OutageContext({ 0.0 }, mapOf(date to MudOutlook(MudLevel.FROZEN, 5))))
        val plain = OutageEstimator.estimate(storm, emptyList(), zone, OutageContext({ 0.0 }))
        assertTrue(frozen.score < plain.score)
        assertTrue(frozen.factors.any { it.kind == OutageKind.FROZEN_GROUND })

        val breezy = day("2027-04-05", highF = 55.0, lowF = 38.0, rainIn = 0.2, gustMph = 38.0)
        val thawing = OutageEstimator.estimate(breezy, emptyList(), zone,
            OutageContext({ 0.0 }, mapOf(breezy.date to MudOutlook(MudLevel.VERY_MUDDY, 90, mudSeason = true))))
        val firm = OutageEstimator.estimate(breezy, emptyList(), zone, OutageContext({ 0.0 }))
        assertTrue(thawing.factors.any { it.kind == OutageKind.SOFT_GROUND })
        assertTrue(thawing.score > firm.score)
    }

    // --- Putting it together ---

    @Test
    fun annotateFillsEveryOutlook() {
        val history = WeatherHistory(
            AcisClient.parseGrid(resource("/live/acis_grid_portland.json")).first, elevationM = 20.0, fetchedAt = NOW,
        )
        val snapshot = WeatherSnapshot(
            locationId = "pwm", fetchedAt = NOW, timeZoneId = "America/New_York",
            daily = listOf(day("2026-10-29", gustMph = 20.0), day("2026-10-30", rainIn = 1.2, chance = 90, gustMph = 50.0), day("2026-10-31")),
            history = history,
        )
        val out = Outlooks.annotate(snapshot, 43.66, -70.26)
        assertTrue(out.daily.all { it.mud != null && it.activities.isNotEmpty() && it.outageRisk != null })
        assertTrue(out.daily.all { it.daytime != null && it.overnight != null })
        val foliage = assertNotNull(out.foliage)
        assertTrue(foliage.today!!.stage >= FoliageStage.PEAK, "${foliage.today}")
        assertTrue(foliage.days[1].leafDrop, "${foliage.days}")
        assertEquals(43.66, out.latitude)
        // Late October on the coast: some leaves are still up, so the windy day is still a concern.
        assertTrue(out.daily[1].outageRisk!!.score >= 45, "${out.daily[1].outageRisk}")
    }
}

private val FoliageReport_Note_COOL = app.northvane.core.model.FoliageReport.Note.COOL_NIGHTS
private val FoliageReport_Note_WARM = app.northvane.core.model.FoliageReport.Note.WARM_FALL
