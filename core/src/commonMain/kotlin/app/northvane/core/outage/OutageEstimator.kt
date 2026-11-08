package app.northvane.core.outage

import app.northvane.core.model.DailyForecast
import app.northvane.core.model.MudLevel
import app.northvane.core.model.MudOutlook
import app.northvane.core.model.OutageFactor
import app.northvane.core.model.OutageFactor.Kind
import app.northvane.core.model.OutageLevel
import app.northvane.core.model.OutageRisk
import app.northvane.core.model.Units
import app.northvane.core.model.WeatherAlert
import app.northvane.core.nws.ForecastDetails
import kotlin.math.roundToInt
import app.northvane.core.time.*

/**
 * Rough likelihood of power outages for a forecast day, tuned for New
 * England. It isn't a utility model; it encodes what drives outages on the
 * region's heavily treed overhead distribution lines:
 *
 * - **Wind gusts**, scaled up when trees are in leaf (mid-May to early
 *   November): foliage acts like a sail, so the same gust brings down far more
 *   limbs in October than in January.
 * - **Ice accretion** from freezing rain, using thresholds similar to the
 *   Sperry-Piltz ice index (0.25" causes scattered outages, 0.5"+ widespread),
 *   worse with wind.
 * - **Heavy, wet snow** near freezing, which loads branches; early- or
 *   late-season snow on leafed trees is especially damaging (e.g. Oct 2011).
 * - **Saturated ground** from heavy rain, which lets wind uproot trees.
 * - **Thunderstorms** and active NWS hazard alerts covering the day.
 *
 * The season matters: leaf cover follows the estimated leaf-out and fall
 * foliage for the location (bare trees from late fall to spring catch far
 * less wind); frozen ground anchors roots in winter, while thawing,
 * waterlogged ground in mud season lets trees topple in moderate wind.
 *
 * Scores are 0–100: the strongest factor plus a share of the others.
 */
object OutageEstimator {

    fun annotate(days: List<DailyForecast>, alerts: List<WeatherAlert>, zone: ZoneId, context: OutageContext? = null): List<DailyForecast> =
        days.map { it.copy(outageRisk = estimate(it, alerts, zone, context)) }

    fun estimate(day: DailyForecast, alerts: List<WeatherAlert>, zone: ZoneId, context: OutageContext? = null): OutageRisk {
        val factors = mutableListOf<Pair<OutageFactor, Double>>() // factor, score
        val leaves = (context?.leafCover?.invoke(day.date) ?: foliage(day.date)).coerceIn(0.0, 1.0)
        val ground = context?.mud?.get(day.date)
        val frozenGround = ground?.level == MudLevel.FROZEN || (ground?.level == MudLevel.SNOW_COVERED && !ground.mudSeason)
        val softGround = ground != null && (ground.mudSeason || ground.level == MudLevel.VERY_MUDDY)

        // Wind. Gusts are what break limbs; sustained wind is a lesser signal.
        val gustMph = day.gustMaxKph?.let(Units::kphToMph)
        if (gustMph != null) {
            val effective = gustMph + 8.0 * leaves // leaf-on sail effect
            val score = ramp(effective, listOf(30.0 to 0.0, 40.0 to 25.0, 50.0 to 55.0, 60.0 to 80.0, 70.0 to 100.0))
            if (score > 0) factors += OutageFactor(Kind.GUSTS, day.gustMaxKph) to score
        }
        val windMph = day.windMaxKph?.let(Units::kphToMph)
        if (windMph != null && windMph >= 25) {
            factors += OutageFactor(Kind.SUSTAINED_WIND, day.windMaxKph) to ramp(windMph, listOf(25.0 to 10.0, 35.0 to 35.0, 45.0 to 60.0))
        }
        if (leaves >= 0.5 && (gustMph ?: 0.0) >= 30) {
            factors += OutageFactor(Kind.LEAVES_ON) to 0.0 // explains the boost above
        } else if (leaves < 0.2 && (gustMph ?: 0.0) >= 40) {
            factors += OutageFactor(Kind.LEAVES_OFF) to 0.0 // explains why the same gusts score lower now
        }

        // Saturated ground + wind: heavy rain lets gusts uproot whole trees; frozen ground resists.
        val rainIn = day.precipitationMm?.let(Units::mmToInches) ?: 0.0
        if (rainIn >= 1.0 && (gustMph ?: 0.0) >= 30) {
            val score = ramp(rainIn, listOf(1.0 to 15.0, 2.0 to 30.0, 3.0 to 40.0)) * if (frozenGround) 0.4 else 1.0
            factors += OutageFactor(Kind.SATURATED_GROUND, day.precipitationMm) to score
        } else if (softGround && (gustMph ?: 0.0) >= 30) {
            factors += OutageFactor(Kind.SOFT_GROUND) to ramp(gustMph!!, listOf(30.0 to 10.0, 45.0 to 25.0, 60.0 to 35.0))
        }
        if (frozenGround && (gustMph ?: 0.0) >= 40) {
            factors += OutageFactor(Kind.FROZEN_GROUND) to 0.0
        }

        // Ice accretion.
        val iceIn = day.iceMm?.let(Units::mmToInches) ?: 0.0
        if (iceIn >= 0.02) {
            var score = ramp(iceIn, listOf(0.02 to 5.0, 0.1 to 25.0, 0.25 to 55.0, 0.5 to 85.0, 0.75 to 100.0))
            if ((gustMph ?: 0.0) >= 25) score += 15
            factors += OutageFactor(Kind.ICE, day.iceMm) to score
        }

        // Snow: wet snow near freezing (or with a low snow-to-liquid ratio) loads branches.
        val snowIn = day.snowMm?.let(Units::mmToInches) ?: 0.0
        if (snowIn >= 2.0) {
            val highF = day.highC?.let(Units::cToF)
            val lowF = day.lowC?.let(Units::cToF)
            val nearFreezing = (highF != null && highF >= 30.0) || (lowF != null && lowF >= 28.0)
            val ratio = day.precipitationMm?.takeIf { it > 0 }?.let { (day.snowMm ?: 0.0) / it }
            val wet = nearFreezing || (ratio != null && ratio < 10.0)
            var score = ramp(snowIn, listOf(2.0 to 5.0, 4.0 to 15.0, 6.0 to 30.0, 10.0 to 55.0, 14.0 to 75.0, 20.0 to 85.0))
            if (!wet) score *= 0.5
            if (leaves > 0.0) score *= 1.0 + 1.2 * leaves // snow on leafed trees (Oct 2011, Oct 2020)
            if ((gustMph ?: 0.0) >= 35) score += 10
            factors += OutageFactor(if (wet) Kind.WET_SNOW else Kind.SNOW, day.snowMm) to score.coerceAtMost(100.0)
        }

        // Thunderstorms: local but intense downbursts, mostly in leaf-on season.
        val thunder = day.thunderChance ?: 0
        if (thunder >= 20) {
            factors += OutageFactor(Kind.THUNDER, thunder.toDouble()) to thunder * (0.25 + 0.2 * leaves)
        }

        // Heat: high load on the grid and equipment.
        val highF = day.highC?.let(Units::cToF)
        if (highF != null && highF >= 92) {
            factors += OutageFactor(Kind.HEAT, day.highC) to if (highF >= 97) 15.0 else 8.0
        }

        // Active NWS alerts covering this day set a floor.
        val (from, to) = ForecastDetails.dayWindow(day, zone)
        for (alert in alerts) {
            val start = alert.onset ?: alert.effective ?: alert.sent ?: continue
            val end = alert.ends ?: alert.expires ?: continue
            if (!end.isAfter(from) || !start.isBefore(to)) continue
            val floor = ALERT_FLOORS.entries.firstOrNull { alert.event.contains(it.key, ignoreCase = true) }?.value ?: continue
            factors += OutageFactor(Kind.ALERT, detail = alert.event) to floor
        }

        val scores = factors.map { it.second }.sortedDescending()
        val total = ((scores.firstOrNull() ?: 0.0) + 0.3 * scores.drop(1).sum()).coerceIn(0.0, 100.0).roundToInt()
        val level = when {
            total >= 70 -> OutageLevel.VERY_HIGH
            total >= 45 -> OutageLevel.HIGH
            total >= 20 -> OutageLevel.ELEVATED
            else -> OutageLevel.LOW
        }
        val ordered = factors.sortedByDescending { it.second }.map { it.first }.distinctBy { it.kind to it.detail }
        return OutageRisk(level, total, ordered, leafCover = leaves)
    }

