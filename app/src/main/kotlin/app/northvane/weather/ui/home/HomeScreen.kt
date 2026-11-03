@file:OptIn(ExperimentalMaterial3Api::class)

package app.northvane.weather.ui.home

import app.northvane.weather.ui.theme.LocalIsDark
import app.northvane.weather.ui.theme.LocalBackdrop
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.northvane.core.alerts.AlertClassifier
import app.northvane.core.model.CurrentConditions
import app.northvane.core.model.DailyForecast
import app.northvane.core.model.DataSource
import app.northvane.core.model.HourlyForecast
import app.northvane.core.model.Units
import app.northvane.core.model.WeatherAlert
import app.northvane.core.model.WeatherCondition
import app.northvane.core.model.WeatherSnapshot
import app.northvane.core.util.UnitFormatter
import app.northvane.weather.data.DataSaverMode
import app.northvane.weather.data.SatelliteFeature
import app.northvane.core.model.Section
import app.northvane.weather.ui.MainUiState
import app.northvane.weather.ui.TimeFormat
import app.northvane.weather.ui.components.NorthvaneIcons
import app.northvane.weather.ui.components.WeatherIcon
import app.northvane.weather.ui.theme.alertColor
import app.northvane.weather.ui.theme.WarningTheme
import app.northvane.weather.ui.theme.skyGradient
import app.northvane.weather.ui.theme.warningGradient
import app.northvane.core.model.AlertCategory
import app.northvane.core.outlook.AllergyEstimator
import app.northvane.core.aurora.AuroraAdvisor
import app.northvane.core.outlook.DryingAdvisor
import app.northvane.core.outlook.HeatingAdvisor
import app.northvane.core.garden.GardenAdvisor
import app.northvane.core.garden.Crop
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

/** Everything the Today screen can ask the app to do. */
class HomeActions(
    val refresh: () -> Unit,
    val selectLocation: (String) -> Unit,
    val useDeviceLocation: () -> Unit,
    val dismissError: () -> Unit,
    val markNotificationPromptShown: () -> Unit,
    val checkAlertsNow: () -> Unit,
    val setHourlyExpanded: (Boolean) -> Unit = {},
    val laundryOut: (Boolean) -> Unit = {},
    val markPlanted: (Crop) -> Unit = {},
    val refreshSummary: () -> Unit = {},
)

@Composable
fun HomeScreen(
    state: MainUiState,
    actions: HomeActions,
    onOpenAlerts: () -> Unit,
    onOpenLocations: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSources: () -> Unit = onOpenSettings,
    onOpenAllergies: () -> Unit = onOpenSettings,
    onOpenHeating: () -> Unit = onOpenSettings,
) {
    val snapshot = state.snapshot
    // A warning (not a watch, advisory or statement) turns the Today screen red.
    val warning = remember(snapshot?.alerts, snapshot?.fetchedAt) { snapshot?.hasActiveWarning(Instant.now()) == true }
    WarningTheme(active = warning) {
        HomeContent(state, actions, warning, onOpenAlerts, onOpenLocations, onOpenSettings, onOpenSources, onOpenAllergies, onOpenHeating)
    }
}

