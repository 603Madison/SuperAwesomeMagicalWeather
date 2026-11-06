@file:OptIn(ExperimentalMaterial3Api::class)

package app.northvane.weather.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import app.northvane.weather.ui.settings.AllergiesScreen
import app.northvane.weather.ui.settings.ThemesScreen
import app.northvane.weather.ui.settings.GardenScreen
import app.northvane.weather.ui.settings.HeatingScreen
import app.northvane.weather.ui.theme.LocalBackdrop
import app.northvane.weather.ui.theme.LocalIsDark
import app.northvane.weather.ui.traffic.TrafficScreen
import app.northvane.weather.ui.outlook.OutlookScreen
import app.northvane.weather.ui.outlook.hasOutlookFeatures
import app.northvane.weather.ui.components.StormChaser
import app.northvane.weather.data.SatelliteFeature
import app.northvane.core.model.Section
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.northvane.weather.AppContainer
import app.northvane.weather.ui.alerts.AlertsScreen
import app.northvane.weather.ui.components.NorthvaneIcons
import app.northvane.weather.ui.home.HomeActions
import app.northvane.weather.ui.home.HomeScreen
import app.northvane.weather.ui.locations.LocationsScreen
import app.northvane.weather.ui.radar.RadarScreen
import app.northvane.weather.ui.settings.DataSourcesScreen
import app.northvane.weather.ui.settings.SettingsScreen
import app.northvane.weather.ui.settings.SettingsNav
import app.northvane.weather.ui.settings.SettingsPage
import kotlinx.coroutines.flow.StateFlow

sealed interface NavRequest {
    data class OpenAlerts(val locationId: String?) : NavRequest
    /** Show this place on the Today tab (e.g. from an aurora notification). */
    data class ShowLocation(val locationId: String) : NavRequest
}

object Routes {
    const val TODAY = "today"
    const val RADAR = "radar"
    const val ALERTS = "alerts"
    const val LOCATIONS = "locations"
    const val SETTINGS = "settings"
    const val SOURCES = "sources"
    const val TRAFFIC = "traffic"
    const val ALLERGIES = "allergies"
    const val THEMES = "themes"
    const val GARDEN = "garden"
    const val OUTLOOK = "outlook"
    const val HEATING = "heating"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.TODAY, "Today", Icons.Filled.Home),
    Tab(Routes.RADAR, "Radar", NorthvaneIcons.Radar),
    Tab(Routes.OUTLOOK, "Outlook", NorthvaneIcons.Pollen),
    Tab(Routes.TRAFFIC, "Traffic", NorthvaneIcons.Traffic),
    Tab(Routes.ALERTS, "Alerts", Icons.Filled.Warning),
)

