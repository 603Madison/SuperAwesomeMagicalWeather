package app.northvane.mini.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.northvane.core.model.AlertCategory
import app.northvane.core.model.DailyForecast
import app.northvane.core.model.HourlyForecast
import app.northvane.core.model.UnitSystem
import app.northvane.core.model.Units
import app.northvane.core.model.WeatherAlert
import app.northvane.core.model.WeatherSnapshot
import app.northvane.core.util.UnitFormatter
import app.northvane.mini.BuildConfig
import app.northvane.mini.MiniApp
import app.northvane.mini.MiniState
import app.northvane.mini.MiniViewModel
import app.northvane.mini.data.MiniTheme
import app.northvane.mini.data.RadarImages
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.style.TextAlign
import app.northvane.core.model.WeatherCondition
import app.northvane.ui.TemperatureRange
import app.northvane.ui.WeatherIcon
import app.northvane.ui.skyGradient
import app.northvane.ui.warningGradient
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Screen routes; the back stack is a list of these. */
object Route {
    const val HOME = "home"
    const val HOURLY = "hourly"
    const val DAYS = "days"
    const val ALERTS = "alerts"
    const val RADAR = "radar"
    const val PLACES = "places"
    const val ADD = "add"
    const val SETTINGS = "settings"
    const val HELP = "help"
    fun day(date: LocalDate) = "day:$date"
    fun alert(id: String) = "alert:$id"
    fun place(id: String) = "place:$id"
}

/** Times and dates in the place's own time zone. */
class Fmt(val zone: ZoneId, is24: Boolean, val units: UnitFormatter) {
    private val hour = DateTimeFormatter.ofPattern(if (is24) "HH:mm" else "h a", Locale.getDefault())
    private val time = DateTimeFormatter.ofPattern(if (is24) "HH:mm" else "h:mm a", Locale.getDefault())
    private val dayTime = DateTimeFormatter.ofPattern(if (is24) "EEE HH:mm" else "EEE h:mm a", Locale.getDefault())
    fun hour(t: Instant): String = hour.format(t.atZone(zone))
    fun time(t: Instant): String = time.format(t.atZone(zone))
    fun dayTime(t: Instant): String = dayTime.format(t.atZone(zone))
    private val date = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault())
    fun date(d: LocalDate): String = date.format(d)
    fun weekday(d: LocalDate): String = when (d) {
        LocalDate.now(zone) -> "Today"
        LocalDate.now(zone).plusDays(1) -> "Tmrw"
        else -> d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
    }
}

@Composable
fun MiniRoot(vm: MiniViewModel, openAlerts: Boolean, onExit: () -> Unit, hub: KeyHub) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val dark = when (state.settings.theme) {
        MiniTheme.DARK -> true
        MiniTheme.LIGHT -> false
        MiniTheme.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
    }
    val stack = rememberSaveable(saver = listSaver(save = { it.toList() }, restore = { mutableStateListOf(*it.toTypedArray()) })) {
        mutableStateListOf(Route.HOME).apply { if (openAlerts) add(Route.ALERTS) }
    }
    val memory = remember { FocusMemory() }
    val zone = remember(state.snapshot?.timeZoneId) {
        state.snapshot?.timeZoneId?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
    }
    val fmt = remember(zone, state.settings.units) {
        Fmt(zone, android.text.format.DateFormat.is24HourFormat(context), UnitFormatter(state.settings.units))
    }
    fun go(route: String) = stack.add(route)
    fun back() = if (stack.size > 1) stack.removeAt(stack.lastIndex) else onExit()
    hub.back = ::back
    BackHandler(enabled = stack.size > 1) { back() }
    // First run: no places yet, so start by adding one.
    LaunchedEffect(state.ready, state.settings.places.isEmpty()) {
        if (state.ready && state.settings.places.isEmpty() && stack.last() == Route.HOME) go(Route.ADD)
    }

    MiniTheme(dark) {
        androidx.compose.runtime.CompositionLocalProvider(LocalKeyHub provides hub, LocalFocusMemory provides memory) {
            // Surface sets the default text color for the theme.
            androidx.compose.material3.Surface(
                Modifier.fillMaxSize().background(pageBrush(dark)),
                color = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onBackground,
            ) {
            Column(Modifier.fillMaxSize()) {
                val route = stack.last()
                // The app's name is long for a 2.4" screen: at home the place itself is the title.
                val place = state.place?.displayName
                if (route == Route.HOME && place != null) TopBar(place, null) else TopBar(titleOf(route, state), place)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    androidx.compose.runtime.key(route) {
                        when {
                            route == Route.HOME -> HomeScreen(state, fmt, ::go, vm::refresh)
                            route == Route.HOURLY -> HourlyScreen(state, fmt, vm::refresh)
                            route == Route.DAYS -> DaysScreen(state, fmt, ::go, vm::refresh)
                            route.startsWith("day:") -> DayScreen(state.snapshot?.daily?.firstOrNull { it.date.toString() == route.substringAfter(':') }, fmt)
                            route == Route.ALERTS -> AlertsScreen(state, fmt, ::go, vm::refresh)
                            route.startsWith("alert:") -> AlertScreen(state.snapshot?.alerts?.firstOrNull { it.id == route.substringAfter(':') }, fmt)
                            route == Route.RADAR -> RadarScreen(state, fmt)
                            route == Route.PLACES -> PlacesScreen(state, ::go, onUse = { vm.select(it); stack.clear(); stack.add(Route.HOME) })
                            route.startsWith("place:") -> PlaceScreen(state, route.substringAfter(':'), vm, ::back)
                            route == Route.ADD -> AddPlaceScreen(state, vm, onAdded = { stack.clear(); stack.add(Route.HOME) })
                            route == Route.SETTINGS -> SettingsScreen(state, vm, ::go)
                            route == Route.HELP -> HelpScreen()
                        }
                    }
                }
                SoftKeyBar(hub.keys.let { if (stack.size == 1) ScreenKeys(it.left, it.center, "Exit", it.onKey) else it }, ::back)
            }
            }
        }
    }
}