@Composable
private fun HomeContent(
    state: MainUiState,
    actions: HomeActions,
    warning: Boolean,
    onOpenAlerts: () -> Unit,
    onOpenLocations: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSources: () -> Unit,
    onOpenAllergies: () -> Unit,
    onOpenHeating: () -> Unit,
) {
    val context = LocalContext.current
    val snapshot = state.snapshot
    val formatter = remember(state.settings.units) { UnitFormatter(state.settings.units) }
    // Easter eggs: tap the temperature seven times for "science mode"; refresh five times in a row for some advice.
    var showKelvin by remember { mutableStateOf(false) }
    LaunchedEffect(showKelvin) {
        if (showKelvin) {
            kotlinx.coroutines.delay(6_000)
            showKelvin = false
        }
    }
    val refreshTimes = remember { ArrayDeque<Long>() }
    var touchGrass by remember { mutableStateOf(false) }
    val onRefresh: () -> Unit = {
        val now = System.currentTimeMillis()
        refreshTimes.addLast(now)
        while (refreshTimes.isNotEmpty() && now - refreshTimes.first() > 20_000) refreshTimes.removeFirst()
        if (refreshTimes.size >= 5) {
            touchGrass = true
            refreshTimes.clear()
        }
        actions.refresh()
    }
    val zone = remember(snapshot?.timeZoneId) {
        snapshot?.timeZoneId?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
    }
    val time = remember(zone) { TimeFormat(zone, DateFormat.is24HourFormat(context)) }

    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { it }) actions.useDeviceLocation()
    }
    val requestDeviceLocation: () -> Unit = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            actions.useDeviceLocation()
        } else {
            // Ask for both: the system dialog lets the user choose precise or approximate.
            locationPermission.launch(LOCATION_PERMISSIONS)
        }
    }

    // Ask for notification permission once, after the user has a location to get alerts for.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) actions.checkAlertsNow()
    }
    LaunchedEffect(state.locations.isNotEmpty(), state.settings.alertsEnabled, state.settings.notificationPromptShown) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            state.locations.isNotEmpty() && state.settings.alertsEnabled && !state.settings.notificationPromptShown &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            actions.markNotificationPromptShown()
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Scaffold(
        // The theme background shows through, except during a warning (red screen).
        containerColor = if (warning) MaterialTheme.colorScheme.background else Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            LocationTopBar(
                state = state,
                onSelect = actions.selectLocation,
                onManage = onOpenLocations,
                onRefresh = actions.refresh,
                onOpenSettings = onOpenSettings,
            )
        },
    ) { padding ->
        if (state.ready && state.locations.isEmpty()) {
            Onboarding(
                modifier = Modifier.padding(padding),
                onUseLocation = requestDeviceLocation,
                onSearch = onOpenLocations,
                error = state.error,
            )
        } else {
            PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = onRefresh,
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
            ) {
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (state.liteMode) {
                        item {
                            DataSaverBanner(
                                forced = state.settings.dataSaver == DataSaverMode.ALWAYS,
                                satellite = state.network.satellite,
                                dataSaver = state.network.dataSaver && !state.network.constrained,
                                allowed = state.settings.satelliteFeatures,
                            )
                        }
                    }
                    state.error?.let { message ->
                        item { MessageCard(message, onDismiss = actions.dismissError, stale = snapshot != null) }
                    }
                    if (touchGrass) {
                        item { TouchGrassCard(onDismiss = { touchGrass = false }) }
                    }
                    if (snapshot == null) {
                        item { LoadingCard(state.refreshing) }
                    } else {
                        val settings = state.settings
                        val showCurrent = state.shows(Section.CURRENT_CONDITIONS)
                        val hidden = mutableListOf<String>()
                        if (showCurrent) {
                            item {
                                HeroCard(snapshot, formatter, time, warning, onEasterEgg = { showKelvin = true }, kelvin = showKelvin)
                            }
                        } else {
                            hidden += "current conditions"
                        }
                        if (settings.aiEnabled && settings.aiBaseUrl.isNotBlank() && settings.aiModel.isNotBlank() &&
                            state.shows(Section.DAILY_FORECAST)
                        ) {
                            item { AiSummaryCard(state.ai, time, onRefresh = actions.refreshSummary) }
                        }
                        if (snapshot.alerts.isNotEmpty() && state.shows(Section.ALERTS)) {
                            item { AlertBanner(snapshot.alerts, time, onOpenAlerts) }
                        }
                        if (snapshot.hourly.isNotEmpty() && state.shows(Section.HOURLY_FORECAST)) {
                            item { HourlySection(snapshot.hourly, formatter, time, state.settings.hourlyExpanded, actions.setHourlyExpanded) }
                        } else if (snapshot.hourly.isNotEmpty() || Section.HOURLY_FORECAST in snapshot.skippedSections) {
                            hidden += "hourly forecast"
                        }
                        if (snapshot.daily.isNotEmpty() && state.shows(Section.DAILY_FORECAST)) {
                            item {
                                DailySection(
                                    snapshot.daily, formatter, time,
                                    showOutage = settings.outageRiskEnabled,
                                    heatTypes = if (settings.heatingEnabled) settings.heatSources.map { it.type }.distinct() else emptyList(),
                                    showPipes = settings.pipeRiskEnabled,
                                    showIce = settings.morningIceEnabled,
                                )
                            }
                        } else if (snapshot.daily.isNotEmpty()) {
                            hidden += "daily forecast"
                        }
                        val aurora = snapshot.aurora
                        if (settings.auroraEnabled && aurora != null && state.shows(Section.AURORA)) {
                            val lat = snapshot.latitude ?: state.selected?.latitude
                            val lon = state.selected?.longitude
                            if (lat != null && lon != null) {
                                // Cheap: a few nights of sun positions against at most 3 days of Kp values.
                                val report = AuroraAdvisor.report(aurora, lat, lon, snapshot.hourly, zone, Instant.now())
                                if (report.worthShowing(state.liteMode)) item { AuroraSection(report, time) }
                            }
                        }
                        val air = snapshot.airQuality
                        val airFresh = state.shows(Section.AIR_QUALITY)
                        if (settings.airQualityEnabled && air != null && airFresh) {
                            item { AirQualitySection(air, time) }
                        }
                        val uv = snapshot.uv
                        if (settings.uvEnabled && uv != null && airFresh) {
                            item { UvSection(uv, time) }
                        }
                        if ((air != null || uv != null) && !airFresh) hidden += "air quality and UV"
                        // Pollen (with the personal allergy forecast) needs a Google Pollen key.
                        val pollen = snapshot.pollen
                        if (settings.pollenEnabled && state.pollenKeySet && pollen != null && state.shows(Section.POLLEN)) {
                            item {
                                val allergyDays = remember(snapshot, settings.allergies) {
                                    AllergyEstimator.forecast(settings.allergies, snapshot.daily, pollen, snapshot.airQuality, time.today())
                                }
                                val allergy: (@Composable () -> Unit)? = if (settings.allergyEnabled) {
                                    { AllergyBlock(allergyDays, settings.allergies.isNotEmpty(), formatter, time, onOpenAllergies) }
                                } else {
                                    null
                                }
                                PollenSection(pollen, hasKey = true, time = time, onOpenSources = onOpenSources, allergyContent = allergy)
                            }
                        }
                        if (hidden.isNotEmpty()) {
                            item {
                                HintText(
                                    "Hidden in Backwoods mode because the data is over 12 hours old: ${hidden.joinToString(", ")}. " +
                                        "Turn on Settings › Backwoods mode › Show stale data to see it anyway.",
                                )
                            }
                        }
                        snapshot.current?.takeIf { showCurrent }?.let { current ->
                            item { DetailsSection(current, formatter) }
                        }
                        state.selected?.let { location -> item { SunCard(location, zone, time) } }
                        item { SourcesFooter(snapshot, time) }
                    }
                }
            }
        }
    }
}

