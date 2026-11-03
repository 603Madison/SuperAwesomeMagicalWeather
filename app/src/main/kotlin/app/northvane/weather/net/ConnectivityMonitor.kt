package app.northvane.weather.net

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import app.northvane.core.model.Section
import app.northvane.core.repo.FetchPlan
import app.northvane.weather.data.AppSettings
import app.northvane.weather.data.DataSaverMode
import app.northvane.weather.data.SatelliteFeature
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class NetworkStatus(
    val connected: Boolean = true,
    /** Network uses a satellite transport (e.g. T-Mobile T-Satellite / Starlink direct-to-cell). */
    val satellite: Boolean = false,
    /** Network lacks NET_CAPABILITY_NOT_BANDWIDTH_CONSTRAINED: data should be used sparingly. */
    val constrained: Boolean = false,
    val metered: Boolean = false,
    /**
     * Android's Data Saver is on. Reported even when Northvane is set to
     * "Unrestricted data", since the user still asked the phone to save data.
     */
    val dataSaver: Boolean = false,
)

/**
 * Tracks the network the app would use, following Android's guidance for
 * constrained satellite networks: request a network *without*
 * NOT_BANDWIDTH_CONSTRAINED so satellite-only connectivity is visible to us,
 * then treat it as constrained if either the capability is missing or the
 * transport is satellite.
 */
class ConnectivityMonitor(context: Context) {
    private val app = context.applicationContext
    private val cm = app.getSystemService(ConnectivityManager::class.java)
    private val _status = MutableStateFlow(NetworkStatus())
    val status: StateFlow<NetworkStatus> = _status.asStateFlow()

    private var started = false

    fun start() {
        if (started || cm == null) return
        started = true
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                _status.value = capabilities.toStatus()
            }

            override fun onLost(network: Network) {
                _status.value = NetworkStatus(connected = false)
            }
        }
        val registered = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            try {
                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_BANDWIDTH_CONSTRAINED)
                    .build()
                cm.registerBestMatchingNetworkCallback(request, callback, Handler(Looper.getMainLooper()))
                true
            } catch (e: RuntimeException) {
                false
            }
        } else {
            false
        }
        if (!registered) {
            runCatching { cm.registerDefaultNetworkCallback(callback) }
        }
        // Data Saver can be switched without the network changing.
        val dataSaverReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                _status.value = _status.value.copy(dataSaver = dataSaverOn())
            }
        }
        runCatching {
            // Sent by the system, so the receiver needn't be exported.
            ContextCompat.registerReceiver(
                app, dataSaverReceiver,
                IntentFilter(ConnectivityManager.ACTION_RESTRICT_BACKGROUND_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
        _status.value = current()
    }

    /** Fresh read, for code that runs before callbacks arrive (e.g. a background worker). */
    fun current(): NetworkStatus {
        val manager = cm ?: return NetworkStatus()
        val capabilities = manager.activeNetwork?.let { manager.getNetworkCapabilities(it) }
            ?: return if (_status.value.satellite) _status.value else NetworkStatus(connected = false)
        return capabilities.toStatus()
    }

    private fun NetworkCapabilities.toStatus(): NetworkStatus {
        val modern = Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA
        return NetworkStatus(
            connected = hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            satellite = modern && hasTransport(NetworkCapabilities.TRANSPORT_SATELLITE),
            constrained = modern && !hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_BANDWIDTH_CONSTRAINED),
            metered = !hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            dataSaver = dataSaverOn(),
        )
    }

    /** "Whitelisted" means Data Saver is on but this app is exempt, which still counts here. */
    private fun dataSaverOn(): Boolean =
        cm?.restrictBackgroundStatus.let {
            it == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED ||
                it == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED
        }
}

/**
 * True when Backwoods limits apply: forced on, or automatic on a satellite or
 * bandwidth-constrained network, or on metered data while Android's Data Saver
 * is on (Data Saver doesn't apply to unmetered Wi-Fi, so neither does this).
 */
fun isLimited(settings: AppSettings, status: NetworkStatus): Boolean = when (settings.dataSaver) {
    DataSaverMode.ALWAYS -> true
    DataSaverMode.OFF -> false
    DataSaverMode.AUTO -> status.satellite || status.constrained || (status.dataSaver && status.metered)
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
        if (SatelliteFeature.POLLEN in features) add(Section.POLLEN)
        if (SatelliteFeature.AIR_QUALITY in features) add(Section.AIR_QUALITY)
        if (SatelliteFeature.AURORA in features) add(Section.AURORA)
    }
    return FetchPlan(sections = sections, observationStations = 1, limited = true)
}
