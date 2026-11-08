package app.northvane.core

import app.northvane.core.garden.Crop
import app.northvane.core.garden.FrostClimate
import app.northvane.core.garden.GardenAdvisor
import app.northvane.core.garden.GardenCrop
import app.northvane.core.garden.HarvestStatus
import app.northvane.core.garden.PlantingMethod
import app.northvane.core.garden.PlantingStep
import app.northvane.core.garden.StepStatus
import app.northvane.core.garden.WateringAction
import app.northvane.core.history.AcisClient
import app.northvane.core.model.Activity
import app.northvane.core.model.DailyForecast
import app.northvane.core.model.HourlyForecast
import app.northvane.core.model.MudLevel
import app.northvane.core.model.MudOutlook
import app.northvane.core.model.ObservedDay
import app.northvane.core.model.OutageFactor
import app.northvane.core.model.OutageLevel
import app.northvane.core.model.OutageRisk
import app.northvane.core.model.PeriodStats
import app.northvane.core.model.Tip
import app.northvane.core.model.UnitSystem
import app.northvane.core.model.Units
import app.northvane.core.model.WeatherCondition
import app.northvane.core.model.WeatherHistory
import app.northvane.core.outlook.ActivityScorer
import app.northvane.core.outlook.DryingAdvisor
import app.northvane.core.outlook.DryingBlocker
import app.northvane.core.outlook.HeatSource
import app.northvane.core.outlook.HeatType
import app.northvane.core.outlook.HeatingAdvisor
import app.northvane.core.outlook.HeatingPhase
import app.northvane.core.outlook.HeatingTask
import app.northvane.core.outlook.OutlookText
import app.northvane.core.util.UnitFormatter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import app.northvane.core.time.*

class HomeAndGardenTest {
    private val zone = ZoneId.of("America/New_York")
    private fun resource(name: String) = requireNotNull(javaClass.getResource(name)) { "missing $name" }.readText()

    private val climate: FrostClimate by lazy {
        FrostClimate.compute(AcisClient.parseDailyLows(resource("/live/acis_mint30_portland.json")), kotlin.time.Clock.System.now())!!
    }

    private fun day(date: LocalDate, highF: Double, lowF: Double, chance: Int = 10, snowIn: Double = 0.0, daytime: PeriodStats? = null) = DailyForecast(
        date, "d", Units.fToC(highF), Units.fToC(lowF), WeatherCondition.CLEAR, true, null, chance,
        snowMm = Units.inchesToMm(snowIn), daytime = daytime,
    )

    private fun week(start: String, highF: Double, lowF: Double) = (0L until 7).map { day(LocalDate.parse(start).plusDays(it), highF, lowF) }

    private fun warmHistory(before: String, meanF: Double, days: Int = 14, rainIn: Double = 0.1) = WeatherHistory(
        (1..days).map { ObservedDay(LocalDate.parse(before).minusDays(it.toLong()), Units.fToC(meanF + 8), Units.fToC(meanF - 8), Units.inchesToMm(rainIn)) },
        fetchedAt = kotlin.time.Clock.System.now(),
    )

    // --- Frost climatology ---

    /** A leap year whose last spring frost falls on Feb 29 (Gulf Coast climates) must not crash. */
    @Test
    fun lastFrostOnLeapDay() {
        val rows = (2000..2023).flatMap { year ->
            val start = app.northvane.core.time.LocalDate.of(year, 1, 1)
            (0 until if (start.isLeapYear()) 366 else 365).map { d ->
                val date = start.plusDays(d.toLong())
                // Mild all year except a frost on the last day of February.
                val lastFeb = date.monthValue == 2 && date.dayOfMonth == date.lengthOfMonth()
                date to if (lastFeb || date.monthValue == 12 && date.dayOfMonth == 20) 30.0 else 50.0
            }
        }
        val climate = kotlin.test.assertNotNull(FrostClimate.compute(rows, kotlin.time.Clock.System.now()))
        assertEquals(app.northvane.core.time.LocalDate.of(2026, 2, 28), climate.lastFrost(2026))
    }

