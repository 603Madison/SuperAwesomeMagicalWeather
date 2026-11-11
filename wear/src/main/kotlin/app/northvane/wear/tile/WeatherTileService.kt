package app.northvane.wear.tile

import android.content.ComponentName
import android.content.Context
import androidx.concurrent.futures.SuspendToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.DeviceParametersBuilders
import androidx.wear.protolayout.DimensionBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material3.MaterialScope
import androidx.wear.protolayout.material3.Typography
import androidx.wear.protolayout.material3.materialScope
import androidx.wear.protolayout.material3.primaryLayout
import androidx.wear.protolayout.material3.text
import androidx.wear.protolayout.modifiers.clickable
import androidx.wear.protolayout.types.LayoutString
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import app.northvane.core.time.*
import app.northvane.wear.MainActivity
import app.northvane.wear.WearApp
import app.northvane.wear.glance.Glance
import app.northvane.wear.glance.TileTexts
import app.northvane.wear.work.WearRefreshWorker
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers

/**
 * The weather tile: temperature, today's headline and the next hour's rain for
 * the tile's place, read from the app's cache (never downloaded here). Old data
 * asks for one background refresh.
 */
class WeatherTileService : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        SuspendToFutureAdapter.launchFuture(Dispatchers.Default) {
            val glance = currentGlance(this@WeatherTileService)
            val old = glance != null && (!glance.hasForecast ||
                glance.updatedAt?.let { Duration.between(it, kotlin.time.Clock.System.now()) > WearRefreshWorker.FULL_REFRESH_AFTER } == true)
            if (old && (application as WearApp).container.store.settings.value.backgroundUpdates) {
                WearRefreshWorker.refreshSoon(this@WeatherTileService)
            }
            TileBuilders.Tile.Builder()
                .setResourcesVersion(RESOURCES_VERSION)
                // The hourly forecast moves on even without new downloads; the system asks again after this.
                .setFreshnessIntervalMillis(Duration.ofMinutes(30).toMillis())
                .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout(this@WeatherTileService, requestParams.deviceConfiguration, TileTexts.of(glance))))
                .build()
        }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        SuspendToFutureAdapter.launchFuture(Dispatchers.Default) {
            ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build()
        }

    companion object {
        private const val RESOURCES_VERSION = "1"

        /** The tile place's glance from the cache, or null without places. */
        suspend fun currentGlance(context: Context): Glance? {
            val store = (context.applicationContext as WearApp).container.store
            store.ensureLoaded()
            val settings = store.settings.value
            val place = settings.tilePlace ?: return null
            return Glance.of(
                place, store.forecast(place.id), settings.units, settings.nextHourRain, kotlin.time.Clock.System.now(),
                android.text.format.DateFormat.is24HourFormat(context),
            )
        }

        fun layout(context: Context, device: DeviceParametersBuilders.DeviceParameters, t: TileTexts): LayoutElementBuilders.LayoutElement =
            materialScope(context, device) {
                primaryLayout(
                    titleSlot = { text(LayoutString(t.title), maxLines = 1) },
                    mainSlot = { body(t) },
                    onClick = clickable(ActionBuilders.launchAction(ComponentName(context, MainActivity::class.java)), "open"),
                )
            }

        private fun MaterialScope.body(t: TileTexts): LayoutElementBuilders.LayoutElement {
            val column = LayoutElementBuilders.Column.Builder()
                .setWidth(DimensionBuilders.expand())
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            column.addContent(text(LayoutString(t.temperature), typography = Typography.NUMERAL_MEDIUM, color = colorScheme.onBackground))
            t.summary?.let { column.addContent(text(LayoutString(it), typography = Typography.LABEL_SMALL, color = colorScheme.onSurfaceVariant, maxLines = 1)) }
            // Room for two more lines on small watches: an alert beats the headline, the rain is always shown.
            val lines = listOfNotNull(t.alert?.let { it to colorScheme.error }, t.headline?.let { it to colorScheme.onSurface }).take(1) +
                listOfNotNull((t.nextHour ?: t.updated)?.let { it to colorScheme.primary })
            lines.forEach { (line, color) ->
                column.addContent(gap())
                column.addContent(text(LayoutString(line), typography = Typography.BODY_SMALL, color = color, maxLines = 2))
            }
            return column.build()
        }

        private fun gap() = LayoutElementBuilders.Spacer.Builder().setHeight(DimensionBuilders.dp(2f)).build()
    }
}
