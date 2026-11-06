@file:OptIn(ExperimentalMaterial3Api::class)

package app.northvane.weather.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.northvane.core.garden.Crop
import app.northvane.core.garden.CropGroup
import app.northvane.core.garden.GardenCrop
import app.northvane.core.garden.PlantingMethod
import app.northvane.weather.AppContainer
import app.northvane.weather.data.GardenOption
import app.northvane.weather.ui.MainUiState
import app.northvane.weather.ui.components.ChoiceDropdown
import app.northvane.weather.ui.components.LabeledSwitch
import app.northvane.weather.ui.components.SectionCard
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Garden features and what the user intends to plant. */
@Composable
fun GardenScreen(state: MainUiState, container: AppContainer, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val s = state.settings
    val plan = s.gardenPlan
    fun save(newPlan: List<GardenCrop>) = scope.launch { container.settings.setGardenPlan(newPlan) }
    val day = DateTimeFormatter.ofPattern("MMM d")

    Scaffold(
        containerColor = Color.Transparent,
        // Text on the theme backdrop needs the theme's color, not the black default for transparent containers.
        contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = {
            TopAppBar(
                title = { Text("My garden") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            SectionCard("Garden features") {
                val climate = state.frostClimate
                Text(
                    if (climate != null) {
                        val y = LocalDate.now().year
                        "Your frost dates (NOAA, 30 years): last spring frost around ${climate.lastFrost(y).format(day)}, " +
                            "first fall frost around ${climate.firstFrost(y).format(day)}, about ${climate.growingSeasonDays} frost-free days."
                    } else {
                        "Frost dates for your place are downloaded once from NOAA (about 40 KB) when the garden is on."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                GardenOption.entries.forEach { option ->
                    val checked = when (option) {
                        GardenOption.PLANTING -> s.gardenPlanting
                        GardenOption.HARVEST -> s.gardenHarvest
                        GardenOption.FROST_ALERTS -> s.gardenFrostAlerts
                        GardenOption.WATERING -> s.gardenWatering
                        GardenOption.SOIL -> s.gardenSoil
                        GardenOption.GDD -> s.gardenGdd
                    }
                    LabeledSwitch(
                        title = option.title,
                        subtitle = option.description,
                        checked = checked,
                        onCheckedChange = { on ->
                            scope.launch {
                                container.settings.setGardenOption(option, on)
                                if (option == GardenOption.FROST_ALERTS) container.alertScheduler.sync()
                            }
                        },
                    )
                }
                Text(
                    "Cards appear only in season: planting from when the first seeds go indoors until the last crop is in, harvest as crops come ready through fall frost.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            CropGroup.entries.forEach { group ->
                SectionCard(group.label) {
                    Crop.entries.filter { it.group == group }.forEach { crop ->
                        val entry = plan.firstOrNull { it.crop == crop }
                        Column {
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    save(if (entry == null) plan + GardenCrop(crop) else plan.filterNot { it.crop == crop })
                                },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = entry != null,
                                    onCheckedChange = { on -> save(if (on) plan + GardenCrop(crop) else plan.filterNot { it.crop == crop }) },
                                )
                                Spacer(Modifier.width(4.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(crop.label, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        "${crop.tolerance.label} · ${crop.daysToHarvest.first}–${crop.daysToHarvest.last} days",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            if (entry != null) {
                                val methods = buildList {
                                    add(PlantingMethod.AUTO)
                                    if (crop.startsIndoors) add(PlantingMethod.SEED_INDOORS)
                                    if (crop.transplant != null) add(PlantingMethod.BUY_TRANSPLANTS)
                                    if (crop.directSow != null || crop.fallPlanted) add(PlantingMethod.DIRECT_SOW)
                                }
                                Column(Modifier.padding(start = 48.dp, bottom = 8.dp)) {
                                    if (methods.size > 2) {
                                        ChoiceDropdown(
                                            label = "How",
                                            options = methods,
                                            selected = entry.method,
                                            optionLabel = { if (it == PlantingMethod.AUTO) "Usual way (${entry.copy(method = PlantingMethod.AUTO).resolvedMethod.label.lowercase()})" else it.label },
                                            onSelect = { m -> save(plan.map { if (it.crop == crop) it.copy(method = m) else it }) },
                                        )
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            entry.plantedOn?.let { "Planted ${it.format(day)}" } ?: "Not planted yet",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.weight(1f),
                                        )
                                        if (entry.plantedOn == null) {
                                            TextButton(onClick = { save(plan.map { if (it.crop == crop) it.copy(plantedOn = LocalDate.now()) else it }) }) { Text("Planted today") }
                                        } else {
                                            TextButton(onClick = { save(plan.map { if (it.crop == crop) it.copy(plantedOn = null) else it }) }) { Text("Clear") }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Text(
                "Timing follows Northeast extension-service planting calendars and your local frost dates, then waits for the forecast to stay above each crop's frost limit.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
