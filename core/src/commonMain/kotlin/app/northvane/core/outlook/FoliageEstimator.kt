package app.northvane.core.outlook

import app.northvane.core.model.DailyForecast
import app.northvane.core.model.FoliageDay
import app.northvane.core.model.FoliageReport
import app.northvane.core.model.FoliageReport.Note
import app.northvane.core.model.FoliageStage
import app.northvane.core.model.Units
import app.northvane.core.model.WeatherHistory
import kotlin.math.roundToInt
import app.northvane.core.time.*

/**
 * Fall foliage estimate. There's no free, machine-readable foliage feed (the
 * Maine and New Hampshire reports are weekly web pages), so this models it:
 *
 * - **Typical peak** from latitude and elevation, calibrated to New England:
 *   about Oct 18 at 43°N near sea level, ~5 days earlier per degree north and
 *   ~5 days earlier per 1,000 ft (late September in the White Mountains and
 *   northern Maine, late October on the southern coast).
 * - **This year's shift** from observed nights since September (NOAA ACIS):
 *   cool nights speed up color, a warm fall delays it, a hard freeze or drought
 *   hurries leaves off the trees.
 * - **Leaf drop** on forecast days with strong gusts or heavy rain near or after peak.
 *
 * It's an estimate: the UI labels it as such and points to the official reports.
 */
object FoliageEstimator {

    fun typicalPeak(year: Int, latitude: Double, elevationM: Double?): LocalDate {
        val elevationKft = (elevationM ?: 0.0).coerceIn(0.0, 2000.0) * 3.28084 / 1000.0
        val shift = (-5.0 * (latitude - 43.0) - 5.0 * elevationKft).coerceIn(-30.0, 35.0)
        return LocalDate.of(year, 10, 18).plusDays(shift.roundToInt().toLong())
    }

    /** Only where deciduous fall color is a thing (roughly the US north of the Gulf coast). */
    fun applies(latitude: Double) = latitude in 32.0..55.0

    fun report(latitude: Double, history: WeatherHistory?, days: List<DailyForecast>, today: LocalDate): FoliageReport? {
        if (!applies(latitude)) return null
        val elevation = history?.elevationM
        val typical = typicalPeak(today.year, latitude, elevation)
        // Show the tracker from ~5 weeks before typical peak until leaves are down.
        if (today.isBefore(typical.minusDays(40)) || today.isAfter(typical.plusDays(35))) return null

        val notes = mutableListOf<Note>()
        var shift = 0.0
        val fall = history?.days.orEmpty().filter { !it.date.isBefore(LocalDate.of(today.year, 9, 1)) && it.date.isBefore(today) }
        if (fall.size >= 7) {
            val lows = fall.mapNotNull { d -> d.lowC?.let { d.date to Units.cToF(it) } }
            if (lows.isNotEmpty()) {
                val anomaly = lows.map { (date, lowF) -> lowF - expectedLowF(date, latitude, elevation) }.average()
                shift += (anomaly * 1.2).coerceIn(-7.0, 7.0) // warmer nights → later
                if (anomaly <= -2) notes += Note.COOL_NIGHTS
                if (anomaly >= 2) notes += Note.WARM_FALL
            }
            val hardFreezes = lows.count { (date, lowF) -> lowF <= 28.0 && date.isBefore(typical) }
            if (hardFreezes > 0) {
                shift -= (2.0 * hardFreezes).coerceAtMost(6.0)
                notes += Note.HARD_FREEZE
            }
            val rainIn = fall.sumOf { it.precipMm ?: 0.0 } / 25.4
            val expected = fall.size * 0.12 // ~3.7 in/month across New England
            if (fall.size >= 21 && rainIn < expected * 0.5) {
                shift -= 3.0
                notes += Note.DRY
            }
        } else {
            notes += Note.NO_HISTORY
        }
        if ((elevation ?: 0.0) >= 600) notes += Note.HIGH_ELEVATION

        val peak = typical.plusDays(shift.roundToInt().toLong())
        var dropBoost = 0L
        val outlook = days.filter { !it.date.isBefore(today) }.ifEmpty {
            listOf(DailyForecast(today, "Today", null, null, app.northvane.core.model.WeatherCondition.UNKNOWN, true, null, null))
        }.map { day ->
            val delta = ChronoUnit.DAYS.between(peak, day.date) + dropBoost
            val stage = stageFor(delta)
            val gustMph = day.gustMaxKph?.let(Units::kphToMph) ?: 0.0
            val rainIn = (day.precipitationMm ?: 0.0) / 25.4
            val drop = delta >= -5 && stage != FoliageStage.BARE && (gustMph >= 35 || rainIn >= 1.0)
            if (drop) dropBoost += 3
            FoliageDay(day.date, stage, colorPercent(delta), leafDrop = drop)
        }
        return FoliageReport(estimatedPeak = peak, shiftDays = shift.roundToInt(), days = outlook, notes = notes)
    }

