package app.northvane.weather.net

import app.northvane.core.model.Section
import app.northvane.core.repo.FetchPlan
import app.northvane.weather.data.AppSettings
import app.northvane.weather.data.DataSaverMode
import app.northvane.weather.data.SatelliteFeature
import kotlinx.coroutines.flow.StateFlow

data class NetworkStatus(
    val connected: Boolean = true,
    /** Network uses a satellite transport (e.g. T-Mobile T-Satellite / Starlink direct-to-cell). */
    val satellite: Boolean = false,
    /** Network lacks NET_CAPABILITY_NOT_BANDWIDTH_CONSTRAINED: data should be used sparingly. */
    val constrained: Boolean = false,
    val metered: Boolean = false,
    /**
     * Android's Data Saver is certainly on: the app is exempt from it ("Unrestricted
     * data"), which Android only reports while Data Saver is on. Counts for Backwoods
     * mode anyway, since the user still asked the phone to save data.
     */
    val dataSaver: Boolean = false,
    /**
     * Android restricts this app's background data. That's either Data Saver (with the
     * app not exempt) or the app's own "Background data" switch turned off: Android
     * reports both the same way and doesn't say which.
     */
    val restricted: Boolean = false,
)

/** The network the phone is on, as the platform reports it. */
interface Connectivity {
    val status: StateFlow<NetworkStatus>
    fun current(): NetworkStatus
}

/**
 * True when Backwoods limits apply: forced on, or automatic on a satellite or
 * bandwidth-constrained network, or on metered data while Android's Data Saver
 * is on or (unless the user turned it off) Android restricts the app's
 * background data. Data Saver doesn't apply to unmetered Wi-Fi, so neither does this.
 */
fun isLimited(settings: AppSettings, status: NetworkStatus): Boolean = when (settings.dataSaver) {
    DataSaverMode.ALWAYS -> true
    DataSaverMode.OFF -> false
    DataSaverMode.AUTO -> status.satellite || status.constrained ||
        (status.metered && (status.dataSaver || (status.restricted && settings.followRestrictedData)))
}

/** Whether [feature] may use the network right now. */
fun isAllowed(feature: SatelliteFeature, settings: AppSettings, status: NetworkStatus): Boolean =
    !isLimited(settings, status) || feature in settings.satelliteFeatures

fun fetchPlanFor(settings: AppSettings, status: NetworkStatus): FetchPlan {
    if (!isLimited(settings, status)) return FetchPlan.FULL
    val features = settings.satelliteFeatures
    val sections = buildSet {
        if (SatelliteFeature.CURRENT_CONDITIONS in features) add(Section.CURRENT_CONDITIONS)
        if (SatelliteFeature.LOCAL_STATION in features) add(Section.LOCAL_STATION)
        if (SatelliteFeature.ALERTS in features) add(Section.ALERTS)
        if (SatelliteFeature.DAILY_FORECAST in features) add(Section.DAILY_FORECAST)
        if (SatelliteFeature.HOURLY_FORECAST in features) add(Section.HOURLY_FORECAST)
        if (SatelliteFeature.FORECAST_DETAILS in features) add(Section.FORECAST_DETAILS)
        if (SatelliteFeature.OWM_FALLBACK in features) add(Section.OWM_FALLBACK)
        if (SatelliteFeature.HISTORY in features) add(Section.HISTORY)
        if (SatelliteFeature.SMOKE in features) add(Section.SMOKE)
        if (SatelliteFeature.WATER in features) add(Section.WATER)
        if (SatelliteFeature.POLLEN in features) add(Section.POLLEN)
        if (SatelliteFeature.AIR_QUALITY in features) add(Section.AIR_QUALITY)
        if (SatelliteFeature.AURORA in features) add(Section.AURORA)
    }
    return FetchPlan(sections = sections, observationStations = 1, limited = true)
}
