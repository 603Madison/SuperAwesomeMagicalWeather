@file:OptIn(ExperimentalMaterial3Api::class)

package app.northvane.weather.ui.settings

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.northvane.core.model.Activity
import app.northvane.core.model.AuroraView
import app.northvane.weather.work.AuroraWatchWorker
import app.northvane.weather.work.BriefingWorker
import app.northvane.weather.work.RainSoonWorker
import app.northvane.core.model.AlertCategory
import app.northvane.core.model.UnitSystem
import app.northvane.core.radar.RadarSource
import app.northvane.core.radar.SatelliteImagery
import app.northvane.weather.AppContainer
import app.northvane.weather.BuildConfig
import app.northvane.weather.data.DataSaverMode
import app.northvane.weather.data.SatelliteGroup
import app.northvane.weather.data.SettingsRepository
import app.northvane.weather.data.ThemeMode
import app.northvane.weather.notify.AlertNotifier
import app.northvane.weather.ui.MainUiState
import app.northvane.weather.ui.components.ChoiceDropdown
import app.northvane.weather.ui.components.LabeledSwitch
import app.northvane.weather.ui.components.SectionCard
import app.northvane.weather.ui.components.SegmentedChoice
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Literal so lint doesn't flag the API 33 constant; only requested on API 33+. */
private const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"

/** Settings is a short index; each category opens its own page. */
enum class SettingsPage(val route: String, val title: String) {
    APPEARANCE("appearance", "Appearance"),
    TODAY("today", "Today screen"),
    OUTLOOK("outlook", "Outlook"),
    ALERTS("alerts", "Alerts & notifications"),
    MAPS("maps", "Radar & traffic"),
    SATELLITE("satellite", "Backwoods mode"),
    AI("ai", "AI summary"),
    ABOUT("about", "About & privacy"),
}

/** Callbacks into other settings screens. */
class SettingsNav(
    val openPage: (SettingsPage) -> Unit = {},
    val openSources: () -> Unit = {},
    val openAllergies: () -> Unit = {},
    val openThemes: () -> Unit = {},
    val openGarden: () -> Unit = {},
    val openHeating: () -> Unit = {},
)

@Composable
internal fun SettingsScaffoldPublic(title: String, onBack: () -> Unit, content: @Composable () -> Unit) = SettingsScaffold(title, onBack, content)

@Composable
private fun SettingsScaffold(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) { content() }
    }
}

@Composable
fun SettingsScreen(state: MainUiState, container: AppContainer, onBack: () -> Unit, nav: SettingsNav, page: SettingsPage? = null) {
    when (page) {
        null -> SettingsIndex(state, onBack, nav)
        SettingsPage.APPEARANCE -> AppearancePage(state, container, onBack, nav)
        SettingsPage.TODAY -> TodayPage(state, container, onBack, nav)
        SettingsPage.OUTLOOK -> OutlookPage(state, container, onBack, nav)
        SettingsPage.ALERTS -> AlertsPage(state, container, onBack)
        SettingsPage.MAPS -> MapsPage(state, container, onBack)
        SettingsPage.SATELLITE -> SatellitePage(state, container, onBack)
        SettingsPage.AI -> AiSettingsPage(state, container, onBack)
        SettingsPage.ABOUT -> AboutPage(onBack)
    }
}

