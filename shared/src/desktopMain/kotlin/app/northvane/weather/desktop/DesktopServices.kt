package app.northvane.weather.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import app.northvane.core.json.NorthvaneJson
import app.northvane.core.model.GeoPoint
import app.northvane.core.net.Request
import app.northvane.core.net.fetch
import app.northvane.core.owm.OwmClient
import app.northvane.core.owm.PlaceResult
import app.northvane.weather.DeviceLocation
import app.northvane.weather.Notifications
import app.northvane.weather.PlaceSearch
import app.northvane.weather.SecretStore
import app.northvane.weather.ThemeBackground
import app.northvane.weather.data.SettingsRepository
import app.northvane.weather.net.Connectivity
import app.northvane.weather.net.NetworkStatus
import app.northvane.weather.work.AppNotification
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Where the app keeps its files, following the XDG base directory spec. */
object DesktopDirs {
    const val APP = "superawesomemagicalweather"

    private fun xdg(variable: String, fallback: String): File =
        File(System.getenv(variable)?.takeIf { it.startsWith("/") } ?: (System.getProperty("user.home") + "/" + fallback), APP)

    /** Settings and places (~/.config/superawesomemagicalweather). */
    val config: File by lazy { private(xdg("XDG_CONFIG_HOME", ".config")) }

    /** Cached forecasts, the forecast log and the background photo (~/.local/share/...). */
    val data: File by lazy { private(xdg("XDG_DATA_HOME", ".local/share")) }

    /** The HTTP cache (~/.cache/...). */
    val cache: File by lazy { private(xdg("XDG_CACHE_HOME", ".cache")) }

    /** A directory only this user can read. */
    fun private(dir: File): File {
        dir.mkdirs()
        runCatching { Files.setPosixFilePermissions(dir.toPath(), PosixFilePermissions.fromString("rwx------")) }
        return dir
    }
}

internal class Ran(val exit: Int, val out: String, val err: String)

/** Runs [command] with [input] on stdin (never through a shell); null if it couldn't start or timed out. */
internal fun runProgram(command: List<String>, input: String? = null, timeoutSeconds: Long = 10): Ran? = runCatching {
    val process = ProcessBuilder(command).start()
    process.outputStream.use { out -> input?.let { out.write(it.toByteArray()) } }
    val err = StringBuilder()
    val errReader = Thread { err.append(process.errorStream.bufferedReader().readText()) }.apply { isDaemon = true; start() }
    val output = process.inputStream.bufferedReader().readText()
    if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        return null
    }
    errReader.join(1000)
    Ran(process.exitValue(), output, err.toString())
}.getOrNull()

/** [command]'s stdout when it succeeded, else null. */
internal fun run(command: List<String>, input: String? = null, timeoutSeconds: Long = 10): String? =
    runProgram(command, input, timeoutSeconds)?.takeIf { it.exit == 0 }?.out

internal fun installed(program: String): Boolean =
    System.getenv("PATH").orEmpty().split(':').any { File(it, program).canExecute() }

/**
 * API keys and passwords in the desktop's keyring (GNOME Keyring or KWallet,
 * through the Secret Service and `secret-tool`), which keeps them encrypted
 * and locked with the login. Where there's no `secret-tool`, a file only this
 * user can read, in the app's private data folder.
 */
class DesktopSecretStore(private val fallbackFile: File) : SecretStore {
    // A lookup that finds nothing exits 1 quietly; without a running keyring it complains on stderr.
    private val keyring: Boolean by lazy {
        installed("secret-tool") &&
            runProgram(listOf("secret-tool", "lookup", "application", DesktopDirs.APP, "name", "probe"))?.err?.isBlank() == true
    }

    /** Where keys are kept, for Settings and the docs. */
    val inKeyring: Boolean get() = keyring
    private val lock = Any()

