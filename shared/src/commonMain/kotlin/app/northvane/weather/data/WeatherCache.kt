package app.northvane.weather.data

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
import okio.Path
import app.northvane.core.time.*

/**
 * Last-known weather per location, so the app opens instantly and still shows
 * something useful offline or on a slow satellite link. Stored in
 * noBackupFilesDir, which Android never includes in backups.
 */
class WeatherCache(private val files: TextFiles) {
    private val mutex = Mutex()

    private fun safeName(locationId: String): String {
        val safe = locationId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(64)
        require(safe.isNotEmpty()) { "Invalid location id" }
        return safe
    }

    private fun fileFor(locationId: String): Path = files.path("weather", "${safeName(locationId)}.json")

    suspend fun read(locationId: String): WeatherSnapshot? = withContext(Dispatchers.Default) {
        mutex.withLock {
            val text = files.read(fileFor(locationId)) ?: return@withLock null
            runCatching { NorthvaneJson.decodeFromString(WeatherSnapshot.serializer(), text) }.getOrNull()
        }
    }

    suspend fun write(snapshot: WeatherSnapshot) = withContext(Dispatchers.Default) {
        mutex.withLock {
            writeLocked(snapshot)
            recordForecast(snapshot)
        }
    }

    private val recordList = kotlinx.serialization.builtins.ListSerializer(app.northvane.core.outlook.ForecastRecord.serializer())

    private fun logFile(locationId: String) = files.path("weather", "${safeName(locationId)}.forecasts.json")

    /** What the forecast said about recent days and the rain that fell, for the accuracy line and "did it rain?" (about 6 KB per place). */
    suspend fun forecastLog(locationId: String): List<app.northvane.core.outlook.ForecastRecord> = withContext(Dispatchers.Default) {
        mutex.withLock { readLog(locationId) }
    }

    private fun readLog(locationId: String) =
        runCatching { NorthvaneJson.decodeFromString(recordList, files.read(logFile(locationId))!!) }.getOrDefault(emptyList())

    /** Call with [mutex] held. */
    private fun recordForecast(snapshot: WeatherSnapshot) {
        runCatching {
            val zone = app.northvane.core.time.ZoneId.of(snapshot.timeZoneId)
            val today = app.northvane.core.time.LocalDate.now(zone)
            val log = app.northvane.core.outlook.ForecastAccuracy.record(readLog(snapshot.locationId), snapshot, zone, today)
            files.write(logFile(snapshot.locationId), NorthvaneJson.encodeToString(recordList, log))
        }
    }

    /** Call with [mutex] held. */
    private fun writeLocked(snapshot: WeatherSnapshot) {
        files.write(fileFor(snapshot.locationId), NorthvaneJson.encodeToString(WeatherSnapshot.serializer(), snapshot))
    }

    /** Refresh just the alerts of a cached snapshot (used by the background alert check). */
    suspend fun updateAlerts(locationId: String, alerts: List<WeatherAlert>) {
        // One read-modify-write under the lock, so a foreground refresh written meanwhile isn't overwritten.
        withContext(Dispatchers.Default) {
            mutex.withLock {
                val existing = runCatching { NorthvaneJson.decodeFromString(WeatherSnapshot.serializer(), files.read(fileFor(locationId))!!) }.getOrNull()
                    ?: return@withLock
                writeLocked(existing.copy(alerts = alerts))
            }
        }
    }

    suspend fun delete(locationId: String) = withContext(Dispatchers.Default) {
        mutex.withLock {
            files.delete(fileFor(locationId))
            files.delete(logFile(locationId))
        }
    }

    val pointCache: NwsPointCache = object : NwsPointCache {
        private val file = files.path("weather", "nws_points.json")
        private val serializer = MapSerializer(String.serializer(), CachedNwsPoint.serializer())
        private val lock = Mutex()

        private fun load(): Map<String, CachedNwsPoint> =
            files.read(file)?.let { runCatching { NorthvaneJson.decodeFromString(serializer, it) }.getOrNull() } ?: emptyMap()

        override suspend fun get(key: String): CachedNwsPoint? = withContext(Dispatchers.Default) {
            lock.withLock { load()[key] }
        }

        override suspend fun put(key: String, value: CachedNwsPoint) = withContext(Dispatchers.Default) {
            lock.withLock {
                // Keep the file small: most recent 50 points.
                val updated = (load() + (key to value)).entries
                    .sortedByDescending { it.value.savedAtEpochMs }
                    .take(50)
                    .associate { it.key to it.value }
                files.write(file, NorthvaneJson.encodeToString(serializer, updated))
            }
        }
    }
}
