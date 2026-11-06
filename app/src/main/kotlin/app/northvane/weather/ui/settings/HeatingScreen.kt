@file:OptIn(ExperimentalMaterial3Api::class)

package app.northvane.weather.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.northvane.core.model.UnitSystem
import app.northvane.core.model.Units
import app.northvane.core.outlook.HeatSource
import app.northvane.core.outlook.HeatType
import app.northvane.weather.AppContainer
import app.northvane.weather.ui.MainUiState
import app.northvane.weather.ui.components.ChoiceDropdown
import app.northvane.weather.ui.components.LabeledSwitch
import app.northvane.weather.ui.components.SectionCard
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.roundToInt

/** How the household heats, and under which outdoor temperatures each source is used. */
@Composable
fun HeatingScreen(state: MainUiState, container: AppContainer, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val s = state.settings
    val imperial = s.units == UnitSystem.IMPERIAL
    var editing by remember { mutableStateOf<HeatSource?>(null) }
    fun save(list: List<HeatSource>) = scope.launch { container.settings.setHeatSources(list) }
    fun show(f: Int?) = f?.let { if (imperial) "$it°F" else "${Units.fToC(it.toDouble()).roundToInt()}°C" }

    Scaffold(
        containerColor = Color.Transparent,
        // Text on the theme backdrop needs the theme's color, not the black default for transparent containers.
        contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = {
            TopAppBar(
                title = { Text("My heating") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            SectionCard(null) {
                LabeledSwitch(
                    title = "Heating season card",
                    subtitle = "Shows from mid-July (fuel and wood prep) through May",
                    checked = s.heatingEnabled,
                    onCheckedChange = { on -> scope.launch { container.settings.setHeating(on) } },
                )
                LabeledSwitch(
                    title = "Aggressive mode",
                    subtitle = "Switch systems day by day (and at night) as the forecast changes. Off: one recommendation from the two-week average.",
                    checked = s.heatingAggressive,
                    onCheckedChange = { on -> scope.launch { container.settings.setHeatingAggressive(on) } },
                )
            }
            SectionCard("How I heat") {
                Text(
                    "Add each heat source and the outdoor temperatures you use it in, judged by the day's average. " +
                        "For example: heat pump above 15°F, wood stove below 30°F, oil furnace as backup below 0°F. Ranges can overlap.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                s.heatSources.forEach { source ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text(source.label, style = MaterialTheme.typography.bodyLarge)
                            val range = when {
                                source.minF != null && source.maxF != null -> "between ${show(source.minF)} and ${show(source.maxF)}"
                                source.minF != null -> "above ${show(source.minF)}"
                                source.maxF != null -> "below ${show(source.maxF)}"
                                else -> "all season"
                            }
                            Text("Use $range", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { editing = source }) { Icon(Icons.Filled.Edit, contentDescription = "Edit ${source.label}") }
                        IconButton(onClick = { save(s.heatSources.filterNot { it.id == source.id }) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Remove ${source.label}")
                        }
                    }
                }
                FilledTonalButton(onClick = { editing = HeatSource(UUID.randomUUID().toString(), HeatType.HEAT_PUMP) }) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text("  Add heat source")
                }
            }
        }
    }

    editing?.let { initial ->
        var type by remember(initial.id) { mutableStateOf(initial.type) }
        var name by remember(initial.id) { mutableStateOf(initial.name.orEmpty()) }
        fun toField(f: Int?) = f?.let { if (imperial) "$it" else "${Units.fToC(it.toDouble()).roundToInt()}" }.orEmpty()
        fun fromField(t: String) = t.trim().toDoubleOrNull()?.let { if (imperial) it.roundToInt() else Units.cToF(it).roundToInt() }
        var min by remember(initial.id) { mutableStateOf(toField(initial.minF)) }
        var max by remember(initial.id) { mutableStateOf(toField(initial.maxF)) }
        val unit = if (imperial) "°F" else "°C"
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Heat source") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ChoiceDropdown("Type", HeatType.entries, type, { it.label }, { type = it })
                    OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, singleLine = true, label = { Text("Name (optional)") }, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = min, onValueChange = { min = it.filter { c -> c.isDigit() || c == '-' }.take(4) }, singleLine = true,
                            label = { Text("Use above ($unit)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = max, onValueChange = { max = it.filter { c -> c.isDigit() || c == '-' }.take(4) }, singleLine = true,
                            label = { Text("Use below ($unit)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
                        )
                    }
                    Text("Leave a box empty for no limit.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val updated = initial.copy(type = type, name = name.trim().ifEmpty { null }, minF = fromField(min), maxF = fromField(max))
                    save(if (s.heatSources.any { it.id == updated.id }) s.heatSources.map { if (it.id == updated.id) updated else it } else s.heatSources + updated)
                    editing = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
}
