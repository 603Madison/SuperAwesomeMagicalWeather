package app.northvane.wear

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.northvane.core.model.SavedLocation
import app.northvane.core.model.UnitSystem
import app.northvane.core.net.toUserMessage
import app.northvane.core.repo.attempt
import app.northvane.core.time.*
import app.northvane.wear.data.PlaceSearch
import app.northvane.wear.data.WearForecast
import app.northvane.wear.data.WearSettings
import app.northvane.wear.glance.Glance
import app.northvane.wear.work.WearRefreshWorker
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class WearState(
    val ready: Boolean = false,
    val settings: WearSettings = WearSettings(),
    val forecast: WearForecast? = null,
    val loading: Boolean = false,
    val message: String? = null,
    val searching: Boolean = false,
    val results: List<PlaceSearch.Result>? = null,
    val locating: Boolean = false,
) {
    val place: SavedLocation? get() = settings.selected
}

class WearViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as WearApp).container
    private val store = container.store
    private val _state = MutableStateFlow(WearState())
    val state: StateFlow<WearState> = _state.asStateFlow()
    private var refreshJob: Job? = null

    init {
        viewModelScope.launch {
            store.ensureLoaded()
            store.settings.collect { s -> _state.update { it.copy(settings = s, ready = true) } }
        }
        viewModelScope.launch {
            store.ensureLoaded()
            showSelected(refreshIfOlderThan = Duration.ofMinutes(10))
        }
    }

    /** Shows the cached forecast for the selected place right away, then refreshes it if it's old. */
    private suspend fun showSelected(refreshIfOlderThan: Duration?) {
        // A refresh still running is for the place shown before; it must not land on this one.
        refreshJob?.cancel()
        _state.update { it.copy(loading = false) }
        val place = store.settings.value.selected ?: run {
            _state.update { it.copy(forecast = null) }
            return
        }
        val cached = store.forecast(place.id)
        _state.update { it.copy(forecast = cached, message = null) }
        val stale = cached == null || refreshIfOlderThan == null ||
            Duration.between(cached.snapshot.fetchedAt, kotlin.time.Clock.System.now()) > refreshIfOlderThan
        if (stale) refresh()
    }

    /** Opening the app is a good moment to update the current location (in the foreground, with permission). */
    fun refresh() {
        val selected = store.settings.value.selected ?: return
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, message = null) }
            val place = if (selected.isDeviceLocation && hasLocationPermission()) locate(selected) ?: selected else selected
            val result = container.refresh(place, store.settings.value.nextHourRain)
            if (store.settings.value.tilePlace?.id == place.id) WearRefreshWorker.updateSurfaces(getApplication())
            if (store.settings.value.selected?.id != place.id) return@launch
            _state.update {
                it.copy(
                    loading = false,
                    forecast = result.getOrNull() ?: it.forecast,
                    message = result.exceptionOrNull()?.let { e -> "Couldn't update: ${e.toUserMessage()}" },
                )
            }
        }
    }

    /** Moves the current-location place to a new fix, if the watch gets one in time. */
    private suspend fun locate(place: SavedLocation): SavedLocation? {
        val fix = attempt { container.deviceLocation.current(Duration.ofSeconds(20)) }.getOrNull() ?: return null
        val moved = place.copy(latitude = fix.latitude, longitude = fix.longitude)
        store.update { s -> s.copy(places = s.places.map { if (it.id == place.id) moved else it }) }
        return moved
    }

    fun select(id: String) {
        viewModelScope.launch {
            if (store.settings.value.places.none { it.id == id }) return@launch
            store.update { it.copy(selectedId = id) }
            showSelected(refreshIfOlderThan = Duration.ofMinutes(10))
        }
    }

    fun addResult(result: PlaceSearch.Result) {
        val place = PlaceSearch.toPlace(result)
        viewModelScope.launch {
            store.update { it.copy(places = it.places + place, selectedId = place.id) }
            _state.update { it.copy(results = null) }
            afterPlacesChanged()
            showSelected(refreshIfOlderThan = null)
        }
    }

    fun removePlace(id: String) {
        viewModelScope.launch {
            store.update { it.without(id) }
            store.deleteForecast(id)
            afterPlacesChanged()
            showSelected(refreshIfOlderThan = Duration.ofMinutes(10))
        }
    }

    /** Adds (or selects) "Current location" from the watch's position. Permission is checked by the caller. */
    fun useCurrentLocation(onDone: (Boolean) -> Unit) {
        if (_state.value.locating) return
        _state.update { it.copy(locating = true, message = null) }
        viewModelScope.launch {
            val fix = attempt { container.deviceLocation.current() }.getOrNull()
            _state.update { it.copy(locating = false, message = if (fix == null) "No location fix. Try outdoors, or search for a place." else null) }
            if (fix == null) {
                onDone(false)
                return@launch
            }
            // Find-or-add in one store update, so there is only ever one current location.
            store.update { s ->
                val existing = s.deviceLocation
                if (existing != null) {
                    s.copy(places = s.places.map { if (it.id == existing.id) it.copy(latitude = fix.latitude, longitude = fix.longitude) else it }, selectedId = existing.id)
                } else {
                    val place = SavedLocation(UUID.randomUUID().toString(), Glance.CURRENT_LOCATION, fix.latitude, fix.longitude, isDeviceLocation = true)
                    // First in the list: it's the place most people want.
                    s.copy(places = listOf(place) + s.places, selectedId = place.id)
                }
            }
            afterPlacesChanged()
            showSelected(refreshIfOlderThan = null)
            onDone(true)
        }
    }

    fun search(query: String) {
        viewModelScope.launch {
            _state.update { it.copy(searching = true, results = null, message = null) }
            val result = attempt { container.places.search(query) }
            _state.update {
                it.copy(
                    searching = false,
                    results = result.getOrNull() ?: emptyList(),
                    message = result.exceptionOrNull()?.let { e -> "Search failed: ${e.toUserMessage()}" },
                )
            }
        }
    }

    fun clearSearch() = _state.update { it.copy(results = null, searching = false) }

    fun setUnits(units: UnitSystem) = change { it.copy(units = units) }
    fun setNextHourRain(on: Boolean) = change { it.copy(nextHourRain = on) }
    fun setAlerts(on: Boolean) = change { it.copy(alertsEnabled = on) }
    fun setBackgroundUpdates(on: Boolean) = change { it.copy(backgroundUpdates = on) }
    /** Null makes the tile follow the place shown in the app. */
    fun setTilePlace(id: String?) = change { it.copy(tilePlaceId = id) }
    fun markNotifyAsked() = viewModelScope.launch { store.update { it.copy(notifyAsked = true) } }
    fun dismissMessage() = _state.update { it.copy(message = null) }

    private fun change(edit: (WearSettings) -> WearSettings) {
        viewModelScope.launch {
            store.update(edit)
            afterPlacesChanged()
        }
    }

    /** The tile, the complication and the background job follow the settings. */
    private fun afterPlacesChanged() {
        val app = getApplication<Application>()
        runCatching { WearRefreshWorker.sync(app, store.settings.value) }
        WearRefreshWorker.updateSurfaces(app)
    }

    fun hasLocationPermission(): Boolean = ContextCompat.checkSelfPermission(getApplication(), Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
}
