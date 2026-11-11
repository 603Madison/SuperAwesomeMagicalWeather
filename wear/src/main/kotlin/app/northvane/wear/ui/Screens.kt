package app.northvane.wear.ui

import android.Manifest
import android.app.RemoteInput
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import androidx.wear.input.RemoteInputIntentHelper
import app.northvane.core.model.AlertCategory
import app.northvane.core.model.DailyForecast
import app.northvane.core.model.HourlyForecast
import app.northvane.core.model.UnitSystem
import app.northvane.core.model.WeatherAlert
import app.northvane.core.time.*
import app.northvane.core.util.UnitFormatter
import app.northvane.ui.WeatherIcon
import app.northvane.ui.alertColor
import app.northvane.wear.BuildConfig
import app.northvane.wear.R
import app.northvane.wear.WearState
import app.northvane.wear.WearViewModel
import app.northvane.wear.glance.Glance
import app.northvane.wear.glance.WatchClock
import kotlinx.coroutines.delay

private object Route {
    const val HOME = "home"
    const val PLACES = "places"
    const val SEARCH = "search"
    const val REMOVE = "remove"
    const val SETTINGS = "settings"
    const val TILE_PLACE = "tile-place"
    const val ALERT = "alert"
    const val DAY = "day"
}

/** Everything a screen needs to call back into the app. */
class WearActions(
    val refresh: () -> Unit = {},
    val select: (String) -> Unit = {},
    val openPlaces: () -> Unit = {},
    val openSettings: () -> Unit = {},
    val openAlert: (String) -> Unit = {},
    val openDay: (LocalDate) -> Unit = {},
    val dismissMessage: () -> Unit = {},
)

@Composable
fun WearRoot(vm: WearViewModel, openAlert: String?) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val is24 = android.text.format.DateFormat.is24HourFormat(context)
    val nav = rememberSwipeDismissableNavController()
    var alertId by rememberSaveable { mutableStateOf(openAlert) }
    var day by rememberSaveable { mutableStateOf<String?>(null) }
    var openedFromNotification by rememberSaveable { mutableStateOf(false) }
    // The next-hour line and the hourly list move on with the clock.
    val now by produceState(kotlin.time.Clock.System.now()) {
        while (true) {
            delay(60_000)
            value = kotlin.time.Clock.System.now()
        }
    }
    val glance = remember(state.place, state.forecast, state.settings.units, state.settings.nextHourRain, now, is24) {
        state.place?.let { place ->
            Glance.of(place, state.forecast?.takeIf { it.snapshot.locationId == place.id }, state.settings.units, state.settings.nextHourRain, now, is24)
        }
    }

    // Alerts are on by default, so ask once (Wear OS 4+) for permission to show them, after the first place.
    val notifyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(state.ready, state.settings.places.isNotEmpty()) {
        val s = state.settings
        if (state.ready && s.places.isNotEmpty() && s.alertsEnabled && !s.notifyAsked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            vm.markNotifyAsked()
            notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    // A tapped alert notification opens that alert once its place's forecast is here.
    LaunchedEffect(openAlert, state.forecast) {
        if (openAlert != null && !openedFromNotification && state.forecast?.snapshot?.alerts?.any { it.id == openAlert } == true) {
            openedFromNotification = true
            nav.navigate(Route.ALERT)
        }
    }

    val actions = WearActions(
        refresh = vm::refresh,
        select = { id ->
            vm.select(id)
            nav.popBackStack(Route.HOME, inclusive = false)
        },
        openPlaces = { nav.navigate(Route.PLACES) },
        openSettings = { nav.navigate(Route.SETTINGS) },
        openAlert = { id ->
            alertId = id
            nav.navigate(Route.ALERT)
        },
        openDay = { date ->
            day = date.toString()
            nav.navigate(Route.DAY)
        },
        dismissMessage = vm::dismissMessage,
    )

    WearTheme {
        AppScaffold {
            SwipeDismissableNavHost(navController = nav, startDestination = Route.HOME) {
                composable(Route.HOME) { HomeScreen(state, glance, now, actions, onAddPlace = { nav.navigate(Route.PLACES) }) }
                composable(Route.PLACES) { PlacesScreen(state, vm, nav, actions) }
                composable(Route.SEARCH) { SearchScreen(state, vm, nav) }
                composable(Route.REMOVE) { RemoveScreen(state, vm, nav) }
                composable(Route.SETTINGS) { SettingsScreen(state, vm, onTilePlace = { nav.navigate(Route.TILE_PLACE) }) }
                composable(Route.TILE_PLACE) { TilePlaceScreen(state, vm, nav) }
                composable(Route.ALERT) {
                    val alert = state.forecast?.snapshot?.alerts?.firstOrNull { it.id == alertId }
                    AlertScreen(alert, glance?.clock)
                }
                composable(Route.DAY) {
                    val forecast = state.forecast?.snapshot
                    DayScreen(forecast?.daily?.firstOrNull { it.date.toString() == day }, UnitFormatter(state.settings.units))
                }
            }
        }
    }
}

// ---- Building blocks ----

/** A scrolling screen: rotary input, scroll indicator, time at the top, round and square insets. */
@Composable
private fun ListScreen(content: TransformingLazyColumnScope.(TransformationSpec) -> Unit) {
    val listState = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    ScreenScaffold(scrollState = listState) { padding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize(),
        ) { content(spec) }
    }
}

