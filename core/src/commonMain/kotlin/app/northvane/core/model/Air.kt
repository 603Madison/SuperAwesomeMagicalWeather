package app.northvane.core.model

import app.northvane.core.json.InstantSerializer
import app.northvane.core.json.LocalDateSerializer
import kotlinx.serialization.Serializable
import app.northvane.core.time.*

/** EPA Air Quality Index categories. */
@Serializable
enum class AqiCategory(val label: String, val max: Int) {
    GOOD("Good", 50),
    MODERATE("Moderate", 100),
    SENSITIVE("Unhealthy for sensitive groups", 150),
    UNHEALTHY("Unhealthy", 200),
    VERY_UNHEALTHY("Very unhealthy", 300),
    HAZARDOUS("Hazardous", Int.MAX_VALUE);

    companion object {
        fun of(aqi: Int): AqiCategory = entries.first { aqi <= it.max }
    }
}

@Serializable
enum class AirSource(val label: String) {
    AIRNOW("EPA AirNow (monitors)"),
    OPEN_METEO("Open-Meteo (CAMS air quality model)"),
}

@Serializable
data class AqiReading(
    val aqi: Int,
    /** Pollutant driving the AQI, e.g. "PM2.5" or "O3". */
    val pollutant: String? = null,
    @Serializable(InstantSerializer::class) val observedAt: Instant? = null,
    /** AirNow reporting area, e.g. "Portland". */
    val area: String? = null,
) {
    val category: AqiCategory get() = AqiCategory.of(aqi)
}

@Serializable
data class AqiDay(
    @Serializable(LocalDateSerializer::class) val date: LocalDate,
    val aqi: Int?,
    val pollutant: String? = null,
    /** Some AirNow forecasts give only a category, not a number. */
    val category: AqiCategory? = aqi?.let(AqiCategory::of),
    val actionDay: Boolean = false,
    val discussion: String? = null,
)

@Serializable
data class AirQuality(
    val current: AqiReading?,
    val days: List<AqiDay> = emptyList(),
    val source: AirSource,
    @Serializable(InstantSerializer::class) val fetchedAt: Instant,
)

@Serializable
data class UvHour(@Serializable(InstantSerializer::class) val time: Instant, val index: Double)

@Serializable
enum class UvLevel(val label: String, val max: Double) {
    LOW("Low", 2.5),
    MODERATE("Moderate", 5.5),
    HIGH("High", 7.5),
    VERY_HIGH("Very high", 10.5),
    EXTREME("Extreme", Double.MAX_VALUE);

    companion object {
        fun of(index: Double): UvLevel = entries.first { index <= it.max }
    }
}

@Serializable
data class UvForecast(
    val hourly: List<UvHour>,
    /** Forecast maximum for today (EPA daily product, or the hourly peak). */
    val todayMax: Double?,
    /** EPA issues a UV Alert when the index is expected to be high and above normal. */
    val alert: Boolean = false,
    val source: String,
    @Serializable(InstantSerializer::class) val fetchedAt: Instant,
) {
    fun at(now: Instant): Double? = hourly.lastOrNull { !it.time.isAfter(now) }?.index ?: hourly.firstOrNull()?.index
    fun peak(): UvHour? = hourly.maxByOrNull { it.index }
}

// --- Allergies ---

@Serializable
enum class AllergenGroup(val label: String) { TREES("Trees"), GRASSES("Grasses"), WEEDS("Weeds"), OTHER("Other triggers") }

/**
 * Things a user can say they react to. [codes] are Google Pollen API plant
 * codes, falling back to the pollen type ([typeCode]) when the plant isn't
 * reported for the area.
 */
@Serializable
enum class Allergen(val label: String, val group: AllergenGroup, val codes: List<String>, val typeCode: String?) {
    ALL_TREES("All tree pollen", AllergenGroup.TREES, listOf("TREE"), "TREE"),
    ALDER("Alder", AllergenGroup.TREES, listOf("ALDER"), "TREE"),
    ASH("Ash", AllergenGroup.TREES, listOf("ASH"), "TREE"),
    BIRCH("Birch", AllergenGroup.TREES, listOf("BIRCH"), "TREE"),
    COTTONWOOD("Cottonwood / poplar", AllergenGroup.TREES, listOf("COTTONWOOD"), "TREE"),
    ELM("Elm", AllergenGroup.TREES, listOf("ELM"), "TREE"),
    HAZEL("Hazel", AllergenGroup.TREES, listOf("HAZEL"), "TREE"),
    JUNIPER("Juniper / cedar", AllergenGroup.TREES, listOf("JUNIPER", "JAPANESE_CEDAR"), "TREE"),
    MAPLE("Maple / box elder", AllergenGroup.TREES, listOf("MAPLE"), "TREE"),
    OAK("Oak", AllergenGroup.TREES, listOf("OAK"), "TREE"),
    OLIVE("Olive", AllergenGroup.TREES, listOf("OLIVE"), "TREE"),
    PINE("Pine", AllergenGroup.TREES, listOf("PINE", "CYPRESS_PINE"), "TREE"),
    GRASS("Grass pollen", AllergenGroup.GRASSES, listOf("GRAMINALES", "GRASS"), "GRASS"),
    ALL_WEEDS("All weed pollen", AllergenGroup.WEEDS, listOf("WEED"), "WEED"),
    RAGWEED("Ragweed", AllergenGroup.WEEDS, listOf("RAGWEED"), "WEED"),
    MUGWORT("Mugwort / sagebrush", AllergenGroup.WEEDS, listOf("MUGWORT"), "WEED"),
    MOLD("Outdoor mold (estimated)", AllergenGroup.OTHER, emptyList(), null),
    AIR_POLLUTION("Air pollution (ozone, smoke)", AllergenGroup.OTHER, emptyList(), null),
}

@Serializable
enum class Sensitivity(val label: String, val weight: Double) {
    MILD("Mild", 0.6),
    MODERATE("Moderate", 0.8),
    SEVERE("Severe", 1.0),
}

@Serializable
enum class AllergyLevel(val label: String) { LOW("Low"), MODERATE("Moderate"), HIGH("High"), VERY_HIGH("Very high") }

/** One allergen's exposure for the day: [index] on the 0–5 pollen-index scale. */
@Serializable
data class AllergyDriver(val allergen: Allergen, val index: Double, val estimated: Boolean = false)

@Serializable
data class AllergyDay(
    @Serializable(LocalDateSerializer::class) val date: LocalDate,
    /** 0 (no symptoms expected) to 100 (very bad day for this person). */
    val score: Int,
    val level: AllergyLevel,
    val drivers: List<AllergyDriver>,
    val tips: List<Tip>,
    /** False when no pollen forecast was available (no key, or beyond the 5-day pollen forecast). */
    val pollenKnown: Boolean,
)
