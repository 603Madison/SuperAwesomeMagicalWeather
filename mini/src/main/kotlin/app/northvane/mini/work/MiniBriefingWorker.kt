package app.northvane.mini.work

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.northvane.core.model.WeatherSnapshot
import app.northvane.core.outlook.Briefing
import app.northvane.core.outlook.BriefingWriter
import app.northvane.core.repo.SourceConfig
import app.northvane.core.repo.attempt
import app.northvane.core.util.UnitFormatter
import app.northvane.mini.MainActivity
import app.northvane.mini.MiniApp
import app.northvane.mini.MiniViewModel
import app.northvane.mini.R
import app.northvane.mini.data.MiniSettings
import java.util.Locale
import java.util.concurrent.TimeUnit
import app.northvane.core.time.*

/** Today's forecast for the selected place as one notification each morning, at the time chosen in Settings. */
class MiniBriefingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as MiniApp).container
        container.store.load()
        val settings = container.store.settings.value
        try {
            if (settings.briefingMinutes == null || !MiniAlertWorker.canNotify(applicationContext)) return Result.success()
            val place = settings.selected ?: return Result.success()
            val fresh = attempt { container.repository.fetch(place, SourceConfig(), MiniViewModel.PLAN) }.getOrNull()
            fresh?.let { container.store.saveSnapshot(it) }
            val snapshot = fresh ?: container.store.snapshot(place.id)?.takeIf { Duration.between(it.fetchedAt, kotlin.time.Clock.System.now()) < Duration.ofHours(12) }
                ?: return Result.success()
            notify(applicationContext, briefing(applicationContext, snapshot, place.displayName, settings))
            return Result.success()
        } finally {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { schedule(applicationContext, container.store.settings.value) }
        }
    }

    companion object {
        private const val WORK = "mini-briefing"
        private const val CHANNEL = "briefing"

        fun schedule(context: Context, settings: MiniSettings) {
            val work = WorkManager.getInstance(context)
            val minutes = settings.briefingMinutes ?: run {
                work.cancelUniqueWork(WORK)
                return
            }
            val now = ZonedDateTime.now()
            var next = now.with(LocalTime.of(minutes / 60, minutes % 60))
            if (!next.isAfter(now.plusMinutes(1))) next = next.plusDays(1)
            work.enqueueUniqueWork(
                WORK, ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<MiniBriefingWorker>().setInitialDelay(Duration.between(now, next).toMillis(), TimeUnit.MILLISECONDS).build(),
            )
        }

        /** The briefing for a forecast: also what "Read aloud" speaks. */
        fun briefing(context: Context, snapshot: WeatherSnapshot, place: String, settings: MiniSettings): Briefing {
            val zone = runCatching { ZoneId.of(snapshot.timeZoneId) }.getOrDefault(ZoneId.systemDefault())
            val f = UnitFormatter(settings.units)
            val hour = DateTimeFormatter.ofPattern(if (android.text.format.DateFormat.is24HourFormat(context)) "HH:mm" else "h a")
            return BriefingWriter.write(
                place, snapshot.daily, snapshot.hourly, snapshot.alerts, zone, kotlin.time.Clock.System.now(),
                temperature = f::temperature, wind = f::windSpeed, hour = { hour.format(it.atZone(zone)) },
            )
        }

        @SuppressLint("MissingPermission") // checked by canNotify
        private fun notify(context: Context, briefing: Briefing) {
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL, "Morning briefing", NotificationManager.IMPORTANCE_DEFAULT),
            )
            val pending = PendingIntent.getActivity(
                context, 2,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_alert)
                .setContentTitle(briefing.title)
                .setContentText(briefing.text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(briefing.text))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setTimeoutAfter(Duration.ofHours(14).toMillis())
                .build()
            NotificationManagerCompat.from(context).notify("briefing", 1, notification)
        }
    }
}
