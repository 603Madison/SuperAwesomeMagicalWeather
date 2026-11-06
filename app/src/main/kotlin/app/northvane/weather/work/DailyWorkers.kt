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
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.northvane.core.model.WeatherSnapshot
import app.northvane.core.outlook.BriefingWriter
import app.northvane.core.outlook.PrecipKind
import app.northvane.core.outlook.RainSoonDetector
import app.northvane.core.repo.attempt
import app.northvane.core.util.UnitFormatter
import app.northvane.weather.MainActivity
import app.northvane.weather.NorthvaneApp
import app.northvane.weather.R
import app.northvane.weather.data.AppSettings
import app.northvane.weather.data.SatelliteFeature
import app.northvane.weather.net.isAllowed
import app.northvane.weather.notify.AlertNotifier
import app.northvane.weather.ui.TimeFormat
import app.northvane.weather.widget.WeatherWidget
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * The morning briefing: today's forecast for the place on the Today screen,
 * as one notification at the time the user picked. Each run schedules the
 * next one, since periodic work can't be pinned to a time of day.
 */
class BriefingWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as NorthvaneApp).container
        val settings = container.settings.settings.first()
        try {
            if (!settings.briefingEnabled || !AlertNotifier.canNotify(applicationContext)) return Result.success()
            val locations = container.locations.locations.first()
            val location = locations.firstOrNull { it.id == settings.selectedLocationId } ?: locations.firstOrNull() ?: return Result.success()
            // A fresh forecast if the network allows (Backwoods limits apply); otherwise the last one, if recent.
            val snapshot = attempt { container.weather.refresh(location) }.getOrNull()
                ?: container.weatherCache.read(location.id)?.takeIf { Duration.between(it.fetchedAt, Instant.now()) < Duration.ofHours(12) }
                ?: return Result.success()
            WeatherWidget.updateAll(applicationContext)
            notify(applicationContext, location.id, location.displayName, snapshot, settings)
            return Result.success()
        } finally {
            // Even if this run was stopped, tomorrow's briefing must still be booked.
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                schedule(applicationContext, container.settings.settings.first())
            }
        }
    }

    companion object {
        private const val WORK = "morning-briefing"
        private const val CHANNEL = "briefing"

        /** Schedules the next briefing (or cancels it when switched off). */
        fun schedule(context: Context, settings: AppSettings) {
            val work = WorkManager.getInstance(context)
            if (!settings.briefingEnabled) {
                work.cancelUniqueWork(WORK)
                return
            }
            val now = ZonedDateTime.now()
            var next = now.with(LocalTime.of(settings.briefingMinutes / 60, settings.briefingMinutes % 60))
            if (!next.isAfter(now.plusMinutes(1))) next = next.plusDays(1)
            val request = OneTimeWorkRequestBuilder<BriefingWorker>()
                .setInitialDelay(Duration.between(now, next).toMillis(), TimeUnit.MILLISECONDS)
                .build()
            work.enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE, request)
        }

        @SuppressLint("MissingPermission") // checked by canNotify
        fun notify(context: Context, locationId: String, place: String, snapshot: WeatherSnapshot, settings: AppSettings) {
            val zone = runCatching { ZoneId.of(snapshot.timeZoneId) }.getOrDefault(ZoneId.systemDefault())
            val f = UnitFormatter(settings.units)
            val time = TimeFormat(zone, android.text.format.DateFormat.is24HourFormat(context))
            val base = BriefingWriter.write(
                place, snapshot.daily, snapshot.hourly, snapshot.alerts, zone, Instant.now(),
                temperature = f::temperature, wind = f::windSpeed, hour = time::hour,
            )
            // The day's clothing and the user's own times, after the forecast sentences.
            val now = Instant.now()
            val extra = listOfNotNull(
                app.northvane.core.outlook.MyTimes.sentence(
                    app.northvane.core.outlook.MyTimes.upcoming(settings.myTimes, now, zone), snapshot.hourly, f, time::hour, now,
                ),
                if (settings.wearEnabled) {
                    app.northvane.core.outlook.WhatToWear.advise(snapshot.hourly, now, settings.comfort, time::hour, snapshot.uv?.todayMax)?.let { "Wear: $it." }
                } else {
                    null
                },
            )
            val briefing = if (extra.isEmpty()) base else base.copy(text = base.text + " " + extra.joinToString(" "))
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL, "Morning briefing", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "Today's forecast once a day, at the time you choose" },
            )
            val intent = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MainActivity.EXTRA_LOCATION_ID, locationId)
            val pending = PendingIntent.getActivity(context, 7201, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
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

/**
 * The evening heads-up: one notification per place (with notifications on)
 * listing what's worth doing before bed. Like the briefing, each run books
 * the next. Sent only when there's something to say.
 */
class TonightWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as NorthvaneApp).container
        try {
            val settings = container.settings.settings.first()
            if (!settings.tonightEnabled || !AlertNotifier.canNotify(applicationContext)) return Result.success()
            val now = Instant.now()
            for (location in container.locations.locations.first().filter { it.notify }.take(5)) {
                // The last forecast if it's recent, otherwise a fresh one (within Backwoods limits).
                val snapshot = container.weatherCache.read(location.id)?.takeIf { Duration.between(it.fetchedAt, now) < Duration.ofHours(3) }
                    ?: attempt { container.weather.refresh(location) }.getOrNull()
                    ?: continue
                val zone = runCatching { ZoneId.of(snapshot.timeZoneId) }.getOrDefault(ZoneId.systemDefault())
                val time = TimeFormat(zone, android.text.format.DateFormat.is24HourFormat(applicationContext))
                val lines = app.northvane.core.outlook.TonightDigest.write(
                    snapshot, now, zone, UnitFormatter(settings.units), time::hour,
                    app.northvane.core.outlook.TonightDigest.Options(
                        garden = settings.gardenEnabled && settings.gardenPlan.isNotEmpty(),
                        laundryOut = settings.laundryOutSince != null,
                        pipes = settings.pipeRiskEnabled,
                        ice = settings.morningIceEnabled,
                        aurora = settings.auroraEnabled,
                        longitude = location.longitude,
                    ),
                )
                if (lines.isNotEmpty()) notify(applicationContext, location.id, location.displayName, lines)
            }
            return Result.success()
        } finally {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                schedule(applicationContext, container.settings.settings.first())
            }
        }
    }

    @SuppressLint("MissingPermission") // checked by canNotify in doWork
    private fun notify(context: Context, locationId: String, place: String, lines: List<String>) {
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL, "Tonight", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "One evening heads-up: frost, pipes, black ice, storms, laundry and the northern lights" },
        )
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_LOCATION_ID, locationId)
        val pending = PendingIntent.getActivity(context, ("tonight$locationId").hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = lines.joinToString(" ")
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_alert)
            .setContentTitle("Tonight in $place")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setTimeoutAfter(Duration.ofHours(14).toMillis())
            .build()
        NotificationManagerCompat.from(context).notify("tonight", locationId.hashCode(), notification)
    }

    companion object {
        private const val WORK = "tonight"
        private const val CHANNEL = "tonight"

        fun schedule(context: Context, settings: AppSettings) {
            val work = WorkManager.getInstance(context)
            if (!settings.tonightEnabled) {
                work.cancelUniqueWork(WORK)
                return
            }
            val now = ZonedDateTime.now()
            var next = now.with(LocalTime.of(settings.tonightMinutes / 60, settings.tonightMinutes % 60))
            if (!next.isAfter(now.plusMinutes(1))) next = next.plusDays(1)
            val request = OneTimeWorkRequestBuilder<TonightWorker>()
                .setInitialDelay(Duration.between(now, next).toMillis(), TimeUnit.MILLISECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.NOT_REQUIRED).build())
                .build()
            work.enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE, request)
        }
    }
}

