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
import app.northvane.weather.ui.components.NorthvaneIcons
import app.northvane.weather.ui.components.SectionCard
import app.northvane.weather.ui.components.SegmentedChoice
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import app.northvane.weather.data.TodayCard
import app.northvane.weather.data.isOn
import app.northvane.weather.ui.components.ReorderableColumn
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.FilterChip
import androidx.compose.material3.AlertDialog
import app.northvane.core.outlook.Comfort
import app.northvane.core.outlook.MyTime

/** Literal so lint doesn't flag the API 33 constant; only requested on API 33+. */
private const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"

/** Settings is a short index; each category opens its own page. */
enum class SettingsPage(val route: String, val title: String) {
    APPEARANCE("appearance", "Appearance"),
    TODAY("today", "Today screen"),
    OUTLOOK("outlook", "Outlook"),
    ALERTS("alerts", "Alerts & notifications"),
    MAPS("maps", "Radar"),
    SATELLITE("satellite", "Backwoods mode"),
    AI("ai", "AI"),
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
        // Text on the theme backdrop needs the theme's color, not the black default for transparent containers.
        contentColor = MaterialTheme.colorScheme.onBackground,
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
        SectionCard("What you see") {
            NavRow("Appearance", "${s.appTheme.label} theme · ${s.themeMode.name.lowercase().replaceFirstChar { it.uppercase() }} · ${if (s.units == UnitSystem.IMPERIAL) "°F" else "°C"}") { nav.openPage(SettingsPage.APPEARANCE) }
            NavRow("Today screen", "Card order, air quality, UV, pollen, storm, smoke, aurora") { nav.openPage(SettingsPage.TODAY) }
            NavRow("Outlook", "Activities, rivers & tides, garden, drying, heating, seasonal cards") { nav.openPage(SettingsPage.OUTLOOK) }
            NavRow("Alerts & notifications", "Alerts ${onOff(s.alertsEnabled)} · every ${s.alertCheckMinutes} min") { nav.openPage(SettingsPage.ALERTS) }
            NavRow("Radar", "${s.radarSource.label} · future cast ${onOff(s.futureCastEnabled)}") { nav.openPage(SettingsPage.MAPS) }
        }
        SectionCard("Data & privacy") {
            NavRow("Backwoods mode", when (s.dataSaver) {
                DataSaverMode.AUTO -> "Turns on by itself on satellite or with Data Saver"
                DataSaverMode.ALWAYS -> "Always on"
                DataSaverMode.OFF -> "Off"
            } + if (s.showStaleData) " · showing stale data" else "") { nav.openPage(SettingsPage.SATELLITE) }
            NavRow("Data sources & API keys", "NWS, Environment Canada, WeeWX, pollen, air quality, OpenWeatherMap", nav.openSources)
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
        SectionCard("Cards on Today") {
            Hint(
                "Long-press a card and drag it to change the order; switch off the ones you don't want. Air quality, UV and pollen " +
                    "sit as small chips at the top until they matter (AQI over 50, UV 6 or more, high pollen). Each card's ⋮ menu " +
                    "on Today does the same.",
            )
            val cards = s.todayOrder.filter { (it != TodayCard.FORECASTERS && it != TodayCard.ASK) || s.aiEnabled }
            ReorderableColumn(cards, key = { it }, onReorder = { order ->
                // Cards not listed here (AI ones while AI is off) keep their places at the end.
                update { setTodayOrder(order + s.todayOrder.filter { it !in order }) }
            }) { card, dragging ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (dragging) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent, RoundedCornerShape(12.dp))
                        .padding(vertical = 2.dp),
                ) {
                    Icon(NorthvaneIcons.DragHandle, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 12.dp))
                    Text(if (card == TodayCard.DAILY) "Daily forecast" else card.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    if (card.hideable) {
                        Switch(checked = s.isOn(card), onCheckedChange = { on ->
                            if (card == TodayCard.AURORA) syncAurora { setTodayCard(card, on) } else update { setTodayCard(card, on) }
                        })
                    } else {
                        Text("Always on", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 14.dp))
                    }
                }
            }
        }
        SectionCard("You") {
            LabeledSwitch("What to wear", s.wearEnabled, { on -> update { setWear(on) } },
                subtitle = "A line in the top card, like \"Light jacket · umbrella after 3 PM\"")
            if (s.wearEnabled) {
                ChoiceDropdown("Comfort", Comfort.entries, s.comfort, { it.label }, { c -> update { setComfort(c) } })
            }
            Text("Your times", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            Hint("When you're usually out. The headline tells you about the weather for them, like \"Rain likely for your drive home (5 PM)\".")
            MyTimesEditor(s.myTimes) { times -> update { setMyTimes(times) } }
        }
        SectionCard("In each day's details") {
            LabeledSwitch("Power outage outlook", s.outageRiskEnabled, { on -> update { setOutageRisk(on) } },
                subtitle = "Tap High risk for preparation steps")
            LabeledSwitch("Frozen pipe risk", s.pipeRiskEnabled, { on -> update { setPipeRisk(on) } },
                subtitle = "Nights near 20°F or colder, with steps to protect your pipes")
            LabeledSwitch("Black ice & frost", s.morningIceEnabled, { on -> update { setMorningIce(on) } },
                subtitle = "Icy roads and frosty windshields that morning")
        }
        if (s.auroraEnabled) {
            SectionCard("Aurora") {
                LabeledSwitch("Aurora notifications", s.auroraNotify, { on ->
                    if (on) askForNotifications()
                    syncAurora { setAuroraNotify(on) }
                }, subtitle = "For places with notifications on: a heads-up the day before, and when it may be visible now")
                if (s.auroraNotify) {
                    ChoiceDropdown("Notify when it's", listOf(AuroraView.CAMERA, AuroraView.HORIZON, AuroraView.OVERHEAD), s.auroraNotifyView, {
                        when (it) {
                            AuroraView.CAMERA -> "Visible to a camera"
                            AuroraView.HORIZON -> "Low on the horizon"
                            else -> "Overhead (rare)"
                        }
                    }, { view -> update { setAuroraNotifyView(view) } })
                }
            }
        }
        if (s.pollenEnabled) SectionCard("Pollen & allergies") {
            if (!state.pollenKeySet) Hint("The pollen card needs a free Google Pollen API key: add one in Data sources.")
            run {
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
    SettingsScaffold("Outlook", onBack) {
        Text(
            "These cards live on the Outlook tab and appear only in season.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SectionCard("Outdoors") {
            LabeledSwitch("Outdoor activities", s.activitiesEnabled, { on -> update { setActivitiesEnabled(on) } },
                subtitle = "When to go and how good it looks; trail mud counts for hiking and biking. Pick your activities on the Outlook tab.")
            LabeledSwitch("Rivers & tides", s.waterEnabled, { on -> update { setWater(on) } },
                subtitle = "Nearest river gauge (USGS) and tide times (NOAA); water temperature and rising rivers also feed paddling")
        }
        SectionCard("Home") {
            LabeledSwitch("Garden", s.gardenEnabled, { on ->
                scope.launch {
                    container.settings.setGarden(on)
                    container.alertScheduler.sync()
                }
            }, subtitle = "Planting and harvest timing for what you grow")
            if (s.gardenEnabled) {
                NavRow("My garden", if (s.gardenPlan.isEmpty()) "Choose what you're planting" else s.gardenPlan.joinToString(", ") { it.crop.label.substringBefore(" (").lowercase() }, nav.openGarden)
            }
            LabeledSwitch("Clothes drying", s.dryingEnabled, { on -> update { setDrying(on) } },
                subtitle = "Best line-drying hours and a rain warning while laundry is out")
            LabeledSwitch("Heating season", s.heatingEnabled, { on -> update { setHeating(on) } },
                subtitle = "Which system to run, and when to buy fuel or stack wood")
            if (s.heatingEnabled) {
                NavRow("My heating", if (s.heatSources.isEmpty()) "Add how you heat" else s.heatSources.joinToString(", ") { it.label.substringBefore(" (").lowercase() }, nav.openHeating)
            }
        }
        SectionCard("Seasonal") {
            LabeledSwitch("Fall foliage", s.foliageEnabled, { on -> update { setFoliage(on) } }, subtitle = "Only while leaves are changing")
            LabeledSwitch("Maple sap runs", s.sapEnabled, { on -> update { setSap(on) } },
                subtitle = "Freeze-thaw days for tapping, mid-January through April")
            LabeledSwitch("Burn days", s.burnEnabled, { on -> update { setBurn(on) } },
                subtitle = "Good days to burn brush, from wind, humidity, dry spells and fire weather alerts")
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
            app.northvane.weather.work.TonightWorker.schedule(context, now)
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
            LabeledSwitch("Tonight", s.tonightEnabled, { on ->
                if (on) askIfNeeded()
                updateDaily { setTonight(on) }
            }, subtitle = "One evening heads-up, only when there's something to do: frost, pipes, black ice by morning, a storm overnight, laundry out, northern lights")
            if (s.tonightEnabled) {
                val evening = (17 * 60..21 * 60 step 30).toList()
                ChoiceDropdown("At", (evening + s.tonightMinutes).distinct().sorted(), s.tonightMinutes, { m ->
                    val t = java.time.LocalTime.of(m / 60, m % 60)
                    java.time.format.DateTimeFormatter.ofPattern(if (android.text.format.DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a").format(t)
                }, { m -> updateDaily { setTonightMinutes(m) } })
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
    SettingsScaffold("Radar", onBack) {
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
    }
}

@Composable
private fun SatellitePage(state: MainUiState, container: AppContainer, onBack: () -> Unit) {
    val s = state.settings
    val update = rememberUpdater(container)
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    SettingsScaffold("Backwoods mode", onBack) {
        SectionCard(null) {
            Hint("Light packing for satellite and metered connections: pick what may download. Everything else keeps its last data.")
            ChoiceDropdown("Backwoods mode turns on", DataSaverMode.entries, s.dataSaver, {
                when (it) {
                    DataSaverMode.AUTO -> "Automatically"
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
                    "Open-Meteo (air quality and 15-minute rain), USGS and NOAA (rivers, tides), your WeeWX server and, if enabled, " +
                    "OpenWeatherMap. Pollen requests send coordinates rounded to about 1 km to Google. If you turn on AI, the place " +
                    "name and weather text go to the AI provider you choose (Claude or your own server), and nothing else. Map tiles " +
                    "come from OpenFreeMap; radar and satellite imagery from NOAA and the Iowa Environmental Mesonet. The aurora forecast " +
                    "is downloaded from NOAA's Space Weather Prediction Center without sending your location. Place search uses " +
                    "your device's built-in geocoder.",
            )
            Text(
                "Map data © OpenStreetMap contributors, © OpenMapTiles. Weather data: NOAA / National Weather Service; Environment and " +
                    "Climate Change Canada; NOAA Regional Climate Centers (ACIS); NOAA Space Weather Prediction Center. Pollen: Google Pollen " +
                    "API. Activity, mud, foliage, garden, heating, outage, frozen pipe, ice, aurora, sap and burn outlooks are this app's estimates. AI summaries are written by your own AI server.",
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

/** The list of the user's times, with add, change and remove. */
@Composable
private fun MyTimesEditor(times: List<MyTime>, onChange: (List<MyTime>) -> Unit) {
    val context = LocalContext.current
    val is24 = android.text.format.DateFormat.is24HourFormat(context)
    fun clock(m: Int) = java.time.format.DateTimeFormatter.ofPattern(if (is24) "HH:mm" else "h:mm a").format(java.time.LocalTime.of(m / 60, m % 60))
    var editing by remember { mutableStateOf<Int?>(null) }
    times.forEachIndexed { i, t ->
        NavRow(t.label, "${clock(t.minutes)} · ${t.durationMinutes} min" + if (t.weekdaysOnly) " · weekdays" else "") { editing = i }
    }
    if (times.size < 8) TextButton(onClick = { editing = -1 }) { Text("Add a time") }
    editing?.let { index ->
        val existing = times.getOrNull(index)
        var label by remember(index) { mutableStateOf(existing?.label ?: "") }
        var minutes by remember(index) { mutableStateOf(existing?.minutes ?: (17 * 60)) }
        var duration by remember(index) { mutableStateOf(existing?.durationMinutes ?: 60) }
        var weekdays by remember(index) { mutableStateOf(existing?.weekdaysOnly ?: true) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (existing == null) "Add a time" else "Change time") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(label, { label = it.take(24) }, singleLine = true, label = { Text("Name") }, placeholder = { Text("Drive home") }, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("Commute", "Drive home", "Walk", "Run").forEach { name ->
                            FilterChip(selected = label == name, onClick = { label = name }, label = { Text(name, style = MaterialTheme.typography.labelSmall) })
                        }
                    }
                    ChoiceDropdown("At", (5 * 60..22 * 60 step 15).toList().let { if (minutes in it) it else (it + minutes).sorted() }, minutes, ::clock, { minutes = it })
                    ChoiceDropdown("For", listOf(15, 30, 45, 60, 90, 120, 180), duration, { if (it < 60) "$it min" else "${it / 60}${if (it % 60 == 30) ".5" else ""} h" }, { duration = it })
                    LabeledSwitch("Weekdays only", weekdays, { weekdays = it })
                }
            },
            confirmButton = {
                TextButton(enabled = label.isNotBlank(), onClick = {
                    val t = MyTime(label.trim(), minutes, duration, weekdays)
                    onChange(if (existing == null) times + t else times.mapIndexed { i, old -> if (i == index) t else old })
                    editing = null
                }) { Text("Save") }
            },
            dismissButton = {
                Row {
                    if (existing != null) TextButton(onClick = { onChange(times.filterIndexed { i, _ -> i != index }); editing = null }) { Text("Remove") }
                    TextButton(onClick = { editing = null }) { Text("Cancel") }
                }
            },
        )
    }
}
