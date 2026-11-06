package app.northvane.mini.work

import android.Manifest
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
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.northvane.core.alerts.AlertTracker
import app.northvane.core.model.AlertCategory
import app.northvane.core.model.AlertSeverity
import app.northvane.core.repo.attempt
import app.northvane.mini.MainActivity
import app.northvane.mini.MiniApp
import app.northvane.mini.R
import app.northvane.mini.data.MiniSettings
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Checks every saved place for new warnings, watches and advisories, and notifies once per alert. */
class MiniAlertWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as MiniApp).container
        container.store.load()
        val settings = container.store.settings.value
        if (!settings.alertsEnabled || settings.places.isEmpty()) return Result.success()
        // Without permission nothing can be shown: don't mark alerts as notified, so they still
        // appear once notifications are allowed.
        if (!canNotify(applicationContext)) return Result.success()
        var state = settings.notified
        var failures = 0
        val tracker = AlertTracker()
        val checked = settings.places.filter { it.notify }.take(10)
        for (place in checked) {
            val alerts = attempt { container.repository.alerts(place.point) }.getOrNull()
            if (alerts == null) {
                failures++
                continue
            }
            val (items, updated) = tracker.select(place.id, alerts, CATEGORIES, state, Instant.now())
            state = updated
            items.forEach { notify(applicationContext, place.displayName, it.alert.event, it.alert.headline ?: it.alert.description, it.alert.severity, it.alert.id) }
        }
        container.store.update { it.copy(notified = state) }
        return if (failures > 0 && failures == checked.size) Result.retry() else Result.success()
    }

    companion object {
        private const val CHANNEL = "alerts"
        private const val WORK = "mini-alerts"
        private val CATEGORIES = setOf(AlertCategory.WARNING, AlertCategory.WATCH, AlertCategory.ADVISORY)

        fun createChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = context.getString(R.string.channel_alerts_desc) },
            )
        }

        fun sync(context: Context, settings: MiniSettings) {
            val work = WorkManager.getInstance(context)
            if (!settings.alertsEnabled) {
                work.cancelUniqueWork(WORK)
                return
            }
            val request = PeriodicWorkRequestBuilder<MiniAlertWorker>(settings.alertCheckMinutes.coerceAtLeast(15).toLong(), TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            work.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        fun canNotify(context: Context): Boolean =
            NotificationManagerCompat.from(context).areNotificationsEnabled() &&
                (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

        @android.annotation.SuppressLint("MissingPermission") // checked by canNotify
        private fun notify(context: Context, place: String, event: String, text: String?, severity: AlertSeverity, id: String) {
            if (!canNotify(context)) return
            val intent = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MainActivity.EXTRA_OPEN_ALERTS, true)
            val pending = PendingIntent.getActivity(context, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_alert)
                .setContentTitle("$event · $place")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(if (severity == AlertSeverity.EXTREME || severity == AlertSeverity.SEVERE) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify("alert", id.hashCode(), notification)
        }
    }
}