private fun titleOf(route: String, state: MiniState): String = when {
    route == Route.HOME -> "SuperAwesomeMagicalWeather"
    route == Route.HOURLY -> "Hourly"
    route == Route.DAYS -> "7-day"
    route.startsWith("day:") -> state.snapshot?.daily?.firstOrNull { it.date.toString() == route.substringAfter(':') }?.name ?: "Day"
    route == Route.ALERTS -> "Alerts"
    route.startsWith("alert:") -> "Alert"
    route == Route.RADAR -> "Radar"
    route == Route.PLACES -> "Places"
    route.startsWith("place:") -> "Place"
    route == Route.ADD -> "Add a place"
    route == Route.SETTINGS -> "Settings"
    route == Route.HELP -> "Keys & help"
    else -> "SuperAwesomeMagicalWeather"
}

private fun refreshKey(state: MiniState, refresh: () -> Unit) = SoftKey(if (state.loading) "Updating…" else "Refresh", refresh)

// --- Home ---

private data class MenuItem(val key: Int, val label: String, val route: String, val detail: String? = null, val color: Color? = null)

@Composable
private fun HomeScreen(state: MiniState, fmt: Fmt, go: (String) -> Unit, refresh: () -> Unit) {
    val snapshot = state.snapshot
    val alerts = snapshot?.alerts.orEmpty()
    val warning = alerts.any { it.category == AlertCategory.WARNING }
    val menu = listOf(
        MenuItem(1, "Hourly", Route.HOURLY),
        MenuItem(2, "7-day", Route.DAYS),
        MenuItem(3, "Alerts", Route.ALERTS, if (alerts.isEmpty()) "None" else "${alerts.size} active", if (warning) MaterialTheme.colorScheme.error else if (alerts.isNotEmpty()) MaterialTheme.colorScheme.tertiary else null),
        MenuItem(4, "Radar", Route.RADAR),
        MenuItem(5, "Places", Route.PLACES),
        MenuItem(6, "Settings", Route.SETTINGS),
        MenuItem(0, "Keys & help", Route.HELP),
    )
    ScreenKeysEffect(
        ScreenKeys(left = refreshKey(state, refresh), center = "Open", right = "Exit", onKey = { code ->
            val digit = digitOf(code)
            menu.firstOrNull { it.key == digit }?.let { go(it.route); true } ?: false
        }),
    )
    MiniList(screen = Route.HOME, count = menu.size, header = { NowHeader(state, snapshot, fmt) }) { i, focus, onFocused ->
        val item = menu[i]
        MiniRow({ go(item.route) }, focusRequester = focus, onFocused = onFocused) {
            KeyBadge("${item.key}")
            Spacer(Modifier.width(10.dp))
            Text(item.label, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), modifier = Modifier.weight(1f))
            item.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = item.color ?: MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** Current conditions on the main app's hero card: sky colors for the weather, red during a warning. */
@Composable
private fun NowHeader(state: MiniState, snapshot: WeatherSnapshot?, fmt: Fmt) {
    val f = fmt.units
    val current = snapshot?.current
    val hour = snapshot?.hourly?.firstOrNull()
    val today = snapshot?.daily?.firstOrNull { it.highC != null || it.lowC != null }
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val warning = snapshot?.alerts.orEmpty().firstOrNull { it.category == AlertCategory.WARNING }
    val condition = current?.condition?.takeIf { it != WeatherCondition.UNKNOWN } ?: hour?.condition ?: WeatherCondition.UNKNOWN
    val isDay = current?.isDaytime ?: hour?.isDaytime ?: true
    val sky = if (warning != null) warningGradient(dark) else skyGradient(condition, isDay, dark)
    val soft = Color.White.copy(alpha = 0.82f)
    Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Brush.verticalGradient(sky))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            if (snapshot == null) {
                Text(if (state.loading) "Loading the forecast…" else "No forecast yet", style = MaterialTheme.typography.titleMedium, color = Color.White)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(f.temperature(current?.temperatureC ?: hour?.temperatureC), style = MaterialTheme.typography.headlineLarge, color = Color.White)
                        Text(
                            current?.description ?: current?.condition?.label ?: hour?.shortForecast ?: "",
                            style = MaterialTheme.typography.titleSmall, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    WeatherIcon(condition, isDay, size = 48.dp, onDark = true)
                }
                val line1 = listOfNotNull(
                    current?.feelsLikeC?.let { "Feels ${f.temperature(it)}" },
                    today?.let { "H ${f.temperature(it.highC)}  L ${f.temperature(it.lowC)}" },
                )
                if (line1.isNotEmpty()) Text(line1.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = soft)
                val line2 = listOfNotNull(
                    current?.windSpeedKph?.let { "Wind ${f.windSpeed(it)}" },
                    current?.humidityPercent?.let { "Humidity ${f.percent(it)}" },
                )
                if (line2.isNotEmpty()) Text(line2.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = soft)
                if (warning != null) {
                    Text("⚠ ${warning.event}", style = MaterialTheme.typography.titleSmall, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                } else {
                    today?.shortForecast?.let { Text("Today: $it", style = MaterialTheme.typography.bodySmall, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                Text(if (state.loading) "Updating…" else "Updated ${fmt.time(snapshot.fetchedAt)}", style = MaterialTheme.typography.labelMedium, color = soft)
            }
        }
        state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp)) }
    }
}