private fun NavHostController.navigateToTab(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

@Composable
fun NorthvaneRoot(
    vm: MainViewModel,
    container: AppContainer,
    navRequests: StateFlow<NavRequest?>,
    onNavRequestHandled: () -> Unit,
) {
    val navController = rememberNavController()
    val state by vm.state.collectAsStateWithLifecycle()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route
    val request by navRequests.collectAsStateWithLifecycle()
    val homeActions = remember(vm) {
        HomeActions(
            refresh = { vm.refresh() },
            selectLocation = vm::selectLocation,
            useDeviceLocation = { vm.useDeviceLocation() },
            dismissError = vm::dismissError,
            markNotificationPromptShown = vm::markNotificationPromptShown,
            checkAlertsNow = vm::checkAlertsNow,
            setHourlyExpanded = vm::setHourlyExpanded,
            laundryOut = vm::laundryOut,
            markPlanted = vm::markPlanted,
            refreshSummary = { vm.summarize() },
            ignoreRestrictedData = { vm.setFollowRestrictedData(false) },
        )
    }

    val settingsNav = remember(navController) {
        SettingsNav(
            openPage = { navController.navigate("${Routes.SETTINGS}/${it.route}") },
            openSources = { navController.navigate(Routes.SOURCES) },
            openAllergies = { navController.navigate(Routes.ALLERGIES) },
            openThemes = { navController.navigate(Routes.THEMES) },
            openGarden = { navController.navigate(Routes.GARDEN) },
            openHeating = { navController.navigate(Routes.HEATING) },
        )
    }

    LaunchedEffect(request) {
        when (val r = request) {
            is NavRequest.OpenAlerts -> {
                r.locationId?.let { id -> if (state.locations.any { it.id == id } || !state.ready) vm.selectLocation(id) }
                navController.navigateToTab(Routes.ALERTS)
                onNavRequestHandled()
            }
            is NavRequest.ShowLocation -> {
                if (state.locations.any { it.id == r.locationId } || !state.ready) vm.selectLocation(r.locationId)
                navController.navigateToTab(Routes.TODAY)
                onNavRequestHandled()
            }
            null -> Unit
        }
    }

    // On satellite, tabs whose data can't be downloaded and has nothing recent cached disappear
    // (unless the user chose to see stale data).
    val settings = state.settings
    val hideStale = state.liteMode && !settings.showStaleData
    val trafficAge = remember(state.selected?.id, state.network, hideStale) {
        state.selected?.let { container.trafficCache.age(it.id) }
    }
    val visibleTabs = tabs.filter { tab ->
        when (tab.route) {
            Routes.TRAFFIC -> settings.trafficEnabled && !(hideStale && SatelliteFeature.TRAFFIC !in settings.satelliteFeatures &&
                (trafficAge == null || trafficAge > MainUiState.STALE_AFTER))
            Routes.RADAR -> !(hideStale && SatelliteFeature.RADAR !in settings.satelliteFeatures)
            Routes.ALERTS -> !(hideStale && SatelliteFeature.ALERTS !in settings.satelliteFeatures && !state.shows(Section.ALERTS))
            Routes.OUTLOOK -> settings.hasOutlookFeatures()
            else -> true
        }
    }
    // If the open tab disappears (e.g. satellite connects), go back to Today.
    LaunchedEffect(visibleTabs.map { it.route }, route) {
        if (route in tabs.map { it.route } && visibleTabs.none { it.route == route }) navController.navigateToTab(Routes.TODAY)
    }
    // Easter egg: tap Radar six times quickly.
    val radarTaps = remember { ArrayDeque<Long>() }
    var stormChaser by remember { mutableStateOf(false) }
    ThemeBackdrop(container, state.settings.customPhotoVersion)
    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (route == null || visibleTabs.any { it.route == route }) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.97f)) {
                    visibleTabs.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = {
                                if (tab.route == Routes.RADAR) {
                                    val now = System.currentTimeMillis()
                                    radarTaps.addLast(now)
                                    while (radarTaps.isNotEmpty() && now - radarTaps.first() > 3_000) radarTaps.removeFirst()
                                    if (radarTaps.size >= 6) {
                                        radarTaps.clear()
                                        stormChaser = true
                                    }
                                }
                                navController.navigateToTab(tab.route)
                            },
                            icon = {
                                val count = if (tab.route == Routes.ALERTS) state.snapshot?.alerts?.size ?: 0 else 0
                                BadgedBox(badge = { if (count > 0) Badge { Text("$count") } }) {
                                    Icon(tab.icon, contentDescription = null)
                                }
                            },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.TODAY,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.TODAY) {
                HomeScreen(
                    state = state,
                    actions = homeActions,
                    onOpenAlerts = { navController.navigateToTab(Routes.ALERTS) },
                    onOpenLocations = { navController.navigate(Routes.LOCATIONS) },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    onOpenSources = { navController.navigate(Routes.SOURCES) },
                    onOpenAllergies = { navController.navigate(Routes.ALLERGIES) },
                    onOpenHeating = { navController.navigate(Routes.HEATING) },
                )
            }
            composable(Routes.RADAR) {
                RadarScreen(state = state, container = container, onOpenSettings = { navController.navigate(Routes.SETTINGS) })
            }
            composable(Routes.OUTLOOK) {
                OutlookScreen(
                    state = state,
                    onOpenSettings = { navController.navigate("${Routes.SETTINGS}/${SettingsPage.OUTLOOK.route}") },
                    onOpenHeating = { navController.navigate(Routes.HEATING) },
                    onLaundry = vm::laundryOut,
                    onMarkPlanted = vm::markPlanted,
                )
            }
            composable(Routes.TRAFFIC) {
                TrafficScreen(state = state, container = container, onOpenSettings = { navController.navigate(Routes.SETTINGS) })
            }
            composable(Routes.ALLERGIES) {
                AllergiesScreen(state = state, container = container, onBack = { navController.popBackStack() })
            }
            composable(Routes.GARDEN) {
                GardenScreen(state = state, container = container, onBack = { navController.popBackStack() })
            }
            composable(Routes.HEATING) {
                HeatingScreen(state = state, container = container, onBack = { navController.popBackStack() })
            }
            composable(Routes.THEMES) {
                ThemesScreen(state = state, container = container, onBack = { navController.popBackStack() })
            }
            composable(Routes.ALERTS) {
                AlertsScreen(
                    state = state,
                    onRefresh = { vm.refresh() },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                )
            }
            composable(Routes.LOCATIONS) {
                LocationsScreen(state = state, vm = vm, container = container, onBack = { navController.popBackStack() })
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(state, container, onBack = { navController.popBackStack() }, nav = settingsNav)
            }
            composable("${Routes.SETTINGS}/{page}") { entry ->
                val page = SettingsPage.entries.firstOrNull { it.route == entry.arguments?.getString("page") }
                SettingsScreen(state, container, onBack = { navController.popBackStack() }, nav = settingsNav, page = page)
            }
            composable(Routes.SOURCES) {
                DataSourcesScreen(
                    state = state,
                    container = container,
                    onBack = { navController.popBackStack() },
                    onSourcesChanged = { vm.refresh() },
                )
            }
        }
    }
    if (stormChaser) StormChaser(onDone = { stormChaser = false })
}

/**
 * Everything behind the screens: the theme's gradient, or the custom photo
 * with a scrim so text stays readable.
 */
@Composable
private fun ThemeBackdrop(container: AppContainer, photoVersion: Long) {
    val backdrop = LocalBackdrop.current
    val dark = LocalIsDark.current
    Box(Modifier.fillMaxSize().background(backdrop.brush)) {
        if (backdrop.photo) {
            val image by produceState<ImageBitmap?>(null, photoVersion) {
                value = withContext(Dispatchers.IO) { container.themeBackground.load()?.asImageBitmap() }
            }
            image?.let { bitmap ->
                Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background((if (dark) Color.Black else Color.White).copy(alpha = backdrop.dim)))
            }
        }
    }
}
