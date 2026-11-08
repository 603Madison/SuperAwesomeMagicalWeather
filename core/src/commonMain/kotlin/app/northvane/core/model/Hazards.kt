package app.northvane.core.model

import app.northvane.core.json.InstantSerializer
import kotlinx.serialization.Serializable
import app.northvane.core.time.*

/**
 * Frozen pipe risk for a day and the night after it. Uses the same four
 * levels as the outage outlook; days without a hard freeze have no risk.
 */
@Serializable
data class PipeRisk(
    val level: OutageLevel,
    val lowC: Double?,
    /** Hours at or below 20°F (−6.7 °C), when the hourly forecast covers the day. */
    val hoursBelow: Int? = null,
    val factors: List<PipeFactor> = emptyList(),
)

/** What drove a pipe risk; [value] is in metric units (°C, km/h, hours). */
@Serializable
data class PipeFactor(val kind: Kind, val value: Double? = null) {
    @Serializable
    enum class Kind {
        /** The overnight low. */
        LOW,
        /** Many hours at or below 20°F. */
        LONG_COLD,
        /** The high stays below freezing. */
        NO_THAW,
        /** Wind pulls heat from walls and crawl spaces. */
        WIND,
        /** A high chance of losing power, and with it most heating. */
        OUTAGE,
    }
}

@Serializable
enum class Likelihood(val label: String) { POSSIBLE("possible"), LIKELY("likely") }

/** Frost on windshields and black ice on roads, for the morning of a day. */
@Serializable
data class MorningIce(
    val frost: Likelihood? = null,
    val blackIce: Likelihood? = null,
    val reasons: List<Reason> = emptyList(),
    val lowC: Double? = null,
    @Serializable(InstantSerializer::class) val coldestAt: Instant? = null,
) {
    @Serializable
    enum class Reason {
        /** Clear sky and light wind let surfaces cool below the air. */
        CLEAR_CALM,
        FREEZING_RAIN,
        /** Rain or drizzle, then a drop below freezing. */
        RAIN_THEN_FREEZE,
        /** Daytime snowmelt refreezes overnight. */
        MELT_REFREEZE,
    }
}

enum class SapRating(val label: String) { NONE("No run"), LIGHT("Light run"), GOOD("Good run"), GREAT("Great run") }

/** One day's maple sap flow: a freezing night followed by a thawing day. */
data class SapDay(val date: LocalDate, val rating: SapRating, val score: Int, val nightLowC: Double?, val highC: Double?)

data class SapReport(val days: List<SapDay>, val note: Note? = null) {
    enum class Note {
        /** Several nights in a row stay above freezing: flow stops, and buds may end the season. */
        WARM_NIGHTS,
        /** Highs stay below freezing: the trees stay frozen. */
        DEEP_FREEZE,
    }
}

enum class BurnRating(val label: String) { GOOD("Good day to burn"), CAUTION("Burn with caution"), NO_BURN("Don't burn") }

/** Why a day got its burn rating; [value] is metric (km/h, %, days). */
data class BurnReason(val kind: Kind, val value: Double? = null, val detail: String? = null) {
    enum class Kind { FIRE_ALERT, GUSTS, WIND, DRY_AIR, DRY_SPELL, SNOW_COVER, LIGHT_WIND, RECENT_RAIN, RAIN_LIKELY }
}

data class BurnDay(val date: LocalDate, val rating: BurnRating, val reasons: List<BurnReason>)

data class BurnReport(val days: List<BurnDay>, val daysSinceRain: Int? = null)

/** One 3-hour planetary K-index value from NOAA's Space Weather Prediction Center. */
@Serializable
data class KpPoint(
    @Serializable(InstantSerializer::class) val start: Instant,
    val kp: Double,
    val kind: Kind,
) {
    @Serializable
    enum class Kind { OBSERVED, ESTIMATED, PREDICTED }
}

@Serializable
data class KpForecast(
    val points: List<KpPoint>,
    @Serializable(InstantSerializer::class) val fetchedAt: Instant,
)

enum class AuroraView(val label: String) {
    NONE("Not visible"),
    CAMERA("Phone camera only"),
    HORIZON("Low on the northern horizon"),
    OVERHEAD("Possibly overhead"),
}

/** One night's aurora chances, from dusk on [date] to dawn the next morning. */
data class AuroraNight(
    val date: LocalDate,
    val maxKp: Double,
    val view: AuroraView,
    /** When the strongest predicted activity overlaps darkness. */
    val peakAt: Instant?,
    val cloudPercent: Int? = null,
)

data class AuroraReport(
    val nights: List<AuroraNight>,
    val geomagneticLatitude: Double,
    /** Kp right now (observed or estimated), if dark enough to look. */
    val nowKp: Double?,
    val nowView: AuroraView,
    /** A strong storm (Kp 7+) within a day: GPS and satellite messaging can be less reliable. */
    val gpsImpact: Boolean,
)
