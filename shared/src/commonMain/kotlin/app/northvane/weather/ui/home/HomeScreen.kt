@file:OptIn(ExperimentalMaterial3Api::class)

package app.northvane.weather.ui.home

import app.northvane.weather.ui.*
import app.northvane.ui.WeatherIcon
import app.northvane.ui.skyGradient
import app.northvane.ui.warningGradient
import app.northvane.ui.alertColor
import app.northvane.weather.ui.theme.LocalIsDark
import app.northvane.weather.ui.theme.LocalBackdrop
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.northvane.core.alerts.AlertClassifier
import app.northvane.core.model.CurrentConditions
import app.northvane.core.model.DailyForecast
import app.northvane.core.model.DataSource
import app.northvane.core.model.HourlyForecast
import app.northvane.core.model.Units
import app.northvane.core.model.WeatherAlert
import app.northvane.core.model.WeatherCondition
import app.northvane.core.model.WeatherSnapshot
import app.northvane.weather.ui.alerts.headline
import app.northvane.core.util.UnitFormatter
import app.northvane.weather.data.DataSaverMode
import app.northvane.weather.data.SatelliteFeature
import app.northvane.core.model.Section
import app.northvane.weather.ui.MainUiState
import app.northvane.weather.ui.TimeFormat
import app.northvane.weather.ui.components.NorthvaneIcons
import app.northvane.weather.ui.theme.WarningTheme
import app.northvane.core.model.AlertCategory
import app.northvane.core.outlook.AllergyEstimator
import app.northvane.core.aurora.AuroraAdvisor
import app.northvane.core.outlook.DryingAdvisor
import app.northvane.core.outlook.HeatingAdvisor
import app.northvane.core.garden.GardenAdvisor
import app.northvane.core.garden.Crop
import kotlin.math.roundToInt
import app.northvane.weather.data.TodayCard
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import app.northvane.core.outlook.TodayHeadline
import app.northvane.core.model.UvLevel
import app.northvane.core.outlook.OutlookText
import app.northvane.weather.ui.components.CardMenu
import app.northvane.weather.ui.components.LocalCardMenu
import app.northvane.weather.ui.components.WeatherCard
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material.icons.filled.KeyboardArrowUp
import app.northvane.core.util.Solar
import app.northvane.core.model.SavedLocation
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import app.northvane.core.model.UnitSystem
import app.northvane.weather.data.AppSettings
import app.northvane.weather.data.Interest
import app.northvane.core.alerts.AlertAreas
import app.northvane.core.alerts.describe
import app.northvane.core.model.GeoPoint
import app.northvane.core.outlook.WhatToWear
import app.northvane.core.outlook.MyTimes
import app.northvane.core.outlook.AskSuggestions
import app.northvane.core.time.*
import app.northvane.core.util.fmt

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
    val refreshDiscussion: () -> Unit = {},
    val moveCard: (TodayCard, Int, List<TodayCard>) -> Unit = { _, _, _ -> },
    val setCardShown: (TodayCard, Boolean) -> Unit = { _, _ -> },
    val openCardSettings: (TodayCard) -> Unit = {},
    val dismissAllergyPrompt: () -> Unit = {},
    val setUnits: (UnitSystem) -> Unit = {},
    val setInterest: (Interest, Boolean) -> Unit = { _, _ -> },
    val ask: (String) -> Unit = {},
    val clearAnswer: () -> Unit = {},
    /** Stop Backwoods mode following Android's ambiguous "restricted background data". */
    val ignoreRestrictedData: () -> Unit = {},
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
    val warning = remember(snapshot?.alerts, snapshot?.fetchedAt) { snapshot?.hasActiveWarning(kotlin.time.Clock.System.now()) == true }
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
    val is24 = is24HourClock()
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
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
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
    // The next big storm, worked out once per forecast.
    val storm = remember(snapshot, zone, state.settings.stormEnabled) {
        snapshot?.takeIf { state.settings.stormEnabled }?.let {
            app.northvane.core.outlook.StormTotals.find(it.hourly, it.daily, it.alerts, kotlin.time.Clock.System.now(), zone)
        }
    }
    val time = remember(zone) { TimeFormat(zone, is24) }
    var openGlance by rememberSaveable { mutableStateOf<Glance?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val hideCard: (TodayCard) -> Unit = { card ->
        actions.setCardShown(card, false)
        scope.launch {
            val result = snackbar.showSnackbar("${card.label} hidden", actionLabel = "Undo", duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) actions.setCardShown(card, true)
        }
    }

    val granted = rememberPermissionCheck()
    val locationPermission = rememberPermissionLauncher { ok -> if (ok) actions.useDeviceLocation() }
    val requestDeviceLocation: () -> Unit = {
        if (granted(Permission.LOCATION)) {
            actions.useDeviceLocation()
        } else {
            // The system dialog lets the user choose precise or approximate.
            locationPermission.launch(Permission.LOCATION)
        }
    }

    // Ask for notification permission once, after the user has a location to get alerts for.
    val notificationPermission = rememberPermissionLauncher { ok -> if (ok) actions.checkAlertsNow() }
    LaunchedEffect(state.locations.isNotEmpty(), state.settings.alertsEnabled, state.settings.notificationPromptShown) {
        if (state.locations.isNotEmpty() && state.settings.alertsEnabled && !state.settings.notificationPromptShown &&
            !granted(Permission.NOTIFICATIONS)
        ) {
            actions.markNotificationPromptShown()
            notificationPermission.launch(Permission.NOTIFICATIONS)
        }
    }

    Scaffold(
        // The theme background shows through, except during a warning (red screen).
        containerColor = if (warning) MaterialTheme.colorScheme.background else Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
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
                settings = state.settings,
                onUseLocation = requestDeviceLocation,
                onSearch = onOpenLocations,
                onSetUnits = actions.setUnits,
                onSetInterest = actions.setInterest,
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
                                restricted = state.network.restricted && !state.network.dataSaver && !state.network.constrained,
                                allowed = state.settings.satelliteFeatures,
                                onIgnoreRestricted = actions.ignoreRestrictedData,
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
                        val now = kotlin.time.Clock.System.now()
                        val hidden = mutableListOf<String>()
                        val showCurrent = state.shows(Section.CURRENT_CONDITIONS)
                        val headline = if (state.shows(Section.HOURLY_FORECAST)) {
                            TodayHeadline.write(
                                snapshot.hourly, snapshot.daily, now, zone, formatter, time::time, time::hour, state.nowcast,
                                times = MyTimes.upcoming(settings.myTimes, now, zone),
                            )
                        } else {
                            emptyList()
                        }
                        if (showCurrent) {
                            item(key = "hero") {
                                val wear = if (settings.wearEnabled && state.shows(Section.HOURLY_FORECAST)) {
                                    WhatToWear.advise(snapshot.hourly, now, settings.comfort, time::hour, snapshot.uv?.todayMax)
                                } else {
                                    null
                                }
                                HeroCard(snapshot, formatter, time, warning, headline, wear, onEasterEgg = { showKelvin = true }, kelvin = showKelvin)
                            }
                        } else {
                            hidden += "current conditions"
                            if (headline.isNotEmpty()) item(key = "headline") { HeadlineCard(headline) }
                        }
                        if (snapshot.alerts.isNotEmpty() && state.shows(Section.ALERTS)) {
                            item(key = "alerts") {
                                AlertBanner(
                                    snapshot.alerts, time, onOpenAlerts, state.selected?.point, formatter,
                                    timeline = if (settings.stormTimelineEnabled) {
                                        { top -> worstLine(top, snapshot, formatter, time) }
                                    } else {
                                        null
                                    },
                                )
                            }
                        }

                        // At a glance: air, UV, pollen and the sun as chips, unless one of them matters today.
                        val airFresh = state.shows(Section.AIR_QUALITY)
                        if ((snapshot.airQuality != null || snapshot.uv != null) && !airFresh) hidden += "air quality and UV"
                        val air = snapshot.airQuality?.takeIf { settings.airQualityEnabled && airFresh && it.current != null }
                        val uv = snapshot.uv?.takeIf { settings.uvEnabled && airFresh && it.at(now) != null }
                        val pollen = snapshot.pollen?.takeIf { settings.pollenEnabled && state.pollenKeySet && state.shows(Section.POLLEN) && it.days.isNotEmpty() }
                        val aqi = air?.current?.aqi ?: 0
                        val uvMax = uv?.let { it.todayMax ?: it.peak()?.index } ?: 0.0
                        val pollenIndex = pollen?.days?.firstOrNull()?.worst?.index ?: 0
                        val airNotable = aqi > 50
                        val uvNotable = uvMax >= 6.0
                        val pollenNotable = pollenIndex >= 4
                        val location = state.selected
                        val chips = buildList {
                            air?.current?.takeIf { !airNotable }?.let { add(GlanceChip(Glance.AIR, "AQI ${it.aqi} · ${it.category.label}", aqiColor(it.category))) }
                            uv?.takeIf { !uvNotable }?.let { u ->
                                val nowUv = u.at(now)?.roundToInt() ?: 0
                                add(GlanceChip(Glance.UV, "UV $nowUv · peak ${uvMax.roundToInt()}", uvColor(UvLevel.of(uvMax))))
                            }
                            pollen?.takeIf { !pollenNotable }?.let {
                                add(GlanceChip(Glance.POLLEN, "Pollen " + OutlookText.pollenCategory(pollenIndex).lowercase(), pollenColor(pollenIndex)))
                            }
                            location?.let { add(GlanceChip(Glance.SUN, sunChipLabel(it, zone, time, now), null)) }
                        }
                        val pollenCard: @Composable () -> Unit = {
                            val allergyDays = remember(snapshot, settings.allergies) {
                                AllergyEstimator.forecast(settings.allergies, snapshot.daily, pollen, snapshot.airQuality, time.today())
                            }
                            val showAllergy = settings.allergyEnabled && (settings.allergies.isNotEmpty() || !settings.allergyPromptDismissed)
                            val allergy: (@Composable () -> Unit)? = if (showAllergy) {
                                {
                                    AllergyBlock(
                                        allergyDays, settings.allergies.isNotEmpty(), formatter, time, onOpenAllergies,
                                        onDismissPrompt = actions.dismissAllergyPrompt,
                                    )
                                }
                            } else {
                                null
                            }
                            PollenSection(pollen, hasKey = true, time = time, onOpenSources = onOpenSources, allergyContent = allergy)
                        }
                        if (state.trip.isNotEmpty()) {
                            item(key = "trip") { TripSection(state.trip, state.selected?.id, formatter, time, actions.selectLocation) }
                        }
                        if (chips.isNotEmpty()) {
                            item(key = "glance") { GlanceRow(chips, openGlance, onToggle = { g -> openGlance = if (openGlance == g) null else g }) }
                            openGlance?.takeIf { g -> chips.any { it.glance == g } }?.let { g ->
                                item(key = "glance-$g") {
                                    when (g) {
                                        Glance.AIR -> air?.let { AirQualitySection(it, time) }
                                        Glance.UV -> uv?.let { UvSection(it, time) }
                                        Glance.POLLEN -> pollenCard()
                                        Glance.SUN -> location?.let { SunCard(it, zone, time) }
                                    }
                                }
                            }
                        }

                        // The movable cards, in the user's order.
                        val cards = linkedMapOf<TodayCard, @Composable () -> Unit>()
                        if (settings.stormEnabled && storm != null && (state.shows(Section.HOURLY_FORECAST) || state.shows(Section.DAILY_FORECAST))) {
                            cards[TodayCard.STORM] = { StormSection(storm, formatter, time) }
                        }
                        val smoke = snapshot.smoke
                        if (settings.smokeEnabled && smoke != null && state.shows(Section.SMOKE) &&
                            (smoke.peak?.microgramsPerM3 ?: 0.0) >= app.northvane.core.radar.SmokeLevel.LIGHT.from
                        ) {
                            cards[TodayCard.SMOKE] = { SmokeSection(smoke, time) }
                        }
                        if (snapshot.hourly.isNotEmpty() && state.shows(Section.HOURLY_FORECAST)) {
                            cards[TodayCard.HOURLY] = { HourlySection(snapshot.hourly, formatter, time, settings.hourlyExpanded, actions.setHourlyExpanded) }
                        } else if (snapshot.hourly.isNotEmpty() || Section.HOURLY_FORECAST in snapshot.skippedSections) {
                            hidden += "hourly forecast"
                        }
                        if (snapshot.daily.isNotEmpty() && state.shows(Section.DAILY_FORECAST)) {
                            cards[TodayCard.DAILY] = {
                                DailySection(
                                    snapshot.daily, formatter, time,
                                    showOutage = settings.outageRiskEnabled,
                                    heatTypes = if (settings.heatingEnabled) settings.heatSources.map { it.type }.distinct() else emptyList(),
                                    showPipes = settings.pipeRiskEnabled,
                                    showIce = settings.morningIceEnabled,
                                    footer = state.accuracy,
                                )
                            }
                        } else if (snapshot.daily.isNotEmpty()) {
                            hidden += "daily forecast"
                        }
                        val tide = snapshot.water?.tide
                        if (settings.tidesEnabled && tide != null && tide.nearby && state.shows(Section.WATER)) {
                            cards[TodayCard.TIDES] = { TideSection(tide, snapshot.alerts, formatter, time, now) }
                        }
                        val rainCheck = state.rainCheck
                        // Only while it's news: the last measured day within the past few days.
                        if (settings.rainCheckEnabled && rainCheck?.latest?.date?.let { !it.isBefore(time.today().minusDays(3)) } == true) {
                            val todayChance = snapshot.daily.firstOrNull { it.date == time.today() }?.precipitationChance
                            cards[TodayCard.RAIN_CHECK] = { RainCheckSection(rainCheck, todayChance, formatter, time) }
                        }
                        val aurora = snapshot.aurora
                        val lat0 = snapshot.latitude ?: location?.latitude
                        if (settings.auroraEnabled && aurora != null && state.shows(Section.AURORA)) {
                            val lat = snapshot.latitude ?: location?.latitude
                            val lon = location?.longitude
                            if (lat != null && lon != null) {
                                // Cheap: a few nights of sun positions against at most 3 days of Kp values.
                                val report = AuroraAdvisor.report(aurora, lat, lon, snapshot.hourly, zone, now)
                                if (report.worthShowing(state.liteMode)) cards[TodayCard.AURORA] = { AuroraSection(report, time) }
                            }
                        }
                        if (air != null && airNotable) cards[TodayCard.AIR] = { AirQualitySection(air, time) }
                        if (uv != null && uvNotable) cards[TodayCard.UV] = { UvSection(uv, time) }
                        if (pollen != null && pollenNotable) cards[TodayCard.POLLEN] = pollenCard
                        // AI, where it adds something: the forecasters' reasoning, and answers to the user's own questions.
                        val aiReady = settings.aiEnabled && settings.aiModel.isNotBlank()
                        val notes = state.ai.discussion
                        if (aiReady && settings.aiDiscussion && state.ai.office != null && (notes.reply != null || notes.loading || notes.error != null)) {
                            cards[TodayCard.FORECASTERS] = { ForecastersNotesCard(state.ai, time, onRefresh = actions.refreshDiscussion) }
                        }
                        if (aiReady && settings.aiAsk && state.shows(Section.HOURLY_FORECAST)) {
                            val frostTonight = settings.gardenEnabled &&
                                snapshot.hourly.filter { it.time.isAfter(now) && it.time.isBefore(now.plusSeconds(16 * 3600)) }.any { (it.temperatureC ?: 9.0) <= 0.5 }
                            val auroraTonight = aurora != null && lat0 != null && location != null &&
                                AuroraAdvisor.report(aurora, lat0, location.longitude, snapshot.hourly, zone, now).nights.firstOrNull()
                                    ?.let { it.view >= app.northvane.core.model.AuroraView.CAMERA && (it.cloudPercent ?: 0) < 85 } == true
                            val suggestions = AskSuggestions.pick(snapshot, time.today(), zone, auroraTonight, frostTonight)
                            cards[TodayCard.ASK] = { AskCard(state.ai, location?.displayName ?: "this place", onAsk = actions.ask, onClear = actions.clearAnswer, suggestions = suggestions) }
                        }
                        val visible = settings.todayOrder.filter { it in cards }
                        visible.forEachIndexed { index, card ->
                            item(key = card.name) {
                                val menu = CardMenu(
                                    onMoveUp = if (index > 0) ({ actions.moveCard(card, -1, visible) }) else null,
                                    onMoveDown = if (index < visible.lastIndex) ({ actions.moveCard(card, 1, visible) }) else null,
                                    onHide = if (card.hideable) ({ hideCard(card) }) else null,
                                    onSettings = { actions.openCardSettings(card) },
                                )
                                CompositionLocalProvider(LocalCardMenu provides menu) {
                                    Box(Modifier.animateItem()) { cards.getValue(card)() }
                                }
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
                        item(key = "sources") { SourcesFooter(snapshot, time) }
                    }
                }
            }
        }
    }
}

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
                        // Before any place is saved, the welcome screen below says it all.
                        state.selected?.displayName.orEmpty(),
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
    headline: List<String> = emptyList(),
    wear: String? = null,
    onEasterEgg: () -> Unit = {},
    kelvin: Boolean = false,
) {
    val taps = remember { ArrayDeque<Long>() }
    var showDetails by rememberSaveable { mutableStateOf(false) }
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
                        if (kelvin) current?.temperatureC?.let { "%.1f K".fmt(it + 273.15) } ?: "--" else f.temperature(current?.temperatureC),
                        style = if (kelvin) MaterialTheme.typography.displayMedium else MaterialTheme.typography.displayLarge,
                        color = onHero,
                        modifier = Modifier.clickable(interactionSource = null, indication = null) {
                            val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
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
                        current?.feelsLikeC?.let { add("Feels like\u00A0${f.temperature(it)}") }
                        if (today != null && (today.highC != null || today.lowC != null)) {
                            // No-break spaces keep "H 62°" and "L 53°" whole when large text wraps.
                            add("H\u00A0${f.temperature(today.highC)}  L\u00A0${f.temperature(today.lowC)}")
                        }
                    }.joinToString("  ·  ")
                    if (line.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(line, style = MaterialTheme.typography.bodyMedium, color = onHero.copy(alpha = 0.88f))
                    }
                    current?.windSpeedKph?.let { wind ->
                        val gust = current.windGustKph?.takeIf { it - wind >= 8 }?.let { ", gusts ${f.windSpeed(it)}" }.orEmpty()
                        Text("Wind ${f.wind(wind, current.windDirectionDeg)}$gust", style = MaterialTheme.typography.bodyMedium, color = onHero.copy(alpha = 0.88f))
                    }
                }
                WeatherIcon(condition, isDay, size = 104.dp, onDark = true)
            }
            if (headline.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Text(
                    headline.joinToString(" "),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = onHero,
                )
            }
            wear?.let {
                Spacer(Modifier.height(6.dp))
                Text("Wear: $it", style = MaterialTheme.typography.bodyMedium, color = onHero.copy(alpha = 0.92f))
            }
            val details = current?.let { detailPairs(it, f) }.orEmpty()
            AnimatedVisibility(visible = showDetails && details.isNotEmpty()) {
                Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    details.chunked(2).forEach { row ->
                        Row {
                            row.forEach { (label, value) ->
                                Column(Modifier.weight(1f)) {
                                    Text(label, style = MaterialTheme.typography.labelMedium, color = onHero.copy(alpha = 0.8f))
                                    Text(value, style = MaterialTheme.typography.titleMedium, color = onHero, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { current?.let { SourceBadge(it, time) } }
                if (details.isNotEmpty()) {
                    TextButton(
                        onClick = { showDetails = !showDetails },
                        colors = ButtonDefaults.textButtonColors(contentColor = onHero),
                    ) {
                        Text(if (showDetails) "Less" else "Details")
                        Icon(
                            if (showDetails) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

/** When the top card can't show (Backwoods mode, old data): the headline on its own. */
@Composable
private fun HeadlineCard(headline: List<String>) {
    WeatherCard(null) {
        Text(headline.joinToString(" "), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 16.dp))
    }
}

/** "Sunset 4:25 PM", or the next sunrise once the sun is down. */
private fun sunChipLabel(location: SavedLocation, zone: ZoneId, time: TimeFormat, now: Instant): String {
    val today = now.atZone(zone).toLocalDate()
    val (rise, set) = Solar.sunTimes(location.latitude, location.longitude, today, zone)
    return when {
        rise != null && now.isBefore(rise) -> "Sunrise ${time.time(rise)}"
        set != null && now.isBefore(set) -> "Sunset ${time.time(set)}"
        else -> Solar.sunTimes(location.latitude, location.longitude, today.plusDays(1), zone).first?.let { "Sunrise ${time.time(it)}" } ?: "Sun"
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

@Composable
private fun SourceBadge(current: CurrentConditions, time: TimeFormat) {
    val label = when (current.source) {
        DataSource.WEEWX -> current.stationName ?: "Local station"
        DataSource.NWS -> current.stationName?.let(::stationLabel) ?: "NWS observation"
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

/** NWS names often repeat the town ("Portland, Portland International Jetport"); keep the informative part. */
internal fun stationLabel(name: String): String {
    val town = name.substringBefore(", ", "")
    val rest = name.substringAfter(", ", "")
    return if (town.isNotEmpty() && rest.contains(town, ignoreCase = true)) rest else name
}

@Composable
private fun AlertBanner(
    alerts: List<WeatherAlert>,
    time: TimeFormat,
    onClick: () -> Unit,
    place: GeoPoint? = null,
    f: UnitFormatter? = null,
    /** When the worst of [WeatherAlert] is, from the storm timeline, if it's on. */
    timeline: ((WeatherAlert) -> String?)? = null,
) {
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
                // Storm-based alerts: in the drawn area, or how far away.
                val spot = if (place != null && f != null) AlertAreas.spot(top, place)?.describe(top) { km -> f.distance(km) } else null
                spot?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = onBanner)
                }
                val worst = remember(top, timeline) { timeline?.invoke(top) }
                worst?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = onBanner) }
                Text(
                    listOfNotNull(until, more).joinToString(" · ").ifEmpty { "Tap for details" },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (solid) Color.White.copy(alpha = 0.9f) else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** "Worst 9 PM–1 AM: gusts to 55 mph" for [alert], when it's a timed event with numbers in the forecast. */
private fun worstLine(alert: WeatherAlert, snapshot: WeatherSnapshot, f: UnitFormatter, time: TimeFormat): String? {
    if (alert.category !in setOf(AlertCategory.WARNING, AlertCategory.WATCH, AlertCategory.ADVISORY)) return null
    val now = kotlin.time.Clock.System.now()
    val timeline = app.northvane.core.alerts.StormTimeline.build(alert, snapshot.hourly, snapshot.water?.tide?.events.orEmpty(), now)
    return timeline.headline(time, f, now)
}

/** The finer numbers, shown under "Details" in the top card. */
private fun detailPairs(current: CurrentConditions, f: UnitFormatter): List<Pair<String, String>> = buildList {
    current.humidityPercent?.let { add("Humidity" to f.percent(it)) }
    current.dewpointC?.let { add("Dew point" to f.temperature(it)) }
    current.pressureHpa?.let { add("Pressure" to f.pressure(it)) }
    current.visibilityKm?.let { add("Visibility" to f.visibility(it)) }
    current.rainTodayMm?.let { add("Rain today" to f.precipitation(it)) }
    current.rainRateMmPerHour?.takeIf { it > 0 }?.let { add("Rain rate" to f.precipitation(it) + "/h") }
    current.uvIndex?.let { add("UV index" to "${it.roundToInt()}") }
    current.solarRadiationWm2?.let { add("Solar" to "${it.roundToInt()} W/m²") }
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
private fun DataSaverBanner(
    forced: Boolean,
    satellite: Boolean,
    allowed: Set<SatelliteFeature>,
    dataSaver: Boolean = false,
    restricted: Boolean = false,
    onIgnoreRestricted: () -> Unit = {},
) {
    val openSettings = rememberSystemSettingsOpener()
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
                        restricted -> "Backwoods mode: Android is limiting this app's data"
                        else -> "Backwoods mode: limited connection"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    if (paused.isEmpty()) {
                        "Everything is allowed (Settings › Backwoods mode)."
                    } else {
                        "Paused to save data: " + paused.joinToString(", ") { f -> f.title.let { if (it.length > 1 && it[1].isUpperCase()) it else it.replaceFirstChar(Char::lowercase) } } +
                            ". Change in Settings › Backwoods mode."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                if (restricted && !forced && !satellite) {
                    // Android reports Data Saver and a switched-off "Background data" the same way.
                    Text(
                        "Either Data Saver is on, or Background data is off for this app in Android's settings. " +
                            "Android doesn't say which.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Row {
                        TextButton(onClick = { openSettings(SystemSettingsPage.DATA) }) { Text("App data settings") }
                        TextButton(onClick = onIgnoreRestricted) { Text("Ignore this") }
                    }
                }
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

/**
 * First run: units, what the person cares about (which switches on the
 * matching cards, so they never meet the long list of settings), then a place.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Onboarding(
    modifier: Modifier,
    settings: AppSettings,
    onUseLocation: () -> Unit,
    onSearch: () -> Unit,
    onSetUnits: (UnitSystem) -> Unit,
    onSetInterest: (Interest, Boolean) -> Unit,
    error: String?,
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        WeatherIcon(WeatherCondition.PARTLY_CLOUDY, isDay = true, size = 96.dp)
        Spacer(Modifier.height(16.dp))
        Text("Welcome to", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        // One line however wide the screen: the name never breaks mid-word.
        Text(
            "SuperAwesomeMagicalWeather",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            maxLines = 1,
            autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(
                minFontSize = 14.sp,
                maxFontSize = MaterialTheme.typography.headlineSmall.fontSize,
            ),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Free forecasts, radar and alerts from the National Weather Service, with optional data from your own WeeWX station.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Text("Units", style = MaterialTheme.typography.titleSmall, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            FilterChip(selected = settings.units == UnitSystem.IMPERIAL, onClick = { onSetUnits(UnitSystem.IMPERIAL) }, label = { Text("°F, mph, inches") })
            FilterChip(selected = settings.units == UnitSystem.METRIC, onClick = { onSetUnits(UnitSystem.METRIC) }, label = { Text("°C, km/h, mm") })
        }
        Spacer(Modifier.height(16.dp))
        Text("What do you care about?", style = MaterialTheme.typography.titleSmall, modifier = Modifier.fillMaxWidth())
        Text(
            "Turns on the matching cards. You can change this any time in Settings.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Interest.entries.forEach { interest ->
                val on = interest.isOn(settings)
                FilterChip(selected = on, onClick = { onSetInterest(interest, !on) }, label = { Text(interest.label) })
            }
        }
        Spacer(Modifier.height(24.dp))
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