    override fun get(name: String): String? = synchronized(lock) {
        // secret-tool prints the secret without a trailing newline.
        if (keyring) run(listOf("secret-tool", "lookup", "application", DesktopDirs.APP, "name", name))?.takeIf { it.isNotEmpty() }
        else readFile()[name]
    }

    override fun has(name: String): Boolean = get(name) != null

    override fun put(name: String, value: String?) = synchronized(lock) {
        if (keyring) {
            if (value == null) {
                run(listOf("secret-tool", "clear", "application", DesktopDirs.APP, "name", name))
            } else {
                run(listOf("secret-tool", "store", "--label=SuperAwesomeMagicalWeather: $name", "application", DesktopDirs.APP, "name", name), input = value)
            }
            Unit
        } else {
            val map = readFile().toMutableMap()
            if (value == null) map.remove(name) else map[name] = value
            writeFile(map)
        }
    }

    private val serializer = kotlinx.serialization.builtins.MapSerializer(String.serializer(), String.serializer())

    private fun readFile(): Map<String, String> =
        runCatching { NorthvaneJson.decodeFromString(serializer, fallbackFile.readText()) }.getOrDefault(emptyMap())

    private fun writeFile(map: Map<String, String>) {
        DesktopDirs.private(fallbackFile.parentFile)
        val tmp = File(fallbackFile.parentFile, fallbackFile.name + ".tmp")
        tmp.writeText("")
        runCatching { Files.setPosixFilePermissions(tmp.toPath(), PosixFilePermissions.fromString("rw-------")) }
        tmp.writeText(NorthvaneJson.encodeToString(serializer, map))
        Files.move(tmp.toPath(), fallbackFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }
}

/**
 * A computer is online or not; there's no metered or satellite link to
 * report. Checked every half minute from the network interfaces.
 */
class DesktopConnectivity : Connectivity {
    private val _status = MutableStateFlow(NetworkStatus(connected = check()))
    override val status: StateFlow<NetworkStatus> = _status.asStateFlow()
    override fun current(): NetworkStatus = NetworkStatus(connected = check()).also { _status.value = it }

    private fun check(): Boolean = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList().any { it.isUp && !it.isLoopback && it.inetAddresses.hasMoreElements() }
    }.getOrDefault(true)

    fun start(scope: kotlinx.coroutines.CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            while (true) {
                current()
                kotlinx.coroutines.delay(30_000)
            }
        }
    }
}

/** Computers have no location service the app can rely on; places are searched for instead. */
object NoDeviceLocation : DeviceLocation {
    override fun hasPermission(): Boolean = false
    override suspend fun current(): GeoPoint? = null
}

/** The custom background photo, kept in the private data folder. */
class DesktopThemeBackground(private val dir: File) : ThemeBackground {
    private val file get() = File(dir, "background")

    override suspend fun load(): ImageBitmap? = withContext(Dispatchers.IO) {
        runCatching { org.jetbrains.skia.Image.makeFromEncoded(file.readBytes()).toComposeImageBitmap() }.getOrNull()
    }

    override suspend fun save(bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        // Only something Skia can read as an image is kept.
        runCatching { org.jetbrains.skia.Image.makeFromEncoded(bytes) }.getOrNull() ?: return@withContext false
        runCatching {
            DesktopDirs.private(dir)
            file.writeBytes(bytes)
            true
        }.getOrDefault(false)
    }

    override fun clear() {
        file.delete()
    }
}

/**
 * Desktop notifications through `notify-send` (libnotify), which GNOME, KDE
 * and the other Linux desktops all show. Warnings are sent as critical, so
 * they stay until dismissed.
 */
object DesktopNotifications : Notifications {
    override fun canNotify(): Boolean = installed("notify-send")

