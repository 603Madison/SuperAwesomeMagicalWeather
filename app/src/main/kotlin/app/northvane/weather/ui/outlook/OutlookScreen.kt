@file:OptIn(ExperimentalMaterial3Api::class)

package app.northvane.weather.ui.outlook

import android.text.format.DateFormat
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
import androidx.compose.ui.platform.LocalContext
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
import app.northvane.weather.ui.home.MudSection
import java.time.Instant
import java.time.ZoneId

/** Whether the Outlook tab has anything turned on. */
fun AppSettings.hasOutlookFeatures(): Boolean =
    (activitiesEnabled && activities.isNotEmpty()) || mudEnabled || foliageEnabled || dryingEnabled || gardenEnabled || heatingEnabled ||
        sapEnabled || burnEnabled

/**
 * Planning cards that used to crowd the Today screen: outdoor activities,
 * trail mud, garden, clothes drying, heating season, maple sap, burn days and
 * fall foliage. Each
 * appears only when turned on, in season, and (on satellite) with fresh data.
 */
@Composable
fun OutlookScreen(
    state: MainUiState,
    onOpenSettings: () -> Unit,
    onOpenHeating: () -> Unit,
    onLaundry: (Boolean) -> Unit,
    onMarkPlanted: (Crop) -> Unit,
) {
    val context = LocalContext.current
    val snapshot = state.snapshot
    val settings = state.settings
    val f = remember(settings.units) { UnitFormatter(settings.units) }
    val zone = remember(snapshot?.timeZoneId) { snapshot?.timeZoneId?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault() }
    val time = remember(zone) { TimeFormat(zone, DateFormat.is24HourFormat(context)) }
    val dailyFresh = state.shows(Section.DAILY_FORECAST)
    val hourlyFresh = state.shows(Section.HOURLY_FORECAST)

    Scaffold(
        containerColor = Color.Transparent,
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
            var shown = 0
            if (snapshot != null) {
                val activities = if (settings.activitiesEnabled) settings.activities else emptySet()
                if (activities.isNotEmpty() && dailyFresh && snapshot.daily.any { d -> d.activities.any { it.activity in activities } }) {
                    shown++
                    item { ActivitiesSection(snapshot.daily, activities, f, time) }
                }
                val climate = state.frostClimate
                if (settings.gardenEnabled && climate != null && settings.gardenPlan.isNotEmpty() && dailyFresh) {
                    val garden = GardenAdvisor.report(settings.gardenPlan, climate, snapshot.daily, snapshot.history, time.today())
                    if (GardenAdvisor.plants(garden, time.today(), settings.gardenPlanting, settings.gardenHarvest).isNotEmpty() || garden.inGrowingSeason) {
                        shown++
                        item { GardenCard(garden, settings, f, time, onMarkPlanted) }
                    }
                }
                if (settings.mudEnabled && dailyFresh && snapshot.daily.any { it.mud != null }) {
                    shown++
                    item { MudSection(snapshot.daily, f, time) }
                }
                if (settings.dryingEnabled && hourlyFresh && snapshot.hourly.isNotEmpty()) {
                    shown++
                    item {
                        val drying = remember(snapshot) { DryingAdvisor.report(snapshot.hourly, snapshot.daily, Instant.now(), zone) }
                        DryingSection(drying, settings.laundryOutSince != null, f, time, onLaundry)
                    }
                }
                if (settings.heatingEnabled && dailyFresh && HeatingAdvisor.visible(time.today(), settings.heatSources)) {
                    shown++
                    item {
                        val heating = remember(snapshot, settings.heatSources, settings.heatingAggressive, climate) {
                            HeatingAdvisor.report(settings.heatSources, snapshot.daily, snapshot.history, climate, time.today(), settings.heatingAggressive)
                        }
                        HeatingSection(heating, settings.heatSources.isNotEmpty(), settings.heatingAggressive, f, time, onOpenHeating)
                    }
                }
                val latitude = snapshot.latitude ?: state.selected?.latitude
                if (settings.sapEnabled && dailyFresh && latitude != null && SapAdvisor.inSeason(time.today(), latitude)) {
                    shown++
                    item {
                        val sap = remember(snapshot) { SapAdvisor.report(snapshot.daily, snapshot.hourly, snapshot.history, time.today(), zone) }
                        SapSection(sap, f, time)
                    }
                }
                if (settings.burnEnabled && dailyFresh && snapshot.daily.isNotEmpty()) {
                    shown++
                    item {
                        val burn = remember(snapshot) { BurnAdvisor.report(snapshot.daily, snapshot.history, snapshot.alerts, time.today(), zone) }
                        BurnSection(burn, f, time)
                    }
                }
                val water = snapshot.water
                if (settings.waterEnabled && water != null && (water.river != null || water.tide != null) && state.shows(Section.WATER)) {
                    shown++
                    item { app.northvane.weather.ui.home.WaterSection(water, f, time) }
                }
                val foliage = snapshot.foliage
                if (settings.foliageEnabled && foliage != null && foliage.inSeason && dailyFresh) {
                    shown++
                    item { FoliageSection(foliage, time) }
                }
            }
            if (shown == 0) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 24.dp)) {
                        Text("Nothing to plan right now", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Cards appear here in season: outdoor activities, trail mud, your garden, clothes drying, heating, maple sap, burn days and fall foliage. " +
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
