package app.northvane.weather.data

import app.northvane.core.alerts.NotifiedAlerts
import app.northvane.core.json.NorthvaneJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Remembers which alerts have already produced a notification. */
class AlertStateStore(private val files: TextFiles) {
    private val file = files.path("notified_alerts.json")

    suspend fun read(): NotifiedAlerts = withContext(Dispatchers.Default) {
        val text = files.read(file) ?: return@withContext NotifiedAlerts()
        runCatching { NorthvaneJson.decodeFromString(NotifiedAlerts.serializer(), text) }
            .getOrDefault(NotifiedAlerts())
    }

    suspend fun write(state: NotifiedAlerts) = withContext(Dispatchers.Default) {
        files.write(file, NorthvaneJson.encodeToString(NotifiedAlerts.serializer(), state))
    }
}
