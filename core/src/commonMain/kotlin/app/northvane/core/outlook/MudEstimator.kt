package app.northvane.core.outlook

import app.northvane.core.model.DailyForecast
import app.northvane.core.model.MudLevel
import app.northvane.core.model.MudOutlook
import app.northvane.core.model.MudReason
import app.northvane.core.model.MudReason.Kind
import app.northvane.core.model.WeatherHistory
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import app.northvane.core.time.*

/**
 * Trail mud estimate from a simple daily water balance, run over the last
 * weeks of observed weather (NOAA ACIS) and on through the forecast.
 *
 * - **Rain and snowmelt** wet the top layer of soil; warm, long days dry it.
 * - **Spring thaw ("mud season")**: in northern New England the ground freezes
 *   a foot or more deep. As it thaws from the top down, meltwater sits on the
 *   still-frozen layer below and can't drain, so trails stay soupy for weeks
 *   even without rain, until the frost is gone (typically late March to late
 *   May; later at elevation). Frost depth is tracked from freezing and
 *   thawing degree-days.
 * - Ground that is frozen solid, or under snow, isn't muddy.
 */
object MudEstimator {
    /** Settled snowpack depth per unit of water equivalent. */
    private const val SNOW_RATIO = 4.0

    private data class DayInput(val date: LocalDate, val highC: Double?, val lowC: Double?, val precipMm: Double?, val forecast: Boolean)

    private class State {
        var wetness = 0.0 // mm of free water near the surface
        var swe = 0.0 // snowpack water equivalent, mm
        var freezeDegreeDays = 0.0
        var thawDegreeDays = 0.0
        val meltLog = ArrayDeque<Double>()
        val rainLog = ArrayDeque<Pair<Double, Boolean>>() // (rain mm, from the forecast)

        fun frostDepthIndex() = sqrt(freezeDegreeDays)
        fun thawDepthIndex() = 1.25 * sqrt(thawDegreeDays)

        /** Thawed at the surface but still frozen below: water can't drain. */
        fun mudSeason() = freezeDegreeDays >= 120 && thawDegreeDays >= 4 && thawDepthIndex() < frostDepthIndex()
        fun groundFrozen() = freezeDegreeDays >= 30 && thawDegreeDays < 4
    }

    fun forecast(history: WeatherHistory?, days: List<DailyForecast>, today: LocalDate): Map<LocalDate, MudOutlook> {
        val observed = history?.days.orEmpty()
            .filter { it.date.isBefore(today) }
            .sortedBy { it.date }
            .map { DayInput(it.date, it.highC, it.lowC, it.precipMm, forecast = false) }
        val ahead = days.filter { !it.date.isBefore(today) }.sortedBy { it.date }.map { d ->
            val precip = d.precipitationMm ?: when (d.precipitationChance ?: 0) {
                in 70..100 -> 10.0
                in 40..69 -> 4.0
                in 20..39 -> 1.0
                else -> 0.0
            }
            DayInput(d.date, d.highC, d.lowC, precip, forecast = true)
        }
        val state = State()
        val noHistory = observed.size < 7
        if (noHistory) seed(state, today)

        val result = linkedMapOf<LocalDate, MudOutlook>()
        var snowDepthApplied = false
        for (day in observed + ahead) {
            if (day.forecast && !snowDepthApplied) {
                // A measured snow depth is better than our running guess; ~4:1 for settled snow.
                history?.snowDepthMm?.let { state.swe = it / SNOW_RATIO }
                snowDepthApplied = true
            }
            step(state, day)
            if (day.forecast) result[day.date] = classify(state, day, noHistory).copy(snowDepthMm = (state.swe * SNOW_RATIO).takeIf { it >= 5 } ?: 0.0)
        }
        return result
    }

    /** Without history, start from typical conditions for the time of year. */
    private fun seed(state: State, today: LocalDate) {
        when (today.monthValue) {
            12, 1, 2 -> { state.freezeDegreeDays = 150.0; state.wetness = 3.0 }
            3, 4 -> { state.freezeDegreeDays = 350.0; state.thawDegreeDays = 30.0; state.wetness = 12.0 }
            5 -> { state.wetness = 8.0 }
            in 6..8 -> state.wetness = 3.0
            else -> state.wetness = 6.0
        }
    }

