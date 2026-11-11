package app.northvane.weather.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.northvane.core.model.WeatherAlert
import app.northvane.core.time.*
import app.northvane.core.util.UnitFormatter
import app.northvane.core.water.TideOutlook
import app.northvane.core.water.Tides
import app.northvane.weather.ui.TimeFormat
import app.northvane.weather.ui.components.WeatherCard

/** Alerts about the sea coming ashore, when the high tides are what to watch. */
internal fun WeatherAlert.isCoastalFlooding(): Boolean =
    event.contains("Coastal Flood", ignoreCase = true) || event.contains("Storm Surge", ignoreCase = true)

/**
 * Tides for a place on the coast: which way the water is going, the next
 * highs and lows, and the day's curve. During a coastal flood alert, the high
 * tides inside it are picked out, since that's when the water comes up.
 */
@Composable
internal fun TideSection(tide: TideOutlook, alerts: List<WeatherAlert>, f: UnitFormatter, time: TimeFormat, now: Instant) {
    val state = remember(tide, now.epochSeconds / 300) { Tides.now(tide.events, now) }
    val flood = alerts.firstOrNull { it.isCoastalFlooding() }
    val floodHighs = flood?.let { a ->
        Tides.highsBetween(tide.events, a.onset ?: a.effective ?: now, a.ends ?: a.expires ?: now.plusSeconds(36 * 3600L))
            .filter { it.time > now }
    }.orEmpty()
    WeatherCard("Tides") {
        if (state == null) {
            Note("No tide predictions for the next few hours.")
            return@WeatherCard
        }
        val next = state.next
        val direction = if (state.rising) "Rising" else "Falling"
        Text(
            "$direction · ${if (next.high) "high" else "low"} at ${time.time(next.time)}",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Note(
            listOfNotNull(
                state.heightM?.let { "About ${f.height(it)} now" },
                "${if (next.high) "high" else "low"} ${f.height(next.heightM)}",
                state.following?.let { "then ${if (it.high) "high" else "low"} ${time.smart(it.time)}" },
            ).joinToString(", ").replaceFirstChar { it.uppercase() },
        )
        if (flood != null && floodHighs.isNotEmpty()) {
            val times = floodHighs.take(2).joinToString(" and ") { time.smart(it.time) }
            Note(
                "${flood.event}: the water comes up most around the high tide${if (floodHighs.size > 1) "s" else ""} at $times.",
                color = Caution,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        TideCurve(tide, now, time, Modifier.fillMaxWidth().height(84.dp).padding(top = 10.dp, bottom = 6.dp))
        tide.events.filter { it.time > now }.take(4).forEach { e ->
            val inFlood = floodHighs.any { it.time == e.time }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text(if (e.high) "High" else "Low", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(56.dp),
                    color = if (inFlood) Caution else MaterialTheme.colorScheme.onSurface)
                Text(time.smart(e.time), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                    color = if (inFlood) Caution else MaterialTheme.colorScheme.onSurface)
                Text(f.height(e.heightM), style = MaterialTheme.typography.bodyMedium)
            }
        }
        Note(
            "${tide.source.label} predictions for ${tide.name}, ${f.distance(tide.distanceKm)} away" +
                (tide.waterTempC?.let { " · water ${f.temperature(it)}" } ?: "") + ". Not for navigation.",
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** The next day of tide, with now marked and the highs and lows dotted. */
@Composable
private fun TideCurve(tide: TideOutlook, now: Instant, time: TimeFormat, modifier: Modifier) {
    val from = now.minusSeconds(3 * 3600L)
    val to = now.plusSeconds(24 * 3600L)
    val points = remember(tide, now.epochSeconds / 300) { Tides.curve(tide.events, from, to) }
    if (points.size < 2) return
    val extremes = tide.events.filter { it.time > from && it.time < to }
    val line = MaterialTheme.colorScheme.primary
    val fill = line.copy(alpha = 0.16f)
    val marker = MaterialTheme.colorScheme.onSurfaceVariant
    val description = "Tide curve for the next day: " + extremes.filter { it.time > now }
        .joinToString("; ") { "${if (it.high) "high" else "low"} at ${time.time(it.time)}" }
    Canvas(modifier.semantics { contentDescription = description }) {
        val lo = points.minOf { it.second }
        val hi = points.maxOf { it.second }
        val range = (hi - lo).takeIf { it > 0.01 } ?: 1.0
        val span = (to.epochSeconds - from.epochSeconds).toFloat()
        val pad = 6.dp.toPx()
        fun x(t: Instant) = (t.epochSeconds - from.epochSeconds) / span * size.width
        fun y(h: Double) = pad + ((hi - h) / range).toFloat() * (size.height - 2 * pad)
        val path = Path().apply {
            points.forEachIndexed { i, (t, h) -> if (i == 0) moveTo(x(t), y(h)) else lineTo(x(t), y(h)) }
        }
        val area = Path().apply {
            addPath(path)
            lineTo(x(points.last().first), size.height)
            lineTo(x(points.first().first), size.height)
            close()
        }
        drawPath(area, fill)
        drawPath(path, line, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
        extremes.forEach { e -> drawCircle(line, radius = 3.5.dp.toPx(), center = Offset(x(e.time), y(e.heightM))) }
        val nx = x(now)
        drawLine(marker, Offset(nx, 0f), Offset(nx, size.height), strokeWidth = 1.5.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
        Tides.heightAt(tide.events, now)?.let { h -> drawCircle(marker, radius = 4.dp.toPx(), center = Offset(nx, y(h))) }
    }
}