    fun show(n: AppNotification): Boolean {
        if (!canNotify()) return false
        val urgency = when (n.kind) {
            AppNotification.Kind.WARNING -> "critical"
            AppNotification.Kind.ALERT -> if (n.quiet) "low" else "normal"
            else -> "normal"
        }
        val icon = DesktopApp.iconPath ?: "weather-severe-alert"
        return run(listOf("notify-send", "--app-name=SuperAwesomeMagicalWeather", "--urgency=$urgency", "--icon=$icon", n.title, n.text)) != null
    }

    override fun sendTest(locationId: String?, place: String?): Boolean = show(
        AppNotification("test", "Test notification", "Alerts${place?.let { " for $it" } ?: ""} will look like this.", locationId, AppNotification.Kind.ALERT),
    )
}

/**
 * Place search by name or ZIP/postal code through Open-Meteo's free geocoder
 * (as Mini uses), coordinates typed as "lat, lon", and OpenWeatherMap's
 * geocoder when the user has a key for it.
 */
class DesktopPlaceSearch(
    private val http: OkHttpClient,
    private val owm: OwmClient,
    private val secrets: SecretStore,
    private val settings: SettingsRepository,
) : PlaceSearch {
    private val coordinates = Regex("""^\s*(-?\d{1,2}(?:\.\d+)?)\s*[, ]\s*(-?\d{1,3}(?:\.\d+)?)\s*$""")

    override suspend fun search(query: String): List<PlaceResult> {
        val q = query.trim().take(100)
        if (q.length < 2) return emptyList()
        coordinates.matchEntire(q)?.let { m ->
            val lat = m.groupValues[1].toDouble()
            val lon = m.groupValues[2].toDouble()
            if (lat in -90.0..90.0 && lon in -180.0..180.0) {
                fun t(v: Double) = app.northvane.core.util.plainDecimal(v, 4)
                return listOf(PlaceResult("${t(lat)}, ${t(lon)}", lat, lon))
            }
        }
        val found = runCatching { openMeteo(q) }.getOrDefault(emptyList())
        if (found.isNotEmpty()) return found
        val key = if (settings.settings.first().owmEnabled) secrets.get(SecretStore.OWM_API_KEY) else null
        return if (key != null) runCatching { owm.geocode(q, key) }.getOrDefault(emptyList()) else emptyList()
    }

    private suspend fun openMeteo(q: String): List<PlaceResult> {
        val url = "https://geocoding-api.open-meteo.com/v1/search".toHttpUrl().newBuilder()
            .addQueryParameter("name", q)
            .addQueryParameter("count", "10")
            .addQueryParameter("language", Locale.getDefault().language.ifBlank { "en" })
            .addQueryParameter("format", "json")
            .build()
        val response = http.fetch(Request.Builder().url(url.toString()).build(), maxBytes = 256L * 1024)
        if (response.code !in 200..299) return emptyList()
        val results = ((NorthvaneJson.parseToJsonElement(response.body) as? JsonObject)?.get("results") as? JsonArray).orEmpty()
        fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
        fun JsonObject.number(key: String) = (this[key] as? JsonPrimitive)?.doubleOrNull
        return results.mapNotNull { it as? JsonObject }.mapNotNull { o ->
            val lat = o.number("latitude")?.takeIf { it in -90.0..90.0 } ?: return@mapNotNull null
            val lon = o.number("longitude")?.takeIf { it in -180.0..180.0 } ?: return@mapNotNull null
            val name = o.text("name") ?: return@mapNotNull null
            val region = listOfNotNull(o.text("admin1"), o.text("country_code")).joinToString(", ")
            Triple(PlaceResult(listOf(name, region).filter { it.isNotBlank() }.joinToString(", "), lat, lon), region, o.text("country_code"))
        }
            // NWS and Environment Canada cover the US and Canada best: list those first.
            .sortedBy { if (it.third == "US" || it.third == "CA") 0 else 1 }
            .map { it.first }
    }

    /** Open-Meteo has no reverse geocoding; EPA's UV forecast is skipped without a ZIP code. */
    override suspend fun postalCode(latitude: Double, longitude: Double): String? = null
}