    @Test
    fun frostDatesFromThirtyYearsOfNoaaLows() {
        assertEquals(30, climate.years)
        assertEquals(LocalDate.parse("2026-04-23"), climate.lastFrost(2026))
        assertEquals(LocalDate.parse("2026-05-01"), climate.lastFrostLate(2026))
        assertEquals(LocalDate.parse("2026-10-23"), climate.firstFrost(2026))
        assertTrue(climate.firstFrostEarly(2026).isBefore(climate.firstFrost(2026)))
        assertTrue(climate.hardFreeze(2026).isAfter(climate.firstFrost(2026)))
        assertTrue(climate.growingSeasonDays in 170..195)
    }

    // --- Planting ---

    @Test
    fun tomatoesStartIndoorsInMarchAndPlantOutAfterLateFrost() {
        val plan = listOf(GardenCrop(Crop.TOMATO))
        val march = GardenAdvisor.report(plan, climate, week("2026-03-05", 40.0, 25.0), null, LocalDate.parse("2026-03-05"))
        assertTrue(march.showPlanting)
        val indoors = march.planting.first { it.step == PlantingStep.START_INDOORS }
        assertEquals(StepStatus.NOW, indoors.status)
        assertEquals(LocalDate.parse("2026-02-26"), indoors.windowStart) // 8 weeks before Apr 23
        assertEquals(StepStatus.UPCOMING, march.planting.first { it.step == PlantingStep.TRANSPLANT }.status)

        // Mid-May, warm soil and no frost in the forecast: plant on the first good day.
        val mayToday = LocalDate.parse("2026-05-12")
        val may = GardenAdvisor.report(plan, climate, week("2026-05-12", 70.0, 50.0), warmHistory("2026-05-12", 62.0), mayToday)
        val out = may.planting.first { it.step == PlantingStep.TRANSPLANT }
        assertEquals(StepStatus.GOOD_DAY, out.status)
        assertEquals(mayToday, out.recommended)
        assertEquals(StepStatus.DONE, may.planting.first { it.step == PlantingStep.START_INDOORS }.status)
    }

    @Test
    fun frostInTheForecastHoldsTenderPlantsBack() {
        val today = LocalDate.parse("2026-05-10")
        val days = week("2026-05-10", 64.0, 46.0).toMutableList()
        days[2] = days[2].copy(lowC = Units.fToC(33.0))
        val report = GardenAdvisor.report(listOf(GardenCrop(Crop.TOMATO, PlantingMethod.BUY_TRANSPLANTS)), climate, days, warmHistory("2026-05-10", 62.0), today)
        val advice = report.planting.single()
        // May 10 and 11 would each be followed by the frosty night of the 12th; the 13th onward is clear.
        assertEquals(StepStatus.GOOD_DAY, advice.status)
        assertEquals(LocalDate.parse("2026-05-13"), advice.recommended)
        assertEquals(LocalDate.parse("2026-05-12"), report.frostNights.single().date)

        val allCold = week("2026-05-10", 55.0, 30.0)
        val waiting = GardenAdvisor.report(listOf(GardenCrop(Crop.TOMATO, PlantingMethod.BUY_TRANSPLANTS)), climate, allCold, warmHistory("2026-05-10", 62.0), today)
        assertEquals(StepStatus.WAIT_FROST, waiting.planting.single().status)
    }

    @Test
    fun coldSoilDelaysWarmSeasonSowing() {
        val today = LocalDate.parse("2026-05-14")
        val report = GardenAdvisor.report(listOf(GardenCrop(Crop.BEANS)), climate, week("2026-05-14", 62.0, 45.0), warmHistory("2026-05-14", 50.0), today)
        val sow = report.planting.single()
        assertEquals(PlantingStep.DIRECT_SOW, sow.step)
        assertEquals(StepStatus.WAIT_SOIL, sow.status)
        assertTrue(sow.blockingValue!! < 60)
    }

