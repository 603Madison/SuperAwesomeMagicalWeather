package app.northvane.weather.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import app.northvane.core.json.NorthvaneJson
import app.northvane.core.model.SavedLocation
import app.northvane.core.weewx.WeeWxStation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.io.IOException

/** Saved locations and WeeWX station definitions (non-secret parts only). */
class LocationStore(private val store: DataStore<Preferences>) {
    private val locationsKey = stringPreferencesKey("locations")
    private val stationsKey = stringPreferencesKey("weewx_stations")
    private val locationList = ListSerializer(SavedLocation.serializer())
    private val stationList = ListSerializer(WeeWxStation.serializer())

    private val data = store.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }

    val locations: Flow<List<SavedLocation>> = data.map { p -> savedLocations(p).orEmpty() }

    val stations: Flow<List<WeeWxStation>> = data.map { p -> savedStations(p).orEmpty() }

    /**
     * The saved places: empty when there are none, null when they can't be
     * read. Changes are skipped when they can't be read, so a list this
     * version doesn't understand (say, from a newer one) is never replaced by
     * an empty one.
     */
    private fun savedLocations(p: Preferences): List<SavedLocation>? {
        val raw = p[locationsKey] ?: return emptyList()
        return runCatching { NorthvaneJson.decodeFromString(locationList, raw) }.getOrNull()
    }

    private fun savedStations(p: Preferences): List<WeeWxStation>? {
        val raw = p[stationsKey] ?: return emptyList()
        return runCatching { NorthvaneJson.decodeFromString(stationList, raw) }.getOrNull()
    }

    suspend fun upsertLocation(location: SavedLocation) = store.edit { p ->
        val current = savedLocations(p) ?: return@edit
        val updated = if (current.any { it.id == location.id }) {
            // Refreshing a place (e.g. the device position) keeps the user's nickname and notification choice.
            current.map { if (it.id == location.id) location.copy(nickname = location.nickname ?: it.nickname, notify = it.notify) else it }
        } else if (location.isDeviceLocation) {
            listOf(location) + current
        } else {
            current + location
        }
        p[locationsKey] = NorthvaneJson.encodeToString(locationList, updated)
    }

    /** Applies [change] to the saved location [id], if it exists. */
    suspend fun updateLocation(id: String, change: (SavedLocation) -> SavedLocation) = store.edit { p ->
        val current = savedLocations(p) ?: return@edit
        p[locationsKey] = NorthvaneJson.encodeToString(locationList, current.map { if (it.id == id) change(it) else it })
    }

    /** Puts the saved locations in the order of [ids]; unknown IDs are ignored, unlisted places keep their place at the end. */
    suspend fun reorder(ids: List<String>) = store.edit { p ->
        val current = savedLocations(p) ?: return@edit
        val byId = current.associateBy { it.id }
        val ordered = ids.mapNotNull { byId[it] } + current.filter { it.id !in ids }
        p[locationsKey] = NorthvaneJson.encodeToString(locationList, ordered)
    }

    suspend fun setNickname(id: String, nickname: String?) =
        updateLocation(id) { it.copy(nickname = nickname?.trim()?.take(40)?.takeIf { n -> n.isNotEmpty() }) }

    suspend fun setNotify(id: String, enabled: Boolean) = updateLocation(id) { it.copy(notify = enabled) }

    suspend fun removeLocation(id: String) = store.edit { p ->
        val current = savedLocations(p) ?: return@edit
        p[locationsKey] = NorthvaneJson.encodeToString(locationList, current.filterNot { it.id == id })
        val stations = savedStations(p) ?: return@edit
        p[stationsKey] = NorthvaneJson.encodeToString(
            stationList,
            stations.map { if (it.linkedLocationId == id) it.copy(linkedLocationId = null) else it },
        )
    }

    /** Removes trip stops whose trip is over; returns their IDs (to clear their caches). */
    suspend fun removeEndedTrips(today: app.northvane.core.time.LocalDate): List<String> {
        var removed = emptyList<String>()
        store.edit { p ->
            val current = savedLocations(p) ?: return@edit
            val ended = current.filter { it.tripOver(today) }
            if (ended.isEmpty()) return@edit
            removed = ended.map { it.id }
            p[locationsKey] = NorthvaneJson.encodeToString(locationList, current - ended.toSet())
            val stations = savedStations(p) ?: return@edit
            p[stationsKey] = NorthvaneJson.encodeToString(
                stationList,
                stations.map { if (it.linkedLocationId in removed) it.copy(linkedLocationId = null) else it },
            )
        }
        return removed
    }

    suspend fun upsertStation(station: WeeWxStation) = store.edit { p ->
        val current = savedStations(p) ?: return@edit
        val updated = if (current.any { it.id == station.id }) {
            current.map { if (it.id == station.id) station else it }
        } else {
            current + station
        }
        p[stationsKey] = NorthvaneJson.encodeToString(stationList, updated)
    }

    suspend fun removeStation(id: String) = store.edit { p ->
        val current = savedStations(p) ?: return@edit
        p[stationsKey] = NorthvaneJson.encodeToString(stationList, current.filterNot { it.id == id })
    }

    companion object {
        const val DEVICE_LOCATION_ID = "device"
    }
}