    /** Rough normal low for New England in fall, °F. */
    internal fun expectedLowF(date: LocalDate, latitude: Double, elevationM: Double?): Double {
        val daysSinceSep1 = ChronoUnit.DAYS.between(LocalDate.of(date.year, 9, 1), date).toDouble()
        val elevationKft = (elevationM ?: 0.0).coerceIn(0.0, 2000.0) * 3.28084 / 1000.0
        return 55.0 - 0.33 * daysSinceSep1 - 1.5 * (latitude - 43.0) - 3.5 * elevationKft
    }

    internal fun stageFor(daysFromPeak: Long): FoliageStage = when {
        daysFromPeak < -28 -> FoliageStage.GREEN
        daysFromPeak < -18 -> FoliageStage.TURNING
        daysFromPeak < -9 -> FoliageStage.MODERATE
        daysFromPeak < -3 -> FoliageStage.NEAR_PEAK
        daysFromPeak <= 4 -> FoliageStage.PEAK
        daysFromPeak <= 18 -> FoliageStage.PAST_PEAK
        else -> FoliageStage.BARE
    }

    private fun colorPercent(daysFromPeak: Long): Int = MudEstimator.ramp(
        daysFromPeak.toDouble(),
        listOf(-35.0 to 0.0, -28.0 to 5.0, -18.0 to 20.0, -9.0 to 50.0, -3.0 to 80.0, 0.0 to 95.0, 4.0 to 90.0, 13.0 to 50.0, 18.0 to 25.0, 25.0 to 10.0),
    ).roundToInt()

    /**
     * Fraction of a full summer canopy on [date], for the outage estimate:
     * leaf-out in May, full through peak color, then dropping. Oaks and
     * beeches keep some leaves into winter.
     */
    fun leafCover(date: LocalDate, latitude: Double, elevationM: Double?, report: FoliageReport? = null): Double {
        val elevationKft = (elevationM ?: 0.0).coerceIn(0.0, 2000.0) * 3.28084 / 1000.0
        val leafOutStart = LocalDate.of(date.year, 5, 5).plusDays((4.0 * (latitude - 43.0) + 5.0 * elevationKft).roundToInt().toLong())
        val sinceLeafOut = ChronoUnit.DAYS.between(leafOutStart, date).toDouble()
        if (sinceLeafOut < 0) return 0.0
        if (sinceLeafOut < 21) return sinceLeafOut / 21.0
        val peak = report?.estimatedPeak ?: typicalPeak(date.year, latitude, elevationM)
        val drop = report?.days?.firstOrNull { it.date == date }?.let { if (it.leafDrop) 3.0 else 0.0 } ?: 0.0
        val fromPeak = ChronoUnit.DAYS.between(peak, date).toDouble() + drop
        return MudEstimator.ramp(fromPeak, listOf(4.0 to 1.0, 13.0 to 0.45, 25.0 to 0.15, 40.0 to 0.05))
            .let { if (fromPeak < 4.0) 1.0 else it }
    }
}