    @Test
    fun peasGoInEarlyAndGarlicInFall() {
        val april = GardenAdvisor.report(listOf(GardenCrop(Crop.PEAS)), climate, week("2026-03-20", 50.0, 30.0), warmHistory("2026-03-20", 42.0), LocalDate.parse("2026-03-20"))
        assertEquals(StepStatus.GOOD_DAY, april.planting.single().status) // hardy: 30°F nights are fine
        val october = GardenAdvisor.report(listOf(GardenCrop(Crop.GARLIC)), climate, week("2026-11-05", 48.0, 30.0), null, LocalDate.parse("2026-11-05"))
        val garlic = october.planting.single()
        assertEquals(PlantingStep.FALL_PLANT, garlic.step)
        assertEquals(StepStatus.NOW, garlic.status)
        assertTrue(october.showPlanting)
    }

    @Test
    fun cardsOnlyShowInSeason() {
        val plan = listOf(GardenCrop(Crop.TOMATO), GardenCrop(Crop.LETTUCE))
        val january = GardenAdvisor.report(plan, climate, week("2026-01-10", 25.0, 10.0), null, LocalDate.parse("2026-01-10"))
        assertFalse(january.showPlanting)
        assertFalse(january.showHarvest)
        val august = GardenAdvisor.report(plan, climate, week("2026-08-10", 82.0, 62.0), warmHistory("2026-08-10", 72.0), LocalDate.parse("2026-08-10"))
        assertFalse(august.showPlanting)
        assertTrue(august.showHarvest)
    }

    @Test
    fun harvestTenderCropsBeforeFallFrost() {
        val plan = listOf(GardenCrop(Crop.TOMATO, plantedOn = LocalDate.parse("2026-05-20")), GardenCrop(Crop.KALE, plantedOn = LocalDate.parse("2026-04-15")))
        val days = week("2026-09-28", 58.0, 42.0).toMutableList()
        days[3] = days[3].copy(lowC = Units.fToC(30.0))
        val report = GardenAdvisor.report(plan, climate, days, null, LocalDate.parse("2026-09-28"))
        val tomato = report.harvest.first { it.crop == Crop.TOMATO }
        assertEquals(HarvestStatus.PICK_BEFORE_FROST, tomato.status)
        assertEquals(LocalDate.parse("2026-10-01"), tomato.frostDate)
        assertFalse(tomato.estimated)
        assertEquals(HarvestStatus.AFTER_FROST_SWEETER, report.harvest.first { it.crop == Crop.KALE }.status)
    }

    @Test
    fun wateringFollowsRainfall() {
        val today = LocalDate.parse("2026-07-15")
        val dry = GardenAdvisor.watering(warmHistory("2026-07-15", 75.0, rainIn = 0.0), week("2026-07-15", 86.0, 65.0), today)!!
        assertEquals(WateringAction.WATER, dry.action)
        val rainy = week("2026-07-15", 80.0, 62.0).toMutableList()
        rainy[0] = rainy[0].copy(precipitationMm = 25.0)
        assertEquals(WateringAction.SKIP_RAIN_COMING, GardenAdvisor.watering(warmHistory("2026-07-15", 75.0, rainIn = 0.0), rainy, today)!!.action)
        assertEquals(WateringAction.NOT_NEEDED, GardenAdvisor.watering(warmHistory("2026-07-15", 75.0, rainIn = 0.2), week("2026-07-15", 78.0, 60.0), today)!!.action)
    }

    // --- Clothes drying ---

    private fun hour(t: String, tempF: Double, rh: Double, windMph: Double, sky: Int, chance: Int = 0, day: Boolean = true) =
        HourlyForecast(Instant.parse(t), Units.fToC(tempF), WeatherCondition.CLEAR, day, null, chance, Units.mphToKph(windMph), "W", rh, skyCoverPercent = sky)

    @Test
    fun dryingNeedsWarmthAndNoRainAndNeverBelowFreezing() {
        val (sunny, _) = DryingAdvisor.index(Units.fToC(72.0), 40.0, Units.mphToKph(10.0), 10, 0, 0.0, true)
        val (muggy, _) = DryingAdvisor.index(Units.fToC(72.0), 92.0, Units.mphToKph(2.0), 90, 20, 0.0, true)
        val (frozen, why) = DryingAdvisor.index(Units.fToC(28.0), 40.0, Units.mphToKph(10.0), 0, 0, 0.0, true)
        val (rainy, rainWhy) = DryingAdvisor.index(Units.fToC(70.0), 80.0, Units.mphToKph(10.0), 100, 80, 2.0, true)
        assertTrue(sunny >= 75, "$sunny")
        assertTrue(muggy <= 15)
        assertEquals(0, frozen)
        assertEquals(DryingBlocker.FREEZING, why)
        assertEquals(DryingBlocker.RAIN, rainWhy)
        assertTrue(DryingAdvisor.hoursToDry(sunny) < 3.0)
    }

