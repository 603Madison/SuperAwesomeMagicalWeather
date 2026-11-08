@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package app.northvane.weather.ios

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.window.ComposeUIViewController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.northvane.core.json.NorthvaneJson
import app.northvane.core.model.WeatherCondition
import app.northvane.core.net.HttpEngine
import app.northvane.core.net.LocalServerClients
import app.northvane.core.owm.OwmClient
import app.northvane.core.time.*
import app.northvane.core.util.UnitFormatter
import app.northvane.weather.AlertScheduler
import app.northvane.weather.AppContainer
import app.northvane.weather.Platform
import app.northvane.weather.PlaceSearch
import app.northvane.weather.SecretStore
import app.northvane.weather.data.AppSettings
import app.northvane.weather.data.SettingsRepository
import app.northvane.weather.ui.MainViewModel
import app.northvane.weather.ui.NavRequest
import app.northvane.weather.ui.NorthvaneRoot
import app.northvane.weather.ui.is24Hour
import app.northvane.weather.ui.theme.NorthvaneTheme
import app.northvane.weather.ui.theme.ThemeWeather
import app.northvane.weather.ui.theme.isAppInDarkTheme
import app.northvane.weather.work.Background
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.BackgroundTasks.BGAppRefreshTask
import platform.BackgroundTasks.BGAppRefreshTaskRequest
import platform.BackgroundTasks.BGTaskScheduler
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSBundle
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.UIKit.UIViewController
import platform.UserNotifications.UNNotification
import platform.UserNotifications.UNNotificationPresentationOptionBanner
import platform.UserNotifications.UNNotificationPresentationOptionList
import platform.UserNotifications.UNNotificationPresentationOptionSound
import platform.UserNotifications.UNNotificationPresentationOptions
import platform.UserNotifications.UNNotificationResponse
import platform.UserNotifications.UNUserNotificationCenter
import platform.UserNotifications.UNUserNotificationCenterDelegateProtocol
import platform.darwin.NSObject

/** The shared app's view of iOS. */
class IosPlatform : Platform {
    override val name = "iOS"
    override val appVersion: String =
        NSBundle.mainBundle.infoDictionary?.get("CFBundleShortVersionString") as? String ?: "dev"
    override val packageName: String = NSBundle.mainBundle.bundleIdentifier ?: "app.northvane.weather"

    override val http: HttpEngine by lazy { HttpEngine.create("SuperAwesomeMagicalWeather/$appVersion (iOS)") }
    override val localServers: LocalServerClients by lazy { HttpEngine.localServerClients(http) }

    private val support: String = run {
        val dir = (NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true).firstOrNull() as? String)
            ?: error("No Application Support directory")
        NSFileManager.defaultManager.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
        dir
    }
    override val settingsDir: String = support
    override val privateDir: String = "$support/private"

    override val secrets: SecretStore = KeychainSecretStore("app.northvane.weather.secrets")
    val iosConnectivity = IosConnectivity()
    override val connectivity get() = iosConnectivity
    override val deviceLocation = IosLocation
    override val themeBackground = IosThemeBackground(privateDir)
    override val notifications = IosNotifications

    override val alertScheduler = object : AlertScheduler {
        override suspend fun sync() = IosApp.scheduleRefresh()
        override fun checkNow() = IosApp.checkNow()
    }

    override fun placeSearch(owm: OwmClient, secrets: SecretStore, settings: SettingsRepository): PlaceSearch =
        IosPlaceSearch(owm, secrets, settings)

    override val localNetworkDeniedMessage: String =
        "Your server is on the local network. Allow Local Network for this app in Settings."

    override fun onWeatherUpdated() = IosApp.updateWidgets()

    override fun syncReminders(settings: AppSettings) = IosApp.bookReminders()

    override val frequentBackgroundChecks = false

    override fun is24HourClock(): Boolean = is24Hour()
}

/** What the home-screen and Lock Screen widgets show; written to the shared App Group container. */
@Serializable
data class WidgetData(
    val place: String,
    val temperature: String,
    val condition: String,
    /** SF Symbol name for the condition. */
    val symbol: String,
    val high: String?,
    val low: String?,
    val headline: String?,
    val updatedEpoch: Long,
)

/**
 * App-wide iOS glue, called from the Swift app: launch, foreground, background
 * refresh, notification taps and the widgets.
 */
object IosApp {
    const val REFRESH_TASK = "app.northvane.weather.refresh"
    const val APP_GROUP = "group.app.northvane.weather"