internal val LOCATION_PERMISSIONS = arrayOf(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
)

@Composable
private fun LocationTopBar(
    state: MainUiState,
    onSelect: (String) -> Unit,
    onManage: () -> Unit,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    TopAppBar(
        title = {
            Box {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(enabled = state.locations.isNotEmpty()) { menuOpen = true }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                ) {
                    if (state.selected?.isDeviceLocation == true) {
                        Icon(Icons.Filled.LocationOn, contentDescription = "Current location", modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        state.selected?.displayName ?: "Northvane",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 240.dp),
                    )
                    if (state.locations.isNotEmpty()) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Switch location")
                    }
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    state.locations.forEach { location ->
                        DropdownMenuItem(
                            text = { Text(location.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingIcon = if (location.isDeviceLocation) {
                                { Icon(Icons.Filled.LocationOn, contentDescription = null) }
                            } else {
                                null
                            },
                            trailingIcon = if (location.id == state.selected?.id) {
                                { Icon(Icons.Filled.Check, contentDescription = "Selected") }
                            } else {
                                null
                            },
                            onClick = {
                                menuOpen = false
                                onSelect(location.id)
                            },
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Manage locations") },
                        leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onManage()
                        },
                    )
                }
            }
        },
        actions = {
            if (state.locations.isNotEmpty()) {
                IconButton(onClick = onRefresh, enabled = !state.refreshing) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                }
            }
            IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
    )
}

