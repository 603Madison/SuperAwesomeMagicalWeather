package app.northvane.weather.data

import android.content.Context
import app.northvane.core.json.NorthvaneJson
import app.northvane.core.model.WeatherAlert
import app.northvane.core.model.WeatherSnapshot
import app.northvane.core.repo.CachedNwsPoint
import app.northvane.core.repo.NwsPointCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

/**
 * Last-known weather per location, so the app opens instantly and still shows
 * something useful offline or on a slow satellite link. Stored in
 * noBackupFilesDir, which Android never includes in backups.
 */
class WeatherCache(context: Context) {
    private val dir = File(context.applicationContext.noBackupFilesDir, "weather")
    private val mutex = Mutex()

    private fun fileFor(locationId: String): File {
        val safe = locationId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(64)
        require(safe.isNotEmpty()) { "Invalid location id" }
        return File(dir, "$safe.json")
    }

    suspend fun read(locationId: String): WeatherSnapshot? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val file = fileFor(locationId)
            if (!file.exists()) return@withLock null
            runCatching { NorthvaneJson.decodeFromString(WeatherSnapshot.serializer(), file.readText()) }.getOrNull()
        }
    }

    suspend fun write(snapshot: WeatherSnapshot) = withContext(Dispatchers.IO) {
        mutex.withLock {
            writeLocked(snapshot)
            recordForecast(snapshot)
        }
    }

    private val recordList = kotlinx.serialization.builtins.ListSerializer(app.northvane.core.outlook.ForecastRecord.serializer())

    private fun logFile(locationId: String) = File(dir, fileFor(locationId).nameWithoutExtension + ".forecasts.json")

    /** What the forecast said about recent days, for the accuracy line (about 1 KB per place). */
    suspend fun forecastLog(locationId: String): List<app.northvane.core.outlook.ForecastRecord> = withContext(Dispatchers.IO) {
        mutex.withLock { readLog(locationId) }
    }

    private fun readLog(locationId: String) =
        runCatching { NorthvaneJson.decodeFromString(recordList, logFile(locationId).readText()) }.getOrDefault(emptyList())

    /** Call with [mutex] held. */
    private fun recordForecast(snapshot: WeatherSnapshot) {
        runCatching {
            val zone = java.time.ZoneId.of(snapshot.timeZoneId)
            val today = java.time.LocalDate.now(zone)
            val log = app.northvane.core.outlook.ForecastAccuracy.record(readLog(snapshot.locationId), snapshot, zone, today)
            logFile(snapshot.locationId).writeText(NorthvaneJson.encodeToString(recordList, log))
        }
    }

    /** Call with [mutex] held. */
    private fun writeLocked(snapshot: WeatherSnapshot) {
        dir.mkdirs()
        val target = fileFor(snapshot.locationId)
        val tmp = File(dir, target.name + ".tmp")
        tmp.writeText(NorthvaneJson.encodeToString(WeatherSnapshot.serializer(), snapshot))
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    /** Refresh just the alerts of a cached snapshot (used by the background alert check). */
    suspend fun updateAlerts(locationId: String, alerts: List<WeatherAlert>) {
        // One read-modify-write under the lock, so a foreground refresh written meanwhile isn't overwritten.
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val file = fileFor(locationId)
                val existing = runCatching { NorthvaneJson.decodeFromString(WeatherSnapshot.serializer(), file.readText()) }.getOrNull()
                    ?: return@withLock
                writeLocked(existing.copy(alerts = alerts))
            }
        }
    }

    suspend fun delete(locationId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            fileFor(locationId).delete()
            logFile(locationId).delete()
        }
    }

    val pointCache: NwsPointCache = object : NwsPointCache {
        private val file = File(dir, "nws_points.json")
        private val serializer = MapSerializer(String.serializer(), CachedNwsPoint.serializer())
        private val lock = Mutex()

        private fun load(): Map<String, CachedNwsPoint> =
            if (file.exists()) runCatching { NorthvaneJson.decodeFromString(serializer, file.readText()) }.getOrDefault(emptyMap())
            else emptyMap()

        override suspend fun get(key: String): CachedNwsPoint? = withContext(Dispatchers.IO) {
            lock.withLock { load()[key] }
        }

        override suspend fun put(key: String, value: CachedNwsPoint) = withContext(Dispatchers.IO) {
            lock.withLock {
                dir.mkdirs()
                // Keep the file small: most recent 50 points.
                val updated = (load() + (key to value)).entries
                    .sortedByDescending { it.value.savedAtEpochMs }
                    .take(50)
                    .associate { it.key to it.value }
                file.writeText(NorthvaneJson.encodeToString(serializer, updated))
            }
        }
    }
}
