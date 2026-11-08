package app.northvane.core.garden

import app.northvane.core.json.InstantSerializer
import app.northvane.core.model.DailyForecast
import app.northvane.core.model.MudLevel
import app.northvane.core.model.Units
import app.northvane.core.model.WeatherHistory
import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.roundToInt
import app.northvane.core.time.*

/**
 * Frost dates for a location from 30 years of NOAA gridded daily lows
 * (ACIS / PRISM). Days are day-of-year in a non-leap year.
 */
@Serializable
data class FrostClimate(
    /** Median date of the last spring low at or below 32°F. */
    val lastSpringFrost: Int,
    /** 1-in-10 late last frost: after this date frost is rare. */
    val lastSpringFrostLate: Int,
    /** Median date of the first fall low at or below 32°F. */
    val firstFallFrost: Int,
    /** 1-in-10 early first frost. */
    val firstFallFrostEarly: Int,
    /** Median first hard freeze (28°F) in fall. */
    val firstHardFreeze: Int,
    val years: Int,
    @Serializable(InstantSerializer::class) val computedAt: Instant,
) {
    fun lastFrost(year: Int): LocalDate = dayOf(year, lastSpringFrost)
    fun lastFrostLate(year: Int): LocalDate = dayOf(year, lastSpringFrostLate)
    fun firstFrost(year: Int): LocalDate = dayOf(year, firstFallFrost)
    fun firstFrostEarly(year: Int): LocalDate = dayOf(year, firstFallFrostEarly)
    fun hardFreeze(year: Int): LocalDate = dayOf(year, firstHardFreeze)
    val growingSeasonDays: Int get() = firstFallFrost - lastSpringFrost

    companion object {
        private fun dayOf(year: Int, doy: Int): LocalDate = LocalDate.of(2025, 1, 1).plusDays(doy - 1L).withYear(year)

        /** Frost statistics from daily minimum temperatures (°F). Needs at least 10 complete years. */
        fun compute(rows: List<Pair<LocalDate, Double>>, now: Instant): FrostClimate? {
            val byYear = rows.groupBy { it.first.year }.filterValues { it.size >= 330 }
            if (byYear.size < 10) return null
            // Day of year in a non-leap year; Feb 29 counts as Feb 28.
            fun doy(d: LocalDate) = LocalDate.of(2025, d.monthValue, if (d.monthValue == 2) minOf(d.dayOfMonth, 28) else d.dayOfMonth).dayOfYear
            val last = mutableListOf<Int>()
            val first = mutableListOf<Int>()
            val hard = mutableListOf<Int>()
            for ((_, days) in byYear) {
                val sorted = days.sortedBy { it.first }
                sorted.filter { it.first.monthValue < 7 && it.second <= 32 }.maxByOrNull { it.first }?.let { last += doy(it.first) }
                sorted.firstOrNull { it.first.monthValue >= 7 && it.second <= 32 }?.let { first += doy(it.first) }
                sorted.firstOrNull { it.first.monthValue >= 7 && it.second <= 28 }?.let { hard += doy(it.first) }
            }
            if (last.isEmpty() || first.isEmpty()) return null
            fun pct(values: List<Int>, p: Double) = values.sorted().let { it[((it.size - 1) * p).roundToInt()] }
            return FrostClimate(
                lastSpringFrost = pct(last, 0.5),
                lastSpringFrostLate = pct(last, 0.9),
                firstFallFrost = pct(first, 0.5),
                firstFallFrostEarly = pct(first, 0.1),
                firstHardFreeze = if (hard.isEmpty()) pct(first, 0.5) + 14 else pct(hard, 0.5),
                years = byYear.size,
                computedAt = now,
            )
        }
    }
}

@Serializable
enum class PlantingStep(val label: String) {
    START_INDOORS("Start seeds indoors"),
    HARDEN_OFF("Harden off seedlings"),
    TRANSPLANT("Plant out transplants"),
    DIRECT_SOW("Sow outdoors"),
    FALL_PLANT("Plant for next year"),
}

