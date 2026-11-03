package app.northvane.core.weewx

import app.northvane.core.model.GeoPoint
import app.northvane.core.model.SavedLocation
import kotlinx.serialization.Serializable

@Serializable
enum class WeeWxFormat(val label: String) {
    AUTO("Auto-detect"),
    NORTHVANE_JSON("SuperAwesomeMagicalWeather JSON template"),
    CUMULUS_REALTIME("realtime.txt (weewx-crt)"),
    CLIENTRAW("clientraw.txt"),
}

@Serializable
enum class WeeWxAuth(val label: String) {
    NONE("None"),
    BASIC("Username & password"),
    BEARER("Bearer token"),
    HEADER("Custom header"),
}

/**
 * A user's WeeWX station. Non-secret settings only; passwords and tokens live
 * in the encrypted secret store and are passed in as [WeeWxSecret].
 */
@Serializable
data class WeeWxStation(
    val id: String,
    val name: String,
    val url: String,
    val format: WeeWxFormat = WeeWxFormat.AUTO,
    val auth: WeeWxAuth = WeeWxAuth.NONE,
    val username: String? = null,
    val headerName: String? = null,
    /**
     * Where the station is. Its readings replace NWS current conditions for
     * every saved location within [radiusKm] of this point.
     */
    val latitude: Double? = null,
    val longitude: Double? = null,
    val radiusKm: Double = DEFAULT_RADIUS_KM,
    /** Before 1.1 a station was paired with one saved location; kept to migrate old settings. */
    val linkedLocationId: String? = null,
    /** Data older than this is treated as unavailable and NWS is used instead. */
    val maxAgeMinutes: Int = 30,
    val enabled: Boolean = true,
) {
    val hasPosition: Boolean get() = latitude != null && longitude != null

    /** Distance from the station to a place, in km, or null if the station's position isn't set. */
    fun distanceKm(lat: Double, lon: Double): Double? {
        val sLat = latitude ?: return null
        val sLon = longitude ?: return null
        return GeoPoint(sLat, sLon).distanceKm(GeoPoint(lat, lon))
    }

    fun covers(location: SavedLocation): Boolean {
        val d = distanceKm(location.latitude, location.longitude) ?: return linkedLocationId == location.id
        return d <= radiusKm
    }

    companion object {
        const val DEFAULT_RADIUS_KM = 8.0
        val RADIUS_CHOICES_KM = listOf(2.0, 5.0, 8.0, 16.0, 25.0, 40.0)

        /** The enabled station covering [location]; the closest wins if several do. */
        fun forLocation(stations: List<WeeWxStation>, location: SavedLocation): WeeWxStation? =
            stations.filter { it.enabled && it.covers(location) }
                .minByOrNull { it.distanceKm(location.latitude, location.longitude) ?: 0.0 }
    }
}

/** Secret material for a station: the password, bearer token, or custom header value. */
data class WeeWxSecret(val value: String?) {
    override fun toString(): String = "WeeWxSecret(***)"
}