    private val platform = IosPlatform()
    val container: AppContainer by lazy { AppContainer(platform) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    internal val navRequests = MutableStateFlow<NavRequest?>(null)

    /** Set from Swift: asks WidgetKit to redraw (WidgetKit has no Objective-C API). */
    var reloadWidgets: (() -> Unit)? = null

    private val notificationDelegate = object : NSObject(), UNUserNotificationCenterDelegateProtocol {
        override fun userNotificationCenter(
            center: UNUserNotificationCenter,
            willPresentNotification: UNNotification,
            withCompletionHandler: (UNNotificationPresentationOptions) -> Unit,
        ) {
            withCompletionHandler(UNNotificationPresentationOptionBanner or UNNotificationPresentationOptionList or UNNotificationPresentationOptionSound)
        }

        override fun userNotificationCenter(
            center: UNUserNotificationCenter,
            didReceiveNotificationResponse: UNNotificationResponse,
            withCompletionHandler: () -> Unit,
        ) {
            val info = didReceiveNotificationResponse.notification.request.content.userInfo
            val locationId = (info["locationId"] as? String)?.takeIf { id -> id.length <= 64 && id.all { it.isLetterOrDigit() || it == '-' } }
            navRequests.value = when {
                info["alerts"] == true -> NavRequest.OpenAlerts(locationId)
                locationId != null -> NavRequest.ShowLocation(locationId)
                else -> null
            }
            withCompletionHandler()
        }
    }

    /** From the app delegate's didFinishLaunching: must register before launch completes. */
    fun onLaunch() {
        UNUserNotificationCenter.currentNotificationCenter().delegate = notificationDelegate
        platform.iosConnectivity.start()
        BGTaskScheduler.sharedScheduler.registerForTaskWithIdentifier(REFRESH_TASK, usingQueue = null) { task ->
            val refresh = task as? BGAppRefreshTask ?: return@registerForTaskWithIdentifier
            val job = scope.launch {
                runCatching { backgroundRefresh() }
                refresh.setTaskCompletedWithSuccess(true)
            }
            refresh.expirationHandler = { job.cancel() }
        }
        IosNotifications.refresh()
        scope.launch {
            SecretStore.RETIRED.forEach { if (container.secrets.has(it)) container.secrets.remove(it) }
        }
    }

    /** From the scene becoming active. */
    fun onForeground() {
        IosNotifications.refresh { bookReminders() }
        scope.launch { scheduleRefresh() }
    }

    /** Asks iOS for the next background refresh (it decides when; usually within the hour). */
    fun scheduleRefresh() {
        val request = BGAppRefreshTaskRequest(REFRESH_TASK)
        scope.launch {
            val minutes = container.settings.settings.first().alertCheckMinutes.coerceAtLeast(15)
            request.earliestBeginDate = NSDate.dateWithTimeIntervalSinceNow(minutes * 60.0)
            BGTaskScheduler.sharedScheduler.submitTaskRequest(request, error = null)
        }
    }

    fun checkNow() {
        scope.launch { runCatching { checkAlerts() } }
    }

    private suspend fun checkAlerts() {
        Background.checkAlerts(container, IosNotifications.authorized).forEach { IosNotifications.show(it) }
    }

    private suspend fun backgroundRefresh() {
        scheduleRefresh()
        checkAlerts()
        val settings = container.settings.settings.first()
        Background.todayPlace(container, settings, refresh = true)
        bookRemindersNow()
        updateWidgetsNow()
    }

    fun bookReminders() {
        scope.launch { runCatching { bookRemindersNow() } }
    }

    /**
     * iOS can't wake the app at a set time, so the briefing and the evening
     * heads-up are written from the latest forecast and booked for their
     * times; every refresh rewrites them with newer data.
     */
    private suspend fun bookRemindersNow() {
        val settings = container.settings.settings.first()
        val now = kotlin.time.Clock.System.now()
        val zone = ZoneId.systemDefault()
        fun next(minutes: Int): Instant {
            val today = now.atZone(zone).toLocalDate()
            val at = today.atTime(minutes / 60, minutes % 60).atZone(zone).toInstant()
            return if (at > now.plusSeconds(60)) at else today.plusDays(1).atTime(minutes / 60, minutes % 60).atZone(zone).toInstant()
        }
        if (!IosNotifications.authorized) return
        val place = Background.todayPlace(container, settings, refresh = false)
        if (settings.briefingEnabled && place != null) {
            val at = next(settings.briefingMinutes)
            IosNotifications.schedule(Background.briefing(place.first, place.second, settings, is24Hour(), at), at.epochSecond, now.epochSecond)
        } else {
            IosNotifications.cancel("briefing")
        }
        val locations = container.locations.locations.first().filter { it.notify }
        for (location in locations) {
            val id = "tonight-${location.id}"
            val snapshot = if (settings.tonightEnabled) container.weatherCache.read(location.id) else null
            val at = next(settings.tonightMinutes)
            val n = snapshot?.let { Background.tonight(location, it, settings, is24Hour(), at) }
            if (n != null) IosNotifications.schedule(n, at.epochSecond, now.epochSecond) else IosNotifications.cancel(id)
        }
    }

    fun updateWidgets() {
        scope.launch { runCatching { updateWidgetsNow() } }
    }

    private suspend fun updateWidgetsNow() {
        val settings = container.settings.settings.first()
        val (location, snapshot) = Background.todayPlace(container, settings, refresh = false) ?: return
        val f = UnitFormatter(settings.units)
        val zone = runCatching { ZoneId.of(snapshot.timeZoneId) }.getOrDefault(ZoneId.systemDefault())
        val now = kotlin.time.Clock.System.now()
        val today = snapshot.daily.firstOrNull { it.date == now.atZone(zone).toLocalDate() } ?: snapshot.daily.firstOrNull()
        val current = snapshot.current
        val condition = current?.condition ?: snapshot.hourly.firstOrNull()?.condition ?: WeatherCondition.UNKNOWN
        val clock = DateTimeFormatter.ofPattern(if (is24Hour()) "HH:mm" else "h:mm a").withZone(zone)
        val hour = DateTimeFormatter.ofPattern(if (is24Hour()) "HH:mm" else "h a").withZone(zone)
        val headline = app.northvane.core.outlook.TodayHeadline.write(snapshot.hourly, snapshot.daily, now, zone, f, clock::format, hour::format)
        val data = WidgetData(
            place = location.displayName,
            temperature = f.temperature(current?.temperatureC ?: snapshot.hourly.firstOrNull()?.temperatureC),
            condition = current?.description ?: condition.label,
            symbol = sfSymbol(condition, current?.isDaytime ?: true),
            high = today?.highC?.let { f.temperature(it) },
            low = today?.lowC?.let { f.temperature(it) },
            headline = headline.firstOrNull(),
            updatedEpoch = snapshot.fetchedAt.epochSecond,
        )
        val dir = NSFileManager.defaultManager.containerURLForSecurityApplicationGroupIdentifier(APP_GROUP)?.path ?: return
        val path = "$dir/widget.json".toPath()
        FileSystem.SYSTEM.write(path) { writeUtf8(NorthvaneJson.encodeToString(WidgetData.serializer(), data)) }
        reloadWidgets?.invoke()
    }

    private fun sfSymbol(c: WeatherCondition, day: Boolean): String = when (c) {
        WeatherCondition.CLEAR, WeatherCondition.MOSTLY_CLEAR, WeatherCondition.HOT, WeatherCondition.COLD ->
            if (day) "sun.max.fill" else "moon.stars.fill"
        WeatherCondition.PARTLY_CLOUDY -> if (day) "cloud.sun.fill" else "cloud.moon.fill"
        WeatherCondition.MOSTLY_CLOUDY, WeatherCondition.OVERCAST -> "cloud.fill"
        WeatherCondition.FOG, WeatherCondition.HAZE -> "cloud.fog.fill"
        WeatherCondition.SMOKE, WeatherCondition.DUST -> "smoke.fill"
        WeatherCondition.DRIZZLE -> "cloud.drizzle.fill"
        WeatherCondition.SHOWERS, WeatherCondition.RAIN -> "cloud.rain.fill"
        WeatherCondition.HEAVY_RAIN -> "cloud.heavyrain.fill"
        WeatherCondition.THUNDERSTORM -> "cloud.bolt.rain.fill"
        WeatherCondition.SNOW, WeatherCondition.BLIZZARD -> "cloud.snow.fill"
        WeatherCondition.RAIN_SNOW, WeatherCondition.SLEET, WeatherCondition.FREEZING_RAIN -> "cloud.sleet.fill"
        WeatherCondition.WINDY -> "wind"
        WeatherCondition.TROPICAL_STORM, WeatherCondition.HURRICANE -> "hurricane"
        WeatherCondition.TORNADO -> "tornado"
        WeatherCondition.UNKNOWN -> "cloud.fill"
    }

    @Composable
    internal fun Content() {
        val vm: MainViewModel = viewModel(factory = MainViewModel.factory(container))
        val state by vm.state.collectAsStateWithLifecycle()
        LaunchedEffect(Unit) { vm.onForeground() }
        val settings = state.settings
        val current = state.snapshot?.current
        val weather = ThemeWeather(
            condition = current?.condition ?: state.snapshot?.daily?.firstOrNull()?.condition ?: WeatherCondition.CLEAR,
            isDay = current?.isDaytime ?: true,
            temperatureC = current?.temperatureC ?: state.snapshot?.hourly?.firstOrNull()?.temperatureC,
        )
        NorthvaneTheme(
            darkTheme = isAppInDarkTheme(settings.themeMode),
            dynamicColor = false,
            theme = settings.appTheme,
            weather = weather,
            customAccent = settings.customAccent,
            customBackground = settings.customBackground,
            customDimPercent = settings.customDimPercent,
        ) {
            NorthvaneRoot(vm = vm, container = container, navRequests = navRequests, onNavRequestHandled = { navRequests.value = null })
        }
    }
}

/** The app's screen, for the Swift app to show. */
fun MainViewController(): UIViewController = ComposeUIViewController { IosApp.Content() }