/** Full width, shrinking and morphing at the top and bottom of a round screen as the list scrolls. */
private fun Modifier.morph(scope: TransformingLazyColumnItemScope, spec: TransformationSpec) = fillMaxWidth().transformedHeight(scope, spec)

@Composable
private fun TransformingLazyColumnItemScope.WearButton(
    spec: TransformationSpec,
    label: String,
    onClick: () -> Unit,
    secondary: String? = null,
    icon: Int? = null,
    danger: Boolean = false,
) {
    Button(
        onClick = onClick,
        modifier = Modifier.morph(this, spec),
        transformation = SurfaceTransformation(spec),
        colors = if (danger) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer)
        else ButtonDefaults.filledTonalButtonColors(),
        icon = icon?.let { res -> { Icon(painterResource(res), contentDescription = null) } },
        secondaryLabel = secondary?.let { s -> { Text(s, maxLines = 2, overflow = TextOverflow.Ellipsis) } },
        label = { Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
    )
}

private fun TransformingLazyColumnScope.header(text: String) {
    item { ListHeader { Text(text, modifier = Modifier.semantics { heading() }) } }
}

/** Text colors that follow the theme, picked outside composition. */
private enum class Tone { NORMAL, ACCENT, ERROR, MUTED }

private fun TransformingLazyColumnScope.paragraph(text: String, tone: Tone = Tone.NORMAL, center: Boolean = false) {
    item {
        val colors = MaterialTheme.colorScheme
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = when (tone) {
                Tone.NORMAL -> colors.onSurface
                Tone.ACCENT -> colors.primary
                Tone.ERROR -> colors.error
                Tone.MUTED -> colors.onSurfaceVariant
            },
            textAlign = if (center) TextAlign.Center else TextAlign.Start,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        )
    }
}

private fun TransformingLazyColumnScope.messageItem(state: WearState, spec: TransformationSpec, actions: WearActions) {
    state.message?.let { message ->
        item { WearButton(spec, message, actions.dismissMessage, secondary = "Tap to dismiss", danger = true) }
    }
}

private fun TransformingLazyColumnScope.progress() {
    item { CircularProgressIndicator(modifier = Modifier.size(36.dp)) }
}

// ---- Home ----

