package app.northvane.weather.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.northvane.core.model.SavedLocation
import app.northvane.core.model.WeatherSnapshot
import app.northvane.core.time.*
import app.northvane.core.util.UnitFormatter
import app.northvane.ui.WeatherIcon
import app.northvane.weather.ui.TimeFormat
import app.northvane.weather.ui.components.WeatherCard

/**
 * A trip at a glance: each stop on each of its days, with that day's
 * forecast, and the stop's alerts. Tapping a row opens that stop.
 */
@Composable
internal fun TripSection(
    stops: List<Pair<SavedLocation, WeatherSnapshot?>>,
    selectedId: String?,
    f: UnitFormatter,
    time: TimeFormat,
    onSelect: (String) -> Unit,
) {
    val first = stops.firstOrNull()?.first?.trip ?: return
    val today = time.today()
    val end = stops.maxOf { it.first.trip!!.end }
    WeatherCard("Trip: ${first.tripName}") {
        stops.forEach { (stop, snapshot) ->
            val trip = stop.trip ?: return@forEach
            val days = generateSequence(maxOf(trip.start, today)) { it.plusDays(1) }.takeWhile { !it.isAfter(trip.end) }.toList()
            val alerts = snapshot?.alerts.orEmpty()
            Column(
                Modifier.fillMaxWidth().clickable(enabled = stop.id != selectedId) { onSelect(stop.id) }.padding(vertical = 6.dp),
            ) {
                Text(
                    stop.displayName.substringBefore(","),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (stop.id == selectedId) FontWeight.Bold else FontWeight.Medium,
                    color = if (stop.id == selectedId) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                if (alerts.isNotEmpty()) {
                    Note(alerts.joinToString(" · ") { it.event }, color = Caution)
                }
                days.forEach { date ->
                    val day = snapshot?.daily?.firstOrNull { it.date == date }
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(time.dayName(date), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(96.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (day != null) {
                            WeatherIcon(day.condition, isDay = true, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                day.shortForecast ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                listOfNotNull(
                                    day.precipitationChance?.takeIf { it >= 20 }?.let { "$it%" },
                                    "${f.temperature(day.highC)} / ${f.temperature(day.lowC)}",
                                ).joinToString("  "),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        } else {
                            val reachable = date.minusDays(6)
                            Text(
                                when {
                                    snapshot == null -> "Loading…"
                                    reachable.isAfter(today) -> "Forecast from ${time.monthDay(reachable)}"
                                    else -> "No forecast for this day"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        Note("These stops drop off your places after ${time.monthDay(end)}.", modifier = Modifier.padding(top = 4.dp))
    }
}
