package app.northvane.wear.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.northvane.core.alerts.AlertTracker
import app.northvane.core.model.AlertCategory
import app.northvane.core.model.AlertSeverity
import app.northvane.core.model.WeatherAlert
import app.northvane.core.repo.attempt
import app.northvane.core.time.*
import app.northvane.wear.MainActivity
import app.northvane.wear.R
import app.northvane.wear.WearApp
import app.northvane.wear.complication.WeatherComplicationService
import app.northvane.wear.data.RainStep
import app.northvane.wear.data.WearSettings
import app.northvane.wear.tile.WeatherTileService
import java.util.concurrent.TimeUnit

/**
 * Keeps the tile's place up to date for the tile and the complication, and
 * notifies new alerts there. One job for both, so the watch wakes up once:
 * every 30 minutes when alerts or next-hour rain are on, else every hour. The
 * full forecast (about 25 KB) is downloaded at most hourly; in between only the
 * rain (1 KB) and the alerts are checked.
 */
class WearRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as WearApp).container
        container.store.ensureLoaded()
        val settings = container.store.settings.value
        val place = settings.tilePlace ?: return Result.success()
        if (!settings.backgroundUpdates && !settings.alertsEnabled) return Result.success()
        val now = kotlin.time.Clock.System.now()
        val cached = container.store.forecast(place.id)
        val forced = inputData.getBoolean(KEY_FORCE, false)
        var alerts: List<WeatherAlert>? = null
        if (settings.backgroundUpdates) {
            val age = cached?.let { Duration.between(it.snapshot.fetchedAt, now) }
            if (age == null || age >= FULL_REFRESH_AFTER || (forced && age >= Duration.ofMinutes(10))) {
                container.refresh(place, settings.nextHourRain).getOrNull()?.let { fresh ->
                    if (fresh.snapshot.alertsAvailable) alerts = fresh.snapshot.alerts
                }
            } else if (settings.nextHourRain) {
                attempt { container.nowcast.next(place.point) }.getOrNull()?.let { steps ->
                    container.store.saveForecast(cached.copy(rain = steps.map(RainStep::of)))
                }
            }
            updateSurfaces(applicationContext)
        }
        if (settings.alertsEnabled && alerts == null && AlertNotifier.canNotify(applicationContext)) {
            alerts = attempt { container.repository.alerts(place.point) }.getOrNull()
        }
        alerts?.let { AlertNotifier.notifyNew(applicationContext, place.id, place.displayName, it) }
        return Result.success()
    }

    companion object {
        private const val WORK = "wear-refresh"
        private const val WORK_NOW = "wear-refresh-now"
        private const val KEY_FORCE = "force"
        val FULL_REFRESH_AFTER: Duration = Duration.ofMinutes(55)

        /** Minutes between background checks, or null when nothing needs them. */
        fun periodMinutes(settings: WearSettings): Long? = when {
            settings.places.isEmpty() -> null
            settings.alertsEnabled -> 30
            settings.backgroundUpdates && settings.nextHourRain -> 30
            settings.backgroundUpdates -> 60
            else -> null
        }

        fun sync(context: Context, settings: WearSettings) {
            val work = WorkManager.getInstance(context)
            val minutes = periodMinutes(settings) ?: run {
                work.cancelUniqueWork(WORK)
                return
            }
            val request = PeriodicWorkRequestBuilder<WearRefreshWorker>(minutes, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            work.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        /** One refresh as soon as the network allows, e.g. when the tile finds its data old. */
        fun refreshSoon(context: Context) {
            val request = OneTimeWorkRequestBuilder<WearRefreshWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(androidx.work.workDataOf(KEY_FORCE to true))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NOW, ExistingWorkPolicy.KEEP, request)
        }

        /** Asks the tile and the complication to read the cache again. */
        fun updateSurfaces(context: Context) {
            runCatching { TileService.getUpdater(context).requestUpdate(WeatherTileService::class.java) }
            runCatching {
                ComplicationDataSourceUpdateRequester.create(context, ComponentName(context, WeatherComplicationService::class.java)).requestUpdateAll()
            }
        }
    }
}

/** Alert notifications for the tile's place, once per alert (the same rules as Mini). */
object AlertNotifier {
    private const val CHANNEL = "alerts"
    private val CATEGORIES = setOf(AlertCategory.WARNING, AlertCategory.WATCH, AlertCategory.ADVISORY)

    fun createChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_HIGH)
                .apply { description = context.getString(R.string.channel_alerts_desc) },
        )
    }

    fun canNotify(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    suspend fun notifyNew(context: Context, placeId: String, placeName: String, alerts: List<WeatherAlert>) {
        val store = (context.applicationContext as WearApp).container.store
        val settings = store.settings.value
        // Without permission nothing can be shown: don't mark alerts as notified, so they still
        // appear once notifications are allowed.
        if (!settings.alertsEnabled || !canNotify(context)) return
        val (items, updated) = AlertTracker().select(placeId, alerts, CATEGORIES, settings.notified, kotlin.time.Clock.System.now())
        items.forEach { notify(context, placeId, placeName, it.alert) }
        store.update { it.copy(notified = updated) }
    }

    @android.annotation.SuppressLint("MissingPermission") // checked by canNotify
    private fun notify(context: Context, placeId: String, place: String, alert: WeatherAlert) {
        if (!canNotify(context)) return
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_PLACE_ID, placeId)
            .putExtra(MainActivity.EXTRA_ALERT_ID, alert.id)
        val pending = PendingIntent.getActivity(context, alert.id.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = alert.headline ?: alert.description
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_alert)
            .setContentTitle(alert.event)
            .setContentText(listOfNotNull(place, text).joinToString(" · "))
            .setStyle(NotificationCompat.BigTextStyle().bigText(listOfNotNull(place, text).joinToString("\n")))
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setPriority(if (alert.severity == AlertSeverity.EXTREME || alert.severity == AlertSeverity.SEVERE) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify("alert", alert.id.hashCode(), notification)
    }
}
