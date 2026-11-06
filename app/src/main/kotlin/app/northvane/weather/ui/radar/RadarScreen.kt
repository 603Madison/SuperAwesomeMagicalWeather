@file:OptIn(ExperimentalMaterial3Api::class)

package app.northvane.weather.ui.radar

import app.northvane.ui.RadarLegend
import androidx.compose.ui.graphics.Color
import app.northvane.weather.ui.theme.LocalIsDark
import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.northvane.core.radar.RadarFrame
import app.northvane.core.radar.RadarSource
import app.northvane.core.radar.SatelliteImagery
import app.northvane.core.util.Solar
import java.time.Instant
import app.northvane.weather.AppContainer
import app.northvane.weather.data.SatelliteFeature
import app.northvane.weather.ui.MainUiState
import app.northvane.weather.ui.TimeFormat
import app.northvane.weather.ui.components.NorthvaneIcons
import java.time.ZoneId
import kotlin.math.roundToInt

@Composable
fun RadarScreen(state: MainUiState, container: AppContainer, onOpenSettings: () -> Unit) {
    val vm: RadarViewModel = viewModel(factory = RadarViewModel.factory(container))
    val radar by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var loadOnSatellite by rememberSaveable { mutableStateOf(false) }
    val location = state.selected
    val dark = LocalIsDark.current
    val zone = remember(state.snapshot?.timeZoneId) {
        state.snapshot?.timeZoneId?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
    }
    val time = remember(zone) { TimeFormat(zone, DateFormat.is24HourFormat(context)) }
    val paused = state.liteMode && SatelliteFeature.RADAR !in state.settings.satelliteFeatures && !loadOnSatellite

    // Re-read frames when the user changes radar settings elsewhere.
    LaunchedEffect(state.settings.radarSource, state.settings.futureCastEnabled, state.settings.futureCastMinutes) {
        if (radar.loadedAt != null &&
            (radar.source != state.settings.radarSource || radar.futureCast != state.settings.futureCastEnabled)
        ) {
            vm.load()
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        // Text on the theme backdrop needs the theme's color, not the black default for transparent containers.
        contentColor = MaterialTheme.colorScheme.onBackground,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Radar")
                        location?.let {
                            Text(it.displayName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                actions = {
                    if (!paused && location != null) {
                        IconButton(onClick = vm::load) { Icon(Icons.Filled.Refresh, contentDescription = "Reload radar") }
                        SourceMenu(radar.source, vm::setSource, onOpenSettings)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            when {
                location == null -> CenteredMessage("Add a location on the Today tab to see radar.")
                paused -> SatellitePaused(
                    onLoadAnyway = {
                        loadOnSatellite = true // the radar then loads like any other visit
                    },
                    onOpenSettings = onOpenSettings,
                )
                else -> {
                    LaunchedEffect(location.point) { vm.ensureLoaded(location.point) }
                    val settings = state.settings
                    val satelliteAllowed = !state.liteMode || SatelliteFeature.SATELLITE_IMAGERY in settings.satelliteFeatures || loadOnSatellite
                    val imagery = remember(settings.satelliteImagery, location.latitude, location.longitude, radar.loadedAt) {
                        SatelliteImagery.resolve(
                            settings.satelliteImagery,
                            Solar.isDaylight(location.latitude, location.longitude, Instant.now()),
                        )
                    }
                    RadarMap(
                        center = location.point,
                        frames = radar.frames,
                        index = radar.index,
                        dark = dark,
                        modifier = Modifier.fillMaxSize(),
                        pin = location.point,
                        alertAreas = state.snapshot?.alerts.orEmpty().mapNotNull { alert ->
                            alert.area?.let { rings -> rings to alertHex(alert.category) }
                        },
                        satelliteTiles = if (settings.satelliteLayer && satelliteAllowed) SatelliteImagery.tileUrl(imagery) else null,
                    )
                    SatelliteToggle(
                        on = settings.satelliteLayer,
                        allowed = satelliteAllowed,
                        label = imagery.label.substringBefore(" ("),
                        onToggle = { vm.setSatelliteLayer(!settings.satelliteLayer) },
                        modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                    )
                    if (radar.loading) {
                        LinearProgressIndicator(
                            Modifier
                                .fillMaxWidth()
                                .align(Alignment.TopCenter),
                        )
                    }
                    Legend(Modifier.align(Alignment.TopStart).padding(12.dp))
                    Controls(
                        state = radar,
                        time = time,
                        satellite = settings.satelliteLayer && satelliteAllowed,
                        onTogglePlay = vm::togglePlay,
                        onSeek = vm::seek,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SourceMenu(current: RadarSource, onSelect: (RadarSource) -> Unit, onOpenSettings: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Radar options") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            RadarSource.entries.forEach { source ->
                DropdownMenuItem(
                    text = { Text((if (source == current) "✓  " else "    ") + source.label) },
                    onClick = {
                        open = false
                        onSelect(source)
                    },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Future cast settings…") },
                onClick = {
                    open = false
                    onOpenSettings()
                },
            )
        }
    }
}

@Composable
private fun Controls(
    state: RadarUiState,
    time: TimeFormat,
    satellite: Boolean,
    onTogglePlay: () -> Unit,
    onSeek: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        // A see-through container isn't a theme color, so say which text color goes on it.
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.95f),
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            state.message?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledIconButton(onClick = onTogglePlay, enabled = state.frames.size > 1) {
                    Icon(
                        if (state.playing) NorthvaneIcons.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (state.playing) "Pause" else "Play",
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    val frame = state.current
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            frame?.let { time.smart(it.time) } ?: "--",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.width(8.dp))
                        frame?.let { FrameBadge(it, state.index == state.lastPastIndex) }
                    }
                    if (state.frames.size > 1) {
                        Slider(
                            value = state.index.toFloat(),
                            onValueChange = { onSeek(it.roundToInt()) },
                            valueRange = 0f..(state.frames.size - 1).toFloat(),
                            steps = (state.frames.size - 2).coerceAtLeast(0),
                        )
                    }
                }
            }
            Text(
                buildString {
                    append(state.source.attribution)
                    if (satellite) append(" · ${SatelliteImagery.ATTRIBUTION}")
                    val forecast = state.frames.filter { it.isForecast }
                    if (forecast.isNotEmpty()) {
                        // Newest run first; later frames may come from the run before while the newest is still publishing.
                        val runs = forecast.mapNotNull { it.run }.distinct().sortedDescending()
                            .joinToString(", ") { "%02dZ".format(it.atOffset(java.time.ZoneOffset.UTC).hour) }
                        append(" · Forecast: NOAA HRRR model" + (if (runs.isNotEmpty()) " ($runs run)" else "") +
                            (if (forecast.first().image != null) ", from NOAA open data" else " via Iowa Environmental Mesonet") +
                            if (forecast.any { it.calibrated }) ", calibrated to the latest radar, which leads the first two hours" else "")
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SatelliteToggle(on: Boolean, allowed: Boolean, label: String, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f),
        contentColor = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        onClick = onToggle,
        modifier = modifier,
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                NorthvaneIcons.Satellite,
                contentDescription = null,
                tint = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                when {
                    on && !allowed -> "Satellite (paused)"
                    on -> "Satellite · $label"
                    else -> "Satellite"
                },
                style = MaterialTheme.typography.labelLarge,
                color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun FrameBadge(frame: RadarFrame, latest: Boolean) {
    val (label, color) = when {
        frame.isForecast -> "Forecast" to MaterialTheme.colorScheme.tertiary
        latest -> "Latest" to MaterialTheme.colorScheme.primary
        else -> "Past" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = 0.15f)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun Legend(modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Canvas(
                Modifier
                    .width(120.dp)
                    .height(8.dp),
            ) {
                drawRoundRect(
                    brush = Brush.horizontalGradient(RadarLegend.map { it.second }),
                    cornerRadius = CornerRadius(size.height / 2, size.height / 2),
                )
            }
            Row(Modifier.width(120.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Light", style = MaterialTheme.typography.labelSmall)
                Text("Heavy", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun SatellitePaused(onLoadAnyway: () -> Unit, onOpenSettings: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            NorthvaneIcons.Satellite,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text("Radar is packed away in Backwoods mode", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "Radar maps download many images, so they're off in Backwoods mode. You can load them once now, or allow them in Backwoods mode settings.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onLoadAnyway) { Text("Load radar anyway") }
        TextButton(onClick = onOpenSettings) { Text("Backwoods mode settings") }
    }
}

@Composable
private fun CenteredMessage(text: String) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Map colors for alert outlines, matching the alert cards. */
private fun alertHex(category: app.northvane.core.model.AlertCategory): String = when (category) {
    app.northvane.core.model.AlertCategory.WARNING -> "#E53935"
    app.northvane.core.model.AlertCategory.WATCH -> "#FB8C00"
    app.northvane.core.model.AlertCategory.ADVISORY -> "#FDD835"
    else -> "#64B5F6"
}