// --- Hourly and days ---

@Composable
private fun HourlyScreen(state: MiniState, fmt: Fmt, refresh: () -> Unit) {
    val hours = state.snapshot?.hourly.orEmpty().take(48)
    ScreenKeysEffect(ScreenKeys(left = refreshKey(state, refresh)))
    if (hours.isEmpty()) return Hint(if (state.loading) "Loading…" else "No hourly forecast for this place.")
    MiniList(screen = Route.HOURLY, count = hours.size) { i, focus, onFocused ->
        HourRow(hours[i], fmt, focus, onFocused)
    }
}

@Composable
private fun HourRow(h: HourlyForecast, fmt: Fmt, focus: FocusRequester, onFocused: () -> Unit) {
    val f = fmt.units
    MiniRow(null, focusRequester = focus, onFocused = onFocused) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(fmt.hour(h.time), style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(54.dp))
                WeatherIcon(h.condition, h.isDaytime, size = 22.dp)
                Spacer(Modifier.width(8.dp))
                Text(f.temperature(h.temperatureC), style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(40.dp))
                Text(h.precipitationChance?.let { "$it%" } ?: "", style = MaterialTheme.typography.bodySmall, color = Accent, modifier = Modifier.width(38.dp))
                Text(f.windSpeed(h.windSpeedKph), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Text(h.shortForecast ?: h.condition.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun DaysScreen(state: MiniState, fmt: Fmt, go: (String) -> Unit, refresh: () -> Unit) {
    val today = LocalDate.now(fmt.zone)
    val days = state.snapshot?.daily.orEmpty().filter { !it.date.isBefore(today) }
    ScreenKeysEffect(ScreenKeys(left = refreshKey(state, refresh), center = "Details"))
    if (days.isEmpty()) return Hint(if (state.loading) "Loading…" else "No daily forecast for this place.")
    // The week's range, so each day's bar sits where it belongs, as in the main app.
    val lows = days.mapNotNull { it.lowC ?: it.highC }
    val highs = days.mapNotNull { it.highC ?: it.lowC }
    val min = lows.minOrNull() ?: 0.0
    val max = highs.maxOrNull() ?: 0.0
    MiniList(screen = Route.DAYS, count = days.size) { i, focus, onFocused ->
        val d = days[i]
        val f = fmt.units
        MiniRow({ go(Route.day(d.date)) }, focusRequester = focus, onFocused = onFocused) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(fmt.weekday(d.date), style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(46.dp))
                    WeatherIcon(d.condition, d.isDaytimeCondition, size = 22.dp)
                    Text(
                        f.temperature(d.lowC), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End, modifier = Modifier.width(34.dp),
                    )
                    TemperatureRange(d.lowC, d.highC, min, max, Modifier.weight(1f).padding(horizontal = 6.dp))
                    Text(f.temperature(d.highC), style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(32.dp))
                }
                Row {
                    d.precipitationChance?.takeIf { it >= 10 }?.let { Text("$it%  ", style = MaterialTheme.typography.bodySmall, color = Accent) }
                    Text(d.shortForecast ?: d.condition.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun DayScreen(day: DailyForecast?, fmt: Fmt) {
    ScreenKeysEffect(ScreenKeys(center = "▲▼ Scroll"))
    if (day == null) return Hint("This day is no longer in the forecast.")
    val f = fmt.units
    ScrollText {
        Row(verticalAlignment = Alignment.CenterVertically) {
            WeatherIcon(day.condition, day.isDaytimeCondition, size = 36.dp)
            Spacer(Modifier.width(10.dp))
            Column {
                Text("${day.name} · ${fmt.date(day.date)}", style = MaterialTheme.typography.titleMedium)
                Text("High ${f.temperature(day.highC)}   Low ${f.temperature(day.lowC)}", style = MaterialTheme.typography.bodyMedium)
            }
        }
        val facts = buildList {
            day.precipitationChance?.let { add("Precipitation $it%" + (day.precipitationMm?.takeIf { mm -> mm > 0 }?.let { mm -> " · ${f.precipitation(mm)}" } ?: "")) }
            day.snowMm?.takeIf { it >= 2.5 }?.let { add("Snow ${f.snow(it)}") }
            day.iceMm?.takeIf { it > 0 }?.let { add("Ice ${f.precipitation(it)}") }
            if (day.windMaxKph != null) {
                add("Wind ${f.windSpeed(day.windMaxKph)}" + (day.gustMaxKph?.let { " · gusts ${f.windSpeed(it)}" } ?: ""))
            } else {
                day.windSummary?.let { add("Wind $it") }
            }
            if (day.humidityMin != null && day.humidityMax != null) add("Humidity ${f.percent(day.humidityMin)}–${f.percent(day.humidityMax)}")
        }
        facts.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        Spacer(Modifier.height(6.dp))
        day.dayDetail?.let { Text("Day: $it", style = MaterialTheme.typography.bodyMedium) }
        day.nightDetail?.let { Text("Night: $it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp)) }
        if (day.dayDetail == null && day.nightDetail == null) day.shortForecast?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}

// --- Alerts ---

@Composable
private fun AlertsScreen(state: MiniState, fmt: Fmt, go: (String) -> Unit, refresh: () -> Unit) {
    val alerts = state.snapshot?.alerts.orEmpty()
    ScreenKeysEffect(ScreenKeys(left = refreshKey(state, refresh), center = if (alerts.isEmpty()) null else "Read"))
    if (alerts.isEmpty()) {
        return Hint(
            if (state.snapshot?.alertsAvailable == false) "No alert service covers this place." else "No active alerts. You'll get a notification if one is issued (Settings).",
        )
    }
    MiniList(screen = Route.ALERTS, count = alerts.size) { i, focus, onFocused ->
        val a = alerts[i]
        MiniRow({ go(Route.alert(a.id)) }, focusRequester = focus, onFocused = onFocused) {
            Box(Modifier.width(4.dp).height(30.dp).background(app.northvane.ui.alertColor(a.category), RoundedCornerShape(2.dp)))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(a.event, style = MaterialTheme.typography.titleSmall)
                (a.ends ?: a.expires)?.let { Text("Until ${fmt.dayTime(it)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

/** The alert's kind as a small colored label, in the main app's alert colors. */
@Composable
private fun CategoryLabel(category: AlertCategory) {
    if (category == AlertCategory.OTHER) return
    Text(
        category.name.lowercase().replaceFirstChar { it.uppercase() },
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
        color = Color.White,
        modifier = Modifier.background(app.northvane.ui.alertColor(category), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

@Composable
private fun AlertScreen(alert: WeatherAlert?, fmt: Fmt) {
    ScreenKeysEffect(ScreenKeys(center = "▲▼ Scroll"))
    if (alert == null) return Hint("This alert has ended.")
    ScrollText {
        CategoryLabel(alert.category)
        Text(alert.event, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 2.dp))
        val from = alert.onset ?: alert.effective
        val until = alert.ends ?: alert.expires
        Text(listOfNotNull(from?.let { "From ${fmt.dayTime(it)}" }, until?.let { "until ${fmt.dayTime(it)}" }).joinToString(" "), style = MaterialTheme.typography.bodySmall)
        alert.headline?.let { Text(it, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp)) }
        alert.description?.let { Text(it.trim(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }
        alert.instruction?.let {
            Text("What to do", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            Text(it.trim(), style = MaterialTheme.typography.bodySmall)
        }
    }
}

// --- Radar ---

@Composable
private fun RadarScreen(state: MiniState, fmt: Fmt) {
    val place = state.place ?: return Hint("Add a place first.")
    val radar = (LocalContext.current.applicationContext as MiniApp).container.radar
    var zoom by rememberSaveable { mutableIntStateOf(1) }
    var panX by rememberSaveable { mutableIntStateOf(0) }
    var panY by rememberSaveable { mutableIntStateOf(0) }
    var frame by rememberSaveable { mutableIntStateOf(6) }
    var playing by rememberSaveable { mutableStateOf(false) }
    val times = remember { radar.frameTimes(Instant.now()) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val half = radar.zooms[zoom]
    val view = RadarImages.View(place.latitude + panY * half * 0.5, place.longitude + panX * half * 0.7, zoom)
    fun pan(dx: Int, dy: Int) { panX += dx; panY += dy }
    fun zoomBy(d: Int) { zoom = (zoom + d).coerceIn(radar.zooms.indices) }
    ScreenKeysEffect(
        ScreenKeys(left = SoftKey("Reset") { zoom = 1; panX = 0; panY = 0; frame = times.lastIndex }, center = if (playing) "Pause" else "Play", onKey = { code ->
            when (code) {
                KeyEvent.KEYCODE_2 -> { pan(0, 1); true }
                KeyEvent.KEYCODE_8 -> { pan(0, -1); true }
                KeyEvent.KEYCODE_4 -> { pan(-1, 0); true }
                KeyEvent.KEYCODE_6 -> { pan(1, 0); true }
                KeyEvent.KEYCODE_5 -> { playing = !playing; true }
                KeyEvent.KEYCODE_0 -> { zoom = 1; panX = 0; panY = 0; true }
                KeyEvent.KEYCODE_STAR -> { zoomBy(-1); true }
                KeyEvent.KEYCODE_POUND -> { zoomBy(1); true }
                else -> false
            }
        }),
    )
    LaunchedEffect(playing) {
        while (playing) {
            delay(if (frame == times.lastIndex) 1500 else 700)
            frame = if (frame >= times.lastIndex) 0 else frame + 1
        }
    }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key) {
                    Key.DirectionLeft -> { playing = false; frame = (frame - 1).coerceAtLeast(0); true }
                    Key.DirectionRight -> { playing = false; frame = (frame + 1).coerceAtMost(times.lastIndex); true }
                    Key.DirectionUp -> { zoomBy(-1); true }
                    Key.DirectionDown -> { zoomBy(1); true }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { playing = !playing; true }
                    else -> false
                }
            }
            .focusable(),
    ) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }.toInt().coerceIn(160, 720)
        val heightPx = (with(density) { (maxHeight - 40.dp).toPx() }.toInt()).coerceIn(160, 900)
        var image by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
        var failed by remember { mutableStateOf(false) }
        LaunchedEffect(view, frame, widthPx, heightPx) {
            failed = false
            val bitmap = radar.image(view, times[frame], widthPx, heightPx)
            if (bitmap != null) image = bitmap else failed = true
            // Fetch the rest of the loop in the background so playback is smooth.
            times.forEach { radar.image(view, it, widthPx, heightPx) }
        }
        Column(Modifier.fillMaxSize()) {
            Box(
                Modifier.weight(1f).fillMaxWidth().pointerInput(times) {
                    detectTapGestures { p ->
                        // Touch: left third back, right third forward, middle play/pause.
                        when {
                            p.x < size.width / 3 -> { playing = false; frame = (frame - 1).coerceAtLeast(0) }
                            p.x > size.width * 2 / 3 -> { playing = false; frame = (frame + 1).coerceAtMost(times.lastIndex) }
                            else -> playing = !playing
                        }
                    }
                },
                contentAlignment = Alignment.Center,
            ) {
                image?.let { Image(it.asImageBitmap(), contentDescription = "Radar map", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
                if (image == null) Text(if (failed) "Radar isn't available right now." else "Loading radar…", style = MaterialTheme.typography.bodySmall)
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(fmt.time(times[frame]) + if (frame == times.lastIndex) " · latest" else "", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                ZoomChip("−") { zoomBy(1) }
                Spacer(Modifier.width(6.dp))
                ZoomChip("+") { zoomBy(-1) }
            }
            Hint("◀▶ time · ▲▼ zoom · 2468 move · OK play", modifier = Modifier.padding(bottom = 2.dp))
        }
    }
}

/** Touch-only zoom buttons; on the keypad, ▲/▼ or * and # zoom. */
@Composable
private fun ZoomChip(label: String, onTap: () -> Unit) {
    Box(
        Modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
            .pointerInput(label) { detectTapGestures { onTap() } }
            .padding(horizontal = 12.dp, vertical = 2.dp),
    ) { Text(label, style = MaterialTheme.typography.titleSmall) }
}

// --- Places ---

@Composable
private fun PlacesScreen(state: MiniState, go: (String) -> Unit, onUse: (String) -> Unit) {
    val places = state.settings.places
    ScreenKeysEffect(ScreenKeys(left = SoftKey("Add") { go(Route.ADD) }, center = "Options"))
    MiniList(screen = Route.PLACES, count = places.size + 1) { i, focus, onFocused ->
        if (i == places.size) {
            MiniRow({ go(Route.ADD) }, focusRequester = focus, onFocused = onFocused) {
                Text("+ Add a place", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
        } else {
            val p = places[i]
            val selected = p.id == state.place?.id
            MiniRow({ go(Route.place(p.id)) }, focusRequester = focus, onFocused = onFocused) {
                Text(if (selected) "●" else "○", color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(18.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.displayName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text((if (p.isDeviceLocation) "GPS · " else "") + "Alerts " + if (p.notify) "on" else "off", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun PlaceScreen(state: MiniState, id: String, vm: MiniViewModel, back: () -> Unit) {
    val place = state.settings.places.firstOrNull { it.id == id } ?: return Hint("This place was removed.")
    var confirm by remember { mutableStateOf(false) }
    ScreenKeysEffect(ScreenKeys(center = "Select"))
    val rows = listOf<Pair<String, () -> Unit>>(
        "Show this place" to { vm.select(id); back(); back() },
        "Alerts: ${if (place.notify) "On" else "Off"}" to { vm.toggleNotify(id) },
        "Move up" to { vm.movePlace(id, -1) },
        "Move down" to { vm.movePlace(id, 1) },
        (if (confirm) "Press OK again to remove" else "Remove") to { if (confirm) { vm.removePlace(id); back() } else confirm = true },
    )
    MiniList(screen = "place", count = rows.size, header = { Hint(place.displayName, color = MaterialTheme.colorScheme.onSurface) }) { i, focus, onFocused ->
        MiniRow(rows[i].second, focusRequester = focus, onFocused = onFocused) {
            Text(rows[i].first, style = MaterialTheme.typography.bodyMedium, color = if (i == rows.lastIndex) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun AddPlaceScreen(state: MiniState, vm: MiniViewModel, onAdded: () -> Unit) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    val fieldFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) vm.useMyLocation { ok -> if (ok) onAdded() }
    }
    fun useLocation() {
        val has = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (has) vm.useMyLocation { ok -> if (ok) onAdded() } else {
            locationPermission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
        }
    }
    LaunchedEffect(Unit) { vm.clearSearch(); runCatching { fieldFocus.requestFocus() } }
    ScreenKeysEffect(ScreenKeys(left = SoftKey("Search") { vm.search(query) }, center = "Select"))
    val results = state.results.orEmpty()
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            label = { Text("Town or ZIP code", style = MaterialTheme.typography.bodySmall) },
            textStyle = MaterialTheme.typography.bodyMedium,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { vm.search(query) }),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .focusRequester(fieldFocus)
                .onPreviewKeyEvent { e ->
                    // ▼ leaves the text box for the results; OK searches.
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.DirectionDown -> { focusManager.moveFocus(FocusDirection.Down); true }
                        Key.DirectionCenter, Key.Enter -> { vm.search(query); true }
                        else -> false
                    }
                },
        )
        val rows = buildList<Pair<String, () -> Unit>> {
            add((if (state.locating) "Finding your location…" else "Use my location (GPS)") to { useLocation() })
            results.forEach { r -> add("${r.name}, ${r.detail}" to { vm.addResult(r); onAdded() }) }
        }
        if (state.searching) Hint("Searching…")
        if (state.results != null && results.isEmpty() && !state.searching) Hint("No places found. Try a town name or ZIP code.")
        state.message?.let { Hint(it, color = MaterialTheme.colorScheme.error) }
        Box(Modifier.weight(1f)) {
            androidx.compose.foundation.lazy.LazyColumn {
                items(rows.size) { i ->
                    MiniRow(rows[i].second) {
                        Text(rows[i].first, style = MaterialTheme.typography.bodyMedium, color = if (i == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }
}

// --- Settings and help ---

@Composable
private fun SettingsScreen(state: MiniState, vm: MiniViewModel, go: (String) -> Unit) {
    val s = state.settings
    val context = LocalContext.current
    val notifyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    ScreenKeysEffect(ScreenKeys(center = "Change"))
    val intervals = listOf(15, 30, 60, 120)
    val rows = listOf<Pair<String, () -> Unit>>(
        "Units: ${if (s.units == UnitSystem.IMPERIAL) "°F, mph, in" else "°C, km/h, mm"}" to {
            vm.setUnits(if (s.units == UnitSystem.IMPERIAL) UnitSystem.METRIC else UnitSystem.IMPERIAL)
        },
        "Theme: ${s.theme.label}" to { vm.setTheme(MiniTheme.entries[(s.theme.ordinal + 1) % MiniTheme.entries.size]) },
        "Alert notifications: ${if (s.alertsEnabled) "On" else "Off"}" to {
            val on = !s.alertsEnabled
            if (on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            vm.setAlerts(on)
        },
        "Check for alerts every ${s.alertCheckMinutes} min" to {
            vm.setAlerts(s.alertsEnabled, intervals[(intervals.indexOf(s.alertCheckMinutes) + 1).mod(intervals.size)])
        },
        "Keys & help" to { go(Route.HELP) },
    )
    MiniList(screen = Route.SETTINGS, count = rows.size) { i, focus, onFocused ->
        MiniRow(rows[i].second, focusRequester = focus, onFocused = onFocused) {
            Text(rows[i].first, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun HelpScreen() {
    ScreenKeysEffect(ScreenKeys(center = "▲▼ Scroll"))
    ScrollText {
        Text("Keys", style = MaterialTheme.typography.titleMedium)
        listOf(
            "▲▼ move · OK select",
            "Right soft key or Back: go back",
            "Left soft key: the action shown at bottom left (Refresh, Add, Search…)",
            "Home: 1 Hourly · 2 7-day · 3 Alerts · 4 Radar · 5 Places · 6 Settings · 0 Help",
            "Radar: ◀▶ time · ▲▼ or * # zoom · 2 4 6 8 move · 5 or OK play · 0 recenter",
            "Long text: ▲▼ scrolls a screenful",
            "Touch works everywhere too: tap rows, soft-key labels and the radar.",
        ).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 3.dp)) }
        Text("About", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 10.dp))
        Text(
            "SuperAwesomeMagicalWeather Mini ${BuildConfig.VERSION_NAME}. Forecasts and alerts from the National Weather Service " +
                "(Environment Canada in Canada), radar pictures from the Iowa Environmental Mesonet (NWS NEXRAD), " +
                "place search by Open-Meteo. No accounts, ads or tracking. Free software under the GNU GPL v3.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
