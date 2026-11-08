package app.northvane.weather.work

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.northvane.core.aurora.AuroraAdvisor
import app.northvane.core.aurora.AuroraAlerts
import app.northvane.core.aurora.AuroraHeadsUp
import app.northvane.core.model.AuroraView
import app.northvane.core.repo.attempt
import app.northvane.weather.MainActivity
import app.northvane.weather.NorthvaneApp
import app.northvane.weather.R
import app.northvane.weather.data.AppSettings
import app.northvane.weather.data.SatelliteFeature
import app.northvane.weather.net.isAllowed
import app.northvane.weather.notify.AlertNotifier
import kotlinx.coroutines.flow.first
import java.util.Locale
import java.util.concurrent.TimeUnit
import app.northvane.core.time.*

/**
 * Every few hours, downloads NOAA's geomagnetic forecast (one ~7 KB file for
 * all places) and notifies when the aurora may be visible from a saved place
 * that has notifications on: once per night, and again only if the outlook
 * improves or it's happening right now.
 */
class AuroraWatchWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as NorthvaneApp).container
        val settings = container.settings.settings.first()
        if (!settings.auroraEnabled || !settings.auroraNotify) return Result.success()
        if (!isAllowed(SatelliteFeature.AURORA, settings, container.connectivity.current())) return Result.success()
        val locations = container.locations.locations.first().filter { it.notify }.take(MAX_LOCATIONS)
        if (locations.isEmpty()) return Result.success()

        val now = kotlin.time.Clock.System.now()
        val forecast = attempt { container.aurora.forecast(now) }.getOrNull() ?: return Result.retry()
        val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        for (location in locations) {
            // Clouds come from the last forecast downloaded for the place, if there is one.
            val cached = container.weatherCache.read(location.id)
            val zone = cached?.timeZoneId?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
            val report = AuroraAdvisor.report(forecast, location.latitude, location.longitude, cached?.hourly.orEmpty(), zone, now)
            val headsUp = AuroraAlerts.pick(report, settings.auroraNotifyView, now, zone) ?: continue
            val key = "${location.id}|${headsUp.key}"
            val before = prefs.getString(key, null)?.let { name -> AuroraView.entries.firstOrNull { it.name == name } }
            if (!AuroraAlerts.isNew(headsUp, before)) continue
            if (notify(applicationContext, location.id, location.displayName, headsUp, zone)) {
                prefs.edit { putString(key, headsUp.view.name) }
            }
        }
        forget(prefs, now)
        return Result.success()
    }

    /** Drops remembered nights older than a few days. */
    private fun forget(prefs: android.content.SharedPreferences, now: Instant) {
        val cutoff = now.atZone(ZoneId.systemDefault()).toLocalDate().minusDays(4)
        // Keys end with the night's date, e.g. "home|night-2026-11-10".
        val old = prefs.all.keys.filter { key -> runCatching { LocalDate.parse(key.takeLast(10)) }.getOrNull()?.isBefore(cutoff) == true }
        if (old.isNotEmpty()) prefs.edit { old.forEach { remove(it) } }
    }

    companion object {
        private const val WORK = "aurora-watch"
        private const val PREFS = "aurora_watch"
        private const val MAX_LOCATIONS = 10
        const val CHANNEL = "aurora"

        fun createChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.channel_aurora), NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = context.getString(R.string.channel_aurora_desc) },
            )
        }

        /** Starts or stops the periodic check to match the settings. */
        fun sync(context: Context, settings: AppSettings) {
            val workManager = WorkManager.getInstance(context)
            if (!settings.auroraEnabled || !settings.auroraNotify) {
                workManager.cancelUniqueWork(WORK)
                return
            }
            val constraints = Constraints.Builder().apply {
                // Same as alert checks: satellite-only networks don't count as "connected" for WorkManager on Android 16+.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) setRequiredNetworkType(NetworkType.CONNECTED)
            }.build()
            val request = PeriodicWorkRequestBuilder<AuroraWatchWorker>(3, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()
            workManager.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        @SuppressLint("MissingPermission") // checked by canNotify
        private fun notify(context: Context, locationId: String, place: String, headsUp: AuroraHeadsUp, zone: ZoneId): Boolean {
            if (!AlertNotifier.canNotify(context)) return false
            val hour = DateTimeFormatter.ofPattern("h a").withZone(zone)
            val today = LocalDate.now(zone)
            val title = if (headsUp.now) {
                "Aurora may be visible now"
            } else {
                val night = headsUp.night!!
                val day = when (night.date) {
                    today -> "tonight"
                    today.plusDays(1) -> "tomorrow night"
                    else -> night.date.dayOfWeek.getDisplayName(app.northvane.core.time.TextStyle.FULL) + " night"
                }
                "Northern lights possible $day"
            }
            val text = buildList {
                add("$place: ${headsUp.view.label.replaceFirstChar { it.lowercase() }}")
                if (!headsUp.now) headsUp.night?.peakAt?.let { add("best around ${hour.format(it)}") }
                add("Kp ${"%.1f".format(headsUp.kp)}")
                headsUp.night?.cloudPercent?.let { add("clouds about $it%") }
            }.joinToString(" · ") + ". Get away from lights and look north."
            val intent = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MainActivity.EXTRA_LOCATION_ID, locationId)
            val pending = PendingIntent.getActivity(context, ("aurora$locationId").hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_alert)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setColor(0xFF2FBF71.toInt())
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setTimeoutAfter(Duration.ofHours(if (headsUp.now) 4 else 30).toMillis())
                .build()
            // One notification per place: a newer heads-up replaces the older one.
            NotificationManagerCompat.from(context).notify("aurora", locationId.hashCode(), notification)
            return true
        }
    }
}