/**
 * Every 15 minutes, checks the 15-minute precipitation nowcast (about 1 KB)
 * for places with notifications on, and notifies once when rain or snow is
 * about to start where it's dry now. Quiet overnight.
 */
class RainSoonWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as NorthvaneApp).container
        val settings = container.settings.settings.first()
        if (!settings.rainSoonEnabled || !AlertNotifier.canNotify(applicationContext)) return Result.success()
        if (!isAllowed(SatelliteFeature.RAIN_SOON, settings, container.connectivity.current())) return Result.success()
        val locations = container.locations.locations.first().filter { it.notify }.take(MAX_LOCATIONS)
        val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = Instant.now()
        for (location in locations) {
            val zone = container.weatherCache.read(location.id)?.timeZoneId?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
            // Nobody wants a drizzle notification at 3 AM.
            val hour = now.atZone(zone).hour
            if (hour >= QUIET_FROM || hour < QUIET_UNTIL) continue
            val steps = attempt { container.nowcast.next(location.point) }.getOrNull() ?: continue
            val soon = RainSoonDetector.detect(steps, now) ?: continue
            val last = prefs.getLong(location.id, 0L).takeIf { it > 0 }?.let(Instant::ofEpochSecond)
            if (!RainSoonDetector.isNew(soon.start, last)) continue
            notify(applicationContext, location.id, location.displayName, soon, zone)
            prefs.edit { putLong(location.id, soon.start.epochSecond) }
        }
        return Result.success()
    }

    @SuppressLint("MissingPermission") // checked by canNotify in doWork
    private fun notify(context: Context, locationId: String, place: String, soon: app.northvane.core.outlook.RainSoon, zone: ZoneId) {
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL, "Rain starting soon", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "A heads-up when rain or snow is about to start" },
        )
        val time = TimeFormat(zone, android.text.format.DateFormat.is24HourFormat(context))
        val what = when (soon.kind) {
            PrecipKind.RAIN -> "Rain"
            PrecipKind.SNOW -> "Snow"
            PrecipKind.MIX -> "Wintry mix"
        }
        val text = "Around ${time.time(soon.start)} in $place" + if (soon.heavy) ", heavy at times." else "."
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_LOCATION_ID, locationId)
        val pending = PendingIntent.getActivity(context, ("rain$locationId").hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_alert)
            .setContentTitle("$what starting soon")
            .setContentText(text)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setTimeoutAfter(Duration.between(Instant.now(), soon.start.plus(Duration.ofMinutes(30))).toMillis().coerceAtLeast(60_000))
            .build()
        NotificationManagerCompat.from(context).notify("rain_soon", locationId.hashCode(), notification)
    }

    companion object {
        private const val WORK = "rain-soon"
        private const val CHANNEL = "rain_soon"
        private const val PREFS = "rain_soon"
        private const val MAX_LOCATIONS = 5
        private const val QUIET_FROM = 22
        private const val QUIET_UNTIL = 6

        fun sync(context: Context, settings: AppSettings) {
            val work = WorkManager.getInstance(context)
            if (!settings.rainSoonEnabled) {
                work.cancelUniqueWork(WORK)
                return
            }
            val request = PeriodicWorkRequestBuilder<RainSoonWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            work.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