    private fun step(state: State, day: DayInput) {
        val high = day.highC ?: day.lowC ?: return
        val low = day.lowC ?: high
        val mean = (high + low) / 2
        val precip = (day.precipMm ?: 0.0).coerceAtLeast(0.0)

        // Frost in the ground.
        if (mean < 0) {
            state.freezeDegreeDays += -mean
            // A hard freeze re-solidifies the thawed surface layer.
            if (mean < -3) state.thawDegreeDays = max(0.0, state.thawDegreeDays - 1.5 * -mean)
        } else if (state.freezeDegreeDays > 0) {
            state.thawDegreeDays += mean
        }
        // Once the frost is fully gone, forget last winter.
        if (state.freezeDegreeDays > 0 && state.thawDepthIndex() >= state.frostDepthIndex() * 1.15 && mean > 4) {
            state.freezeDegreeDays = 0.0
            state.thawDegreeDays = 0.0
        }

        // Precipitation: snow when it's cold, rain otherwise.
        val rain: Double
        if (mean <= 0.5 && high < 3) {
            state.swe += precip
            rain = 0.0
        } else {
            rain = precip
        }
        val melt = if (mean > 0 && state.swe > 0) min(state.swe, 3.0 * mean + 0.5 * max(0.0, high)) else 0.0
        state.swe -= melt

        // Drying: little drainage through frozen ground; evaporation rises with warmth.
        val loss = when {
            state.groundFrozen() && high < 2 -> 0.02
            state.mudSeason() -> 0.07
            else -> (0.10 + 0.012 * max(0.0, high)).coerceAtMost(0.4)
        }
        state.wetness = (state.wetness * (1 - loss) - if (state.groundFrozen()) 0.0 else 0.6).coerceAtLeast(0.0)
        state.wetness = min(60.0, state.wetness + rain * 0.8 + melt * 0.9)

        state.rainLog.addLast(rain to day.forecast)
        state.meltLog.addLast(melt)
        while (state.rainLog.size > 3) state.rainLog.removeFirst()
        while (state.meltLog.size > 3) state.meltLog.removeFirst()
    }

    private fun classify(state: State, day: DayInput, noHistory: Boolean): MudOutlook {
        val reasons = mutableListOf<MudReason>()
        if (noHistory) reasons += MudReason(Kind.NO_HISTORY)
        val high = day.highC ?: 5.0
        val low = day.lowC ?: high
        val recentRain = state.rainLog.sumOf { it.first }
        val observedRain = state.rainLog.filterNot { it.second }.sumOf { it.first }
        val forecastRain = recentRain - observedRain
        val recentMelt = state.meltLog.sum()

        if (state.swe >= 12 && high < 6) {
            reasons += MudReason(Kind.SNOW_COVER, state.swe * 4)
            return MudOutlook(MudLevel.SNOW_COVERED, 10, mudSeason = false, reasons = reasons)
        }
        if (high < 1 && low < -3) {
            reasons += MudReason(Kind.FROZEN)
            return MudOutlook(MudLevel.FROZEN, 5, mudSeason = false, reasons = reasons)
        }

        var score = ramp(state.wetness, listOf(0.0 to 0.0, 4.0 to 15.0, 12.0 to 40.0, 25.0 to 65.0, 45.0 to 90.0))
        // Midwinter thaws make surface mud too, but the long spring thaw is what's called mud season.
        val thaw = state.mudSeason() && day.date.monthValue in 2..6
        if (thaw) {
            // Afternoons are the worst: overnight refreeze firms trails up until mid-morning.
            score = max(score + 30, 55.0)
            reasons += MudReason(Kind.THAW)
        }
        if (observedRain >= 6) reasons += MudReason(Kind.RECENT_RAIN, observedRain)
        if (forecastRain >= 6) reasons += MudReason(Kind.FORECAST_RAIN, forecastRain)
        if (recentMelt >= 5) reasons += MudReason(Kind.SNOWMELT, recentMelt)
        if (!thaw && recentRain < 1 && state.wetness < 8 && high > 15) reasons += MudReason(Kind.DRYING)
        val s = score.coerceIn(0.0, 100.0).roundToInt()
        val level = when {
            s >= 70 -> MudLevel.VERY_MUDDY
            s >= 40 -> MudLevel.MUDDY
            s >= 15 -> MudLevel.DAMP
            else -> MudLevel.DRY
        }
        return MudOutlook(level, s, mudSeason = thaw, reasons = reasons)
    }

    internal fun ramp(x: Double, points: List<Pair<Double, Double>>): Double {
        if (x <= points.first().first) return points.first().second
        for (i in 1 until points.size) {
            val (x0, y0) = points[i - 1]
            val (x1, y1) = points[i]
            if (x <= x1) return y0 + (y1 - y0) * (x - x0) / (x1 - x0)
        }
        return points.last().second
    }
}
