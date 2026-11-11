package app.northvane.weather.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.northvane.core.outlook.RainCheck
import app.northvane.core.time.*
import app.northvane.core.util.UnitFormatter
import app.northvane.weather.ui.TimeFormat
import app.northvane.weather.ui.components.WeatherCard

/** "Stayed dry despite a 70% chance", for the last measured day. */
internal fun RainCheck.Day.verdict(f: UnitFormatter): String = when {
    rained && chance >= 50 -> "Rain was likely, and ${f.precipitation(rainMm)} fell"
    rained && chance < 30 -> "${f.precipitation(rainMm)} fell, with only a $chance% chance"
    rained -> "${f.precipitation(rainMm)} fell, with a $chance% chance"
    chance >= 50 -> "Stayed dry, despite a $chance% chance"
    chance < 30 -> "Dry, as forecast ($chance%)"
    else -> "Stayed dry, with a $chance% chance"
}

/**
 * Did it rain? The last measured day's chance against what fell, the last
 * two weeks at a glance, and, after a few weeks, how the chances work out at
 * this spot, including today's.
 */
@Composable
internal fun RainCheckSection(report: RainCheck.Report, todayChance: Int?, f: UnitFormatter, time: TimeFormat) {
    val latest = report.latest ?: return
    WeatherCard("Did it rain?") {
        val day = if (latest.date == time.today().minusDays(1)) "Yesterday" else time.dayName(latest.date)
        Text("$day: ${latest.verdict(f)}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        val recent = report.days.takeLast(14)
        if (recent.size >= 3) {
            val rainy = recent.count { it.rained }
            val description = "Last ${recent.size} days: rain on $rainy. " +
                recent.joinToString("; ") { "${time.weekday(it.date)} ${it.chance}% ${if (it.rained) "rained" else "dry"}" }
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp).clearAndSetSemantics { contentDescription = description },
            ) {
                recent.forEach { d -> DayDot(d) }
            }
            Note("Dots filled where rain fell; the forecast chance underneath.", modifier = Modifier.padding(top = 4.dp))
        }
        val telling = report.bands.filter { it.days >= 10 }
        if (telling.isNotEmpty()) {
            Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                telling.forEach { b ->
                    val tens = (b.rainPercent + 5) / 10
                    Text(
                        "Chances ${b.band.label}: rain $tens day${if (tens == 1) "" else "s"} in 10 (${b.days} days)",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        } else {
            Note("After about a month this also shows how the rain chances work out at this spot.", modifier = Modifier.padding(top = 8.dp))
        }
        todayChance?.takeIf { it >= 20 }?.let { chance ->
            report.note(chance)?.let { Text("Today's $chance%: $it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp)) }
        }
        Note(
            if (report.fromStation) {
                "Rain measured by your weather station."
            } else {
                "Rain from NOAA's daily analysis for this spot, which can miss a passing shower."
            } + " Chances as forecast the day before.",
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun DayDot(d: RainCheck.Day) {
    val color = MaterialTheme.colorScheme.primary
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(12.dp)) {
            if (d.rained) drawCircle(color) else drawCircle(color, style = Stroke(width = 1.5.dp.toPx()))
        }
        Text("${d.chance}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