@Composable
fun HomeScreen(state: WearState, glance: Glance?, now: Instant, actions: WearActions, onAddPlace: () -> Unit) {
    val snapshot = state.forecast?.snapshot?.takeIf { it.locationId == state.place?.id }
    ListScreen { spec ->
        if (glance == null) {
            if (!state.ready) {
                progress()
                return@ListScreen
            }
            header("Weather")
            paragraph("Pick a place to see its forecast.", center = true)
            item { WearButton(spec, "Add a place", onAddPlace, icon = R.drawable.ic_add) }
            return@ListScreen
        }
        item { PlaceTitle(glance, actions.openPlaces) }
        messageItem(state, spec, actions)
        if (!glance.hasForecast) {
            if (state.loading) progress() else item { WearButton(spec, "Update", actions.refresh, icon = R.drawable.ic_refresh) }
            return@ListScreen
        }
        item { Hero(glance) }
        glance.nextHour?.let { paragraph(it, Tone.ACCENT, center = true) }
        glance.headline?.let { paragraph(it, center = true) }
        items(glance.alerts.take(3)) { alert -> AlertButton(spec, alert, glance.clock) { actions.openAlert(alert.id) } }
        if (snapshot != null) {
            val f = UnitFormatter(state.settings.units)
            val hours = snapshot.hourly.filter { it.time.plusSeconds(3600) > now }.take(12)
            if (hours.isNotEmpty()) {
                header("Next ${hours.size} hours")
                items(hours.chunked(4)) { row -> HourRow(row, now, glance.clock, f) }
            }
            val today = now.atZone(glance.clock.zone).toLocalDate()
            val days = snapshot.daily.filter { !it.date.isBefore(today) }.take(7)
            if (days.isNotEmpty()) {
                header("${days.size} days")
                items(days) { d -> DayButton(spec, d, today, glance.clock, f) { actions.openDay(d.date) } }
            }
        }
        item {
            WearButton(
                spec, if (state.loading) "Updating…" else "Update", actions.refresh,
                secondary = glance.updatedAt?.let { "Updated ${glance.clock.time(it)}" }, icon = R.drawable.ic_refresh,
            )
        }
        item { WearButton(spec, "Places", actions.openPlaces, secondary = "Change or add a place", icon = R.drawable.ic_place) }
        item { WearButton(spec, "Settings", actions.openSettings, icon = R.drawable.ic_settings) }
    }
}

/** The place's name at the top, where a round screen is narrow; tap it to change places. */
@Composable
private fun PlaceTitle(g: Glance, onClick: () -> Unit) {
    val name = if (g.isCurrentLocation) "${Glance.CURRENT_LOCATION}, ${g.place}".takeIf { g.place != Glance.CURRENT_LOCATION } ?: g.place else g.place
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxWidth(0.8f)
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(24.dp))
            .clickable(onClickLabel = "Change place", role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = name },
    ) {
        Icon(
            painterResource(if (g.isCurrentLocation) R.drawable.ic_my_location else R.drawable.ic_place),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(g.place, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Hero(g: Glance) {
    val description = listOfNotNull(g.temperature, g.conditionText.ifBlank { null }, g.highLow?.replace("H ", "high ")?.replace("L ", "low ")).joinToString(", ")
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = description },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            WeatherIcon(g.condition, g.isDay, size = 40.dp, onDark = true)
            Spacer(Modifier.width(8.dp))
            Text(g.temperature, style = MaterialTheme.typography.displayMedium)
        }
        if (g.conditionText.isNotBlank()) {
            Text(g.conditionText, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        g.highLow?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun TransformingLazyColumnItemScope.AlertButton(spec: TransformationSpec, alert: WeatherAlert, clock: WatchClock, onClick: () -> Unit) {
    val until = (alert.ends ?: alert.expires)?.let { "Until ${clock.dayTime(it)}" }
    val strong = alert.category == AlertCategory.WARNING
    Button(
        onClick = onClick,
        modifier = Modifier.morph(this, spec),
        transformation = SurfaceTransformation(spec),
        colors = ButtonDefaults.buttonColors(
            containerColor = alertColor(alert.category).copy(alpha = if (strong) 1f else 0.85f),
            contentColor = Color.White,
            secondaryContentColor = Color.White.copy(alpha = 0.85f),
            iconColor = Color.White,
        ),
        icon = { Icon(painterResource(R.drawable.ic_warning), contentDescription = null) },
        secondaryLabel = until?.let { { Text(it, maxLines = 1) } },
        label = { Text(alert.event, maxLines = 2, overflow = TextOverflow.Ellipsis) },
    )
}

@Composable
private fun HourRow(hours: List<HourlyForecast>, now: Instant, clock: WatchClock, f: UnitFormatter) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        hours.forEach { h ->
            val label = if (!h.time.isAfter(now)) "Now" else clock.hour(h.time)
            val chance = h.precipitationChance?.takeIf { it >= 20 }
            val description = listOfNotNull(label, f.temperature(h.temperatureC), h.condition.label, chance?.let { "$it% chance of precipitation" }).joinToString(", ")
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f).clearAndSetSemantics { contentDescription = description },
            ) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                WeatherIcon(h.condition, h.isDaytime, size = 22.dp, onDark = true)
                Text(f.temperature(h.temperatureC), style = MaterialTheme.typography.labelMedium, maxLines = 1)
                Text(chance?.let { "$it%" } ?: " ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1)
            }
        }
        // Keep the last row's columns lined up with the rows above.
        repeat(4 - hours.size) { Spacer(Modifier.weight(1f)) }
    }
}

