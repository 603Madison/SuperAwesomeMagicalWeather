package app.northvane.weather.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.northvane.core.json.NorthvaneJson
import app.northvane.core.model.WeatherCondition
import app.northvane.core.net.HttpClients
import app.northvane.core.net.HttpEngine
import app.northvane.core.net.LocalServerClients
import app.northvane.core.owm.OwmClient
import app.northvane.core.repo.attempt
import app.northvane.core.time.*
import app.northvane.weather.AlertScheduler
import app.northvane.weather.AppContainer
import app.northvane.weather.Platform
import app.northvane.weather.PlaceSearch
import app.northvane.weather.SecretStore
import app.northvane.weather.data.AppSettings
import app.northvane.weather.data.SettingsRepository
import app.northvane.weather.ui.MainViewModel
import app.northvane.weather.ui.NorthvaneRoot
import app.northvane.weather.ui.desktop24Hour
import app.northvane.weather.ui.theme.NorthvaneTheme
import app.northvane.weather.ui.theme.ThemeWeather
import app.northvane.weather.ui.theme.isAppInDarkTheme
import app.northvane.weather.work.Background
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock

/** The shared app's view of a Linux desktop. */
class DesktopPlatform(version: String) : Platform {
    override val name = "Linux"
    override val appVersion: String = version
    override val packageName: String = "app.northvane.weather"

    override val http: HttpEngine by lazy {
        HttpClients.create("SuperAwesomeMagicalWeather/$appVersion (Linux)", cacheDir = File(DesktopDirs.cache, "http"))
    }
    override val localServers: LocalServerClients by lazy { HttpClients.localServerClients(http) }

    override val settingsDir: String = DesktopDirs.config.path
    override val privateDir: String = DesktopDirs.private(File(DesktopDirs.data, "private")).path

    override val secrets = DesktopSecretStore(File(privateDir, "keys.json"))
    val desktopConnectivity = DesktopConnectivity()
    override val connectivity get() = desktopConnectivity
    override val deviceLocation = NoDeviceLocation
    override val hasDeviceLocation = false
    override val themeBackground = DesktopThemeBackground(File(privateDir))
    override val notifications = DesktopNotifications

    override val alertScheduler = object : AlertScheduler {
        override suspend fun sync() = DesktopApp.syncTimer()
        override fun checkNow() = DesktopApp.checkSoon()
    }

    override fun placeSearch(owm: OwmClient, secrets: SecretStore, settings: SettingsRepository): PlaceSearch =
        DesktopPlaceSearch(http, owm, secrets, settings)

    override fun syncReminders(settings: AppSettings) = DesktopApp.syncTimer()

    /** The app checks every 15 minutes at most when it's closed. */
    override val frequentBackgroundChecks = false

    override fun is24HourClock(): Boolean = desktop24Hour()
}

/** When the morning briefing and Tonight were last sent, so each goes out once a day. */
@Serializable
private data class ReminderState(val briefing: String? = null, val tonight: String? = null, val alertsCheckedAt: Long = 0)

/**
 * App-wide desktop glue: the window's content, and the work phones do in the
 * background (alert checks, the morning briefing, Tonight). While the window
 * is open the app does that work itself; when it's closed, a systemd user
 * timer starts it every 15 minutes with --background-check. A lock file keeps
 * the two from running at once.
 */
object DesktopApp {
    const val TIMER = "superawesomemagicalweather-check"

    var version: String = "dev"
    /** The installed launcher (jpackage sets this), for the systemd timer. */
    private val launcher: String? get() = System.getProperty("jpackage.app-path")?.takeIf { File(it).canExecute() }

    /** The app icon, for notifications. */
    val iconPath: String?
        get() = launcher?.let { File(File(it).parentFile.parentFile, "lib/SuperAwesomeMagicalWeather.png").takeIf(File::exists)?.path }

