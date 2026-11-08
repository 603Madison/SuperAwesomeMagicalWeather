package app.northvane.weather.work

import app.northvane.core.alerts.AlertAreas
import app.northvane.core.alerts.AlertToNotify
import app.northvane.core.alerts.AlertTracker
import app.northvane.core.alerts.describe
import app.northvane.core.model.SavedLocation
import app.northvane.core.model.WeatherSnapshot
import app.northvane.core.net.HttpStatusException
import app.northvane.core.outlook.BriefingWriter
import app.northvane.core.outlook.MyTimes
import app.northvane.core.outlook.TonightDigest
import app.northvane.core.outlook.WhatToWear
import app.northvane.core.time.*
import app.northvane.core.util.UnitFormatter
import app.northvane.weather.AppContainer
import app.northvane.weather.data.AppSettings
import app.northvane.weather.data.SatelliteFeature
import app.northvane.weather.net.isAllowed
import app.northvane.weather.ui.TimeFormat
import kotlinx.coroutines.flow.first

/** A notification to show: what it's about and its text. */
data class AppNotification(
    val id: String,
    val title: String,
    val text: String,
    val locationId: String?,
    val kind: Kind,
    /** For alerts: an update to one already shown, so it shouldn't make a sound. */
    val quiet: Boolean = false,
) {
    enum class Kind { WARNING, ALERT, BRIEFING, TONIGHT }
}

/**
 * The background work, written once for every platform: checking alerts for
 * the places with notifications on, and writing the morning briefing and the
 * evening heads-up. The platform decides when it runs and shows the results.
 */
object Background {
    private const val MAX_LOCATIONS = 10

    /** New and updated alerts worth a notification; also refreshes each place's cached alerts. */
    suspend fun checkAlerts(container: AppContainer, canNotify: Boolean): List<AppNotification> {
        val settings = container.settings.settings.first()
        if (!settings.alertsEnabled || (settings.alertCategories.isEmpty() && !(settings.gardenEnabled && settings.gardenFrostAlerts))) return emptyList()
        if (!isAllowed(SatelliteFeature.BACKGROUND_ALERTS, settings, container.connectivity.current())) return emptyList()
        val locations = container.locations.locations.first().filter { it.notify }.take(MAX_LOCATIONS)
        if (locations.isEmpty()) return emptyList()

        val tracker = AlertTracker()
        var state = container.alertState.read()
        val found = mutableListOf<AlertToNotify>()
        for (location in locations) {
            val result = runCatching { container.repository.alerts(location.point) }
            val error = result.exceptionOrNull()
            // A 4xx means NWS doesn't cover this point (e.g. outside the US): nothing to alert on, not a failure.
            val outsideCoverage = error is HttpStatusException && error.code in 400..499
            val alerts = result.getOrNull() ?: if (outsideCoverage) emptyList() else continue
            container.weatherCache.updateAlerts(location.id, alerts)
            // Without permission nothing can be shown: keep alerts un-notified so they appear once allowed.
            if (!canNotify) continue
            val gardenFrost = settings.gardenEnabled && settings.gardenFrostAlerts
            val (items, updated) = tracker.select(location.id, alerts, settings.alertCategories, state, kotlin.time.Clock.System.now()) { alert ->
                gardenFrost && (alert.event.contains("Frost", ignoreCase = true) || alert.event.contains("Freeze", ignoreCase = true))
            }
            state = updated
            found += items
        }
        if (canNotify) container.alertState.write(state)
        val byId = locations.associateBy { it.id }
        return found.map { item -> alertNotification(item, byId[item.locationId], settings) }
    }

    fun alertNotification(item: AlertToNotify, location: SavedLocation?, settings: AppSettings): AppNotification {
        val alert = item.alert
        val place = location?.displayName
        val spot = location?.let { AlertAreas.spot(alert, it.point) }
            ?.describe(alert) { km -> UnitFormatter(settings.units).distance(km) }
        val body = listOfNotNull(place, spot, alert.headline, alert.instruction?.take(600)).joinToString("\n\n")
        return AppNotification(
            id = "alert-${item.locationId}-${alert.threadId}",
            title = (if (item.isUpdateOfNotified) "Updated: " else "") + alert.event,
            text = body.ifBlank { alert.event },
            locationId = item.locationId,
            kind = if (alert.category == app.northvane.core.model.AlertCategory.WARNING) AppNotification.Kind.WARNING else AppNotification.Kind.ALERT,
            quiet = item.isUpdateOfNotified && alert.category != app.northvane.core.model.AlertCategory.WARNING,
        )
    }

    /** The place on the Today screen and its forecast: fresh if the network allows, else a recent cached one. */
    suspend fun todayPlace(container: AppContainer, settings: AppSettings, refresh: Boolean): Pair<SavedLocation, WeatherSnapshot>? {
        val locations = container.locations.locations.first()
        val location = locations.firstOrNull { it.id == settings.selectedLocationId } ?: locations.firstOrNull() ?: return null
        val snapshot = (if (refresh) runCatching { container.weather.refresh(location) }.getOrNull() else null)
            ?: container.weatherCache.read(location.id)?.takeIf { Duration.between(it.fetchedAt, kotlin.time.Clock.System.now()) < Duration.ofHours(12) }
            ?: return null
        return location to snapshot
    }

    /** The morning briefing for [snapshot]. */
    fun briefing(location: SavedLocation, snapshot: WeatherSnapshot, settings: AppSettings, is24: Boolean, now: Instant): AppNotification {
        val zone = runCatching { ZoneId.of(snapshot.timeZoneId) }.getOrDefault(ZoneId.systemDefault())
        val f = UnitFormatter(settings.units)
        val time = TimeFormat(zone, is24)
        val base = BriefingWriter.write(
            location.displayName, snapshot.daily, snapshot.hourly, snapshot.alerts, zone, now,
            temperature = f::temperature, wind = f::windSpeed, hour = time::hour,
        )
        val extra = listOfNotNull(
            MyTimes.sentence(MyTimes.upcoming(settings.myTimes, now, zone), snapshot.hourly, f, time::hour, now),
            if (settings.wearEnabled) WhatToWear.advise(snapshot.hourly, now, settings.comfort, time::hour, snapshot.uv?.todayMax)?.let { "Wear: $it." } else null,
        )
        val text = if (extra.isEmpty()) base.text else base.text + " " + extra.joinToString(" ")
        return AppNotification("briefing", base.title, text, location.id, AppNotification.Kind.BRIEFING)
    }

    /** The evening heads-up for one place, or null when there's nothing worth saying. */
    fun tonight(location: SavedLocation, snapshot: WeatherSnapshot, settings: AppSettings, is24: Boolean, now: Instant): AppNotification? {
        val zone = runCatching { ZoneId.of(snapshot.timeZoneId) }.getOrDefault(ZoneId.systemDefault())
        val time = TimeFormat(zone, is24)
        val lines = TonightDigest.write(
            snapshot, now, zone, UnitFormatter(settings.units), time::hour,
            TonightDigest.Options(
                garden = settings.gardenEnabled && settings.gardenPlan.isNotEmpty(),
                laundryOut = settings.laundryOutSince != null,
                pipes = settings.pipeRiskEnabled,
                ice = settings.morningIceEnabled,
                aurora = settings.auroraEnabled,
                longitude = location.longitude,
            ),
        )
        if (lines.isEmpty()) return null
        return AppNotification("tonight-${location.id}", "Tonight in ${location.displayName}", lines.joinToString(" "), location.id, AppNotification.Kind.TONIGHT)
    }
}