@Composable
private fun TransformingLazyColumnItemScope.DayButton(spec: TransformationSpec, d: DailyForecast, today: LocalDate, clock: WatchClock, f: UnitFormatter, onClick: () -> Unit) {
    val temps = listOfNotNull(d.highC?.let { f.temperature(it) }, d.lowC?.let { f.temperature(it) }).joinToString(" / ")
    val chance = d.precipitationChance?.takeIf { it >= 20 }?.let { "$it%" }
    Button(
        onClick = onClick,
        modifier = Modifier.morph(this, spec),
        transformation = SurfaceTransformation(spec),
        colors = ButtonDefaults.filledTonalButtonColors(),
        icon = { WeatherIcon(d.condition, d.isDaytimeCondition, size = 26.dp, onDark = true) },
        secondaryLabel = { Text(listOfNotNull(temps.ifBlank { null }, chance).joinToString(" · "), maxLines = 1) },
        label = { Text(clock.weekday(d.date, today), maxLines = 1) },
    )
}

// ---- Alerts and days ----

@Composable
fun AlertScreen(alert: WeatherAlert?, clock: WatchClock?) {
    ListScreen { _ ->
        if (alert == null) {
            header("Alert")
            paragraph("This alert has ended.", center = true)
            return@ListScreen
        }
        item {
            ListHeader {
                Text(alert.event, color = alertColor(alert.category).copy(red = 1f), textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
            }
        }
        val until = (alert.ends ?: alert.expires)?.let { t -> clock?.let { "Until ${it.dayTime(t)}" } }
        listOfNotNull(until, alert.areaDesc?.take(200)).forEach { paragraph(it, Tone.MUTED, center = true) }
        alert.headline?.let { paragraph(it) }
        paragraphs(alert.description).forEach { paragraph(it) }
        val instruction = paragraphs(alert.instruction)
        if (instruction.isNotEmpty()) {
            header("What to do")
            instruction.forEach { paragraph(it) }
        }
        alert.senderName?.let { paragraph(it, Tone.MUTED, center = true) }
    }
}

/** NWS text wraps at about 70 characters; paragraphs are separated by blank lines. */
private fun paragraphs(text: String?): List<String> =
    text.orEmpty().split(Regex("\n\\s*\n")).map { it.replace(Regex("\\s*\n\\s*"), " ").trim() }.filter { it.isNotEmpty() }

@Composable
fun DayScreen(day: DailyForecast?, f: UnitFormatter) {
    ListScreen { _ ->
        if (day == null) {
            header("Forecast")
            paragraph("No forecast for this day.", center = true)
            return@ListScreen
        }
        header(day.name)
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) {}) {
                WeatherIcon(day.condition, day.isDaytimeCondition, size = 36.dp, onDark = true)
                Spacer(Modifier.width(8.dp))
                Text(listOfNotNull(day.highC?.let { "H ${f.temperature(it)}" }, day.lowC?.let { "L ${f.temperature(it)}" }).joinToString("  "), style = MaterialTheme.typography.titleMedium)
            }
        }
        listOfNotNull(
            day.shortForecast ?: day.condition.label,
            day.precipitationChance?.let { "Precipitation $it%" + (day.precipitationMm?.takeIf { mm -> mm > 0 }?.let { mm -> " · ${f.precipitation(mm)}" } ?: "") },
            day.snowMm?.takeIf { it > 0 }?.let { "Snow ${f.snow(it)}" },
            day.windSummary,
        ).forEach { paragraph(it, center = true) }
        day.dayDetail?.let { paragraph(it) }
        day.nightDetail?.let {
            header("Night")
            paragraph(it)
        }
    }
}

