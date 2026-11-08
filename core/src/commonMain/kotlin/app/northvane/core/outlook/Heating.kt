package app.northvane.core.outlook

import app.northvane.core.garden.FrostClimate
import app.northvane.core.json.LocalDateSerializer
import app.northvane.core.model.DailyForecast
import app.northvane.core.model.Units
import app.northvane.core.model.WeatherHistory
import kotlinx.serialization.Serializable
import app.northvane.core.time.*

@Serializable
enum class HeatType(val label: String, val needsPower: Boolean, val fuel: String?) {
    HEAT_PUMP("Heat pump (mini-split / air-source)", true, null),
    GEOTHERMAL("Geothermal heat pump", true, null),
    OIL("Oil furnace / boiler", true, "oil"),
    PROPANE("Propane", true, "propane"),
    NATURAL_GAS("Natural gas", true, null),
    KEROSENE("Kerosene / direct-vent heater", true, "kerosene"),
    /** Stored as WOOD for compatibility with 1.3 settings. */
    WOOD("Wood stove / fireplace", false, "wood"),
    WOOD_BOILER("Wood boiler / wood furnace", true, "wood"),
    /** Stored as PELLETS for compatibility with 1.3 settings. */
    PELLETS("Pellet stove", true, "pellets"),
    PELLET_BOILER("Pellet boiler / pellet furnace", true, "pellets"),
    ELECTRIC("Electric baseboard / resistance", true, null),
}

/**
 * One way the user heats, and when they use it: an outdoor temperature range
 * (°F, using the day's average) — e.g. heat pump above 20°F, wood stove
 * below 30°F, oil as backup below 5°F. Ranges may overlap.
 */
@Serializable
data class HeatSource(
    val id: String,
    val type: HeatType,
    val name: String? = null,
    val minF: Int? = null,
    val maxF: Int? = null,
) {
    val label: String get() = name?.takeIf { it.isNotBlank() } ?: type.label
    fun covers(tempF: Double): Boolean = (minF == null || tempF >= minF) && (maxF == null || tempF < maxF)
}

@Serializable
enum class HeatingPhase(val label: String) {
    OFF("Off season"),
    PREPARE("Get ready for heating season"),
    EARLY("Heating season is starting"),
    PEAK("Heart of heating season"),
    LATE("Heating season is winding down"),
}

@Serializable
data class HeatingDay(
    @Serializable(LocalDateSerializer::class) val date: LocalDate,
    val meanC: Double?,
    val lowC: Double?,
    /** Heating degree days (base 65°F). */
    val hdd: Double,
    /** Which of the user's sources fit this day's temperature (by their own rules). */
    val sources: List<String>,
    /** A different source covers the coldest part of the night. */
    val nightSources: List<String> = emptyList(),
)

@Serializable
data class HeatingTask(val kind: Kind, val source: HeatType? = null, val value: Double? = null) {
    @Serializable
    enum class Kind {
        SERVICE_SYSTEM, CHIMNEY_SWEEP, CO_DETECTORS, FILL_TANK_PREBUY, CHECK_TANK_COLD_SNAP, BUY_SEASONED_WOOD,
        SPLIT_STACK_NEXT_YEAR, MOVE_WOOD_IN, BUY_PELLETS, HEAT_PUMP_FILTERS, CLEAR_OUTDOOR_UNIT, BELOW_HEAT_PUMP_RANGE,
        PROTECT_PIPES, END_OF_SEASON, MONITOR_FUEL, NO_SOURCES,
    }
}

@Serializable
data class HeatingReport(
    val phase: HeatingPhase,
    /** Sources that fit the average temperature over the coming weeks (the normal recommendation). */
    val recommended: List<String> = emptyList(),
    /** Average temperature over the past week and the forecast week, °C. */
    val outlookMeanC: Double? = null,
    /** Day-by-day (and night) sources: only filled in aggressive mode. */
    val days: List<HeatingDay>,
    val tasks: List<HeatingTask>,
    val weekHdd: Double,
    /** Heating degree days over the last 30 observed days. */
    val recentHdd: Double?,
    @Serializable(LocalDateSerializer::class) val typicalStart: LocalDate?,
    @Serializable(LocalDateSerializer::class) val typicalEnd: LocalDate?,
)

/**
 * Heating season helper: which of the user's systems to run this week (by
 * their own temperature rules), and seasonal chores timed to the local
 * climate — fuel pre-buys, wood seasoning, service visits, cold-snap prep.
 */
object HeatingAdvisor {

