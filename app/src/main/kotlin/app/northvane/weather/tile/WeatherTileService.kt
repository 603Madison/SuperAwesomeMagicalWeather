package app.northvane.weather.tile

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.northvane.core.outlook.TodayHeadline
import app.northvane.core.util.UnitFormatter
import app.northvane.weather.MainActivity
import app.northvane.weather.NorthvaneApp
import app.northvane.weather.ui.TimeFormat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/**
 * Quick Settings tile: the temperature and the headline's first sentence for
 * the place on the Today screen, from the last downloaded forecast (it never
 * downloads anything itself). Tap to open the app.
 */
class WeatherTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val app = applicationContext as NorthvaneApp
        app.container.applicationScope.launch {
            val content = content(applicationContext)
            qsTile?.apply {
                label = content.first
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) subtitle = content.second
                contentDescription = "${content.first}, ${content.second}"
                state = Tile.STATE_ACTIVE
                updateTile()
            }
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 7401, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        /** Label ("54° Portland, ME") and subtitle (the next thing to know). */
        suspend fun content(context: Context): Pair<String, String> {
            val container = (context.applicationContext as NorthvaneApp).container
            val settings = container.settings.settings.first()
            val locations = container.locations.locations.first()
            val location = locations.firstOrNull { it.id == settings.selectedLocationId } ?: locations.firstOrNull()
                ?: return "Weather" to "Add a place"
            val snapshot = container.weatherCache.read(location.id) ?: return location.displayName to "Open the app to load"
            val now = Instant.now()
            val zone = runCatching { ZoneId.of(snapshot.timeZoneId) }.getOrDefault(ZoneId.systemDefault())
            val f = UnitFormatter(settings.units)
            val time = TimeFormat(zone, android.text.format.DateFormat.is24HourFormat(context))
            val temp = snapshot.current?.temperatureC ?: snapshot.hourly.firstOrNull { it.time.plusSeconds(3600).isAfter(now) }?.temperatureC
            val line = TodayHeadline.write(snapshot.hourly, snapshot.daily, now, zone, f, time::time, time::hour, maxSentences = 1).firstOrNull()
            return "${f.temperature(temp)} ${location.displayName}" to (line?.trimEnd('.') ?: "Tap for the forecast")
        }

        /** Asks the system to redraw the tile, e.g. after a new forecast. */
        fun requestUpdate(context: Context) {
            runCatching { requestListeningState(context, ComponentName(context, WeatherTileService::class.java)) }
        }
    }
}
