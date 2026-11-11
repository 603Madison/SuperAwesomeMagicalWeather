package app.northvane.weather.ui.alerts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.northvane.core.alerts.StormTimeline
import app.northvane.core.alerts.StormTimeline.Hazard
import app.northvane.core.time.*
import app.northvane.core.util.UnitFormatter
import app.northvane.weather.ui.TimeFormat

/** What the timeline's bars measure, in words, for [value]. */
internal fun StormTimeline.describe(value: Double, f: UnitFormatter): String? = when (hazard) {
    Hazard.SNOW -> "${f.snow(value)} of snow an hour"
    Hazard.ICE -> "${f.precipitation(value)} of ice an hour"
    Hazard.RAIN -> "${f.precipitation(value)} of rain an hour"
    Hazard.WIND -> "gusts to ${f.windSpeed(value)}"
    Hazard.THUNDER -> "${value.toInt()}% chance of thunder"
    Hazard.HEAT -> "feels like ${f.temperature(value)}"
    Hazard.COLD -> "feels like ${f.temperature(value)}"
    Hazard.COASTAL -> "high tide ${f.height(value)}"
    Hazard.OTHER -> null
}

/** One line for the Today banner: when the worst of it is. */
internal fun StormTimeline.headline(time: TimeFormat, f: UnitFormatter, now: Instant): String? {
    val w = worst ?: return null
    val span = if (w.from == w.to) time.smart(w.from) else "${time.hour(w.from)}–${time.hour(w.to.plusSeconds(3600))}"
    val what = describe(w.value, f)
    return if (w.from <= now && w.to.plusSeconds(3600) > now) {
        "Worst now, until ${time.hour(w.to.plusSeconds(3600))}" + (what?.let { ": $it" } ?: "")
    } else {
        "Worst $span" + (what?.let { ": $it" } ?: "")
    }
}

/**
 * The alert in time: starts, worst, ends, over a bar chart of the hazard's
 * hourly forecast with the alert's hours shaded and now marked.
 */
@Composable
internal fun StormTimelineCard(timeline: StormTimeline, color: Color, f: UnitFormatter, time: TimeFormat, now: Instant, onOpenRadar: () -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("${timeline.alert.event}: timeline", style = MaterialTheme.typography.titleMedium, color = color, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                Stage(
                    "Starts",
                    if (timeline.start <= now) "Under way" else time.smart(timeline.start),
                    if (timeline.start <= now) "since ${time.smart(timeline.start)}" else null,
                    Modifier.weight(1f),
                )
                val worst = timeline.worst
                Stage(
                    "Worst",
                    when {
                        worst == null -> "—"
                        worst.from == worst.to -> time.smart(worst.from)
                        else -> "${time.hour(worst.from)}–${time.hour(worst.to.plusSeconds(3600))}"
                    },
                    worst?.let { timeline.describe(it.value, f) },
                    Modifier.weight(1f),
                )
                Stage(
                    "Ends",
                    timeline.end?.let { time.smart(it) } ?: "Not given",
                    when (val easing = timeline.tapering) {
                        null -> if (timeline.endIsExpiry) "may be extended" else null
                        else -> "easing from ${time.time(easing)}"
                    },
                    Modifier.weight(1f),
                )
            }
            if (timeline.hours.isNotEmpty()) {
                Bars(timeline, color, time, now, Modifier.fillMaxWidth().height(96.dp).padding(top = 14.dp))
                Row(Modifier.fillMaxWidth()) {
                    Text(time.smart(timeline.hours.first().time), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    Text(time.smart(timeline.hours.last().time), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else if (timeline.hazard != Hazard.OTHER) {
                Text(
                    "The hourly forecast doesn't give numbers for this yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Text(
                "Times from the alert; the worst hours from the hourly forecast for this spot.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (timeline.hazard != Hazard.HEAT && timeline.hazard != Hazard.COLD) {
                OutlinedButton(onClick = onOpenRadar, modifier = Modifier.padding(top = 8.dp)) { Text("Watch it on radar") }
            }
        }
    }
}

@Composable
private fun Stage(label: String, value: String, detail: String?, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun Bars(timeline: StormTimeline, color: Color, time: TimeFormat, now: Instant, modifier: Modifier) {
    val hours = timeline.hours
    val from = hours.first().time
    val to = hours.last().time.plusSeconds(3600)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val band = color.copy(alpha = 0.12f)
    val description = buildString {
        append("Hourly bars from ${time.smart(from)} to ${time.smart(to)}")
        timeline.worst?.let { append("; worst around ${time.smart(it.at)}") }
    }
    Canvas(modifier.semantics { contentDescription = description }) {
        val span = (to.epochSeconds - from.epochSeconds).toFloat().coerceAtLeast(1f)
        fun x(t: Instant) = ((t.epochSeconds - from.epochSeconds) / span * size.width).coerceIn(0f, size.width)
        // The alert's hours.
        val bandStart = x(timeline.start)
        val bandEnd = timeline.end?.let { x(it) } ?: size.width
        if (bandEnd > bandStart) drawRect(band, Offset(bandStart, 0f), Size(bandEnd - bandStart, size.height))
        val values = hours.map { it.value }
        // Cold is worse the lower it goes: bars grow downward from the warmest hour.
        val lo = if (timeline.hazard.lowerIsWorse) values.max() else 0.0
        val hi = if (timeline.hazard.lowerIsWorse) values.min() else values.max()
        val range = (hi - lo).let { if (kotlin.math.abs(it) < 1e-6) 1.0 else it }
        val slot = size.width / (span / 3600f)
        val worst = timeline.worst
        hours.forEach { h ->
            val frac = ((h.value - lo) / range).toFloat().coerceIn(0.04f, 1f)
            val barHeight = frac * size.height
            val inWorst = worst != null && h.time >= worst.from && h.time <= worst.to
            val w = if (timeline.hazard == Hazard.COASTAL) 6.dp.toPx() else (slot * 0.7f).coerceAtLeast(2f)
            drawRoundRect(
                if (inWorst) color else muted.copy(alpha = 0.45f),
                topLeft = Offset(x(h.time) + (slot - w) / 2, size.height - barHeight),
                size = Size(w, barHeight),
                cornerRadius = CornerRadius(2.dp.toPx()),
            )
        }
        if (now in from..to) {
            val nx = x(now)
            drawLine(muted, Offset(nx, 0f), Offset(nx, size.height), strokeWidth = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
        }
    }
}
