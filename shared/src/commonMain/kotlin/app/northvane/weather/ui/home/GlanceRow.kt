package app.northvane.weather.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Things that are usually unremarkable, shown as chips until they matter (or are tapped). */
enum class Glance { AIR, UV, POLLEN, SUN }

data class GlanceChip(val glance: Glance, val label: String, val color: Color?)

/**
 * One row of small chips (air quality, UV, pollen, sunset). Tapping a chip
 * opens its full card just below; tapping it again closes it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GlanceRow(chips: List<GlanceChip>, open: Glance?, onToggle: (Glance) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        chips.forEach { chip ->
            FilterChip(
                selected = open == chip.glance,
                onClick = { onToggle(chip.glance) },
                label = { Text(chip.label, maxLines = 1) },
                leadingIcon = chip.color?.let { c -> { Box(Modifier.size(10.dp).clip(CircleShape).background(c)) } },
                colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                modifier = Modifier.semantics { contentDescription = chip.label + if (open == chip.glance) ", open" else ", tap for details" },
            )
        }
    }
}