    @Test
    fun bestWindowEndsBeforeTheRain() {
        val now = Instant.parse("2026-07-15T13:00:00Z") // 9 AM EDT
        val hours = (0 until 12).map { i ->
            val t = now.plusSeconds(i * 3600L).toString()
            if (i < 6) hour(t, 78.0, 40.0, 9.0, 15) else hour(t, 70.0, 85.0, 15.0, 90, chance = 70)
        }
        val report = DryingAdvisor.report(hours, emptyList(), now, zone)
        val best = assertNotNull(report.best)
        assertEquals(now, best.start)
        assertEquals(now.plusSeconds(6 * 3600L), best.end)
        assertEquals(now.plusSeconds(6 * 3600L), report.nextRain)
        assertNotNull(DryingAdvisor.rainSoon(hours, now.plusSeconds(5 * 3600L)))
        assertNull(DryingAdvisor.rainSoon(hours, now))
    }

    // --- Heating ---

    private val sources = listOf(
        HeatSource("hp", HeatType.HEAT_PUMP, minF = 15),
        HeatSource("wood", HeatType.WOOD, maxF = 30),
        HeatSource("oil", HeatType.OIL, maxF = 5),
    )

    @Test
    fun heatingPicksSystemsByTheUsersRules() {
        val today = LocalDate.parse("2027-01-12")
        val days = listOf(day(today, 45.0, 30.0), day(today.plusDays(1), 20.0, 2.0), day(today.plusDays(2), 8.0, -8.0))
        val report = HeatingAdvisor.report(sources, days, null, climate, today, aggressive = true)
        assertEquals(HeatingPhase.PEAK, report.phase)
        assertEquals(listOf("Heat pump (mini-split / air-source)"), report.days[0].sources)
        assertEquals(listOf("Wood stove / fireplace"), report.days[1].sources)
        assertTrue("Oil furnace / boiler" in report.days[2].sources && "Wood stove / fireplace" in report.days[2].sources)
        val kinds = report.tasks.map { it.kind }
        assertTrue(HeatingTask.Kind.BELOW_HEAT_PUMP_RANGE in kinds)
        assertTrue(HeatingTask.Kind.CHECK_TANK_COLD_SNAP in kinds)
        assertTrue(HeatingTask.Kind.PROTECT_PIPES in kinds)
        assertTrue(report.weekHdd > 100)
    }

    @Test
    fun normalModeFollowsTheMultiWeekAverage() {
        val today = LocalDate.parse("2026-11-20")
        // A mild fortnight (averages near 40°F) with one cold night in the forecast.
        val past = warmHistory("2026-11-20", 38.0, 7)
        val days = week("2026-11-20", 46.0, 34.0).toMutableList()
        days[3] = day(days[3].date, 30.0, 10.0)
        val normal = HeatingAdvisor.report(sources, days, past, climate, today)
        assertEquals(listOf("Heat pump (mini-split / air-source)"), normal.recommended)
        assertTrue(normal.days.all { it.sources.isEmpty() && it.nightSources.isEmpty() }, "no daily switching")
        assertTrue(Units.cToF(normal.outlookMeanC!!) in 30.0..45.0)
        val aggressive = HeatingAdvisor.report(sources, days, past, climate, today, aggressive = true)
        assertTrue(aggressive.days[3].nightSources.isNotEmpty() || aggressive.days[3].sources.contains("Wood stove / fireplace"))
        // Wood boilers and pellet stoves need power; a wood stove doesn't.
        assertTrue(HeatType.WOOD_BOILER.needsPower && HeatType.PELLETS.needsPower && HeatType.PELLET_BOILER.needsPower && !HeatType.WOOD.needsPower)
    }