    /** The card is useful from mid-summer prep (fuel pre-buy, wood) through the end of May. */
    fun visible(today: LocalDate, sources: List<HeatSource>): Boolean {
        val md = MonthDay.from(today)
        val woodSeason = sources.any { it.type.fuel == "wood" } && today.monthValue in 3..7
        return md >= MonthDay.of(7, 15) || md <= MonthDay.of(5, 31) || woodSeason
    }

    fun report(
        sources: List<HeatSource>,
        days: List<DailyForecast>,
        history: WeatherHistory?,
        climate: FrostClimate?,
        today: LocalDate,
        /** Switch sources day by day (and at night) instead of following the multi-week average. */
        aggressive: Boolean = false,
    ): HeatingReport {
        val year = today.year
        // Heating typically starts around the first fall frost and runs until a few weeks after the last spring frost.
        val typicalStart = climate?.firstFrost(year)?.minusDays(21) ?: LocalDate.of(year, 9, 25)
        val typicalEnd = climate?.lastFrost(year)?.plusDays(21) ?: LocalDate.of(year, 5, 15)
        val recent = history?.days.orEmpty().filter { it.date.isBefore(today) }.sortedBy { it.date }.takeLast(30)
        val recentHdd = recent.takeIf { it.size >= 20 }?.sumOf { hdd(it.highC, it.lowC) }
        val weekMean = recent.takeLast(7).mapNotNull { d -> if (d.highC != null && d.lowC != null) (d.highC + d.lowC) / 2 else null }
            .takeIf { it.isNotEmpty() }?.average()?.let(Units::cToF)
        val phase = when {
            today.monthValue in 6..7 && (weekMean ?: 70.0) > 55 -> if (MonthDay.from(today) >= MonthDay.of(7, 15)) HeatingPhase.PREPARE else HeatingPhase.OFF
            today.isBefore(typicalStart) && today.monthValue >= 7 && (weekMean ?: 70.0) > 52 -> HeatingPhase.PREPARE
            today.monthValue in 12..12 || today.monthValue in 1..2 -> HeatingPhase.PEAK
            today.monthValue in 9..11 -> HeatingPhase.EARLY
            today.monthValue in 3..5 -> if (today.isAfter(typicalEnd) && (weekMean ?: 50.0) > 58) HeatingPhase.OFF else HeatingPhase.LATE
            else -> HeatingPhase.OFF
        }

        val forecast = days.filter { !it.date.isBefore(today) }.take(7).map { d ->
            val mean = if (d.highC != null && d.lowC != null) (d.highC + d.lowC) / 2 else d.highC ?: d.lowC
            val meanF = mean?.let(Units::cToF)
            val lowF = d.lowC?.let(Units::cToF)
            val daySources = meanF?.let { m -> sources.filter { it.covers(m) }.map { it.label } }.orEmpty()
            val night = lowF?.let { l -> sources.filter { it.covers(l) }.map { it.label } }.orEmpty().filterNot { it in daySources }
            HeatingDay(d.date, mean, d.lowC, hdd(d.highC, d.lowC), daySources, night)
        }

        // The normal recommendation follows the average over about two weeks (the past week observed plus the
        // forecast week), so it doesn't flip between systems with each warm afternoon or cold night.
        val pastMeans = recent.takeLast(7).mapNotNull { d -> if (d.highC != null && d.lowC != null) (d.highC + d.lowC) / 2 else null }
        val outlookMean = (pastMeans + forecast.mapNotNull { it.meanC }).takeIf { it.size >= 3 }?.average()
        val recommended = outlookMean?.let(Units::cToF)?.let { m ->
            sources.filter { it.covers(m) }.ifEmpty {
                // Nothing covers it: use the source whose range is closest.
                sources.minByOrNull { src ->
                    when {
                        src.minF != null && m < src.minF -> src.minF - m
                        src.maxF != null && m >= src.maxF -> m - src.maxF
                        else -> 0.0
                    }
                }?.let(::listOf).orEmpty()
            }.map { it.label }
        }.orEmpty()

        val tasks = mutableListOf<HeatingTask>()
        if (sources.isEmpty()) tasks += HeatingTask(HeatingTask.Kind.NO_SOURCES)
        val types = sources.map { it.type }.toSet()
        val burnsWood = types.any { it.fuel == "wood" }
        val burnsPellets = types.any { it.fuel == "pellets" }
        val month = today.monthValue
        val coldest = forecast.mapNotNull { it.lowC }.minOrNull()?.let(Units::cToF)
        val fuelTypes = types.filter { it.fuel != null && it.fuel != "wood" && it.fuel != "pellets" }

        // Before the season.
        if (phase == HeatingPhase.PREPARE || (phase == HeatingPhase.EARLY && month <= 10)) {
            tasks += HeatingTask(HeatingTask.Kind.SERVICE_SYSTEM)
            if (burnsWood || burnsPellets) tasks += HeatingTask(HeatingTask.Kind.CHIMNEY_SWEEP)
            tasks += HeatingTask(HeatingTask.Kind.CO_DETECTORS)
            fuelTypes.forEach { tasks += HeatingTask(HeatingTask.Kind.FILL_TANK_PREBUY, it) }
            if (burnsWood) tasks += HeatingTask(HeatingTask.Kind.BUY_SEASONED_WOOD, HeatType.WOOD)
            if (burnsPellets) tasks += HeatingTask(HeatingTask.Kind.BUY_PELLETS, HeatType.PELLETS)
            if (HeatType.HEAT_PUMP in types) tasks += HeatingTask(HeatingTask.Kind.HEAT_PUMP_FILTERS, HeatType.HEAT_PUMP)
        }
        // Spring/summer: next winter's wood needs 6–12 months to season; pellets are cheapest off-season.
        if (burnsWood && month in 3..7) tasks += HeatingTask(HeatingTask.Kind.SPLIT_STACK_NEXT_YEAR, HeatType.WOOD)
        if (burnsPellets && month in 4..6) tasks += HeatingTask(HeatingTask.Kind.BUY_PELLETS, HeatType.PELLETS)

        // In season: cold snaps.
        if (coldest != null && coldest <= 10) {
            fuelTypes.forEach { tasks += HeatingTask(HeatingTask.Kind.CHECK_TANK_COLD_SNAP, it, Units.fToC(coldest)) }
            if (burnsWood) tasks += HeatingTask(HeatingTask.Kind.MOVE_WOOD_IN, HeatType.WOOD)
        }
        if (coldest != null && coldest <= 0) tasks += HeatingTask(HeatingTask.Kind.PROTECT_PIPES, value = Units.fToC(coldest))
        sources.filter { it.type == HeatType.HEAT_PUMP && it.minF != null }.forEach { hp ->
            val below = forecast.firstOrNull { d -> d.lowC?.let { Units.cToF(it) < hp.minF!! } == true }
            if (below != null) tasks += HeatingTask(HeatingTask.Kind.BELOW_HEAT_PUMP_RANGE, HeatType.HEAT_PUMP, below.lowC)
        }
        if (HeatType.HEAT_PUMP in types && days.any { (it.snowMm ?: 0.0) >= 50 && !it.date.isBefore(today) && it.date.isBefore(today.plusDays(3)) }) {
            tasks += HeatingTask(HeatingTask.Kind.CLEAR_OUTDOOR_UNIT, HeatType.HEAT_PUMP)
        }
        if (phase == HeatingPhase.PEAK && fuelTypes.isNotEmpty() && today.dayOfMonth <= 7) {
            fuelTypes.forEach { tasks += HeatingTask(HeatingTask.Kind.MONITOR_FUEL, it) }
        }
        if (phase == HeatingPhase.LATE && month == 5 || phase == HeatingPhase.OFF && month == 6) {
            tasks += HeatingTask(HeatingTask.Kind.END_OF_SEASON)
        }

        return HeatingReport(
            phase = phase,
            recommended = recommended,
            outlookMeanC = outlookMean,
            days = if (aggressive) forecast else forecast.map { it.copy(sources = emptyList(), nightSources = emptyList()) },
            tasks = tasks.distinctBy { it.kind to it.source },
            weekHdd = forecast.sumOf { it.hdd },
            recentHdd = recentHdd,
            typicalStart = typicalStart,
            typicalEnd = typicalEnd,
        )
    }

    /** Heating degree days, base 65°F, from a day's high and low (°C). */
    /**
     * How much heating this week needs, in words: from the average daily
     * heating degree days (°F, base 65), and compared with the past month.
     */
    fun demandText(weekHdd: Double, recentHdd: Double?): String {
        val perDay = weekHdd / 7
        val level = when {
            perDay < 3 -> "Barely any heating needed this week"
            perDay < 12 -> "Light heating this week"
            perDay < 25 -> "Steady heating this week"
            else -> "Heavy heating this week"
        }
        val month = recentHdd?.let { it / 30 }
        val compare = when {
            month == null || perDay < 3 -> ""
            perDay > month * 1.25 + 1 -> ", colder than the past month"
            perDay < month * 0.75 - 1 -> ", milder than the past month"
            else -> ", about like the past month"
        }
        return "$level$compare."
    }

    fun hdd(highC: Double?, lowC: Double?): Double {
        val hi = highC?.let(Units::cToF) ?: return 0.0
        val lo = lowC?.let(Units::cToF) ?: hi
        return (65 - (hi + lo) / 2).coerceAtLeast(0.0)
    }
}