@Composable
private fun HeroCard(
    snapshot: WeatherSnapshot,
    f: UnitFormatter,
    time: TimeFormat,
    warning: Boolean,
    onEasterEgg: () -> Unit = {},
    kelvin: Boolean = false,
) {
    val taps = remember { ArrayDeque<Long>() }
    val current = snapshot.current
    val today = snapshot.daily.firstOrNull { it.date == time.today() } ?: snapshot.daily.firstOrNull()
    val condition = current?.condition ?: today?.condition ?: WeatherCondition.UNKNOWN
    val isDay = current?.isDaytime ?: true
    val dark = LocalIsDark.current
    val themeHero = LocalBackdrop.current.hero
    val gradient = remember(condition, isDay, dark, warning, themeHero) {
        when {
            warning -> warningGradient(dark)
            themeHero != null -> themeHero
            else -> skyGradient(condition, isDay, dark)
        }
    }
    val onHero = Color.White

    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.verticalGradient(gradient))
            .padding(20.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (kelvin) current?.temperatureC?.let { "%.1f K".format(it + 273.15) } ?: "--" else f.temperature(current?.temperatureC),
                        style = if (kelvin) MaterialTheme.typography.displayMedium else MaterialTheme.typography.displayLarge,
                        color = onHero,
                        modifier = Modifier.clickable(interactionSource = null, indication = null) {
                            val now = System.currentTimeMillis()
                            taps.addLast(now)
                            while (taps.isNotEmpty() && now - taps.first() > 3_000) taps.removeFirst()
                            if (taps.size >= 7) {
                                taps.clear()
                                onEasterEgg()
                            }
                        },
                    )
                    if (kelvin) Text("Science mode engaged. Absolute zero is a long way off.", style = MaterialTheme.typography.labelMedium, color = onHero.copy(alpha = 0.9f))
                    Text(
                        current?.description ?: condition.label,
                        style = MaterialTheme.typography.titleMedium,
                        color = onHero,
                    )
                    val line = buildList {
                        current?.feelsLikeC?.let { add("Feels like ${f.temperature(it)}") }
                        if (today != null && (today.highC != null || today.lowC != null)) {
                            add("H ${f.temperature(today.highC)}  L ${f.temperature(today.lowC)}")
                        }
                    }.joinToString("  ·  ")
                    if (line.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(line, style = MaterialTheme.typography.bodyMedium, color = onHero.copy(alpha = 0.88f))
                    }
                    val air = buildList {
                        current?.windSpeedKph?.let { wind ->
                            val gust = current.windGustKph?.takeIf { it - wind >= 8 }?.let { " g${f.windNumber(it)}" }.orEmpty()
                            add("Wind ${f.wind(wind, current.windDirectionDeg)}$gust")
                        }
                        current?.humidityPercent?.let { add("Humidity ${f.percent(it)}") }
                    }.joinToString("  ·  ")
                    if (air.isNotEmpty()) {
                        Text(air, style = MaterialTheme.typography.bodyMedium, color = onHero.copy(alpha = 0.88f))
                    }
                }
                WeatherIcon(condition, isDay, size = 104.dp, onDark = true)
            }
            Spacer(Modifier.height(16.dp))
            current?.let { SourceBadge(it, time) }
        }
    }
}

@Composable
private fun TouchGrassCard(onDismiss: () -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "🌱  The forecast is as fresh as it gets. Maybe step outside and check it in person?",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) { Text("Fine") }
        }
    }
}