@Serializable
enum class StepStatus { UPCOMING, NOW, GOOD_DAY, WAIT_FROST, WAIT_SOIL, LATE, DONE }

@Serializable
data class PlantingAdvice(
    val crop: Crop,
    val step: PlantingStep,
    val status: StepStatus,
    @Serializable(app.northvane.core.json.LocalDateSerializer::class) val windowStart: LocalDate,
    @Serializable(app.northvane.core.json.LocalDateSerializer::class) val windowEnd: LocalDate,
    /** A specific good day from the forecast, when there is one. */
    @Serializable(app.northvane.core.json.LocalDateSerializer::class) val recommended: LocalDate? = null,
    /** Frost date or soil temperature (°F) behind a WAIT status. */
    @Serializable(app.northvane.core.json.LocalDateSerializer::class) val blockingDate: LocalDate? = null,
    val blockingValue: Double? = null,
)

@Serializable
enum class HarvestStatus { GROWING, READY, PICK_BEFORE_FROST, COVER_FOR_FROST, AFTER_FROST_SWEETER, FINISHED }

@Serializable
data class HarvestAdvice(
    val crop: Crop,
    val status: HarvestStatus,
    @Serializable(app.northvane.core.json.LocalDateSerializer::class) val start: LocalDate,
    @Serializable(app.northvane.core.json.LocalDateSerializer::class) val end: LocalDate,
    @Serializable(app.northvane.core.json.LocalDateSerializer::class) val frostDate: LocalDate? = null,
    val frostLowC: Double? = null,
    /** True when timing is estimated from the recommended planting date rather than a date the user entered. */
    val estimated: Boolean = true,
)

@Serializable
data class FrostNight(@Serializable(app.northvane.core.json.LocalDateSerializer::class) val date: LocalDate, val lowC: Double)

@Serializable
enum class WateringAction { WATER, LIGHT_WATER, SKIP_RAIN_COMING, NOT_NEEDED }

@Serializable
data class WateringAdvice(val action: WateringAction, val pastWeekMm: Double, val comingMm: Double, val neededMm: Double)

@Serializable
enum class SoilState(val label: String) { FROZEN("Frozen"), TOO_WET("Too wet to work"), WORKABLE("Ready to work"), DRY("Dry: water before planting") }

/** One plant's current planting step and/or harvest outlook, for a single combined garden card. */
data class PlantStatus(val crop: Crop, val planting: PlantingAdvice?, val harvest: HarvestAdvice?)

/** Everything the garden cards show. */
@Serializable
data class GardenReport(
    val climate: FrostClimate,
    val planting: List<PlantingAdvice>,
    val harvest: List<HarvestAdvice>,
    val showPlanting: Boolean,
    val showHarvest: Boolean,
    val frostNights: List<FrostNight>,
    val watering: WateringAdvice?,
    val soil: SoilState?,
    /** Estimated soil temperature at planting depth, °F (from recent air temperatures). */
    val soilTempF: Double?,
    /** Growing degree days (base 50°F) since March 1. */
    val gddBase50: Double?,
    val inGrowingSeason: Boolean,
)

/**
 * Planting and harvest timing for the user's garden: frost-date windows from
 * climatology, then checked against the actual forecast (no frost for the
 * crop's tolerance in the coming nights, warm enough soil).
 */
object GardenAdvisor {

