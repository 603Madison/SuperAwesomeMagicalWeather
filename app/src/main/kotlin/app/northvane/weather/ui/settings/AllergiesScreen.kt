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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.northvane.core.model.Allergen
import app.northvane.core.model.AllergenGroup
import app.northvane.core.model.Sensitivity
import app.northvane.weather.AppContainer
import app.northvane.weather.ui.MainUiState
import app.northvane.weather.ui.components.LabeledSwitch
import app.northvane.weather.ui.components.SectionCard
import app.northvane.weather.ui.components.SegmentedChoice
import kotlinx.coroutines.launch

/** Pick allergens and how strongly you react to each; drives the personal allergy score. */
@Composable
fun AllergiesScreen(state: MainUiState, container: AppContainer, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val s = state.settings
    Scaffold(
        containerColor = Color.Transparent,
        // Text on the theme backdrop needs the theme's color, not the black default for transparent containers.
        contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = {
            TopAppBar(
                title = { Text("My allergies") },
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
                    title = "Allergy score on Today",
                    subtitle = "A daily 0–100 score for how your allergies will be, from the pollen forecast for the plants you pick, " +
                        "estimated outdoor mold, air quality and the weather (rain washes pollen out; dry wind spreads it).",
                    checked = s.allergyEnabled,
                    onCheckedChange = { on -> scope.launch { container.settings.setAllergy(on) } },
                )
                if (!state.pollenKeySet) {
                    Text(
                        "Pollen data needs a Google Pollen API key (Settings › Data sources). Mold and air pollution work without one.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            AllergenGroup.entries.forEach { group ->
                SectionCard(group.label) {
                    Allergen.entries.filter { it.group == group }.forEach { allergen ->
                        val sensitivity = s.allergies[allergen]
                        Column {
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    scope.launch { container.settings.setAllergen(allergen, if (sensitivity == null) Sensitivity.MODERATE else null) }
                                },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = sensitivity != null,
                                    onCheckedChange = { on -> scope.launch { container.settings.setAllergen(allergen, if (on) Sensitivity.MODERATE else null) } },
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(allergen.label, style = MaterialTheme.typography.bodyLarge)
                            }
                            if (sensitivity != null) {
                                SegmentedChoice(
                                    options = Sensitivity.entries,
                                    selected = sensitivity,
                                    label = { it.label },
                                    onSelect = { level -> scope.launch { container.settings.setAllergen(allergen, level) } },
                                    modifier = Modifier.padding(start = 48.dp, bottom = 8.dp),
                                )
                            }
                        }
                    }
                }
            }
            Text(
                "This is a weather-based estimate, not medical advice. Your doctor or allergist knows your allergies best.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