    @Test
    fun gardenPlantsCombinePlantingAndHarvest() {
        val plan = listOf(GardenCrop(Crop.TOMATO), GardenCrop(Crop.LETTUCE), GardenCrop(Crop.GARLIC))
        val may = GardenAdvisor.report(plan, climate, week("2026-05-12", 70.0, 50.0), warmHistory("2026-05-12", 62.0), LocalDate.parse("2026-05-12"))
        val rows = GardenAdvisor.plants(may, LocalDate.parse("2026-05-12"))
        val tomato = rows.first { it.crop == Crop.TOMATO }
        assertEquals(PlantingStep.TRANSPLANT, tomato.planting!!.step)
        assertNull(tomato.harvest)
        assertTrue(rows.none { it.crop == Crop.GARLIC }, "garlic isn't relevant in May")
        val august = GardenAdvisor.report(plan, climate, week("2026-08-01", 82.0, 62.0), null, LocalDate.parse("2026-08-01"))
        val aug = GardenAdvisor.plants(august, LocalDate.parse("2026-08-01"))
        assertTrue(aug.first { it.crop == Crop.TOMATO }.harvest != null && aug.first { it.crop == Crop.TOMATO }.planting == null)
        assertTrue(GardenAdvisor.plants(august, LocalDate.parse("2026-08-01"), harvest = false).none { it.crop == Crop.TOMATO })
    }

    @Test
    fun seasonalHeatingChores() {
        val august = HeatingAdvisor.report(sources, week("2026-08-20", 80.0, 60.0), warmHistory("2026-08-20", 70.0, 30), climate, LocalDate.parse("2026-08-20"))
        assertEquals(HeatingPhase.PREPARE, august.phase)
        val kinds = august.tasks.map { it.kind }
        assertTrue(HeatingTask.Kind.FILL_TANK_PREBUY in kinds && HeatingTask.Kind.BUY_SEASONED_WOOD in kinds && HeatingTask.Kind.CHIMNEY_SWEEP in kinds)
        val april = HeatingAdvisor.report(sources, week("2027-04-10", 55.0, 35.0), null, climate, LocalDate.parse("2027-04-10"))
        assertTrue(HeatingTask.Kind.SPLIT_STACK_NEXT_YEAR in april.tasks.map { it.kind })
        assertTrue(HeatingAdvisor.visible(LocalDate.parse("2026-11-01"), sources))
        assertTrue(HeatingAdvisor.visible(LocalDate.parse("2026-06-20"), sources)) // wood stacking reminder
        assertFalse(HeatingAdvisor.visible(LocalDate.parse("2026-06-20"), listOf(HeatSource("oil", HeatType.OIL))))
        val f = UnitFormatter(UnitSystem.IMPERIAL)
        HeatingTask.Kind.entries.forEach { assertTrue(OutlookText.heatingTask(HeatingTask(it, HeatType.OIL, -15.0), f).isNotBlank()) }
    }

    @Test
    fun outagePrepMentionsWhatNeedsPower() {
        val risk = OutageRisk(OutageLevel.HIGH, 60, listOf(OutageFactor(OutageFactor.Kind.ICE, 10.0)))
        val steps = OutlookText.outagePrep(risk, listOf(HeatType.OIL, HeatType.WOOD))
        assertTrue(steps.any { "oil furnace / boiler won't run without power" in it && "wood stove" in it }, "$steps")
        assertTrue(steps.any { "pipes" in it })
        assertTrue(steps.any { "Generators" in it })
    }

    // --- Seasonal activities ---

    private val ctx = ActivityScorer.Context(43.66, -70.26, zone, emptyList())

    private fun winterDay(date: String, snowIn: Double = 0.0) = day(
        LocalDate.parse(date), 28.0, 15.0, snowIn = snowIn,
        daytime = PeriodStats(tempMaxC = Units.fToC(28.0), tempMinC = Units.fToC(18.0), windMeanKph = 8.0, gustMaxKph = 20.0, skyCoverMean = 40, snowMm = Units.inchesToMm(snowIn)),
    )

    private fun names(scores: List<app.northvane.core.model.ActivityScore>) = scores.map { it.label }

