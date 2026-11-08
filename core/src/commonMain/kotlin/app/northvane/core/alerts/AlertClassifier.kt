package app.northvane.core.alerts

import app.northvane.core.model.AlertCategory
import app.northvane.core.model.AlertSeverity
import app.northvane.core.model.WeatherAlert

object AlertClassifier {
    /** NWS event names end with the product type ("Tornado Warning", "Wind Advisory"). */
    fun categorize(event: String): AlertCategory {
        val e = event.trim().lowercase()
        return when {
            e.endsWith("warning") || e.endsWith("emergency") || e == "evacuation immediate" ||
                e == "civil danger warning" -> AlertCategory.WARNING
            e.endsWith("watch") -> AlertCategory.WATCH
            e.endsWith("advisory") -> AlertCategory.ADVISORY
            e.endsWith("statement") || e.endsWith("outlook") || e.endsWith("message") ||
                e.endsWith("forecast") -> AlertCategory.STATEMENT
            else -> AlertCategory.OTHER
        }
    }

    private fun categoryRank(c: AlertCategory) = when (c) {
        AlertCategory.WARNING -> 0
        AlertCategory.WATCH -> 1
        AlertCategory.ADVISORY -> 2
        AlertCategory.STATEMENT -> 3
        AlertCategory.OTHER -> 4
    }

    val displayOrder: Comparator<WeatherAlert> =
        compareBy<WeatherAlert> { categoryRank(it.category) }
            .thenBy { it.severity.ordinal }
            .thenBy { it.onset ?: it.effective }

    /** Highest-priority alert, used for the banner on the main screen. */
    fun mostUrgent(alerts: List<WeatherAlert>): WeatherAlert? = alerts.minWithOrNull(displayOrder)

    fun isHighPriority(alert: WeatherAlert): Boolean =
        alert.category == AlertCategory.WARNING ||
            alert.severity == AlertSeverity.EXTREME || alert.severity == AlertSeverity.SEVERE
}
