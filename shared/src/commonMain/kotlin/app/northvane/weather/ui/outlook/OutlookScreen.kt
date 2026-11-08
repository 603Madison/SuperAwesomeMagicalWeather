@file:OptIn(ExperimentalMaterial3Api::class)

package app.northvane.weather.ui.outlook

import app.northvane.weather.ui.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.northvane.core.garden.Crop
import app.northvane.core.garden.GardenAdvisor
import app.northvane.core.model.Section
import app.northvane.core.outlook.BurnAdvisor
import app.northvane.core.outlook.DryingAdvisor
import app.northvane.core.outlook.HeatingAdvisor
import app.northvane.core.outlook.SapAdvisor
import app.northvane.core.util.UnitFormatter
import app.northvane.weather.data.AppSettings
import app.northvane.weather.ui.MainUiState
import app.northvane.weather.ui.TimeFormat
import app.northvane.weather.ui.home.ActivitiesSection
import app.northvane.weather.ui.home.BurnSection
import app.northvane.weather.ui.home.SapSection
import app.northvane.weather.ui.home.DryingSection
import app.northvane.weather.ui.home.FoliageSection
import app.northvane.weather.ui.home.GardenCard
import app.northvane.weather.ui.home.HeatingSection
import app.northvane.core.time.*

/** Whether the Outlook tab has anything turned on. */
fun AppSettings.hasOutlookFeatures(): Boolean =
    activitiesEnabled || waterEnabled || foliageEnabled || dryingEnabled || gardenEnabled || heatingEnabled ||
        sapEnabled || burnEnabled

/**
 * Planning cards in three groups: Outdoors (activities, rivers & tides),
 * Home (garden, clothes drying, heating) and Seasonal (fall foliage, maple
 * sap, burn days). Each
 * appears only when turned on, in season, and (on satellite) with fresh data.
 */
@Composable
fun OutlookScreen(
    state: MainUiState,
    onOpenSettings: () -> Unit,
    onOpenHeating: () -> Unit,
    onLaundry: (Boolean) -> Unit,
    onMarkPlanted: (Crop) -> Unit,
    onSetActivity: (app.northvane.core.model.Activity, Boolean) -> Unit = { _, _ -> },
) {
    val is24 = is24HourClock()
    val snapshot = state.snapshot
    val settings = state.settings
    val f = remember(settings.units) { UnitFormatter(settings.units) }
    val zone = remember(snapshot?.timeZoneId) { snapshot?.timeZoneId?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault() }
    val time = remember(zone) { TimeFormat(zone, is24) }
    val dailyFresh = state.shows(Section.DAILY_FORECAST)
    val hourlyFresh = state.shows(Section.HOURLY_FORECAST)

    Scaffold(
        containerColor = Color.Transparent,
        // Text on the theme backdrop needs the theme's color, not the black default for transparent containers.
        contentColor = MaterialTheme.colorScheme.onBackground,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Outlook")
                        state.selected?.let { Text(it.displayName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Three groups, each shown only when it has a card: Outdoors, Home, Seasonal.
            val outdoors = mutableListOf<@Composable () -> Unit>()
            val home = mutableListOf<@Composable () -> Unit>()
            val seasonal = mutableListOf<@Composable () -> Unit>()
            if (snapshot != null) {
                if (settings.activitiesEnabled && dailyFresh && snapshot.daily.isNotEmpty()) {
                    outdoors += { ActivitiesSection(snapshot.daily, if (hourlyFresh) snapshot.hourly else emptyList(), zone, settings.activities, f, time, onSetActivity) }
                }
                val water = snapshot.water
                if (settings.waterEnabled && water != null && (water.river != null || water.tide != null) && state.shows(Section.WATER)) {
                    outdoors += { app.northvane.weather.ui.home.WaterSection(water, f, time) }
                }
                val climate = state.frostClimate
                if (settings.gardenEnabled && climate != null && settings.gardenPlan.isNotEmpty() && dailyFresh) {
                    val garden = GardenAdvisor.report(settings.gardenPlan, climate, snapshot.daily, snapshot.history, time.today())
                    if (GardenAdvisor.plants(garden, time.today(), settings.gardenPlanting, settings.gardenHarvest).isNotEmpty() || garden.inGrowingSeason) {
                        home += { GardenCard(garden, settings, f, time, onMarkPlanted) }
                    }
                }
                if (settings.dryingEnabled && hourlyFresh && snapshot.hourly.isNotEmpty()) {
                    home += {
                        val drying = remember(snapshot) { DryingAdvisor.report(snapshot.hourly, snapshot.daily, kotlin.time.Clock.System.now(), zone) }
                        DryingSection(drying, settings.laundryOutSince != null, f, time, onLaundry)
                    }
                }
                if (settings.heatingEnabled && dailyFresh && HeatingAdvisor.visible(time.today(), settings.heatSources)) {
                    home += {
                        val heating = remember(snapshot, settings.heatSources, settings.heatingAggressive, climate) {
                            HeatingAdvisor.report(settings.heatSources, snapshot.daily, snapshot.history, climate, time.today(), settings.heatingAggressive)
                        }
                        HeatingSection(heating, settings.heatSources.isNotEmpty(), settings.heatingAggressive, f, time, onOpenHeating)
                    }
                }
                val foliage = snapshot.foliage
                if (settings.foliageEnabled && foliage != null && foliage.inSeason && dailyFresh) {
                    seasonal += { FoliageSection(foliage, time) }
                }
                val latitude = snapshot.latitude ?: state.selected?.latitude
                if (settings.sapEnabled && dailyFresh && latitude != null && SapAdvisor.inSeason(time.today(), latitude)) {
                    seasonal += {
                        val sap = remember(snapshot) { SapAdvisor.report(snapshot.daily, snapshot.hourly, snapshot.history, time.today(), zone) }
                        SapSection(sap, f, time)
                    }
                }
                if (settings.burnEnabled && dailyFresh && snapshot.daily.isNotEmpty()) {
                    seasonal += {
                        val burn = remember(snapshot) { BurnAdvisor.report(snapshot.daily, snapshot.history, snapshot.alerts, time.today(), zone) }
                        BurnSection(burn, f, time)
                    }
                }
            }
            listOf("Outdoors" to outdoors, "Home" to home, "Seasonal" to seasonal).forEach { (title, cards) ->
                if (cards.isNotEmpty()) {
                    item(key = "group-$title") {
                        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
                    }
                    cards.forEachIndexed { i, card -> item(key = "$title-$i") { card() } }
                }
            }
            val shown = outdoors.size + home.size + seasonal.size
            if (shown == 0) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 24.dp)) {
                        Text("Nothing to plan right now", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Cards appear here in season: outdoor activities, rivers & tides, your garden, clothes drying, heating, fall foliage, maple sap and burn days. " +
                                "Turn them on in Settings › Outlook." + if (state.liteMode && !settings.showStaleData) " In Backwoods mode, cards with data over 12 hours old are hidden." else "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(onClick = onOpenSettings) { Text("Outlook settings") }
                    }
                }
            }
        }
    }
}
