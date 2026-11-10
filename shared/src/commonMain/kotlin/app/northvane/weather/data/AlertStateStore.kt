package app.northvane.weather.data

import app.northvane.core.alerts.NotifiedAlerts
import app.northvane.core.json.NorthvaneJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Remembers which alerts have already produced a notification. */
class AlertStateStore(private val files: TextFiles) {
    private val file = files.path("notified_alerts.json")
    private val mutex = Mutex()

    /**
     * Runs one alert check (read, fetch, notify, write) at a time, so two
     * overlapping checks can't notify the same alert twice or undo each
     * other's record.
     */
    suspend fun <T> exclusive(block: suspend () -> T): T = mutex.withLock { block() }

    suspend fun read(): NotifiedAlerts = withContext(Dispatchers.Default) {
        val text = files.read(file) ?: return@withContext NotifiedAlerts()
        runCatching { NorthvaneJson.decodeFromString(NotifiedAlerts.serializer(), text) }
            .getOrDefault(NotifiedAlerts())
    }

    suspend fun write(state: NotifiedAlerts) = withContext(Dispatchers.Default) {
        files.write(file, NorthvaneJson.encodeToString(NotifiedAlerts.serializer(), state))
    }
}