    fun report(
        plan: List<GardenCrop>,
        climate: FrostClimate,
        days: List<DailyForecast>,
        history: WeatherHistory?,
        today: LocalDate,
    ): GardenReport {
        val year = today.year
        val lastFrost = climate.lastFrost(year)
        val lastFrostLate = climate.lastFrostLate(year)
        val firstFrost = climate.firstFrost(year)
        val ahead = days.filter { !it.date.isBefore(today) }.sortedBy { it.date }
        val soilF = soilTemperatureF(history, ahead, today)

        val planting = plan.flatMap { plantingSteps(it, climate, ahead, soilF, today) }
        val harvest = plan.mapNotNull { harvestFor(it, climate, ahead, planting, today) }

        val earliestStart = planting.minOfOrNull { it.windowStart } ?: lastFrost
        val latestEnd = planting.maxOfOrNull { it.windowEnd } ?: lastFrost
        val showPlanting = planting.any { it.status != StepStatus.DONE } &&
            !today.isBefore(earliestStart.minusDays(14)) && !today.isAfter(latestEnd.plusDays(7))
        val showHarvest = harvest.any { h ->
            h.status != HarvestStatus.FINISHED && !today.isBefore(h.start.minusDays(14)) && !today.isAfter(h.end.plusDays(7))
        }
        val inSeason = !today.isBefore(lastFrost.minusDays(21)) && !today.isAfter(climate.hardFreeze(year).plusDays(14))
        val frostNights = if (inSeason) {
            ahead.take(7).mapNotNull { d -> d.lowC?.takeIf { Units.cToF(it) <= 34.0 }?.let { FrostNight(d.date, it) } }
        } else {
            emptyList()
        }
        return GardenReport(
            climate = climate,
            planting = planting.sortedWith(compareBy({ statusOrder(it.status) }, { it.windowStart })),
            harvest = harvest.sortedBy { it.start },
            showPlanting = showPlanting,
            showHarvest = showHarvest,
            frostNights = frostNights,
            watering = if (!today.isBefore(lastFrostLate.minusDays(7)) && !today.isAfter(firstFrost)) watering(history, ahead, today) else null,
            soil = soilState(ahead.firstOrNull(), soilF),
            soilTempF = soilF,
            gddBase50 = gdd(history, today),
            inGrowingSeason = inSeason,
        )
    }

    /**
     * Per plant: the next planting step that's relevant now (open, blocked, late,
     * or starting within three weeks) and the harvest if it's coming up within
     * three weeks or under way. Plants with neither are left out.
     */
    fun plants(report: GardenReport, today: LocalDate, planting: Boolean = true, harvest: Boolean = true): List<PlantStatus> {
        val order = PlantingStep.entries
        val crops = (report.planting.map { it.crop } + report.harvest.map { it.crop }).distinct()
        return crops.mapNotNull { crop ->
            // The most actionable step first (a good planting day beats hardening off), then the earliest step.
            val step = report.planting.filter { it.crop == crop && it.status != StepStatus.DONE }
                .filterNot { it.status == StepStatus.LATE && today.isAfter(it.windowEnd.plusDays(28)) }
                .minWithOrNull(compareBy({ if (it.status == StepStatus.GOOD_DAY) 0 else 1 }, { statusOrder(it.status) }, { order.indexOf(it.step) }))
                ?.takeIf { planting && (it.status != StepStatus.UPCOMING || !it.windowStart.isAfter(today.plusDays(21))) }
                ?.takeUnless { it.status == StepStatus.LATE && today.isAfter(it.windowEnd.plusDays(28)) }
            val h = report.harvest.firstOrNull { it.crop == crop }
                ?.takeIf { harvest && it.status != HarvestStatus.FINISHED && !today.isBefore(it.start.minusDays(21)) && !today.isAfter(it.end.plusDays(7)) }
            if (step == null && h == null) null else PlantStatus(crop, step, h)
        }.sortedBy { p ->
            when {
                p.planting?.status in setOf(StepStatus.GOOD_DAY, StepStatus.NOW) -> 0
                p.harvest?.status in setOf(HarvestStatus.PICK_BEFORE_FROST, HarvestStatus.COVER_FOR_FROST) -> 0
                p.harvest?.status == HarvestStatus.READY -> 1
                p.planting?.status in setOf(StepStatus.WAIT_FROST, StepStatus.WAIT_SOIL) -> 2
                else -> 3
            }
        }
    }

    private fun statusOrder(s: StepStatus) = when (s) {
        StepStatus.GOOD_DAY, StepStatus.NOW -> 0
        StepStatus.WAIT_FROST, StepStatus.WAIT_SOIL -> 1
        StepStatus.UPCOMING -> 2
        StepStatus.LATE -> 3
        StepStatus.DONE -> 4
    }

    private fun weeks(base: LocalDate, w: Int) = base.plusDays(w * 7L)