@Composable
private fun SettingsIndex(state: MainUiState, onBack: () -> Unit, nav: SettingsNav) {
    val s = state.settings
    fun onOff(on: Boolean) = if (on) "on" else "off"
    SettingsScaffold("Settings", onBack) {
        SectionCard(null) {
            NavRow("Appearance", "${s.appTheme.label} theme · ${s.themeMode.name.lowercase().replaceFirstChar { it.uppercase() }} · ${if (s.units == UnitSystem.IMPERIAL) "°F" else "°C"}") { nav.openPage(SettingsPage.APPEARANCE) }
            NavRow("Today screen", "Air quality, UV, pollen & allergies, outage outlook, AI summary") { nav.openPage(SettingsPage.TODAY) }
            NavRow("Outlook", "Activities, garden, heating, clothes drying, trail mud, fall foliage") { nav.openPage(SettingsPage.OUTLOOK) }
            NavRow("Alerts & notifications", "Alerts ${onOff(s.alertsEnabled)} · every ${s.alertCheckMinutes} min") { nav.openPage(SettingsPage.ALERTS) }
            NavRow("Radar & traffic", "${s.radarSource.label} · traffic tab ${onOff(s.trafficEnabled)}") { nav.openPage(SettingsPage.MAPS) }
        }
        SectionCard(null) {
            NavRow("Backwoods mode", when (s.dataSaver) {
                DataSaverMode.AUTO -> "Turns on by itself on satellite or with Data Saver"
                DataSaverMode.ALWAYS -> "Always on"
                DataSaverMode.OFF -> "Off"
            } + if (s.showStaleData) " · showing stale data" else "") { nav.openPage(SettingsPage.SATELLITE) }
            NavRow("Data sources & API keys", "NWS, Environment Canada, WeeWX, pollen, air quality, traffic, OpenWeatherMap", nav.openSources)
            NavRow("AI", if (s.aiEnabled) "${s.aiProvider.label} · ${s.aiModel.ifBlank { "no model" }}" else "Off. Forecasters' notes, alert explanations and questions") { nav.openPage(SettingsPage.AI) }
            NavRow("About & privacy", "SuperAwesomeMagicalWeather ${BuildConfig.VERSION_NAME}") { nav.openPage(SettingsPage.ABOUT) }
        }
    }
}

@Composable
private fun rememberUpdater(container: AppContainer): (suspend SettingsRepository.() -> Unit) -> Unit {
    val scope = rememberCoroutineScope()
    return { block -> scope.launch { container.settings.block() } }
}

@Composable
private fun AppearancePage(state: MainUiState, container: AppContainer, onBack: () -> Unit, nav: SettingsNav) {
    val s = state.settings
    val update = rememberUpdater(container)
    SettingsScaffold("Appearance", onBack) {
        SectionCard(null) {
            NavRow("Theme", "${s.appTheme.label}: ${s.appTheme.description}", nav.openThemes)
            ChoiceDropdown("Light or dark", ThemeMode.entries, s.themeMode, {
                when (it) {
                    ThemeMode.SYSTEM -> "Follow system"
                    ThemeMode.LIGHT -> "Light"
                    ThemeMode.DARK -> "Dark"
                }
            }, { mode -> update { setThemeMode(mode) } })
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                LabeledSwitch(
                    title = "Wallpaper colors",
                    subtitle = "Use Material You colors instead of the theme",
                    checked = s.dynamicColor,
                    onCheckedChange = { enabled -> update { setDynamicColor(enabled) } },
                )
            }
            Label("Units")
            SegmentedChoice(
                options = listOf(UnitSystem.IMPERIAL, UnitSystem.METRIC),
                selected = s.units,
                label = { if (it == UnitSystem.IMPERIAL) "°F · mph · in" else "°C · km/h · mm" },
                onSelect = { units -> update { setUnits(units) } },
            )
        }
        SectionCard("Home-screen widget") {
            Hint("Long-press your home screen, tap Widgets and pick SuperAwesomeMagicalWeather. It shows the place on your Today screen and updates about every hour.")
        }
    }
}

