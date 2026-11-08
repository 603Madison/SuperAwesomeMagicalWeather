package app.northvane.weather.work

import android.content.Context
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.northvane.core.alerts.AlertToNotify
import app.northvane.core.alerts.AlertTracker
import app.northvane.core.net.HttpStatusException
import app.northvane.core.repo.attempt
import app.northvane.weather.NorthvaneApp
import app.northvane.weather.data.SatelliteFeature
import app.northvane.weather.data.SettingsRepository
import app.northvane.weather.net.isAllowed
import app.northvane.weather.notify.AlertNotifier
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit
import app.northvane.core.time.*

/**
 * Periodically checks NWS active alerts for every saved location and posts a
 * notification for each new warning/watch/advisory the user opted into.
 * Each check is a few small requests, so it also runs on satellite links.
 */
class AlertCheckWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as NorthvaneApp).container
        val settings = container.settings.settings.first()
        if (!settings.alertsEnabled || (settings.alertCategories.isEmpty() && !(settings.gardenEnabled && settings.gardenFrostAlerts))) return Result.success()
        // Respect the user's satellite settings for background checks.
        if (!isAllowed(SatelliteFeature.BACKGROUND_ALERTS, settings, container.connectivity.current())) return Result.success()

        val locations = container.locations.locations.first().filter { it.notify }.take(MAX_LOCATIONS)
        if (locations.isEmpty()) return Result.success()

        val tracker = AlertTracker()
        // Without permission nothing can be shown: keep alerts un-notified so they still appear once
        // notifications are allowed (the cached alerts for each place are refreshed either way).
        val canNotify = AlertNotifier.canNotify(applicationContext)
        var state = container.alertState.read()
        val toNotify = mutableListOf<AlertToNotify>()
        var failures = 0
        for (location in locations) {
            val result = attempt { container.repository.alerts(location.point) }
            val error = result.exceptionOrNull()
            // A 4xx means NWS doesn't cover this point (e.g. outside the US): nothing to alert on, not a failure.
            val outsideCoverage = error is HttpStatusException && error.code in 400..499
            val alerts = result.getOrNull() ?: if (outsideCoverage) emptyList() else null
            if (alerts == null) {
                failures++
                continue
            }
            container.weatherCache.updateAlerts(location.id, alerts)
            if (!canNotify) continue
            val gardenFrost = settings.gardenEnabled && settings.gardenFrostAlerts
            val (items, updated) = tracker.select(location.id, alerts, settings.alertCategories, state, kotlin.time.Clock.System.now()) { alert ->
                gardenFrost && (alert.event.contains("Frost", ignoreCase = true) || alert.event.contains("Freeze", ignoreCase = true))
            }
            state = updated
            toNotify += items
        }
        if (canNotify) {
            AlertNotifier.notify(
                applicationContext, toNotify, locations.associate { it.id to it.displayName },
                points = locations.associate { it.id to it.point }, units = container.settings.settings.first().units,
            )
            container.alertState.write(state)
        }
        return if (failures == locations.size) Result.retry() else Result.success()
    }

    private companion object {
        const val MAX_LOCATIONS = 10
    }
}

class AlertScheduler(private val context: Context, private val settings: SettingsRepository) {

    private fun constraints(): Constraints {
        val builder = Constraints.Builder()
        // On Android 16+ a satellite-only network doesn't satisfy WorkManager's
        // default (unconstrained) network request, so don't require one there;
        // the worker simply retries if it can't reach NWS.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) {
            builder.setRequiredNetworkType(NetworkType.CONNECTED)
        }
        return builder.build()
    }

    suspend fun sync() {
        val current = settings.settings.first()
        val workManager = WorkManager.getInstance(context)
        if (!current.alertsEnabled) {
            workManager.cancelUniqueWork(PERIODIC)
            return
        }
        val request = PeriodicWorkRequestBuilder<AlertCheckWorker>(
            current.alertCheckMinutes.coerceAtLeast(15).toLong(), TimeUnit.MINUTES,
        )
            .setConstraints(constraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun checkNow() {
        val request = OneTimeWorkRequestBuilder<AlertCheckWorker>().setConstraints(constraints()).build()
        WorkManager.getInstance(context).enqueueUniqueWork(ONE_SHOT, ExistingWorkPolicy.REPLACE, request)
    }

    private companion object {
        const val PERIODIC = "nws-alert-check"
        const val ONE_SHOT = "nws-alert-check-now"
    }
}
