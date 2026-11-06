package app.northvane.weather.notify

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.northvane.core.alerts.AlertToNotify
import app.northvane.core.model.AlertCategory
import app.northvane.core.model.AlertSeverity
import app.northvane.weather.MainActivity
import app.northvane.weather.R
import java.time.Duration
import java.time.Instant
import app.northvane.core.alerts.AlertAreas
import app.northvane.core.alerts.describe
import app.northvane.core.model.GeoPoint
import app.northvane.core.model.UnitSystem
import app.northvane.core.util.UnitFormatter

object AlertNotifier {
    const val CHANNEL_WARNINGS = "alerts_warnings"
    const val CHANNEL_WATCHES = "alerts_watches"
    const val CHANNEL_ADVISORIES = "alerts_advisories"
    const val CHANNEL_STATEMENTS = "alerts_statements"
    private const val GROUP = "northvane_alerts"
    private const val TAG = "nws_alert"

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channels = listOf(
            NotificationChannel(CHANNEL_WARNINGS, context.getString(R.string.channel_warnings), NotificationManager.IMPORTANCE_HIGH)
                .apply { description = context.getString(R.string.channel_warnings_desc); enableVibration(true) },
            NotificationChannel(CHANNEL_WATCHES, context.getString(R.string.channel_watches), NotificationManager.IMPORTANCE_HIGH)
                .apply { description = context.getString(R.string.channel_watches_desc) },
            NotificationChannel(CHANNEL_ADVISORIES, context.getString(R.string.channel_advisories), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = context.getString(R.string.channel_advisories_desc) },
            NotificationChannel(CHANNEL_STATEMENTS, context.getString(R.string.channel_statements), NotificationManager.IMPORTANCE_LOW)
                .apply { description = context.getString(R.string.channel_statements_desc) },
        )
        manager.createNotificationChannels(channels)
    }

    fun canNotify(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun channelFor(item: AlertToNotify): String = when {
        item.alert.category == AlertCategory.WARNING || item.alert.severity == AlertSeverity.EXTREME -> CHANNEL_WARNINGS
        item.alert.category == AlertCategory.WATCH -> CHANNEL_WATCHES
        item.alert.category == AlertCategory.ADVISORY -> CHANNEL_ADVISORIES
        else -> CHANNEL_STATEMENTS
    }

    private fun colorFor(category: AlertCategory): Int = when (category) {
        AlertCategory.WARNING -> 0xFFD32F2F.toInt()
        AlertCategory.WATCH -> 0xFFEF6C00.toInt()
        AlertCategory.ADVISORY -> 0xFFF9A825.toInt()
        else -> 0xFF1E88E5.toInt()
    }

    /** Posts a sample warning so the user can check sound, vibration and lock-screen behavior. */
    fun sendTest(context: Context, locationId: String?, locationName: String?): Boolean {
        if (!canNotify(context)) return false
        val now = Instant.now()
        val sample = app.northvane.core.model.WeatherAlert(
            id = "test-" + now.toEpochMilli(),
            threadId = "northvane-test",
            event = "Test Warning",
            headline = "This is a test notification from SuperAwesomeMagicalWeather. No action is needed.",
            description = null,
            instruction = "Real warnings from the National Weather Service will look like this.",
            severity = AlertSeverity.SEVERE,
            urgency = null,
            certainty = null,
            category = AlertCategory.WARNING,
            sent = now,
            ends = now.plus(Duration.ofMinutes(10)),
        )
        notify(context, listOf(AlertToNotify(locationId ?: "test", sample, isUpdateOfNotified = false)), mapOfNotNull(locationId, locationName))
        return true
    }

    private fun mapOfNotNull(id: String?, name: String?): Map<String, String> =
        if (id != null && name != null) mapOf(id to name) else emptyMap()

    @SuppressLint("MissingPermission") // canNotify() checks POST_NOTIFICATIONS
    fun notify(
        context: Context,
        items: List<AlertToNotify>,
        locationNames: Map<String, String>,
        points: Map<String, GeoPoint> = emptyMap(),
        units: UnitSystem = UnitSystem.IMPERIAL,
    ) {
        if (items.isEmpty() || !canNotify(context)) return
        val manager = NotificationManagerCompat.from(context)
        val now = Instant.now()
        for (item in items) {
            val alert = item.alert
            val place = locationNames[item.locationId]
            val title = (if (item.isUpdateOfNotified) "Updated: " else "") + alert.event
            // For storm-based alerts: whether the place is inside the drawn area, or how far away it is.
            val spot = points[item.locationId]?.let { AlertAreas.spot(alert, it) }
                ?.describe(alert) { km -> UnitFormatter(units).distance(km) }
            val summary = spot ?: alert.headline ?: alert.areaDesc ?: alert.event
            val body = listOfNotNull(place, spot, alert.headline, alert.instruction?.take(600)).joinToString("\n\n")

            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(MainActivity.EXTRA_OPEN_ALERTS, true)
                putExtra(MainActivity.EXTRA_LOCATION_ID, item.locationId)
            }
            val notificationId = (item.locationId + alert.threadId).hashCode()
            val pendingIntent = PendingIntent.getActivity(
                context, notificationId, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

            val builder = NotificationCompat.Builder(context, channelFor(item))
                .setSmallIcon(R.drawable.ic_stat_alert)
                .setContentTitle(title)
                .setContentText(if (place != null) "$place · $summary" else summary)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setColor(colorFor(alert.category))
                // NWS alerts are public information; showing them on the lock screen is the point.
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setCategory(if (alert.category == AlertCategory.WARNING) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_STATUS)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setSilent(item.isUpdateOfNotified && alert.category != AlertCategory.WARNING)
                .setGroup(GROUP)
                .setContentIntent(pendingIntent)
                .setShowWhen(true)
                .setWhen((alert.sent ?: now).toEpochMilli())

            (alert.ends ?: alert.expires)?.let { end ->
                val remaining = Duration.between(now, end)
                if (!remaining.isNegative) builder.setTimeoutAfter(remaining.toMillis())
            }
            // Same tag+id per alert thread, so an update replaces the earlier notification.
            manager.notify(TAG, notificationId, builder.build())
        }
    }
}
