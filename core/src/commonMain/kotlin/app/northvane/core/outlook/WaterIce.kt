package app.northvane.core.outlook

import app.northvane.core.model.DailyForecast
import app.northvane.core.model.WeatherHistory
import kotlin.math.max
import kotlin.math.min
import app.northvane.core.time.*

/**
 * Ice on lakes and rivers, as a running freezing-degree-day balance (°C-days):
 * cold days add their degrees below freezing, mild days melt it back. A few
 * days of hard freeze ([FROZEN]) skin over ponds and coves and send ice floes
 * down rivers; after a long winter the balance (capped at [CAP]) takes weeks
 * of thaw to clear, matching late-April ice-out on New England lakes.
 */
object WaterIce {
    const val SHORE_ICE = 5.0
    const val FROZEN = 20.0
    private const val CAP = 200.0

    /**
     * Rough lake-ice thickness (inches) from the freezing balance, using
     * Stefan's law with a coefficient typical of snow-covered lake ice.
     */
    fun thicknessInches(balance: Double): Double = 1.5 * kotlin.math.sqrt(balance.coerceAtLeast(0.0)) / 2.54

    fun forecast(history: WeatherHistory?, days: List<DailyForecast>, today: LocalDate): Map<LocalDate, Double> {
        val observed = history?.days.orEmpty().filter { it.date.isBefore(today) }.sortedBy { it.date }
            .map { Triple(it.date, it.highC, it.lowC) }
        val ahead = days.filter { !it.date.isBefore(today) }.sortedBy { it.date }.map { Triple(it.date, it.highC, it.lowC) }
        var ice = 0.0
        val result = linkedMapOf<LocalDate, Double>()
        for ((date, high, low) in observed + ahead) {
            val h = high ?: low ?: continue
            val mean = (h + (low ?: h)) / 2
            ice = if (mean < 0) min(CAP, ice - mean) else max(0.0, ice - mean)
            if (!date.isBefore(today)) result[date] = ice
        }
        return result
    }
}