// ---- Places ----

@Composable
fun PlacesScreen(state: WearState, vm: WearViewModel, nav: NavHostController, actions: WearActions) {
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) vm.useCurrentLocation { ok -> if (ok) nav.popBackStack(Route.HOME, inclusive = false) }
    }
    fun useLocation() {
        if (vm.hasLocationPermission()) vm.useCurrentLocation { ok -> if (ok) nav.popBackStack(Route.HOME, inclusive = false) }
        else locationPermission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
    }
    val s = state.settings
    ListScreen { spec ->
        header("Places")
        messageItem(state, spec, actions)
        if (state.locating) {
            paragraph("Finding your location…", center = true)
            progress()
        }
        items(s.places, key = { it.id }) { place ->
            val notes = listOfNotNull(
                "Showing".takeIf { place.id == s.selected?.id },
                "On the tile".takeIf { place.id == s.tilePlace?.id },
            ).joinToString(" · ").ifBlank { null }
            WearButton(
                spec, if (place.isDeviceLocation) Glance.CURRENT_LOCATION else place.displayName, { actions.select(place.id) },
                secondary = notes, icon = if (place.isDeviceLocation) R.drawable.ic_my_location else R.drawable.ic_place,
            )
        }
        if (s.deviceLocation == null) {
            item { WearButton(spec, Glance.CURRENT_LOCATION, ::useLocation, secondary = "Use the watch's position", icon = R.drawable.ic_my_location) }
        }
        item { WearButton(spec, "Add a place", { nav.navigate(Route.SEARCH) }, secondary = "Town or ZIP code", icon = R.drawable.ic_add) }
        if (s.places.isNotEmpty()) {
            item { WearButton(spec, "Remove a place", { nav.navigate(Route.REMOVE) }, icon = R.drawable.ic_delete) }
        }
    }
}

private const val QUERY_KEY = "query"

@Composable
fun SearchScreen(state: WearState, vm: WearViewModel, nav: NavHostController) {
    var query by rememberSaveable { mutableStateOf("") }
    var asked by rememberSaveable { mutableStateOf(false) }
    // Without the system's text input screen (rare), type into a field instead.
    var typing by rememberSaveable { mutableStateOf(false) }
    val input = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = result.data?.let { RemoteInput.getResultsFromIntent(it) }?.getCharSequence(QUERY_KEY)?.toString()?.trim()
        if (!text.isNullOrEmpty()) {
            query = text.take(100)
            vm.search(query)
        }
    }
    fun ask() {
        val intent: Intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
        RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(RemoteInput.Builder(QUERY_KEY).setLabel("Town or ZIP code").build()))
        try {
            input.launch(intent)
        } catch (e: ActivityNotFoundException) {
            typing = true
        }
    }
    LaunchedEffect(Unit) {
        if (!asked) {
            asked = true
            ask()
        }
    }
    DisposableEffect(Unit) { onDispose { vm.clearSearch() } }
    ListScreen { spec ->
        header("Add a place")
        if (typing) {
            item {
                BasicTextField(
                    value = query,
                    onValueChange = { query = it.take(100) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { vm.search(query) }),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(48.dp)
                        .semantics { contentDescription = "Town or ZIP code" },
                )
            }
        }
        item {
            WearButton(
                spec, if (query.isBlank()) "Type or speak" else "“$query”",
                { if (typing) vm.search(query) else ask() },
                secondary = if (query.isBlank()) "Town or ZIP code" else if (typing) "Search" else "Search again",
                icon = R.drawable.ic_search,
            )
        }
        state.message?.let { paragraph(it, Tone.ERROR, center = true) }
        if (state.searching) progress()
        state.results?.let { results ->
            if (results.isEmpty() && state.message == null) paragraph("No places found.", center = true)
            items(results) { r ->
                WearButton(spec, r.name, {
                    vm.addResult(r)
                    nav.popBackStack(Route.HOME, inclusive = false)
                }, secondary = r.detail.ifBlank { null }, icon = R.drawable.ic_place)
            }
        }
    }
}

