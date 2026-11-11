package app.northvane.wear.data

import android.content.Context
import android.util.AtomicFile
import app.northvane.core.alerts.NotifiedAlerts
import app.northvane.core.json.NorthvaneJson
import app.northvane.core.model.SavedLocation
import app.northvane.core.model.UnitSystem
import app.northvane.core.model.WeatherSnapshot
import app.northvane.core.outlook.MinutePrecip
import app.northvane.core.repo.CachedNwsPoint
import app.northvane.core.repo.NwsPointCache
import app.northvane.core.time.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.util.Locale

/** Everything the watch app remembers, in one small file. */
@Serializable
data class WearSettings(
    val places: List<SavedLocation> = emptyList(),
    /** The place the app shows. */
    val selectedId: String? = null,
    /** The place the tile and complication show; null follows the app. */
    val tilePlaceId: String? = null,
    // Same default as Mini: Fahrenheit where people use it, Celsius elsewhere.
    val units: UnitSystem = if (Locale.getDefault().country in setOf("US", "LR", "MM")) UnitSystem.IMPERIAL else UnitSystem.METRIC,
    /** The 15-minute rain forecast for the next hour (about 1 KB more per refresh). */
    val nextHourRain: Boolean = true,
    /** Notifications for new warnings, watches and advisories at the tile's place. */
    val alertsEnabled: Boolean = true,
    /** Refreshes the tile's place now and then, for the tile and complication. */
    val backgroundUpdates: Boolean = true,
    val notified: NotifiedAlerts = NotifiedAlerts(),
    /** Whether the app has asked for permission to post notifications yet (Wear OS 4+). */
    val notifyAsked: Boolean = false,
) {
    val selected: SavedLocation? get() = places.firstOrNull { it.id == selectedId } ?: places.firstOrNull()
    val tilePlace: SavedLocation? get() = places.firstOrNull { it.id == tilePlaceId } ?: selected
    val deviceLocation: SavedLocation? get() = places.firstOrNull { it.isDeviceLocation }

    /** Removes a place, moving the app (and the tile, if it showed it) to another. */
    fun without(id: String): WearSettings {
        val remaining = places.filterNot { it.id == id }
        return copy(
            places = remaining,
            selectedId = if (selectedId == id) remaining.firstOrNull()?.id else selectedId,
            tilePlaceId = tilePlaceId.takeIf { it != id },
        )
    }
}

/** One step of the 15-minute rain forecast, as stored. */
@Serializable
data class RainStep(val epochSecond: Long, val precipitationMm: Double, val snowfallCm: Double) {
    fun toMinutePrecip() = MinutePrecip(Instant.ofEpochSecond(epochSecond), precipitationMm, snowfallCm)

    companion object {
        fun of(step: MinutePrecip) = RainStep(step.time.epochSecond, step.precipitationMm, step.snowfallCm)
    }
}

/** A place's forecast plus its next-hour rain, as the app, tile and complication read it. */
@Serializable
data class WearForecast(
    val snapshot: WeatherSnapshot,
    val rain: List<RainStep> = emptyList(),
) {
    val rainSteps: List<MinutePrecip> get() = rain.map { it.toMinutePrecip() }
}

/**
 * Settings and cached forecasts as JSON files in no-backup storage (the same
 * approach as Mini). Writes are atomic and synced, so a crash or a dead battery
 * can't leave half a file.
 */
class WearStore(context: Context) {
    private val dir = File(context.applicationContext.noBackupFilesDir, "wear").apply { mkdirs() }
    private val mutex = Mutex()
    private val _settings = MutableStateFlow(WearSettings())
    val settings: StateFlow<WearSettings> = _settings.asStateFlow()
    @Volatile private var loaded = false

    /** Reads the settings file once per process; every later change goes through [update]. */
    suspend fun ensureLoaded() {
        if (!loaded) load()
    }

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val file = File(dir, "settings.json")
            val read = read(file, WearSettings.serializer())
            if (read == null && file.exists()) {
                // Unreadable (damaged) settings: keep a copy instead of silently overwriting the user's places.
                runCatching { file.copyTo(File(dir, "settings.json.bad"), overwrite = true) }
            }
            _settings.value = read ?: WearSettings()
            loaded = true
        }
    }

    suspend fun update(change: (WearSettings) -> WearSettings) = withContext(Dispatchers.IO) {
        ensureLoaded()
        mutex.withLock {
            val next = change(_settings.value)
            write(File(dir, "settings.json"), WearSettings.serializer(), next)
            _settings.value = next
        }
    }

    suspend fun forecast(id: String): WearForecast? = withContext(Dispatchers.IO) {
        mutex.withLock { read(forecastFile(id), WearForecast.serializer()) }
    }

    suspend fun saveForecast(forecast: WearForecast) = withContext(Dispatchers.IO) {
        mutex.withLock { write(forecastFile(forecast.snapshot.locationId), WearForecast.serializer(), forecast) }
    }

    suspend fun deleteForecast(id: String) = withContext(Dispatchers.IO) { mutex.withLock { forecastFile(id).delete() } }

    /** NWS grid metadata per point, which almost never changes. */
    val pointCache = object : NwsPointCache {
        private val serializer = MapSerializer(String.serializer(), CachedNwsPoint.serializer())
        private val file = File(dir, "points.json")
        override suspend fun get(key: String): CachedNwsPoint? = withContext(Dispatchers.IO) {
            mutex.withLock { read(file, serializer)?.get(key) }
        }
        override suspend fun put(key: String, value: CachedNwsPoint) = withContext(Dispatchers.IO) {
            mutex.withLock {
                val all = (read(file, serializer).orEmpty() + (key to value)).entries.toList().takeLast(20).associate { it.key to it.value }
                write(file, serializer, all)
                Unit
            }
        }
    }

    private fun forecastFile(id: String): File {
        val safe = id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(64)
        require(safe.isNotEmpty()) { "Invalid place id" }
        return File(dir, "forecast-$safe.json")
    }

    private fun <T> read(file: File, serializer: KSerializer<T>): T? {
        val atomic = AtomicFile(file)
        // No exists() check first: after a crash mid-write the data may be only in AtomicFile's backup,
        // which readFully restores. A file that isn't there at all reads as null.
        return runCatching { NorthvaneJson.decodeFromString(serializer, atomic.readFully().decodeToString()) }.getOrNull()
    }

    /** A failed write (a full disk) keeps the old file and doesn't crash the app; the value stays in memory. */
    private fun <T> write(file: File, serializer: KSerializer<T>, value: T): Boolean {
        val atomic = AtomicFile(file)
        var out: java.io.FileOutputStream? = null
        return try {
            out = atomic.startWrite()
            out.write(NorthvaneJson.encodeToString(serializer, value).encodeToByteArray())
            atomic.finishWrite(out)
            true
        } catch (e: java.io.IOException) {
            out?.let { atomic.failWrite(it) }
            false
        }
    }
}