@Composable
private fun TodayPage(state: MainUiState, container: AppContainer, onBack: () -> Unit, nav: SettingsNav) {
    val s = state.settings
    val update = rememberUpdater(container)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Aurora notifications run in the background, so the schedule follows these switches.
    fun syncAurora(block: suspend SettingsRepository.() -> Unit) {
        scope.launch {
            container.settings.block()
            AuroraWatchWorker.sync(context, container.settings.settings.first())
        }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    fun askForNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(POST_NOTIFICATIONS)
        }
    }
    SettingsScaffold("Today screen", onBack) {
        SectionCard("Cards") {
            LabeledSwitch("Hourly details open by default", s.hourlyExpanded, { on -> update { setHourlyExpanded(on) } },
                subtitle = "You can also tap the hourly card any time; it remembers your choice")
            LabeledSwitch("Air quality (AQI)", s.airQualityEnabled, { on -> update { setAirQuality(on) } },
                subtitle = "EPA AirNow with your free key, otherwise Open-Meteo's model")
            LabeledSwitch("UV index", s.uvEnabled, { on -> update { setUv(on) } }, subtitle = "EPA's hourly UV forecast by ZIP code")
            LabeledSwitch("Power outage outlook", s.outageRiskEnabled, { on -> update { setOutageRisk(on) } },
                subtitle = "In each day's details; tap High risk for preparation steps")
            LabeledSwitch("Frozen pipe risk", s.pipeRiskEnabled, { on -> update { setPipeRisk(on) } },
                subtitle = "In each day's details on nights near 20°F or colder, with steps to protect your pipes")
            LabeledSwitch("Black ice & frost", s.morningIceEnabled, { on -> update { setMorningIce(on) } },
                subtitle = "In each day's details: icy roads and frosty windshields that morning")
            LabeledSwitch("Storm totals", s.stormEnabled, { on -> update { setStorm(on) } },
                subtitle = "When a big storm is coming: snow, ice and rain totals, timing and the heaviest hours")
            LabeledSwitch("Wildfire smoke", s.smokeEnabled, { on -> update { setSmoke(on) } },
                subtitle = "Only when smoke is in the forecast (NOAA HRRR, about 2 MB every 6 hours, continental US)")
            LabeledSwitch("Aurora forecast", s.auroraEnabled, { on -> syncAurora { setAurora(on) } },
                subtitle = "Shows up only when the northern lights may be visible from here (NOAA, free)")
            if (s.auroraEnabled) {
                LabeledSwitch("Aurora notifications", s.auroraNotify, { on ->
                    if (on) askForNotifications()
                    syncAurora { setAuroraNotify(on) }
                }, subtitle = "For places with notifications on: a heads-up the day before, and when it may be visible now")
                if (s.auroraNotify) {
                    ChoiceDropdown("Notify when it may be", listOf(AuroraView.CAMERA, AuroraView.HORIZON, AuroraView.OVERHEAD), s.auroraNotifyView, {
                        when (it) {
                            AuroraView.CAMERA -> "Visible to a phone camera"
                            AuroraView.HORIZON -> "Visible to the eye, low on the horizon"
                            else -> "Overhead (rare, big storms)"
                        }
                    }, { view -> update { setAuroraNotifyView(view) } })
                }
            }
        }
        SectionCard("Pollen & allergies") {
            LabeledSwitch("Pollen forecast", s.pollenEnabled, { on -> update { setPollen(on) } },
                subtitle = if (state.pollenKeySet || !s.pollenEnabled) "Shown only when you have a Google Pollen API key" else "Add a Google Pollen key in Data sources")
            if (s.pollenEnabled) {
                LabeledSwitch("Personal allergy score", s.allergyEnabled, { on -> update { setAllergy(on) } },
                    subtitle = "Inside the pollen card")
                NavRow("My allergies", if (s.allergies.isEmpty()) "Choose allergens" else s.allergies.keys.joinToString(", ") { it.label.lowercase() }, nav.openAllergies)
            }
        }
    }
}