    internal fun plantingSteps(g: GardenCrop, climate: FrostClimate, ahead: List<DailyForecast>, soilF: Double?, today: LocalDate): List<PlantingAdvice> {
        val crop = g.crop
        val year = today.year
        // Tender crops key off the late (1-in-10) last frost; hardier ones off the median.
        val reference = if (crop.tolerance == FrostTolerance.TENDER) climate.lastFrostLate(year) else climate.lastFrost(year)
        if (crop.fallPlanted) {
            // Garlic: plant a few weeks after the first fall frost, before the ground freezes.
            val start = climate.firstFrost(year).plusDays(7)
            val end = climate.firstFrost(year).plusDays(35)
            val status = when {
                g.plantedOn != null && g.plantedOn.year == year && g.plantedOn.monthValue >= 8 -> StepStatus.DONE
                today.isBefore(start) -> StepStatus.UPCOMING
                today.isAfter(end) -> StepStatus.LATE
                else -> StepStatus.NOW
            }
            return listOf(PlantingAdvice(crop, PlantingStep.FALL_PLANT, status, start, end))
        }
        val planted = g.plantedOn?.takeIf { it.year == year }
        val steps = mutableListOf<PlantingAdvice>()
        when (g.resolvedMethod) {
            PlantingMethod.SEED_INDOORS -> {
                val range = crop.startIndoors ?: (4..6)
                // Indoor sowing counts back from the median last frost, as seed packets do.
                val start = weeks(climate.lastFrost(year), -range.last)
                val end = weeks(climate.lastFrost(year), -range.first)
                val transplantStart = weeks(reference, crop.transplant ?: 0)
                val indoorStatus = when {
                    planted != null || today.isAfter(transplantStart) -> StepStatus.DONE
                    today.isBefore(start) -> StepStatus.UPCOMING
                    today.isAfter(end.plusDays(14)) -> StepStatus.LATE
                    else -> StepStatus.NOW
                }
                steps += PlantingAdvice(crop, PlantingStep.START_INDOORS, indoorStatus, start, end)
                steps += hardenOff(crop, transplantStart, planted, today)
                steps += outdoor(crop, PlantingStep.TRANSPLANT, transplantStart, ahead, soilF, planted, today)
            }
            PlantingMethod.BUY_TRANSPLANTS -> {
                val transplantStart = weeks(reference, crop.transplant ?: crop.directSow ?: 0)
                steps += outdoor(crop, PlantingStep.TRANSPLANT, transplantStart, ahead, soilF, planted, today)
            }
            PlantingMethod.DIRECT_SOW, PlantingMethod.AUTO -> {
                val start = weeks(reference, crop.directSow ?: crop.transplant ?: 0)
                steps += outdoor(crop, PlantingStep.DIRECT_SOW, start, ahead, soilF, planted, today)
            }
        }
        return steps
    }

    private fun hardenOff(crop: Crop, transplantStart: LocalDate, planted: LocalDate?, today: LocalDate): PlantingAdvice {
        val start = transplantStart.minusDays(10)
        val status = when {
            planted != null || today.isAfter(transplantStart.plusDays(21)) -> StepStatus.DONE
            today.isBefore(start) -> StepStatus.UPCOMING
            else -> StepStatus.NOW
        }
        return PlantingAdvice(crop, PlantingStep.HARDEN_OFF, status, start, transplantStart)
    }

