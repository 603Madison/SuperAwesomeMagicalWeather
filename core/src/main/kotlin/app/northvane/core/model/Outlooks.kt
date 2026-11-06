package app.northvane.core.model

import app.northvane.core.json.InstantSerializer
import app.northvane.core.json.LocalDateSerializer
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate

/** Forecast conditions summarised over part of a day: daytime (08–18) or overnight (20–07). */
@Serializable
data class PeriodStats(
    val tempMaxC: Double? = null,
    val tempMinC: Double? = null,
    val feelsMaxC: Double? = null,
    val feelsMinC: Double? = null,
    val dewpointMaxC: Double? = null,
    val humidityMean: Double? = null,
    val precipChanceMax: Int? = null,
    val precipMm: Double? = null,
    val snowMm: Double? = null,
    val iceMm: Double? = null,
    val windMeanKph: Double? = null,
    val windMaxKph: Double? = null,
    val gustMaxKph: Double? = null,
    val thunderChanceMax: Int? = null,
    val skyCoverMean: Int? = null,
)

/** One observed day from NOAA's Applied Climate Information System (gridded station analysis). */
@Serializable
data class ObservedDay(
    @Serializable(LocalDateSerializer::class) val date: LocalDate,
    val highC: Double?,
    val lowC: Double?,
    val precipMm: Double?,
)

/** Recent observed weather, used for mud, foliage and seasonal outage estimates. */
@Serializable
data class WeatherHistory(
    val days: List<ObservedDay>,
    /** Latest reported snow depth at the nearest reporting station, if any. */
    val snowDepthMm: Double? = null,
    val snowDepthStation: String? = null,
    /** Ground elevation of the analysis grid cell. */
    val elevationM: Double? = null,
    @Serializable(InstantSerializer::class) val fetchedAt: Instant,
)

// --- Pollen ---

@Serializable
data class PollenReading(
    /** Google code, e.g. TREE, GRASS, WEED, or a plant such as BIRCH, OAK, RAGWEED. */
    val code: String,
    val name: String,
    /** Universal Pollen Index 0 (none) to 5 (very high); null when out of season or unknown. */
    val index: Int?,
    val category: String?,
    val inSeason: Boolean,
)

@Serializable
data class PollenDay(
    @Serializable(LocalDateSerializer::class) val date: LocalDate,
    val types: List<PollenReading>,
    val plants: List<PollenReading> = emptyList(),
    val tips: List<String> = emptyList(),
) {
    /** The highest pollen type today, or null when nothing is in season. */
    val worst: PollenReading? get() = types.filter { it.index != null }.maxByOrNull { it.index!! }

    /** Plants contributing the most pollen today, highest first. */
    val topPlants: List<PollenReading> get() = plants.filter { (it.index ?: 0) > 0 }.sortedByDescending { it.index }
}

@Serializable
data class PollenForecast(
    val days: List<PollenDay>,
    @Serializable(InstantSerializer::class) val fetchedAt: Instant,
)

// --- Mud ---

@Serializable
enum class MudLevel(val label: String) {
    SNOW_COVERED("Snow-covered"),
    FROZEN("Frozen, firm"),
    DRY("Dry"),
    DAMP("Damp"),
    MUDDY("Muddy"),
    VERY_MUDDY("Very muddy"),
}

@Serializable
data class MudReason(val kind: Kind, val value: Double? = null) {
    @Serializable
    enum class Kind { RECENT_RAIN, FORECAST_RAIN, THAW, SNOWMELT, FROZEN, DRYING, SNOW_COVER, NO_HISTORY }
}

@Serializable
data class MudOutlook(
    val level: MudLevel,
    /** 0 (dry, firm) to 100 (deep mud). */
    val score: Int,
    /** True during spring thaw, when frozen subsoil keeps water at the surface (mud season). */
    val mudSeason: Boolean = false,
    val reasons: List<MudReason> = emptyList(),
    /** Modeled snow depth on the ground, mm (seeded from the nearest snow report). */
    val snowDepthMm: Double? = null,
)

// --- Activities ---

@Serializable
enum class Activity(val label: String, val overnight: Boolean = false) {
    HIKING("Hiking"),
    CAMPING("Camping", overnight = true),
    OFF_ROADING("Off-roading"),
    MOUNTAIN_BIKING("Mountain biking"),
    PADDLING("Paddling"),
    SWIMMING("Swimming"),
    HUNTING("Hunting"),
    SKIING("Downhill skiing"),
    XC_SKIING("Cross-country skiing"),
    SNOWSHOEING("Snowshoeing"),
    SNOWMOBILING("Snowmobiling"),
    ICE_FISHING("Ice fishing"),
    STARGAZING("Stargazing", overnight = true),
}