    @Test
    fun snowpackBringsWinterActivities() {
        val d = winterDay("2027-01-20", snowIn = 4.0)
        val snow = MudOutlook(MudLevel.SNOW_COVERED, 10, snowDepthMm = Units.inchesToMm(14.0))
        val confirmed = ctx.copy(snowConfirmed = true)
        val scores = ActivityScorer.score(d, snow, confirmed)
        val labels = names(scores)
        assertTrue("Downhill skiing" in labels && "Cross-country skiing" in labels && "Snowshoeing" in labels && "Snowmobiling" in labels, "$labels")
        assertTrue("Winter hiking" in labels && "Winter camping" in labels && "Fat biking" in labels, "$labels")
        assertFalse(scores.any { it.activity == Activity.PADDLING || it.activity == Activity.OFF_ROADING || it.activity == Activity.SWIMMING })
        assertTrue("Stargazing" in labels, "$labels")
        val ski = scores.first { it.activity == Activity.SKIING }
        assertTrue(Tip.Kind.POWDER_DAY in ski.tips.map { it.kind }, "${ski.tips}")
        val hike = scores.first { it.activity == Activity.HIKING }
        assertTrue(hike.tips.any { it.kind == Tip.Kind.SNOWSHOES || it.kind == Tip.Kind.WINTER_GEAR || it.kind == Tip.Kind.MICROSPIKES }, "${hike.tips}")
        val wax = scores.first { it.activity == Activity.XC_SKIING }.tips.first { it.kind == Tip.Kind.KICK_WAX }
        assertTrue(OutlookText.tip(wax, UnitFormatter(UnitSystem.IMPERIAL)).contains("purple")) // high of 28°F
    }

    @Test
    fun snowSportsNeedConfirmedSnow() {
        val modelOnly = ActivityScorer.score(winterDay("2027-01-20"), MudOutlook(MudLevel.SNOW_COVERED, 10, snowDepthMm = 300.0), ctx)
        assertTrue(modelOnly.none { it.activity == Activity.SKIING })
        val thin = ActivityScorer.score(winterDay("2027-01-20"), MudOutlook(MudLevel.SNOW_COVERED, 10, snowDepthMm = Units.inchesToMm(3.0)), ctx.copy(snowConfirmed = true))
        val acts = thin.map { it.activity }
        assertTrue(Activity.SKIING in acts && Activity.SNOWSHOEING !in acts && Activity.SNOWMOBILING !in acts)
        val july = ActivityScorer.score(day(LocalDate.parse("2026-07-20"), 86.0, 66.0), null, ctx)
        assertTrue(july.none { it.activity in setOf(Activity.SKIING, Activity.XC_SKIING, Activity.SNOWMOBILING, Activity.ICE_FISHING, Activity.HUNTING) })
        assertTrue(july.any { it.activity == Activity.SWIMMING })
        assertTrue(names(july).contains("Hiking"))
    }

    @Test
    fun huntingInSeasonAndIceFishingOnSolidIce() {
        val nov = ActivityScorer.score(day(LocalDate.parse("2026-11-14"), 42.0, 25.0), null, ctx)
        val hunt = nov.first { it.activity == Activity.HUNTING }
        assertTrue(Tip.Kind.BLAZE_ORANGE in hunt.tips.map { it.kind } || hunt.tips.size >= 3)
        assertTrue(ActivityScorer.score(day(LocalDate.parse("2026-07-14"), 80.0, 60.0), null, ctx).none { it.activity == Activity.HUNTING })
        val iced = ctx.copy(waterIce = mapOf(LocalDate.parse("2027-02-01") to 150.0))
        val feb = ActivityScorer.score(winterDay("2027-02-01"), null, iced)
        assertTrue(feb.any { it.activity == Activity.ICE_FISHING })
        val thinIce = ctx.copy(waterIce = mapOf(LocalDate.parse("2027-02-01") to 20.0))
        assertTrue(ActivityScorer.score(winterDay("2027-02-01"), null, thinIce).none { it.activity == Activity.ICE_FISHING })
    }

    @Test
    fun everyNewTipHasWords() {
        val f = UnitFormatter(UnitSystem.METRIC)
        Tip.Kind.entries.forEach { assertTrue(OutlookText.tip(Tip(it, 12.0), f).isNotBlank(), "$it") }
    }
}
