package app.northvane.core.outlook

import app.northvane.core.model.BurnDay
import app.northvane.core.model.BurnRating
import app.northvane.core.model.BurnReason
import app.northvane.core.model.BurnReport
import app.northvane.core.model.DailyForecast
import app.northvane.core.model.HourlyForecast
import app.northvane.core.model.SapDay
import app.northvane.core.model.SapRating
import app.northvane.core.model.SapReport
import app.northvane.core.model.WeatherAlert
import app.northvane.core.model.WeatherHistory
import kotlin.math.roundToInt
import app.northvane.core.time.*

/**
 * Maple sap runs. Sap flows on a day that thaws after a freezing night, best
 * with nights in the 20s °F (−7 to −2 °C) and sunny days in the 40s °F
 * (4 to 9 °C). Nights that stay above freezing stop the flow, and a run of
 * them late in the season brings buds that end it.
 */
object SapAdvisor {
    /** Mid-January to the end of April, in the sugar maple's latitudes. */
    fun inSeason(today: LocalDate, latitude: Double): Boolean =
        latitude in 35.0..60.0 && when (today.monthValue) {
            1 -> today.dayOfMonth >= 15
            2, 3, 4 -> true
            else -> false
        }

    fun report(days: List<DailyForecast>, hourly: List<HourlyForecast>, history: WeatherHistory?, today: LocalDate, zone: ZoneId): SapReport {
        val upcoming = days.filter { !it.date.isBefore(today) }
        val byDate = days.associateBy { it.date }
        val result = upcoming.mapNotNull { d ->
            val high = d.highC ?: d.daytime?.tempMaxC ?: return@mapNotNull null
            val low = nightBefore(d.date, byDate, hourly, history, zone)
            val (rating, score) = rate(low, high, d.daytime?.skyCoverMean)
            SapDay(d.date, rating, score, low, high)
        }
        // Three warm nights in a row with mild days: flow stops, and late in the season buds follow.
        val warmSpell = upcoming.windowed(3).any { w -> w.all { (it.lowC ?: -99.0) > 1.0 } && w.count { (it.highC ?: 0.0) >= 13.0 } >= 2 }
        val note = when {
            today.monthValue >= 3 && warmSpell -> SapReport.Note.WARM_NIGHTS
            result.isNotEmpty() && result.take(5).all { (it.highC ?: 0.0) <= 0.0 } -> SapReport.Note.DEEP_FREEZE
            else -> null
        }
        return SapReport(result, note)
    }

    fun rate(nightLowC: Double?, highC: Double, skyCover: Int?): Pair<SapRating, Int> {
        if (nightLowC == null || highC <= 1.0) return SapRating.NONE to 0
        if (nightLowC > -1.0) {
            // A night that barely froze gives a short, light run at best.
            return if (nightLowC <= 1.0 && highC in 2.0..12.0) SapRating.LIGHT to 25 else SapRating.NONE to 0
        }
        val freeze = MudEstimator.ramp(nightLowC, listOf(-20.0 to 30.0, -14.0 to 60.0, -10.0 to 90.0, -6.0 to 100.0, -3.0 to 85.0, -1.0 to 45.0))
        val thaw = MudEstimator.ramp(highC, listOf(1.0 to 0.0, 3.0 to 60.0, 5.0 to 100.0, 8.0 to 100.0, 11.0 to 70.0, 15.0 to 30.0, 18.0 to 10.0))
        val sun = if ((skyCover ?: 100) <= 40) 10.0 else 0.0
        val score = (minOf(freeze, thaw) + sun).coerceAtMost(100.0).roundToInt()
        val rating = when {
            score >= 75 -> SapRating.GREAT
            score >= 50 -> SapRating.GOOD
            score >= 20 -> SapRating.LIGHT
            else -> SapRating.NONE
        }
        return rating to score
    }

    /** The low on the night before [date]: hourly forecast, then the previous day's low, then observed history. */
    private fun nightBefore(
        date: LocalDate,
        byDate: Map<LocalDate, DailyForecast>,
        hourly: List<HourlyForecast>,
        history: WeatherHistory?,
        zone: ZoneId,
    ): Double? {
        val from = date.minusDays(1).atTime(18, 0).atZone(zone).toInstant()
        val to = date.atTime(9, 0).atZone(zone).toInstant()
        covering(hourly, from, to)?.mapNotNull { it.temperatureC }?.minOrNull()?.let { return it }
        val previous = byDate[date.minusDays(1)]
        return previous?.lowC ?: previous?.overnight?.tempMinC
            ?: history?.days?.firstOrNull { it.date == date }?.lowC
    }
}

/**
 * Whether it's a good day to burn brush or yard waste. Wind, dry air, dry
 * spells and NWS fire weather alerts make fires escape; snow cover and recent
 * rain make burning safest. Permits and rules vary by town and state.
 */
object BurnAdvisor {
    private const val WET_DAY_MM = 2.5