@Serializable
enum class ScoreBand(val label: String) {
    GREAT("Great"), GOOD("Good"), FAIR("Fair"), POOR("Poor"), BAD("Bad");

    companion object {
        fun of(score: Int): ScoreBand = when {
            score >= 80 -> GREAT
            score >= 60 -> GOOD
            score >= 40 -> FAIR
            score >= 20 -> POOR
            else -> BAD
        }
    }
}

/**
 * A recommendation or explanation attached to an activity score. Values are
 * metric (°C, km/h, mm, %); the text is produced at display time so it follows
 * the user's units.
 */
@Serializable
data class Tip(val kind: Kind, val value: Double? = null) {
    @Serializable
    enum class Kind {
        // Conditions that lowered the score
        HOT, HUMID, COLD, VERY_COLD, RAIN, SNOW, ICE, THUNDER, WINDY, CLOUDY, MUDDY, MUD_SEASON, ROUGH_WATER,
        COLD_WATER, BRIGHT_MOON, WARNING, ICE_ON_WATER, SHORE_ICE, RIVER_RISING,
        // Gear and planning
        SLEEPING_BAG_0F, SLEEPING_BAG_20F, SLEEPING_BAG_30F, SLEEPING_BAG_SUMMER, HOT_NIGHT, RAINFLY, GUYLINES,
        CONDENSATION, FROST, SUMMIT_WIND, EARLY_START, MICROSPIKES, SNOWSHOES, LAYERS, HYDRATE, SUNSCREEN,
        RECOVERY_GEAR, AVOID_RUTS, TRAIL_CLOSURES, LET_TRAILS_DRY, DUSTY, BLAZE_ORANGE, BLACK_FLIES, TICKS,
        MOSQUITOES, SHORT_DAYLIGHT, DARK_SKIES, MOONLIT, OFFSHORE_WIND, DRY_BAG,
        // Winter and seasonal activities
        WINTER_GEAR, POWDER_DAY, ICY_SURFACES, LIFT_HOLDS, THIN_COVER, CHECK_SNOW_REPORT, SPRING_SNOW, RAIN_ON_SNOW,
        KICK_WAX, BREAK_TRAIL, TRAIL_REPORT, ICE_CHECK, ICE_WEAKENING, HAND_WARMERS, COLD_WATER_SWIM, LIGHTNING_SWIM,
        TRACKING_SNOW, LEGAL_HOURS, MEAT_CARE, QUIET_WIND, SCENT_WIND,
        // Allergies
        ALLERGY_WINDOWS, ALLERGY_SHOWER, ALLERGY_MEDICATE, ALLERGY_TIMING, ALLERGY_RAIN_HELPS, ALLERGY_WIND,
        ALLERGY_MASK_YARDWORK, ALLERGY_THUNDERSTORM, ALLERGY_AIR, ALLERGY_QUIET,
        // Positive notes
        IDEAL,
    }
}

@Serializable
data class ActivityScore(
    val activity: Activity,
    val score: Int,
    val tips: List<Tip>,
    /** Seasonal name, e.g. "Winter hiking" or "Fat biking"; null uses the activity's own label. */
    val title: String? = null,
) {
    val band: ScoreBand get() = ScoreBand.of(score)
    val label: String get() = title ?: activity.label
}

// --- Foliage ---

@Serializable
enum class FoliageStage(val label: String) {
    GREEN("Mostly green"),
    TURNING("Starting to turn"),
    MODERATE("Moderate color"),
    NEAR_PEAK("Near peak"),
    PEAK("Peak color"),
    PAST_PEAK("Past peak"),
    BARE("Leaves down"),
}

@Serializable
data class FoliageDay(
    @Serializable(LocalDateSerializer::class) val date: LocalDate,
    val stage: FoliageStage,
    /** Estimated share of leaves showing color, 0–100. */
    val colorPercent: Int,
    /** Wind or heavy rain likely to strip leaves this day. */
    val leafDrop: Boolean = false,
)

@Serializable
data class FoliageReport(
    @Serializable(LocalDateSerializer::class) val estimatedPeak: LocalDate,
    /** How many days the season is running ahead (negative) or behind (positive) of typical, from recent temperatures. */
    val shiftDays: Int,
    val days: List<FoliageDay>,
    val notes: List<Note> = emptyList(),
) {
    val today: FoliageDay? get() = days.firstOrNull()

    /** Leaves are actually changing: from first color until they're down. */
    val inSeason: Boolean get() = today?.stage?.let { it != FoliageStage.GREEN && it != FoliageStage.BARE } == true

    @Serializable
    enum class Note { COOL_NIGHTS, WARM_FALL, HARD_FREEZE, DRY, HIGH_ELEVATION, NO_HISTORY }
}