    /** Planting outdoors: open window, then the first forecast day with safe nights and warm-enough soil. */
    private fun outdoor(
        crop: Crop,
        step: PlantingStep,
        windowStart: LocalDate,
        ahead: List<DailyForecast>,
        soilF: Double?,
        planted: LocalDate?,
        today: LocalDate,
    ): PlantingAdvice {
        val windowEnd = windowStart.plusDays(28)
        if (planted != null) return PlantingAdvice(crop, step, StepStatus.DONE, windowStart, windowEnd)
        if (today.isBefore(windowStart.minusDays(7))) return PlantingAdvice(crop, step, StepStatus.UPCOMING, windowStart, windowEnd)
        if (today.isAfter(windowEnd.plusDays(21))) return PlantingAdvice(crop, step, StepStatus.LATE, windowStart, windowEnd)

        val safeLow = crop.tolerance.safeLowF
        fun lowF(d: DailyForecast) = d.lowC?.let(Units::cToF)
        val candidates = ahead.filter { !it.date.isBefore(windowStart) && !it.date.isBefore(today) }
        val soilOk = soilF == null || soilF >= crop.minSoilF - 2
        if (!soilOk && candidates.isNotEmpty()) {
            return PlantingAdvice(crop, step, StepStatus.WAIT_SOIL, windowStart, windowEnd, blockingValue = soilF)
        }
        for (day in candidates) {
            // This night and the following ones in the forecast must stay above the crop's limit.
            val nights = ahead.filter { !it.date.isBefore(day.date) }.take(5)
            val cold = nights.firstOrNull { (lowF(it) ?: 99.0) < safeLow }
            if (cold == null && (day.precipitationChance ?: 0) < 70) {
                return PlantingAdvice(crop, step, StepStatus.GOOD_DAY, windowStart, windowEnd, recommended = day.date)
            }
        }
        val frost = ahead.firstOrNull { (lowF(it) ?: 99.0) < safeLow }
        return if (frost != null && candidates.isNotEmpty()) {
            PlantingAdvice(crop, step, StepStatus.WAIT_FROST, windowStart, windowEnd, blockingDate = frost.date, blockingValue = lowF(frost))
        } else {
            PlantingAdvice(crop, step, if (today.isBefore(windowStart)) StepStatus.UPCOMING else StepStatus.NOW, windowStart, windowEnd)
        }
    }

    internal fun harvestFor(g: GardenCrop, climate: FrostClimate, ahead: List<DailyForecast>, planting: List<PlantingAdvice>, today: LocalDate): HarvestAdvice? {
        val crop = g.crop
        val year = today.year
        val outdoor = planting.firstOrNull { it.crop == crop && it.step in setOf(PlantingStep.TRANSPLANT, PlantingStep.DIRECT_SOW, PlantingStep.FALL_PLANT) }
        val plantedOn = g.plantedOn
        val planted: LocalDate = when {
            crop.fallPlanted -> plantedOn?.takeIf { it.year == year - 1 } ?: climate.firstFrost(year - 1).plusDays(14)
            plantedOn != null && plantedOn.year == year -> plantedOn
            else -> outdoor?.recommended ?: outdoor?.windowStart ?: return null
        }
        val start = planted.plusDays(crop.daysToHarvest.first.toLong())
        var end = planted.plusDays(crop.daysToHarvest.last.toLong()).plusDays(crop.harvestWeeks * 7L)
        val hardFreeze = climate.hardFreeze(year)
        if (crop.tolerance != FrostTolerance.HARDY && end.isAfter(hardFreeze)) end = hardFreeze
        if (crop.tolerance == FrostTolerance.HARDY && crop.frostSweetened) end = maxOf(end, hardFreeze.plusDays(21))
        val estimated = plantedOn == null

        val frost = ahead.firstOrNull { d -> d.lowC?.let { Units.cToF(it) <= 32.0 } == true && !d.date.isBefore(start.minusDays(30)) }
        val hard = ahead.firstOrNull { d -> d.lowC?.let { Units.cToF(it) <= 28.0 } == true }
        val inWindow = !today.isBefore(start.minusDays(3)) && !today.isAfter(end)
        val status = when {
            today.isAfter(end.plusDays(7)) -> HarvestStatus.FINISHED
            crop.tolerance == FrostTolerance.TENDER && frost != null && !today.isBefore(start.minusDays(30)) ->
                if (inWindow) HarvestStatus.PICK_BEFORE_FROST else HarvestStatus.COVER_FOR_FROST
            crop.tolerance == FrostTolerance.HALF_HARDY && hard != null && inWindow -> HarvestStatus.PICK_BEFORE_FROST
            crop.frostSweetened && frost != null && inWindow -> HarvestStatus.AFTER_FROST_SWEETER
            inWindow -> HarvestStatus.READY
            else -> HarvestStatus.GROWING
        }
        val frostDay = when (status) {
            HarvestStatus.PICK_BEFORE_FROST -> if (crop.tolerance == FrostTolerance.HALF_HARDY) hard else frost
            HarvestStatus.COVER_FOR_FROST, HarvestStatus.AFTER_FROST_SWEETER -> frost
            else -> null
        }
        return HarvestAdvice(crop, status, start, end, frostDay?.date, frostDay?.lowC, estimated)
    }

