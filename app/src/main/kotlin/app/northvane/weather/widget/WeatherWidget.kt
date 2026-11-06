package app.northvane.weather.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.compose.ui.graphics.Color
import android.widget.RemoteViews
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.northvane.core.model.AlertCategory
import app.northvane.core.model.SavedLocation
import app.northvane.core.model.WeatherCondition
import app.northvane.core.model.WeatherSnapshot
import app.northvane.core.outlook.TodayHeadline
import app.northvane.core.repo.attempt
import app.northvane.core.util.UnitFormatter
import app.northvane.ui.gradientBitmap
import app.northvane.ui.skyGradient
import app.northvane.ui.warningGradient
import app.northvane.ui.weatherIconBitmap
import app.northvane.weather.MainActivity
import app.northvane.weather.NorthvaneApp
import app.northvane.weather.R
import app.northvane.weather.data.AppSettings
import app.northvane.weather.ui.TimeFormat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Home-screen widget in three sizes: small (temperature and icon), medium
 * (conditions, high and low, and the headline) and large (plus the next five
 * hours), on the main app's sky colors or the wallpaper's with a dynamic theme. It shows the last downloaded forecast; the app
 * refreshes it after every update, and [WidgetRefreshWorker] hourly.
 */
class WeatherWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = refreshAsync(context)

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) = refreshAsync(context)

    override fun onEnabled(context: Context) = WidgetRefreshWorker.sync(context)

    override fun onDisabled(context: Context) = WidgetRefreshWorker.sync(context)

    private fun refreshAsync(context: Context) {
        val pending = goAsync()
        val container = (context.applicationContext as NorthvaneApp).container
        container.applicationScope.launch {
            try {
                updateAll(context)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        fun ids(context: Context): IntArray =
            AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, WeatherWidget::class.java))

        /** Redraws every widget from the cached forecast (cheap; nothing when no widget is placed). */
        suspend fun updateAll(context: Context) {
            val ids = ids(context)
            if (ids.isEmpty()) return
            val container = (context.applicationContext as NorthvaneApp).container
            val settings = container.settings.settings.first()
            val locations = container.locations.locations.first()
            val location = locations.firstOrNull { it.id == settings.selectedLocationId } ?: locations.firstOrNull()
            val snapshot = location?.let { container.weatherCache.read(it.id) }
            val manager = AppWidgetManager.getInstance(context)
            ids.forEach { id -> manager.updateAppWidget(id, views(context, manager, id, location, snapshot, settings)) }
        }

        /** Fire-and-forget [updateAll], e.g. after the app downloads a forecast. */
        fun requestUpdate(context: Context) {
            app.northvane.weather.tile.WeatherTileService.requestUpdate(context)
            val container = (context.applicationContext as NorthvaneApp).container
            container.applicationScope.launch { runCatching { updateAll(context) } }
        }

        private fun views(
            context: Context,
            manager: AppWidgetManager,
            id: Int,
            location: SavedLocation?,
            snapshot: WeatherSnapshot?,
            settings: AppSettings,
        ): RemoteViews {
            val options = manager.getAppWidgetOptions(id)
            val widthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250).takeIf { it > 0 } ?: 250
            val heightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 140).takeIf { it > 0 } ?: 140
            return render(context, widthDp, heightDp, location, snapshot, settings)
        }

        /** Which layout fits the space the launcher gives the widget. */
        internal enum class Size { SMALL, MEDIUM, LARGE }

        internal fun sizeFor(widthDp: Int, heightDp: Int): Size = when {
            widthDp < 160 || heightDp < 95 -> Size.SMALL
            heightDp >= 190 && widthDp >= 220 -> Size.LARGE
            else -> Size.MEDIUM
        }

        /** The widget's views at [widthDp] × [heightDp]. */
        internal fun render(
            context: Context,
            widthDp: Int,
            heightDp: Int,
            location: SavedLocation?,
            snapshot: WeatherSnapshot?,
            settings: AppSettings,
        ): RemoteViews {
            val size = sizeFor(widthDp, heightDp)
            val layout = when (size) {
                Size.SMALL -> R.layout.widget_small
                Size.MEDIUM -> R.layout.widget_weather
                Size.LARGE -> R.layout.widget_large
            }
            val views = RemoteViews(context.packageName, layout)
            val open = PendingIntent.getActivity(
                context, 7301,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            views.setOnClickPendingIntent(R.id.widget_root, open)
            val now = Instant.now()
            val zone = snapshot?.timeZoneId?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
            val f = UnitFormatter(settings.units)
            val time = TimeFormat(zone, android.text.format.DateFormat.is24HourFormat(context))
            val hour = snapshot?.hourly?.firstOrNull { it.time.plusSeconds(3600) > now }
            val current = snapshot?.current
            val condition = current?.condition?.takeIf { it != WeatherCondition.UNKNOWN } ?: hour?.condition ?: WeatherCondition.CLEAR
            val isDay = current?.isDaytime ?: hour?.isDaytime ?: true
            val warning = snapshot?.alerts.orEmpty().any { it.category == AlertCategory.WARNING && (it.ends ?: it.expires)?.isAfter(now) != false }

            // Background sized to the widget, at most 1.5× density to keep the bitmap small. With a dynamic
            // (wallpaper) theme the widget takes the wallpaper's colors, except during a warning.
            val density = context.resources.displayMetrics.density.coerceAtMost(1.5f)
            val colors = when {
                warning -> warningGradient(dark = false)
                settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> listOf(
                    Color(context.getColor(android.R.color.system_accent1_500)),
                    Color(context.getColor(android.R.color.system_accent1_700)),
                )
                else -> skyGradient(condition, isDay, dark = false)
            }
            views.setImageViewBitmap(R.id.widget_background, gradientBitmap(colors, (widthDp * density).toInt(), (heightDp * density).toInt(), 20 * density))

            val placeName = location?.displayName ?: context.getString(R.string.app_name)
            views.setTextViewText(R.id.widget_place, placeName)
            if (snapshot == null) {
                views.setTextViewText(R.id.widget_temperature, "--")
                if (size != Size.SMALL) {
                    views.setTextViewText(R.id.widget_condition, "Open the app to load the forecast")
                    views.setTextViewText(R.id.widget_rain, "")
                }
                return views
            }
            val iconPx = ((if (size == Size.SMALL) 28 else 48) * context.resources.displayMetrics.density).toInt()
            views.setImageViewBitmap(R.id.widget_icon, weatherIconBitmap(condition, isDay, iconPx))
            views.setTextViewText(R.id.widget_temperature, f.temperature(current?.temperatureC ?: hour?.temperatureC))
            // An old forecast says so next to the place.
            if (Duration.between(snapshot.fetchedAt, now) > Duration.ofHours(2)) {
                views.setTextViewText(R.id.widget_place, "$placeName · ${time.smart(snapshot.fetchedAt)}")
            }
            if (size == Size.SMALL) return views

            val today = time.today()
            val day = snapshot.daily.firstOrNull { it.date == today } ?: snapshot.daily.firstOrNull { !it.date.isBefore(today) }
            val highLow = listOfNotNull(day?.highC?.let { "H ${f.temperature(it)}" }, day?.lowC?.let { "L ${f.temperature(it)}" }).joinToString(" ")
            views.setTextViewText(
                R.id.widget_condition,
                listOf(current?.description ?: hour?.shortForecast ?: condition.label, highLow).filter { it.isNotBlank() }.joinToString(" · "),
            )
            val headline = TodayHeadline.write(
                snapshot.hourly, snapshot.daily, now, zone, f, time::time, time::hour,
                maxSentences = if (size == Size.LARGE) 2 else 1,
                times = app.northvane.core.outlook.MyTimes.upcoming(settings.myTimes, now, zone),
            )
            views.setTextViewText(
                R.id.widget_rain,
                when {
                    warning -> snapshot.alerts.first { it.category == AlertCategory.WARNING }.event
                    headline.isNotEmpty() -> headline.joinToString(" ")
                    else -> ""
                },
            )
            if (size == Size.LARGE) {
                val cells = listOf(
                    Triple(R.id.widget_h1_time, R.id.widget_h1_icon, R.id.widget_h1_temp),
                    Triple(R.id.widget_h2_time, R.id.widget_h2_icon, R.id.widget_h2_temp),
                    Triple(R.id.widget_h3_time, R.id.widget_h3_icon, R.id.widget_h3_temp),
                    Triple(R.id.widget_h4_time, R.id.widget_h4_icon, R.id.widget_h4_temp),
                    Triple(R.id.widget_h5_time, R.id.widget_h5_icon, R.id.widget_h5_temp),
                )
                // The next five hours after this one.
                val next = snapshot.hourly.filter { it.time.isAfter(now) }.take(cells.size)
                val small = (24 * context.resources.displayMetrics.density).toInt()
                cells.forEachIndexed { i, (t, icon, temp) ->
                    val h = next.getOrNull(i)
                    views.setTextViewText(t, h?.let { time.hour(it.time) }.orEmpty())
                    views.setTextViewText(temp, h?.let { f.temperature(it.temperatureC) }.orEmpty())
                    if (h != null) views.setImageViewBitmap(icon, weatherIconBitmap(h.condition, h.isDaytime, small))
                }
            }
            return views
        }
    }
}

/** Keeps placed widgets current: an hourly refresh of the Today place (within Backwoods limits). */
class WidgetRefreshWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        if (WeatherWidget.ids(applicationContext).isEmpty()) return Result.success()
        val container = (applicationContext as NorthvaneApp).container
        val settings = container.settings.settings.first()
        val locations = container.locations.locations.first()
        val location = locations.firstOrNull { it.id == settings.selectedLocationId } ?: locations.firstOrNull()
        if (location != null) {
            val cached = container.weatherCache.read(location.id)
            if (cached == null || Duration.between(cached.fetchedAt, Instant.now()) > Duration.ofMinutes(45)) {
                attempt { container.weather.refresh(location) }
            }
        }
        WeatherWidget.updateAll(applicationContext)
        return Result.success()
    }

    companion object {
        private const val WORK = "widget-refresh"

        /** Hourly while at least one widget is placed. */
        fun sync(context: Context) {
            val work = WorkManager.getInstance(context)
            if (WeatherWidget.ids(context).isEmpty()) {
                work.cancelUniqueWork(WORK)
                return
            }
            val request = PeriodicWorkRequestBuilder<WidgetRefreshWorker>(1, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            work.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