/** A short plain-language briefing written by the user's own AI server from this forecast. */
@Composable
private fun AiSummaryCard(ai: app.northvane.weather.ui.AiUiState, time: TimeFormat, onRefresh: () -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 4.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("✦ Summary", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                if (ai.loading) {
                    CircularProgressIndicator(Modifier.size(18.dp).padding(end = 4.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                } else {
                    IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, contentDescription = "Write a new summary") }
                }
            }
            val summary = ai.summary
            when {
                summary != null -> Text(summary.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 12.dp))
                ai.loading -> Text("Writing a summary…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> Text("No summary yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ai.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp, end = 12.dp)) }
            summary?.let {
                Text(
                    "AI-generated by ${it.model} · ${TimeFormat.ago(it.createdAt)}. Check the forecast for details.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp, end = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun SourceBadge(current: CurrentConditions, time: TimeFormat) {
    val label = when (current.source) {
        DataSource.WEEWX -> current.stationName ?: "Local station"
        DataSource.NWS -> current.stationName ?: "NWS observation"
        DataSource.OWM -> "OpenWeatherMap"
        DataSource.NWS_FORECAST_ESTIMATE -> "Estimated from NWS forecast"
        else -> current.source.displayName
    }
    val age = current.observedAt?.let { " · ${TimeFormat.ago(it)}" }.orEmpty()
    Surface(shape = RoundedCornerShape(50), color = Color.White.copy(alpha = 0.18f)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (current.source == DataSource.WEEWX) Color(0xFF7CF2B4) else Color.White.copy(alpha = 0.8f)),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                (if (current.source == DataSource.WEEWX) "Local station · " else "") + label + age,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AlertBanner(alerts: List<WeatherAlert>, time: TimeFormat, onClick: () -> Unit) {
    val top = AlertClassifier.mostUrgent(alerts) ?: return
    val color = alertColor(top.category)
    // Warnings get a solid banner; watches, advisories and statements keep the light tint.
    val solid = top.category == AlertCategory.WARNING
    val onBanner = if (solid) Color.White else MaterialTheme.colorScheme.onSurface
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = if (solid) color else color.copy(alpha = 0.14f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = if (solid) Color.White else color)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(top.event, style = MaterialTheme.typography.titleMedium, color = onBanner)
                val until = (top.ends ?: top.expires)?.let { "Until ${time.smart(it)}" }
                val more = if (alerts.size > 1) "+${alerts.size - 1} more" else null
                Text(
                    listOfNotNull(until, more).joinToString(" · ").ifEmpty { "Tap for details" },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (solid) Color.White.copy(alpha = 0.9f) else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
    )
}

@Composable
private fun DetailsSection(current: CurrentConditions, f: UnitFormatter) {
    val tiles = buildList {
        current.humidityPercent?.let { add("Humidity" to f.percent(it)) }
        current.dewpointC?.let { add("Dew point" to f.temperature(it)) }
        if (current.windSpeedKph != null) add("Wind" to f.wind(current.windSpeedKph, current.windDirectionDeg))
        current.windGustKph?.let { add("Gusts" to f.windSpeed(it)) }
        current.pressureHpa?.let { add("Pressure" to f.pressure(it)) }
        current.visibilityKm?.let { add("Visibility" to f.visibility(it)) }
        current.rainTodayMm?.let { add("Rain today" to f.precipitation(it)) }
        current.rainRateMmPerHour?.takeIf { it > 0 }?.let { add("Rain rate" to f.precipitation(it) + "/h") }
        current.uvIndex?.let { add("UV index" to "${it.roundToInt()}") }
        current.solarRadiationWm2?.let { add("Solar" to "${it.roundToInt()} W/m²") }
    }
    if (tiles.isEmpty()) return
    Column {
        SectionTitle("Details")
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            tiles.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { (label, value) -> DetailTile(label, value, Modifier.weight(1f)) }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun DetailTile(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = modifier,
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(value, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SourcesFooter(snapshot: WeatherSnapshot, time: TimeFormat) {
    val current = snapshot.current
    val lines = buildList {
        current?.let { c ->
            val supplement = c.supplementedFrom?.let { " (gaps filled from ${it.displayName})" }.orEmpty()
            add("Current: ${c.source.displayName}$supplement")
        }
        snapshot.forecastSource?.let { add("Forecast: ${it.displayName}") }
        if (snapshot.alertsAvailable) add("Alerts: National Weather Service")
        add("Updated ${time.smart(snapshot.fetchedAt)}")
    }
    Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        lines.forEach {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        snapshot.issues.forEach { issue ->
            Text(
                "• ${issue.source.displayName}: ${issue.message}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

@Composable
private fun DataSaverBanner(forced: Boolean, satellite: Boolean, allowed: Set<SatelliteFeature>, dataSaver: Boolean = false) {
    val paused = SatelliteFeature.entries.filter { it !in allowed && it != SatelliteFeature.OWM_FALLBACK }
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(NorthvaneIcons.Satellite, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    when {
                        forced -> "Backwoods mode is on"
                        satellite -> "Backwoods mode: satellite connection"
                        dataSaver -> "Backwoods mode: Android Data Saver is on"
                        else -> "Backwoods mode: limited connection"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    if (paused.isEmpty()) {
                        "Everything is allowed (Settings › Backwoods mode)."
                    } else {
                        "Paused to save data: " + paused.joinToString(", ") { it.title.lowercase() } +
                            ". Change in Settings › Backwoods mode."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

@Composable
private fun MessageCard(message: String, onDismiss: () -> Unit, stale: Boolean) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                if (stale) {
                    Text("Showing the last saved data.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

@Composable
private fun LoadingCard(refreshing: Boolean) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(240.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (refreshing) CircularProgressIndicator() else HintText("Pull down to load the forecast.")
    }
}

@Composable
private fun HintText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

@Composable
private fun Onboarding(modifier: Modifier, onUseLocation: () -> Unit, onSearch: () -> Unit, error: String?) {
    Column(
        modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        WeatherIcon(WeatherCondition.PARTLY_CLOUDY, isDay = true, size = 120.dp)
        Spacer(Modifier.height(24.dp))
        Text("Welcome to Northvane", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Free forecasts, radar and alerts from the National Weather Service, with optional data from your own WeeWX station.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        Button(onClick = onUseLocation, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.LocationOn, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Use my location")
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onSearch, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Search, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Search for a place")
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "Your location is only used while the app is open.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        }
    }
}
