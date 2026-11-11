@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.northvane.weather.ui.locations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.northvane.core.model.TripStop
import app.northvane.core.owm.PlaceResult
import app.northvane.core.time.*
import app.northvane.weather.ui.components.ChoiceDropdown

private const val DAY_MS = 86_400_000L

/** "Sat, Nov 14" or "Sat, Nov 14 – Mon, Nov 16". */
internal fun tripDates(start: LocalDate, end: LocalDate): String {
    val f = DateTimeFormatter.ofPattern("EEE, MMM d")
    return if (start == end) f.format(start) else "${f.format(start)} – ${f.format(end)}"
}

/** The coming Saturday and Sunday (or this weekend's, on a weekend). */
internal fun comingWeekend(today: LocalDate): Pair<LocalDate, LocalDate> {
    val toSaturday = (6 - today.dayOfWeek.value + 7) % 7
    val saturday = if (today.dayOfWeek.value == 7) today.minusDays(1) else today.plusDays(toSaturday.toLong())
    return saturday to saturday.plusDays(1)
}

/**
 * Adds a searched place as a trip stop: to a new trip, or as the next stop of
 * a trip already planned (a route), for some days.
 */
@Composable
internal fun TripDialog(
    place: PlaceResult,
    trips: List<TripStop>,
    onDismiss: () -> Unit,
    onSave: (tripId: String?, name: String, start: LocalDate, end: LocalDate) -> Unit,
) {
    val today = remember { LocalDate.now() }
    // One entry per trip; null stands for a new one.
    val choices: List<TripStop?> = listOf<TripStop?>(null) + trips.distinctBy { it.tripId }
    var trip by remember { mutableStateOf<TripStop?>(null) }
    var name by remember { mutableStateOf("") }
    val weekend = remember { comingWeekend(today) }
    var start by remember { mutableStateOf(weekend.first) }
    var end by remember { mutableStateOf(weekend.second) }
    var picking by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add ${place.name.substringBefore(",")} to a trip") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (choices.size > 1) {
                    ChoiceDropdown("Trip", choices, trip, { it?.tripName ?: "New trip" }, { chosen ->
                        trip = chosen
                        // A route's next stop starts where the trip is.
                        chosen?.let { start = maxOf(it.start, today); end = it.end.let { e -> if (e < start) start else e } }
                    })
                }
                if (trip == null) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(40) },
                        singleLine = true,
                        label = { Text("Trip name") },
                        placeholder = { Text(place.name.substringBefore(",")) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                OutlinedButton(onClick = { picking = true }, modifier = Modifier.fillMaxWidth()) { Text(tripDates(start, end)) }
                Text(
                    "Its forecast shows with the trip's other stops. Alerts for it start two days before, and it's removed the day after.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(trip?.tripId, name, start, end) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    if (picking) {
        val state = rememberDateRangePickerState(
            initialSelectedStartDateMillis = start.toEpochDay() * DAY_MS,
            initialSelectedEndDateMillis = end.toEpochDay() * DAY_MS,
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedStartDateMillis?.let { s ->
                        val first = LocalDate.ofEpochDay(s / DAY_MS)
                        start = maxOf(first, today)
                        end = maxOf(state.selectedEndDateMillis?.let { LocalDate.ofEpochDay(it / DAY_MS) } ?: first, start)
                    }
                    picking = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
        ) {
            DateRangePicker(state, modifier = Modifier.fillMaxWidth().height(520.dp), title = { Text("Trip dates", modifier = Modifier) })
        }
    }
}