    fun report(
        days: List<DailyForecast>,
        history: WeatherHistory?,
        alerts: List<WeatherAlert>,
        today: LocalDate,
        zone: ZoneId,
    ): BurnReport {
        val observed = history?.days.orEmpty().filter { it.date.isBefore(today) }.sortedBy { it.date }
        val lastObservedRain = observed.lastOrNull { (it.precipMm ?: 0.0) >= WET_DAY_MM }?.date
        val snowDepth = history?.snowDepthMm ?: 0.0
        val upcoming = days.filter { !it.date.isBefore(today) }.take(7)
        var lastRain = lastObservedRain
        var snowLasts = snowDepth >= 25.0
        val result = upcoming.map { d ->
            val dryDays = lastRain?.let { app.northvane.core.time.ChronoUnit.DAYS.between(it, d.date).toInt() }
                ?: if (observed.size >= 7) observed.size else null
            val ratedDay = rate(d, dryDays, snowLasts, alerts, zone)
            // A wet day resets the dry spell for the days after it; a mild day melts thin snow cover.
            val stats = d.daytime
            val expected = listOfNotNull(d.precipitationMm, stats?.precipMm).maxOrNull() ?: 0.0
            if (expected >= WET_DAY_MM && (d.precipitationChance ?: 0) >= 60) lastRain = d.date
            if ((d.highC ?: 0.0) >= 7.0 && snowDepth < 150) snowLasts = false
            ratedDay
        }
        val sinceRain = lastObservedRain?.let { app.northvane.core.time.ChronoUnit.DAYS.between(it, today).toInt() }
        return BurnReport(result, sinceRain)
    }

    internal fun rate(day: DailyForecast, dryDays: Int?, snowCover: Boolean, alerts: List<WeatherAlert>, zone: ZoneId): BurnDay {
        val stats = day.daytime
        val gust = listOfNotNull(stats?.gustMaxKph, day.gustMaxKph).maxOrNull()
        val wind = listOfNotNull(stats?.windMaxKph, day.windMaxKph).maxOrNull()
        val minHumidity = day.humidityMin ?: stats?.humidityMean?.let { it - 15 }
        val reasons = mutableListOf<BurnReason>()
        var rating = BurnRating.GOOD
        fun worsen(to: BurnRating) { if (to > rating) rating = to }

        fireAlert(day.date, alerts, zone)?.let {
            reasons += BurnReason(BurnReason.Kind.FIRE_ALERT, detail = it.event)
            worsen(BurnRating.NO_BURN)
        }
        when {
            gust != null && gust >= 40.0 -> { reasons += BurnReason(BurnReason.Kind.GUSTS, gust); worsen(BurnRating.NO_BURN) }
            wind != null && wind >= 24.0 -> { reasons += BurnReason(BurnReason.Kind.WIND, wind); worsen(BurnRating.NO_BURN) }
            gust != null && gust >= 29.0 -> { reasons += BurnReason(BurnReason.Kind.GUSTS, gust); worsen(BurnRating.CAUTION) }
            wind != null && wind >= 16.0 -> { reasons += BurnReason(BurnReason.Kind.WIND, wind); worsen(BurnRating.CAUTION) }
        }
        if (minHumidity != null && !snowCover) {
            if (minHumidity < 25) { reasons += BurnReason(BurnReason.Kind.DRY_AIR, minHumidity); worsen(BurnRating.NO_BURN) }
            else if (minHumidity < 35) { reasons += BurnReason(BurnReason.Kind.DRY_AIR, minHumidity); worsen(BurnRating.CAUTION) }
        }
        if (dryDays != null && dryDays >= 5 && !snowCover) {
            reasons += BurnReason(BurnReason.Kind.DRY_SPELL, dryDays.toDouble())
            worsen(if (dryDays >= 10 && (minHumidity ?: 50.0) < 40) BurnRating.NO_BURN else BurnRating.CAUTION)
        }
        if (snowCover) reasons += BurnReason(BurnReason.Kind.SNOW_COVER)
        if (dryDays != null && dryDays <= 2 && !snowCover) reasons += BurnReason(BurnReason.Kind.RECENT_RAIN)
        if (rating == BurnRating.GOOD && (wind ?: 99.0) < 16.0) reasons += BurnReason(BurnReason.Kind.LIGHT_WIND, wind)
        if ((day.precipitationChance ?: 0) >= 60) reasons += BurnReason(BurnReason.Kind.RAIN_LIKELY, day.precipitationChance?.toDouble())
        return BurnDay(day.date, rating, reasons)
    }

    /** An NWS Red Flag Warning, Fire Weather Watch or similar in effect during the day. */
    private fun fireAlert(date: LocalDate, alerts: List<WeatherAlert>, zone: ZoneId): WeatherAlert? {
        val from = date.atTime(8, 0).atZone(zone).toInstant()
        val to = date.atTime(20, 0).atZone(zone).toInstant()
        return alerts.firstOrNull { a ->
            val e = a.event.lowercase()
            val fire = "red flag" in e || "fire weather" in e || "fire danger" in e || "fire warning" in e
            val start = a.onset ?: a.effective ?: a.sent
            val end = a.ends ?: a.expires
            fire && (start == null || start.isBefore(to)) && (end == null || end.isAfter(from))
        }
    }
}