    /**
     * Soil temperature at seeding depth tracks the average air temperature of
     * the past several days fairly well in spring; this estimate uses the last
     * five observed days plus today's forecast.
     */
    fun soilTemperatureF(history: WeatherHistory?, ahead: List<DailyForecast>, today: LocalDate): Double? {
        val recent = history?.days.orEmpty().filter { it.date.isBefore(today) && it.highC != null && it.lowC != null }
            .sortedBy { it.date }.takeLast(5).map { (it.highC!! + it.lowC!!) / 2 }
        val todayMean = ahead.firstOrNull()?.let { d -> if (d.highC != null && d.lowC != null) (d.highC + d.lowC) / 2 else null }
        val all = recent + listOfNotNull(todayMean)
        if (all.size < 3) return null
        return Units.cToF(all.average())
    }

    private fun soilState(today: DailyForecast?, soilF: Double?): SoilState? {
        val mud = today?.mud ?: return null
        return when {
            mud.level == MudLevel.FROZEN || mud.level == MudLevel.SNOW_COVERED || (soilF != null && soilF < 33) -> SoilState.FROZEN
            mud.mudSeason || mud.level == MudLevel.MUDDY || mud.level == MudLevel.VERY_MUDDY -> SoilState.TOO_WET
            mud.level == MudLevel.DRY && mud.reasons.any { it.kind == app.northvane.core.model.MudReason.Kind.DRYING } -> SoilState.DRY
            else -> SoilState.WORKABLE
        }
    }

    /** About an inch of water a week, more in heat; rain in the past week and the next two days counts. */
    fun watering(history: WeatherHistory?, ahead: List<DailyForecast>, today: LocalDate): WateringAdvice? {
        val past = history?.days.orEmpty().filter { it.date.isBefore(today) && !it.date.isBefore(today.minusDays(7)) }
        if (past.size < 4) return null
        val pastMm = past.sumOf { it.precipMm ?: 0.0 }
        val coming = ahead.take(2).sumOf { d -> d.precipitationMm ?: if ((d.precipitationChance ?: 0) >= 60) 5.0 else 0.0 }
        val hot = ahead.take(3).any { (it.highC ?: 0.0) >= Units.fToC(85.0) }
        val need = if (hot) 32.0 else 25.0
        val deficit = need - pastMm
        val action = when {
            deficit <= 3 -> WateringAction.NOT_NEEDED
            coming >= minOf(deficit, 10.0) -> WateringAction.SKIP_RAIN_COMING
            deficit >= 12 -> WateringAction.WATER
            else -> WateringAction.LIGHT_WATER
        }
        return WateringAdvice(action, pastMm, coming, max(0.0, deficit))
    }

    /** Growing degree days, base 50°F, since March 1 (corn and pest timing use these). */
    fun gdd(history: WeatherHistory?, today: LocalDate): Double? {
        val season = history?.days.orEmpty().filter { !it.date.isBefore(LocalDate.of(today.year, 3, 1)) && it.date.isBefore(today) }
        if (today.monthValue !in 4..10 || season.isEmpty()) return null
        val expected = ChronoUnit.DAYS.between(LocalDate.of(today.year, 3, 1), today)
        if (season.size < expected * 0.8) return null
        return season.sumOf { d ->
            val hi = d.highC?.let(Units::cToF)?.coerceIn(50.0, 86.0) ?: return@sumOf 0.0
            val lo = d.lowC?.let(Units::cToF)?.coerceIn(50.0, 86.0) ?: return@sumOf 0.0
            (hi + lo) / 2 - 50
        }
    }
}