    /** Calendar fallback for the fraction of full leaf-out (southern New England), when no location is known. */
    fun foliage(date: LocalDate): Double {
        val md = MonthDay.from(date)
        return when {
            md >= MonthDay.of(6, 1) && md <= MonthDay.of(10, 20) -> 1.0
            md >= MonthDay.of(5, 15) && md < MonthDay.of(6, 1) -> 0.7
            md > MonthDay.of(10, 20) && md <= MonthDay.of(11, 10) -> 0.6 // late foliage, oaks hold leaves
            md >= MonthDay.of(5, 1) && md < MonthDay.of(5, 15) -> 0.3
            else -> 0.0
        }
    }

    /** Piecewise-linear interpolation; below the first point is 0, above the last is the last score. */
    private fun ramp(x: Double, points: List<Pair<Double, Double>>): Double {
        if (x < points.first().first) return 0.0
        for (i in 1 until points.size) {
            val (x0, y0) = points[i - 1]
            val (x1, y1) = points[i]
            if (x <= x1) return y0 + (y1 - y0) * (x - x0) / (x1 - x0)
        }
        return points.last().second
    }

    private val ALERT_FLOORS = linkedMapOf(
        "Hurricane Warning" to 85.0,
        "Ice Storm Warning" to 75.0,
        "Extreme Wind Warning" to 90.0,
        "Tropical Storm Warning" to 60.0,
        "High Wind Warning" to 60.0,
        "Blizzard Warning" to 60.0,
        "Tornado Warning" to 60.0,
        "Severe Thunderstorm Warning" to 50.0,
        "Winter Storm Warning" to 40.0,
        "Hurricane Watch" to 45.0,
        "High Wind Watch" to 35.0,
        "Tropical Storm Watch" to 35.0,
        "Severe Thunderstorm Watch" to 30.0,
        "Freezing Rain Advisory" to 25.0,
        "Wind Advisory" to 25.0,
        "Winter Storm Watch" to 20.0,
    )
}

/** Seasonal state for a location: leaf cover by date, and ground conditions (frozen, thawing) from the mud model. */
class OutageContext(
    val leafCover: ((LocalDate) -> Double)? = null,
    val mud: Map<LocalDate, MudOutlook> = emptyMap(),
)
