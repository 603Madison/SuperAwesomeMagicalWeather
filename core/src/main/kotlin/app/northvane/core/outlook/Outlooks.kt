package app.northvane.core.outlook

import app.northvane.core.model.WeatherSnapshot
import app.northvane.core.outage.OutageContext
import app.northvane.core.outage.OutageEstimator
import java.time.Instant
import java.time.ZoneId

/**
 * Derived outlooks for a snapshot: mud, fall foliage, activity scores, the
 * season-aware power outage estimate, frozen pipe risk and morning ice. Pure functions of the snapshot, so
 * they're recomputed whenever cached sections are merged into a fresh one.
 */
object Outlooks {
    fun annotate(s: WeatherSnapshot, latitude: Double, longitude: Double, now: Instant = s.fetchedAt): WeatherSnapshot {
        val zone = runCatching { ZoneId.of(s.timeZoneId) }.getOrDefault(ZoneId.systemDefault())
        val today = now.atZone(zone).toLocalDate()
        val elevation = s.history?.elevationM ?: s.elevationM
        val days = Periods.fill(s.daily, s.hourly, grid = null, zone = zone)

        val mud = MudEstimator.forecast(s.history, days, today)
        val foliage = FoliageEstimator.report(latitude, s.history, days, today)
        val ctx = ActivityScorer.Context(
            latitude, longitude, zone, s.alerts, WaterIce.forecast(s.history, days, today),
            snowConfirmed = (s.history?.snowDepthMm ?: 0.0) >= 25.0,
        )
        val outage = OutageContext(
            leafCover = { date -> FoliageEstimator.leafCover(date, latitude, elevation, foliage) },
            mud = mud,
        )
        val snowOnGround = (s.history?.snowDepthMm ?: 0.0) >= 25.0
        val withOutage = OutageEstimator.annotate(days, s.alerts, zone, outage)
        val annotated = withOutage.mapIndexed { i, d ->
            val dayMud = mud[d.date]
            d.copy(
                mud = dayMud,
                activities = ActivityScorer.score(d, dayMud, ctx),
                pipeRisk = PipeFreeze.estimate(d, s.hourly, zone),
                morningIce = MorningIceEstimator.estimate(d, withOutage.getOrNull(i - 1)?.takeIf { it.date == d.date.minusDays(1) }, s.hourly, zone, snowOnGround, now),
            )
        }
        return s.copy(daily = annotated, foliage = foliage, latitude = latitude, elevationM = elevation)
    }
}