@Composable
private fun OutlookPage(state: MainUiState, container: AppContainer, onBack: () -> Unit, nav: SettingsNav) {
    val s = state.settings
    val update = rememberUpdater(container)
    val scope = rememberCoroutineScope()
    var showActivities by rememberSaveable { mutableStateOf(false) }
    SettingsScaffold("Outlook", onBack) {
        Text(
            "These cards live on the Outlook tab and appear only in season.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SectionCard("Outdoors") {
            LabeledSwitch("Outdoor activity scores", s.activitiesEnabled, { on -> update { setActivitiesEnabled(on) } },
                subtitle = "0–100 with tips; seasonal ones appear only when they make sense")
            if (s.activitiesEnabled) {
                ExpandRow("Activities", "${s.activities.size} of ${Activity.entries.size} on", showActivities) { showActivities = !showActivities }
                if (showActivities) {
                    Activity.entries.forEach { activity ->
                        val checked = activity in s.activities
                        CheckRow(activity.label + if (activity.overnight) " (overnight)" else "", activitySeason(activity), checked) { on -> update { setActivity(activity, on) } }
                    }
                }
            }
            LabeledSwitch("Trail mud", s.mudEnabled, { on -> update { setMud(on) } }, subtitle = "Rain, snowmelt and spring thaw (NOAA observed data)")
            LabeledSwitch("Rivers & tides", s.waterEnabled, { on -> update { setWater(on) } },
                subtitle = "Nearest river gauge (USGS) and tide times (NOAA); water temperature and rising rivers also feed paddling")
            LabeledSwitch("Fall foliage", s.foliageEnabled, { on -> update { setFoliage(on) } }, subtitle = "Only while leaves are changing")
        }
        SectionCard("Home") {
            LabeledSwitch("Clothes drying", s.dryingEnabled, { on -> update { setDrying(on) } },
                subtitle = "Best line-drying hours and a rain warning while laundry is out")
            LabeledSwitch("Garden", s.gardenEnabled, { on ->
                scope.launch {
                    container.settings.setGarden(on)
                    container.alertScheduler.sync()
                }
            }, subtitle = "Planting and harvest timing for what you grow")
            if (s.gardenEnabled) {
                NavRow("My garden", if (s.gardenPlan.isEmpty()) "Choose what you're planting" else s.gardenPlan.joinToString(", ") { it.crop.label.substringBefore(" (").lowercase() }, nav.openGarden)
            }
            LabeledSwitch("Burn days", s.burnEnabled, { on -> update { setBurn(on) } },
                subtitle = "Good days to burn brush, from wind, humidity, dry spells and fire weather alerts")
            LabeledSwitch("Maple sap runs", s.sapEnabled, { on -> update { setSap(on) } },
                subtitle = "Freeze-thaw days for tapping, mid-January through April")
            LabeledSwitch("Heating season", s.heatingEnabled, { on -> update { setHeating(on) } },
                subtitle = "Which system to run, and when to buy fuel or stack wood")
            if (s.heatingEnabled) {
                NavRow("My heating", if (s.heatSources.isEmpty()) "Add how you heat" else s.heatSources.joinToString(", ") { it.label.substringBefore(" (").lowercase() }, nav.openHeating)
            }
        }
    }
}

