package app.northvane.weather.work

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.northvane.core.model.Section
import app.northvane.core.outlook.DryingAdvisor
import app.northvane.core.repo.FetchPlan
import app.northvane.core.repo.SourceConfig
import app.northvane.core.repo.attempt
import app.northvane.core.util.Solar
import app.northvane.weather.MainActivity
import app.northvane.weather.NorthvaneApp
import app.northvane.weather.R
import app.northvane.weather.data.SatelliteFeature
import app.northvane.weather.net.isAllowed
import app.northvane.weather.notify.AlertNotifier
import kotlinx.coroutines.flow.first
import java.util.Locale
import java.util.concurrent.TimeUnit
import app.northvane.core.time.*

/**
 * While laundry is out on the line, checks the hourly forecast every 20
 * minutes and warns before rain or thunder (and before evening dew), then
 * stops by itself after 12 hours. Each check is one small hourly-forecast
 * request.
 */
class LaundryWatchWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as NorthvaneApp).container
        val settings = container.settings.settings.first()
        val since = settings.laundryOutSince ?: return Result.success()
        val now = kotlin.time.Clock.System.now()
        if (Duration.between(Instant.ofEpochMilli(since), now) > Duration.ofHours(12)) {
            container.settings.setLaundryOut(null)
            return Result.success()
        }
        val location = container.locations.locations.first().firstOrNull { it.id == settings.laundryLocationId }
            ?: return Result.success().also { container.settings.setLaundryOut(null) }
        if (isAllowed(SatelliteFeature.BACKGROUND_ALERTS, settings, container.connectivity.current())) {
            val plan = FetchPlan(sections = setOf(Section.HOURLY_FORECAST), observationStations = 0, limited = true)
            attempt { container.repository.fetch(location, SourceConfig(), plan) }.getOrNull()?.let { snapshot ->
                val zone = runCatching { ZoneId.of(snapshot.timeZoneId) }.getOrDefault(ZoneId.systemDefault())
                val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val rain = DryingAdvisor.rainSoon(snapshot.hourly, now, Duration.ofMinutes(100))
                val rainKey = "rain-$since-${rain?.time?.epochSecond}"
                if (rain != null && !prefs.getBoolean(rainKey, false)) {
                    val time = DateTimeFormatter.ofPattern("h:mm a").withZone(zone).format(rain.time)
                    val what = if ((rain.thunderChance ?: 0) >= 20) "Thunderstorms" else "Rain"
                    notify(applicationContext, "$what likely around $time", "Time to bring the laundry in at ${location.displayName}.")
                    prefs.edit { putBoolean(rainKey, true) }
                }
                val sunset = Solar.sunTimes(location.latitude, location.longitude, now.atZone(zone).toLocalDate(), zone).second
                val damp = snapshot.hourly.firstOrNull { it.time.isAfter(now) }?.humidityPercent?.let { it >= 85 } == true
                val duskKey = "dusk-$since"
                if (sunset != null && Duration.between(now, sunset).toMinutes() in 0..45 && damp && !prefs.getBoolean(duskKey, false)) {
                    notify(applicationContext, "Dew is coming", "Damp evening air will re-wet the laundry. Bring it in around sunset.")
                    prefs.edit { putBoolean(duskKey, true) }
                }
            }
        }
        schedule(applicationContext, delayMinutes = 20)
        return Result.success()
    }

    companion object {
        private const val WORK = "laundry-watch"
        private const val PREFS = "laundry_watch"
        const val CHANNEL = "laundry"

        fun createChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.channel_laundry), NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = context.getString(R.string.channel_laundry_desc) },
            )
        }

        fun start(context: Context) = schedule(context, delayMinutes = 0)

        fun stop(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { clear() }
        }

        private fun schedule(context: Context, delayMinutes: Long) {
            val request = OneTimeWorkRequestBuilder<LaundryWatchWorker>().setInitialDelay(delayMinutes, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE, request)
        }

        @SuppressLint("MissingPermission") // checked by canNotify
        private fun notify(context: Context, title: String, text: String) {
            if (!AlertNotifier.canNotify(context)) return
            val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val pending = PendingIntent.getActivity(context, 7101, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_alert)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setTimeoutAfter(Duration.ofHours(3).toMillis())
                .build()
            NotificationManagerCompat.from(context).notify("laundry", title.hashCode(), notification)
        }
    }
}
