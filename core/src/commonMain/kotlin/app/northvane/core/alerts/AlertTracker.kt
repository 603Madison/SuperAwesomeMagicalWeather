package app.northvane.core.alerts

import app.northvane.core.model.AlertCategory
import app.northvane.core.model.WeatherAlert
import kotlinx.serialization.Serializable
import app.northvane.core.time.*

/** Which alert messages have already produced a notification, so background checks only notify once. */
@Serializable
data class NotifiedAlerts(
    /** "locationId|alertId" -> epoch millis when notified. */
    val entries: Map<String, Long> = emptyMap(),
)

data class AlertToNotify(
    val locationId: String,
    val alert: WeatherAlert,
    /** True when this is an update of an alert the user was already notified about. */
    val isUpdateOfNotified: Boolean,
)

class AlertTracker(private val retention: Duration = Duration.ofDays(10)) {

    fun select(
        locationId: String,
        alerts: List<WeatherAlert>,
        enabled: Set<AlertCategory>,
        state: NotifiedAlerts,
        now: Instant,
        /** Extra alerts to notify regardless of category (e.g. frost advisories for gardeners). */
        alsoNotify: (WeatherAlert) -> Boolean = { false },
    ): Pair<List<AlertToNotify>, NotifiedAlerts> {
        val cutoff = now.minus(retention).toEpochMilli()
        val entries = state.entries.filterValues { it >= cutoff }.toMutableMap()
        val notifiedThreads = entries.keys.mapNotNull { key ->
            key.split('|', limit = 3).takeIf { it.size == 3 && it[0] == locationId }?.get(2)
        }.toSet()

        val toNotify = mutableListOf<AlertToNotify>()
        for (alert in alerts) {
            if (alert.category !in enabled && !alsoNotify(alert)) continue
            val key = key(locationId, alert)
            if (key in entries) continue
            entries[key] = now.toEpochMilli()
            val expired = (alert.ends ?: alert.expires)?.isBefore(now) == true
            if (expired) continue
            toNotify += AlertToNotify(locationId, alert, isUpdateOfNotified = alert.threadId in notifiedThreads)
        }
        return toNotify to NotifiedAlerts(entries)
    }

    /** Includes the thread id so updates can be recognized as such later. */
    private fun key(locationId: String, alert: WeatherAlert) = "$locationId|${alert.id}|${alert.threadId}"
}
