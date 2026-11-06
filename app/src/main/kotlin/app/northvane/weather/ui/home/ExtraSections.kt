@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.northvane.weather.ui.home

import app.northvane.weather.ui.components.WeatherCard
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.northvane.core.outlook.PrecipKind
import app.northvane.core.outlook.StormSummary
import app.northvane.core.radar.SmokeLevel
import app.northvane.core.radar.SmokeOutlook
import app.northvane.core.util.UnitFormatter
import app.northvane.core.water.WaterReport
import app.northvane.ui.WeatherIcon
import app.northvane.weather.ui.TimeFormat
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow

/** The next significant storm: when, how much, and its heaviest stretch. */
@Composable
internal fun StormSection(storm: StormSummary, f: UnitFormatter, time: TimeFormat) {
    WeatherCard("Storm totals") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            WeatherIcon(
                when (storm.kind) {
                    PrecipKind.SNOW -> app.northvane.core.model.WeatherCondition.SNOW
                    PrecipKind.MIX -> app.northvane.core.model.WeatherCondition.FREEZING_RAIN
                    PrecipKind.RAIN -> app.northvane.core.model.WeatherCondition.HEAVY_RAIN
                },
                isDay = true, size = 36.dp,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    storm.alert ?: storm.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (storm.alert != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
                Note(
                    if (storm.start != null && storm.end != null) "${time.dayTime(storm.start!!)} to ${time.dayTime(storm.end!!)}"
                    else storm.days.joinToString(" – ") { time.dayName(it) },
                )
            }
        }
        val totals = listOfNotNull(
            storm.snowMm.takeIf { it >= 2.5 }?.let { "Snow ${f.snow(it)}" },
            storm.iceMm.takeIf { it >= 0.25 }?.let { "Ice ${f.precipitation(it)}" },
            storm.rainMm.takeIf { it >= 1.0 }?.let { "Rain ${f.precipitation(it)}" },
        )
        if (totals.isNotEmpty()) {
            Text(totals.joinToString("  ·  "), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
        }
        if (storm.peakStart != null && storm.peakEnd != null) {
            Note("Heaviest ${time.dayTime(storm.peakStart!!)} to ${time.hour(storm.peakEnd!!)}")
        }
        Note("Totals from the NWS forecast; they change as the storm nears.", modifier = Modifier.padding(top = 4.dp))
    }
}

private fun smokeColor(level: SmokeLevel): Color = when (level) {
    SmokeLevel.NONE, SmokeLevel.LIGHT -> Color(0xFF8A8F98)
    SmokeLevel.MODERATE -> Color(0xFFC9A100)
    SmokeLevel.SENSITIVE -> Color(0xFFEF7D1A)
    SmokeLevel.UNHEALTHY -> Color(0xFFD32F2F)
    SmokeLevel.VERY_UNHEALTHY -> Color(0xFF8E3FAF)
    SmokeLevel.HAZARDOUS -> Color(0xFF7E0023)
}

/** Near-surface wildfire smoke over the next day; only shown when there's some. */
@Composable
internal fun SmokeSection(smoke: SmokeOutlook, time: TimeFormat) {
    val peak = smoke.peak ?: return
    val level = SmokeLevel.of(peak.microgramsPerM3)
    WeatherCard("Wildfire smoke") {
        Text("Smoke: ${level.label.lowercase()}", style = MaterialTheme.typography.titleMedium, color = smokeColor(level))
        Note("Worst around ${time.smart(peak.time)}")
        smoke.hours.forEach { h ->
            val l = SmokeLevel.of(h.microgramsPerM3)
            // Wraps instead of colliding when the font is large.
            FlowRow(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(time.smart(h.time), style = MaterialTheme.typography.bodyMedium, maxLines = 1, modifier = Modifier.padding(end = 12.dp))
                Text("${l.short} · ${h.microgramsPerM3.roundToInt()} µg/m³", style = MaterialTheme.typography.bodyMedium, color = smokeColor(l), maxLines = 1)
            }
        }
        val advice = when {
            level >= SmokeLevel.UNHEALTHY -> "Limit time outdoors, keep windows closed and run a HEPA filter or air purifier."
            level >= SmokeLevel.SENSITIVE -> "People with asthma, heart disease, older adults and children should limit time outdoors."
            level >= SmokeLevel.MODERATE -> "Hazy; unusually sensitive people may notice it."
            else -> "A light haze at most."
        }
        Note(advice, modifier = Modifier.padding(top = 6.dp))
        Note("NOAA HRRR smoke model, ${time.dayTime(smoke.run)} run. Smoke adds to other pollution in the AQI.")
    }
}

/** The nearest river gauge and tide station, for paddling. */
@Composable
internal fun WaterSection(report: WaterReport, f: UnitFormatter, time: TimeFormat) {
    WeatherCard("Rivers & tides") {
        report.river?.let { r ->
            Text(r.name, style = MaterialTheme.typography.titleMedium)
            Note("USGS gauge, ${f.distance(r.distanceKm)} away" + (r.observedAt?.let { " · ${time.smart(it)}" } ?: ""))
            val parts = listOfNotNull(
                r.dischargeCfs?.let { "Flow ${f.discharge(it)}" },
                r.gageHeightFt?.let { "Height ${f.height(it / 3.28084)}" },
                r.waterTempC?.let { "Water ${f.temperature(it)}" },
            )
            if (parts.isNotEmpty()) Text(parts.joinToString("  ·  "), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
            r.heightChangeFt?.takeIf { abs(it) >= 0.1 }?.let { change ->
                val text = (if (change > 0) "Rising " else "Falling ") + f.height(abs(change) / 3.28084) + " in 6 hours"
                Note(text, color = if (r.rising) Caution else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        report.tide?.let { t ->
            if (report.river != null) Spacer(Modifier.padding(top = 10.dp))
            Text("Tides at ${t.name}", style = MaterialTheme.typography.titleMedium)
            Note("NOAA station, ${f.distance(t.distanceKm)} away" + (t.waterTempC?.let { " · water ${f.temperature(it)}" } ?: ""))
            t.events.filter { it.time.isAfter(java.time.Instant.now()) }.take(4).forEach { e ->
                Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Text(if (e.high) "High" else "Low", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(56.dp))
                    Text(time.smart(e.time), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(f.height(e.heightM), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (report.river == null && report.tide == null) Note("No river gauge or tide station nearby.")
    }
}
