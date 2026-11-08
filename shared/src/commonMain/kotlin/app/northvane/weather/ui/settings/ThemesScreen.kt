@file:OptIn(ExperimentalMaterial3Api::class)

package app.northvane.weather.ui.settings

import app.northvane.weather.ui.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.northvane.weather.AppContainer
import app.northvane.weather.ui.MainUiState
import app.northvane.weather.ui.components.SectionCard
import app.northvane.weather.ui.theme.AppTheme
import app.northvane.weather.ui.theme.CustomAccents
import app.northvane.weather.ui.theme.CustomBackground
import app.northvane.weather.ui.theme.LocalIsDark
import app.northvane.weather.ui.theme.ThemeKind
import app.northvane.weather.ui.theme.ThemeWeather
import app.northvane.weather.ui.theme.palette
import app.northvane.core.model.WeatherCondition
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun ThemesScreen(state: MainUiState, container: AppContainer, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val s = state.settings
    val dark = LocalIsDark.current
    var photoProblem by remember { mutableStateOf<String?>(null) }
    val picker = rememberPhotoPicker { bytes ->
        if (bytes != null) {
            scope.launch {
                if (container.themeBackground.save(bytes)) {
                    container.settings.setCustomBackground(CustomBackground.PHOTO)
                    container.settings.bumpCustomPhoto()
                    photoProblem = null
                } else {
                    photoProblem = "Couldn't read that image."
                }
            }
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        // Text on the theme backdrop needs the theme's color, not the black default for transparent containers.
        contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = {
            TopAppBar(
                title = { Text("Themes") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (s.dynamicColor) {
                Text(
                    "Wallpaper colors are on (Settings › Appearance), which overrides these themes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            ThemeKind.entries.forEach { kind ->
                SectionCard(
                    when (kind) {
                        ThemeKind.STATIC -> "Themes"
                        ThemeKind.DYNAMIC -> "Weather themes (change with conditions)"
                        ThemeKind.CUSTOM -> "Make your own"
                    },
                ) {
                    AppTheme.entries.filter { it.kind == kind }.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            row.forEach { theme ->
                                ThemeTile(theme, selected = theme == s.appTheme, dark = dark, accent = s.customAccent, background = s.customBackground,
                                    modifier = Modifier.weight(1f)) {
                                    scope.launch { container.settings.setAppTheme(theme) }
                                }
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                    if (kind == ThemeKind.CUSTOM && s.appTheme == AppTheme.CUSTOM) {
                        Text("Accent", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        CustomAccents.chunked(6).forEach { swatches ->
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                swatches.forEach { argb ->
                                    val selected = argb == s.customAccent
                                    Box(
                                        Modifier.size(36.dp).clip(CircleShape).background(Color(argb))
                                            .border(if (selected) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                            .clickable { scope.launch { container.settings.setCustomAccent(argb) } },
                                        contentAlignment = Alignment.Center,
                                    ) { if (selected) Icon(Icons.Filled.Check, contentDescription = "Selected", tint = Color.White) }
                                }
                            }
                        }
                        Text("Background", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        CustomBackground.entries.filter { it != CustomBackground.PHOTO }.chunked(3).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { bg ->
                                    FilterChip(
                                        selected = s.customBackground == bg,
                                        onClick = { scope.launch { container.settings.setCustomBackground(bg) } },
                                        label = { Text(bg.label) },
                                        leadingIcon = {
                                            val colors = (if (dark) bg.dark else bg.light).map { Color(it) }
                                            Box(Modifier.size(16.dp).clip(CircleShape).background(Brush.verticalGradient(colors)))
                                        },
                                    )
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            FilledTonalButton(onClick = {
                                picker()
                            }) { Text(if (s.customBackground == CustomBackground.PHOTO) "Change photo" else "Use my photo") }
                            if (s.customBackground == CustomBackground.PHOTO) {
                                FilterChip(selected = true, onClick = {
                                    scope.launch {
                                        container.themeBackground.clear()
                                        container.settings.setCustomBackground(CustomBackground.DUSK)
                                    }
                                }, label = { Text("Remove photo") })
                            }
                        }
                        photoProblem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        if (s.customBackground == CustomBackground.PHOTO) {
                            Text("Dim the photo: ${s.customDimPercent}%", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            var dim by remember(s.customDimPercent) { mutableFloatStateOf(s.customDimPercent.toFloat()) }
                            Slider(
                                value = dim,
                                onValueChange = { dim = it },
                                onValueChangeFinished = { scope.launch { container.settings.setCustomDim(dim.roundToInt()) } },
                                valueRange = 0f..80f,
                            )
                            Text(
                                "The photo is copied into the app (resized, location data removed) and stays on this device.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A small preview of a theme: its background with a primary-colored "card". */
@Composable
private fun ThemeTile(
    theme: AppTheme,
    selected: Boolean,
    dark: Boolean,
    accent: Long,
    background: CustomBackground,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    // Dynamic themes preview two of their moods side by side.
    val samples = when (theme) {
        AppTheme.LIVE_SKY -> listOf(ThemeWeather(WeatherCondition.CLEAR, true), ThemeWeather(WeatherCondition.RAIN, true))
        AppTheme.TEMPERATURE -> listOf(ThemeWeather(temperatureC = -8.0), ThemeWeather(temperatureC = 29.0))
        else -> listOf(ThemeWeather())
    }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier,
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(10.dp))) {
                samples.forEach { w ->
                    val p = palette(theme, dark, w, accent, if (background == CustomBackground.PHOTO) CustomBackground.DUSK else background)
                    Box(
                        Modifier.weight(1f).fillMaxSize().background(Brush.verticalGradient(listOf(p.backgroundTop, p.backgroundBottom))).padding(8.dp),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(Modifier.width(36.dp).height(10.dp).clip(RoundedCornerShape(5.dp)).background(p.primary))
                            Box(Modifier.width(24.dp).height(8.dp).clip(RoundedCornerShape(4.dp)).background(p.secondary.copy(alpha = 0.7f)))
                            Box(Modifier.size(12.dp).clip(CircleShape).background(p.tertiary))
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(theme.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (selected) Icon(Icons.Filled.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            }
            Text(theme.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
    }
}