@Composable
fun RemoveScreen(state: WearState, vm: WearViewModel, nav: NavHostController) {
    var armed by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(state.settings.places.isEmpty()) {
        if (state.ready && state.settings.places.isEmpty()) nav.popBackStack(Route.HOME, inclusive = false)
    }
    ListScreen { spec ->
        header("Remove a place")
        items(state.settings.places, key = { it.id }) { place ->
            val name = if (place.isDeviceLocation) Glance.CURRENT_LOCATION else place.displayName
            // Two taps, so a stray touch can't delete a place.
            WearButton(
                spec, name, {
                    if (armed == place.id) {
                        armed = null
                        vm.removePlace(place.id)
                    } else {
                        armed = place.id
                    }
                },
                secondary = if (armed == place.id) "Tap again to remove" else null,
                icon = R.drawable.ic_delete,
                danger = armed == place.id,
            )
        }
    }
}

// ---- Settings ----

@Composable
fun SettingsScreen(state: WearState, vm: WearViewModel, onTilePlace: () -> Unit) {
    val s = state.settings
    val context = LocalContext.current
    val notifyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    ListScreen { spec ->
        header("Settings")
        item {
            WearButton(
                spec, "Units", { vm.setUnits(if (s.units == UnitSystem.IMPERIAL) UnitSystem.METRIC else UnitSystem.IMPERIAL) },
                secondary = if (s.units == UnitSystem.IMPERIAL) "Fahrenheit (°F)" else "Celsius (°C)",
            )
        }
        item {
            WearButton(
                spec, "Tile place", onTilePlace,
                secondary = s.places.firstOrNull { it.id == s.tilePlaceId }?.let { if (it.isDeviceLocation) Glance.CURRENT_LOCATION else it.displayName } ?: "Same as the app",
            )
        }
        item { Toggle(spec, "Next-hour rain", s.nextHourRain, vm::setNextHourRain) }
        item {
            Toggle(spec, "Alert notifications", s.alertsEnabled, { on ->
                vm.setAlerts(on)
                if (on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }, secondary = "For the tile's place")
        }
        item { Toggle(spec, "Background updates", s.backgroundUpdates, vm::setBackgroundUpdates, secondary = "Keeps the tile and complication fresh") }
        paragraph(
            "Version ${BuildConfig.VERSION_NAME}. Forecasts and alerts: National Weather Service, Environment Canada. Rain and place search: Open-Meteo.",
            Tone.MUTED, center = true,
        )
    }
}

@Composable
private fun TransformingLazyColumnItemScope.Toggle(spec: TransformationSpec, label: String, checked: Boolean, onChange: (Boolean) -> Unit, secondary: String? = null) {
    SwitchButton(
        checked = checked,
        onCheckedChange = onChange,
        modifier = Modifier.morph(this, spec),
        transformation = SurfaceTransformation(spec),
        secondaryLabel = secondary?.let { { Text(it, maxLines = 2) } },
        label = { Text(label, maxLines = 2) },
    )
}

@Composable
fun TilePlaceScreen(state: WearState, vm: WearViewModel, nav: NavHostController) {
    val s = state.settings
    fun pick(id: String?) {
        vm.setTilePlace(id)
        nav.popBackStack()
    }
    ListScreen { spec ->
        header("Tile place")
        item { WearButton(spec, "Same as the app", { pick(null) }, secondary = "Selected".takeIf { s.tilePlaceId == null || s.places.none { it.id == s.tilePlaceId } }) }
        items(s.places, key = { it.id }) { place ->
            WearButton(
                spec, if (place.isDeviceLocation) Glance.CURRENT_LOCATION else place.displayName, { pick(place.id) },
                secondary = "Selected".takeIf { place.id == s.tilePlaceId },
                icon = if (place.isDeviceLocation) R.drawable.ic_my_location else R.drawable.ic_place,
            )
        }
    }
}