@Composable
private fun AlertsPage(state: MainUiState, container: AppContainer, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val s = state.settings
    fun updateAlerts(block: suspend SettingsRepository.() -> Unit) {
        scope.launch {
            container.settings.block()
            container.alertScheduler.sync()
        }
    }
    var testSent by remember { mutableStateOf(false) }
    var notificationsAllowed by remember { mutableStateOf(AlertNotifier.canNotify(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { notificationsAllowed = AlertNotifier.canNotify(context) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationsAllowed = AlertNotifier.canNotify(context)
        if (granted) container.alertScheduler.checkNow()
    }
    val openNotificationSettings = {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
    fun needsRuntimePermission() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

    fun updateDaily(block: suspend SettingsRepository.() -> Unit) {
        scope.launch {
            container.settings.block()
            val now = container.settings.settings.first()
            BriefingWorker.schedule(context, now)
            RainSoonWorker.sync(context, now)
        }
    }
    fun askIfNeeded() {
        if (needsRuntimePermission()) notificationPermission.launch(POST_NOTIFICATIONS)
    }

    SettingsScaffold("Alerts & notifications", onBack) {
        SectionCard(null) {
            LabeledSwitch("Alert notifications", s.alertsEnabled, { enabled -> updateAlerts { setAlertsEnabled(enabled) } },
                subtitle = "Background checks of NWS (US) and Environment Canada alerts")
            if (s.alertsEnabled && !notificationsAllowed) {
                Text("Notifications are blocked for SuperAwesomeMagicalWeather, so alerts can't reach you.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                FilledTonalButton(onClick = { if (needsRuntimePermission()) notificationPermission.launch(POST_NOTIFICATIONS) else openNotificationSettings() }) {
                    Text("Allow notifications")
                }
            }
            if (s.alertsEnabled) {
                ChoiceDropdown("Check every", listOf(15, 30, 60), s.alertCheckMinutes, { "$it minutes" }, { m -> updateAlerts { setAlertInterval(m) } })
            }
        }
        SectionCard("Daily & rain") {
            LabeledSwitch("Morning briefing", s.briefingEnabled, { on ->
                if (on) askIfNeeded()
                updateDaily { setBriefing(on) }
            }, subtitle = "Today's forecast for the place on your Today screen, once a day")
            if (s.briefingEnabled) {
                val times = (5 * 60..10 * 60 step 30).toList()
                ChoiceDropdown("At", (times + s.briefingMinutes).distinct().sorted(), s.briefingMinutes, { m ->
                    val t = java.time.LocalTime.of(m / 60, m % 60)
                    java.time.format.DateTimeFormatter.ofPattern(if (android.text.format.DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a").format(t)
                }, { m -> updateDaily { setBriefingMinutes(m) } })
            }
            LabeledSwitch("Rain starting soon", s.rainSoonEnabled, { on ->
                if (on) askIfNeeded()
                updateDaily { setRainSoon(on) }
            }, subtitle = "A heads-up up to an hour before rain or snow starts, for places with notifications on. Never 10 PM–6 AM")
        }
        if (s.alertsEnabled) {
            SectionCard("Notify me about") {
                CategoryRow(AlertCategory.WARNING, "Warnings", "Tornado, severe thunderstorm, flash flood, winter storm…", s.alertCategories) { c, on -> updateAlerts { setAlertCategory(c, on) } }
                CategoryRow(AlertCategory.WATCH, "Watches", "Conditions favorable for hazardous weather", s.alertCategories) { c, on -> updateAlerts { setAlertCategory(c, on) } }
                CategoryRow(AlertCategory.ADVISORY, "Advisories", "Wind, heat, frost, dense fog…", s.alertCategories) { c, on -> updateAlerts { setAlertCategory(c, on) } }
                CategoryRow(AlertCategory.STATEMENT, "Statements", "Special weather statements and outlooks", s.alertCategories) { c, on ->
                    updateAlerts {
                        setAlertCategory(c, on)
                        setAlertCategory(AlertCategory.OTHER, on)
                    }
                }
            }
            if (state.locations.isNotEmpty()) {
                SectionCard("Locations that notify") {
                    state.locations.forEach { location ->
                        CheckRow(location.displayName, null, location.notify) { on -> scope.launch { container.locations.setNotify(location.id, on) } }
                    }
                    if (state.locations.count { it.notify } > 10) Hint("Background checks cover the first 10 notifying locations.")
                }
            }
            SectionCard("Test") {
                Hint("Android may delay background checks to save battery. Warnings use a high-priority channel; adjust sounds per channel in system settings.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalButton(onClick = {
                        val sent = AlertNotifier.sendTest(context, state.selected?.id, state.selected?.displayName)
                        if (!sent) { if (needsRuntimePermission()) notificationPermission.launch(POST_NOTIFICATIONS) else openNotificationSettings() }
                        testSent = sent
                    }) { Text("Send test notification") }
                    TextButton(onClick = openNotificationSettings) { Text("Channels") }
                }
                if (testSent) Hint("Test sent. If it didn't appear, check the Warnings channel and Do Not Disturb.")
            }
        }
    }
}

@Composable
private fun MapsPage(state: MainUiState, container: AppContainer, onBack: () -> Unit) {
    val s = state.settings
    val update = rememberUpdater(container)
    SettingsScaffold("Radar & traffic", onBack) {
        SectionCard("Radar") {
            ChoiceDropdown("Past radar", RadarSource.entries, s.radarSource, { it.label }, { source -> update { setRadarSource(source) } })
            LabeledSwitch("Future cast", s.futureCastEnabled, { on -> update { setFutureCast(on) } }, subtitle = "NOAA's HRRR model, newest run, in the same colors as the radar (continental US)")
            if (s.futureCastEnabled) {
                ChoiceDropdown("Future cast range", listOf(60, 120, 180), s.futureCastMinutes, { "${it / 60} hour${if (it > 60) "s" else ""} ahead" }, { m -> update { setFutureCastMinutes(m) } })
            }
            LabeledSwitch("Satellite imagery", s.satelliteLayer, { on -> update { setSatelliteLayer(on) } }, subtitle = "GOES clouds under the radar (also toggles on the map)")
            if (s.satelliteLayer) {
                ChoiceDropdown("Imagery", SatelliteImagery.entries, s.satelliteImagery, { it.label }, { i -> update { setSatelliteImagery(i) } })
            }
        }
        SectionCard("Traffic") {
            LabeledSwitch("Traffic tab", s.trafficEnabled, { on -> update { setTraffic(on) } }, subtitle = "Work zones, closures and crashes from state DOTs")
            if (s.trafficEnabled) {
                val radii = listOf(16.0, 40.0, 80.0)
                ChoiceDropdown("Show events within", radii, radii.minBy { kotlin.math.abs(it - s.trafficRadiusKm) }, {
                    if (s.units == UnitSystem.IMPERIAL) "${(it / 1.609344).roundToInt()} miles" else "${it.roundToInt()} km"
                }, { km -> update { setTrafficRadius(km) } })
                LabeledSwitch("Live speed layer", s.trafficFlowLayer, { on -> update { setTrafficFlow(on) } }, subtitle = "TomTom, needs your key in Data sources")
            }
        }
    }
}

@Composable
private fun SatellitePage(state: MainUiState, container: AppContainer, onBack: () -> Unit) {
    val s = state.settings
    val update = rememberUpdater(container)
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    SettingsScaffold("Backwoods mode", onBack) {
        SectionCard(null) {
            Hint(
                "Backwoods mode is SuperAwesomeMagicalWeather's light-packing mode for satellite connections such as T-Mobile T-Satellite: " +
                    "choose exactly what it may download. Anything you turn off keeps its last downloaded data. " +
                    "When set to automatic, it also turns on while Android's Data Saver is on and you're on mobile data, " +
                    "even if SuperAwesomeMagicalWeather has unrestricted data.",
            )
            ChoiceDropdown("Backwoods mode turns on", DataSaverMode.entries, s.dataSaver, {
                when (it) {
                    DataSaverMode.AUTO -> "Automatically on satellite or with Data Saver"
                    DataSaverMode.ALWAYS -> "Always"
                    DataSaverMode.OFF -> "Never"
                }
            }, { mode -> update { setDataSaver(mode) } })
            LabeledSwitch(
                "Show stale data", s.showStaleData, { on -> update { setShowStaleData(on) } },
                subtitle = "In Backwoods mode, cards (and tabs) with data over 12 hours old are hidden. Turn this on to show them anyway.",
            )
            LabeledSwitch(
                "Also when Android limits this app's data", s.followRestrictedData, { on -> update { setFollowRestrictedData(on) } },
                subtitle = "Android reports Data Saver and a switched-off \"Background data\" for this app the same way. " +
                    "Turn this off if Background data is off on purpose and Backwoods mode keeps turning on.",
            )
        }
        SectionCard("Allowed in Backwoods mode") {
            SatelliteGroup.entries.forEach { group ->
                val on = group.features.count { it in s.satelliteFeatures }
                val toggle = when (on) {
                    0 -> ToggleableState.Off
                    group.features.size -> ToggleableState.On
                    else -> ToggleableState.Indeterminate
                }
                Column(Modifier.animateContentSize()) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        TriStateCheckbox(state = toggle, onClick = { update { setSatelliteFeatures(group.features, toggle != ToggleableState.On) } })
                        Column(Modifier.weight(1f).clickable { expanded = if (expanded == group.name) null else group.name }) {
                            Text(group.title, style = MaterialTheme.typography.bodyLarge)
                            Text("${group.description} ($on of ${group.features.size} on)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { expanded = if (expanded == group.name) null else group.name }) {
                            Icon(if (expanded == group.name) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = "Show items")
                        }
                    }
                    if (expanded == group.name) {
                        group.features.forEach { feature ->
                            Row(Modifier.fillMaxWidth().padding(start = 32.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = feature in s.satelliteFeatures, onCheckedChange = { c -> update { setSatelliteFeature(feature, c) } })
                                Column(Modifier.weight(1f)) {
                                    Text(feature.title, style = MaterialTheme.typography.bodyMedium)
                                    Text(feature.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(feature.approxSize, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                }
                if (group != SatelliteGroup.entries.last()) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        val network = state.network
        Hint(
            "Current connection: " + when {
                !network.connected -> "offline"
                network.satellite -> "satellite" + if (network.constrained) " (bandwidth-limited)" else ""
                network.constrained -> "bandwidth-limited"
                network.metered -> "mobile data"
                else -> "unmetered"
            } + (if (network.dataSaver) " · Android Data Saver is on" else if (network.restricted) " · Android limits this app's background data" else "") + (if (state.liteMode) " · Backwoods mode is on" else "") + ". Over satellite, a WeeWX or AI server is only reachable at a public address.",
        )
    }
}

@Composable
private fun AboutPage(onBack: () -> Unit) {
    SettingsScaffold("About & privacy", onBack) {
        SectionCard(null) {
            Text("SuperAwesomeMagicalWeather ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyLarge)
            Hint(
                "Free software under the GNU General Public License v3.0 or later. You can use, study, share and change it; " +
                    "it comes with no warranty. Source code: github.com/603Madison/SuperAwesomeMagicalWeather",
            )
            Hint(
                "No accounts, ads, analytics or trackers. Your locations, settings and keys stay on this device and are excluded from " +
                    "backups. Weather requests send rounded coordinates (about 11 m) only to the services you use: weather.gov, " +
                    "Environment Canada, NOAA's climate data service (ACIS), EPA (UV index by ZIP code, and AirNow if you add a key), " +
                    "Open-Meteo (air quality model), state DOT traffic feeds, your WeeWX server and, if enabled, OpenWeatherMap, " +
                    "New England 511 and TomTom. Pollen requests send coordinates rounded to about 1 km to Google. If you turn on the " +
                    "AI summary, the place name and forecast numbers go to the AI server you configure, and nothing else. Map tiles " +
                    "come from OpenFreeMap; radar and satellite imagery from NOAA and the Iowa Environmental Mesonet. The aurora forecast " +
                    "is downloaded from NOAA's Space Weather Prediction Center without sending your location. Place search uses " +
                    "your device's built-in geocoder.",
            )
            Text(
                "Map data © OpenStreetMap contributors, © OpenMapTiles. Weather data: NOAA / National Weather Service; Environment and " +
                    "Climate Change Canada; NOAA Regional Climate Centers (ACIS); NOAA Space Weather Prediction Center. Pollen: Google Pollen " +
                    "API. Activity, mud, foliage, garden, heating, outage, frozen pipe, ice, aurora, sap and burn outlooks are SuperAwesomeMagicalWeather estimates. AI summaries are written by your own AI server.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// --- Shared bits ---

@Composable
internal fun NavRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
    }
}

@Composable
private fun ExpandRow(title: String, subtitle: String, open: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = if (open) "Collapse" else "Expand")
    }
}

@Composable
internal fun CheckRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Spacer(Modifier.width(4.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
internal fun Hint(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun CategoryRow(category: AlertCategory, title: String, subtitle: String, enabled: Set<AlertCategory>, onChange: (AlertCategory, Boolean) -> Unit) =
    CheckRow(title, subtitle, category in enabled) { onChange(category, it) }

private fun activitySeason(a: Activity): String? = when (a) {
    Activity.SKIING -> "Winter, with reported snow on the ground"
    Activity.XC_SKIING, Activity.SNOWSHOEING -> "Winter, with 4\"+ of reported snow"
    Activity.SNOWMOBILING -> "Winter, with 8\"+ of reported snow"
    Activity.ICE_FISHING -> "Winter, once lakes have likely frozen solid"
    Activity.SWIMMING -> "Summer (June to mid-September)"
    Activity.HUNTING -> "Fall and spring turkey seasons"
    Activity.PADDLING, Activity.OFF_ROADING -> "Hidden while there's snowpack"
    else -> null
}
