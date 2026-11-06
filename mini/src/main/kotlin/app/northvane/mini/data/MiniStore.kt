package app.northvane.mini.data

import android.content.Context
import app.northvane.core.alerts.NotifiedAlerts
import app.northvane.core.json.NorthvaneJson
import app.northvane.core.model.SavedLocation
import app.northvane.core.model.UnitSystem
import app.northvane.core.model.WeatherSnapshot
import app.northvane.core.repo.CachedNwsPoint
import app.northvane.core.repo.NwsPointCache
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
import android.util.AtomicFile
import java.io.File
import java.util.Locale

@Serializable
enum class MiniTheme(val label: String) { DARK("Dark"), LIGHT("Light"), SYSTEM("Match phone") }

/** Everything Mini remembers, in one small file. */
@Serializable
data class MiniSettings(
    val places: List<SavedLocation> = emptyList(),
    val selectedId: String? = null,
    val units: UnitSystem = if (Locale.getDefault().country in setOf("US", "LR", "MM")) UnitSystem.IMPERIAL else UnitSystem.METRIC,
    val theme: MiniTheme = MiniTheme.DARK,
    val alertsEnabled: Boolean = true,
    val alertCheckMinutes: Int = 30,
    val notified: NotifiedAlerts = NotifiedAlerts(),
    /** Android 13+: whether Mini has asked for permission to post notifications yet. */
    val notifyAsked: Boolean = false,
) {
    val selected: SavedLocation? get() = places.firstOrNull { it.id == selectedId } ?: places.firstOrNull()
}

/**
 * Settings, places and cached forecasts as JSON files in no-backup storage.
 * Writes are atomic and synced, so a crash or power loss can't leave half a file.
 */
class MiniStore(context: Context) {
    private val dir = File(context.applicationContext.noBackupFilesDir, "mini").apply { mkdirs() }
    private val mutex = Mutex()
    private val _settings = MutableStateFlow(MiniSettings())
    val settings: StateFlow<MiniSettings> = _settings.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val file = File(dir, "settings.json")
            val loaded = read(file, MiniSettings.serializer())
            if (loaded == null && file.exists()) {
                // Unreadable (damaged) settings: keep a copy instead of silently overwriting the user's places.
                runCatching { file.copyTo(File(dir, "settings.json.bad"), overwrite = true) }
            }
            _settings.value = loaded ?: MiniSettings()
        }
    }

    suspend fun update(change: (MiniSettings) -> MiniSettings) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val next = change(_settings.value)
            write(File(dir, "settings.json"), MiniSettings.serializer(), next)
            _settings.value = next
        }
    }

    suspend fun snapshot(id: String): WeatherSnapshot? = withContext(Dispatchers.IO) {
        mutex.withLock { read(snapshotFile(id), WeatherSnapshot.serializer()) }
    }

    suspend fun saveSnapshot(snapshot: WeatherSnapshot) = withContext(Dispatchers.IO) {
        mutex.withLock { write(snapshotFile(snapshot.locationId), WeatherSnapshot.serializer(), snapshot) }
    }

    suspend fun deleteSnapshot(id: String) = withContext(Dispatchers.IO) { mutex.withLock { snapshotFile(id).delete() } }

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

    private fun snapshotFile(id: String): File {
        val safe = id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(64)
        require(safe.isNotEmpty()) { "Invalid place id" }
        return File(dir, "snapshot-$safe.json")
    }

    private fun <T> read(file: File, serializer: KSerializer<T>): T? {
        val atomic = AtomicFile(file)
        if (!file.exists() && !atomic.baseFile.exists()) return null
        return runCatching { NorthvaneJson.decodeFromString(serializer, atomic.readFully().decodeToString()) }.getOrNull()
    }

    /**
     * Writes through [AtomicFile] (synced to disk before it replaces the old
     * file). A failed write, such as a full disk on a small phone, keeps the
     * old file and doesn't crash the app; the value stays in memory.
     */
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