    private val platform by lazy { DesktopPlatform(version) }
    val container: AppContainer by lazy { AppContainer(platform) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val checks = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    internal val foregrounds = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val navRequests = kotlinx.coroutines.flow.MutableStateFlow<app.northvane.weather.ui.NavRequest?>(null)

    /** The window came to the front: refresh if the forecast is old. */
    fun onFocus() {
        foregrounds.tryEmit(Unit)
    }

    private val lockFile get() = File(DesktopDirs.data, "running.lock")
    private var lock: FileLock? = null

    /** Holds the lock while the window is open; waits briefly if a background check has it. */
    private fun takeLock(wait: Boolean): FileLock? {
        val channel = RandomAccessFile(lockFile, "rw").channel
        repeat(if (wait) 60 else 1) {
            runCatching { channel.tryLock() }.getOrNull()?.let { return it }
            if (wait) Thread.sleep(500)
        }
        channel.close()
        return null
    }

    /** Starts the in-app checks; call once, when the window opens. */
    fun onLaunch() {
        lock = takeLock(wait = true)
        platform.desktopConnectivity.start(scope)
        scope.launch {
            attempt { Background.pruneTrips(container) }
            syncTimer()
        }
        // Every minute: alerts when due, and the reminders at their times.
        scope.launch {
            while (true) {
                attempt { work(force = false) }
                kotlinx.coroutines.withTimeoutOrNull(60_000) { checks.first() }
            }
        }
        // Settings that change what the timer needs to do.
        scope.launch {
            container.settings.settings.map { listOf(it.alertsEnabled, it.briefingEnabled, it.tonightEnabled) }.distinctUntilChanged()
                .collect { syncTimer() }
        }
    }

    fun onExit() {
        runCatching { lock?.release(); lock?.channel()?.close() }
    }

    fun checkSoon() {
        scope.launch { attempt { work(force = true) } }
    }

    /** One headless pass for the systemd timer; returns quietly if the app is open. */
    fun backgroundCheck() {
        val held = takeLock(wait = false) ?: return
        try {
            runBlocking { attempt { work(force = false) } }
        } finally {
            runCatching { held.release(); held.channel().close() }
        }
    }

    private val stateFile get() = File(DesktopDirs.data, "reminders.json")

    private fun readState(): ReminderState =
        runCatching { NorthvaneJson.decodeFromString(ReminderState.serializer(), stateFile.readText()) }.getOrDefault(ReminderState())

    private fun writeState(s: ReminderState) {
        runCatching {
            val tmp = File(stateFile.parentFile, stateFile.name + ".tmp")
            tmp.writeText(NorthvaneJson.encodeToString(ReminderState.serializer(), s))
            java.nio.file.Files.move(tmp.toPath(), stateFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private suspend fun work(force: Boolean) {
        val settings = container.settings.settings.first()
        var state = readState()
        val nowMs = System.currentTimeMillis()
        // Alerts every few minutes, as chosen in Settings (the timer itself runs every 15).
        if (force || nowMs - state.alertsCheckedAt >= (settings.alertCheckMinutes - 1) * 60_000L) {
            Background.checkAlerts(container, DesktopNotifications.canNotify()).forEach { DesktopNotifications.show(it) }
            state = state.copy(alertsCheckedAt = nowMs)
        }
        // The morning briefing and Tonight: once a day, within two hours after their time.
        val zone = ZoneId.systemDefault()
        val now = kotlin.time.Clock.System.now()
        val local = now.atZone(zone)
        val today = local.toLocalDate().toString()
        val minute = local.hour * 60 + local.minute
        fun due(at: Int, sent: String?) = sent != today && minute >= at && minute < at + 120
        if (settings.briefingEnabled && due(settings.briefingMinutes, state.briefing)) {
            Background.todayPlace(container, settings, refresh = true)?.let { (location, snapshot) ->
                DesktopNotifications.show(Background.briefing(location, snapshot, settings, desktop24Hour(), now))
                state = state.copy(briefing = today)
            }
        }
        if (settings.tonightEnabled && due(settings.tonightMinutes, state.tonight)) {
            val todayDate = LocalDate.now(zone)
            for (location in container.locations.locations.first().filter { it.alertsWanted(todayDate) }.take(5)) {
                val snapshot = container.weatherCache.read(location.id) ?: continue
                Background.tonight(location, snapshot, settings, desktop24Hour(), now)?.let(DesktopNotifications::show)
            }
            state = state.copy(tonight = today)
        }
        writeState(state)
    }

    private val unitDir get() = File(System.getenv("XDG_CONFIG_HOME")?.takeIf { it.startsWith("/") } ?: (System.getProperty("user.home") + "/.config"), "systemd/user")

    /**
     * Installs (or removes) the systemd user timer that checks alerts and sends
     * the reminders while the app is closed. Only for the installed app: a
     * development run has no stable launcher to point it at.
     */
    fun syncTimer() {
        val exe = launcher ?: return
        if (!installed("systemctl")) return
        scope.launch(Dispatchers.IO) {
            val s = container.settings.settings.first()
            val wanted = s.alertsEnabled || s.briefingEnabled || s.tonightEnabled
            val service = File(unitDir, "$TIMER.service")
            val timer = File(unitDir, "$TIMER.timer")
            if (wanted) {
                unitDir.mkdirs()
                val serviceText = """
                    |[Unit]
                    |Description=SuperAwesomeMagicalWeather: weather alerts and reminders
                    |# Skipped quietly once the app is uninstalled.
                    |ConditionPathExists=$exe
                    |
                    |[Service]
                    |Type=oneshot
                    |ExecStart="$exe" --background-check
                    |Nice=10
                    |""".trimMargin()
                val timerText = """
                    |[Unit]
                    |Description=SuperAwesomeMagicalWeather: check every 15 minutes
                    |
                    |[Timer]
                    |OnStartupSec=2min
                    |OnUnitActiveSec=15min
                    |AccuracySec=1min
                    |
                    |[Install]
                    |WantedBy=timers.target
                    |""".trimMargin()
                if (!service.exists() || service.readText() != serviceText || !timer.exists() || timer.readText() != timerText) {
                    service.writeText(serviceText)
                    timer.writeText(timerText)
                    run(listOf("systemctl", "--user", "daemon-reload"))
                }
                run(listOf("systemctl", "--user", "enable", "--now", "$TIMER.timer"))
            } else if (timer.exists()) {
                run(listOf("systemctl", "--user", "disable", "--now", "$TIMER.timer"))
                timer.delete()
                service.delete()
                run(listOf("systemctl", "--user", "daemon-reload"))
            }
        }
    }

    @Composable
    fun Content() {
        val vm: MainViewModel = viewModel(factory = MainViewModel.factory(container))
        val state by vm.state.collectAsStateWithLifecycle()
        LaunchedEffect(vm) {
            vm.onForeground()
            foregrounds.collect { vm.onForeground() }
        }
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
