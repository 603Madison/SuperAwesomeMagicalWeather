package app.northvane.mini

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.northvane.core.model.SavedLocation
import app.northvane.core.model.Section
import app.northvane.core.model.UnitSystem
import app.northvane.core.model.WeatherSnapshot
import app.northvane.core.net.toUserMessage
import app.northvane.core.repo.FetchPlan
import app.northvane.core.repo.SourceConfig
import app.northvane.core.repo.attempt
import app.northvane.mini.data.MiniSettings
import app.northvane.mini.data.MiniTheme
import app.northvane.mini.data.PlaceSearch
import app.northvane.mini.work.MiniAlertWorker
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class MiniState(
    val ready: Boolean = false,
    val settings: MiniSettings = MiniSettings(),
    val snapshot: WeatherSnapshot? = null,
    val loading: Boolean = false,
    val message: String? = null,
    val searching: Boolean = false,
    val results: List<PlaceSearch.Result>? = null,
    val locating: Boolean = false,
) {
    val place: SavedLocation? get() = settings.selected
}

class MiniViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as MiniApp).container
    private val store = container.store
    private val _state = MutableStateFlow(MiniState())
    val state: StateFlow<MiniState> = _state.asStateFlow()
    private var refreshJob: Job? = null

    init {
        viewModelScope.launch {
            store.load()
            store.settings.collect { s -> _state.update { it.copy(settings = s, ready = true) } }
        }
        viewModelScope.launch {
            store.load()
            showSelected(refreshIfOlderThan = Duration.ofMinutes(10))
        }
    }

    /** Shows the cached forecast for the selected place right away, then refreshes it if it's old. */
    private suspend fun showSelected(refreshIfOlderThan: Duration?) {
        val place = store.settings.value.selected ?: run {
            _state.update { it.copy(snapshot = null) }
            return
        }
        val cached = store.snapshot(place.id)
        _state.update { it.copy(snapshot = cached, message = null) }
        val stale = cached == null || refreshIfOlderThan == null || Duration.between(cached.fetchedAt, Instant.now()) > refreshIfOlderThan
        if (stale) refresh()
    }

    fun refresh() {
        val place = store.settings.value.selected ?: return
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, message = null) }
            val result = attempt { container.repository.fetch(place, SourceConfig(), PLAN) }
            result.getOrNull()?.let { snapshot ->
                store.saveSnapshot(snapshot)
                // A place added from GPS takes the town name NWS gives its point.
                if (place.isDeviceLocation && place.name == MY_LOCATION && snapshot.placeName != null) {
                    store.update { s -> s.copy(places = s.places.map { if (it.id == place.id) it.copy(name = snapshot.placeName!!) else it }) }
                }
            }
            _state.update {
                it.copy(
                    loading = false,
                    snapshot = result.getOrNull() ?: it.snapshot,
                    message = result.exceptionOrNull()?.let { e -> "Couldn't update: ${e.toUserMessage()}" },
                )
            }
        }
    }

    fun select(id: String) {
        viewModelScope.launch {
            store.update { it.copy(selectedId = id) }
            showSelected(refreshIfOlderThan = Duration.ofMinutes(10))
        }
    }

    fun addPlace(place: SavedLocation) {
        viewModelScope.launch {
            store.update { it.copy(places = it.places + place, selectedId = place.id) }
            _state.update { it.copy(results = null) }
            showSelected(refreshIfOlderThan = null)
        }
    }

    fun addResult(result: PlaceSearch.Result) = addPlace(container.places.toPlace(result))

    fun removePlace(id: String) {
        viewModelScope.launch {
            store.update { s ->
                val remaining = s.places.filterNot { it.id == id }
                s.copy(places = remaining, selectedId = if (s.selectedId == id) remaining.firstOrNull()?.id else s.selectedId)
            }
            store.deleteSnapshot(id)
            showSelected(refreshIfOlderThan = Duration.ofMinutes(10))
        }
    }

    fun movePlace(id: String, by: Int) {
        viewModelScope.launch {
            store.update { s ->
                val list = s.places.toMutableList()
                val i = list.indexOfFirst { it.id == id }
                val j = (i + by).coerceIn(0, list.lastIndex)
                if (i >= 0 && i != j) list.add(j, list.removeAt(i))
                s.copy(places = list)
            }
        }
    }

    fun toggleNotify(id: String) {
        viewModelScope.launch { store.update { s -> s.copy(places = s.places.map { if (it.id == id) it.copy(notify = !it.notify) else it }) } }
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

    /** Adds (or updates) the "My location" place from the phone's position. Permission is checked by the caller. */
    fun useMyLocation(onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(locating = true, message = null) }
            val fix = attempt { container.deviceLocation.current() }.getOrNull()
            _state.update { it.copy(locating = false, message = if (fix == null) "Couldn't get a location fix. Try near a window, or search instead." else null) }
            if (fix == null) {
                onDone(false)
                return@launch
            }
            val existing = store.settings.value.places.firstOrNull { it.isDeviceLocation }
            if (existing != null) {
                store.update { s ->
                    s.copy(
                        places = s.places.map { if (it.id == existing.id) it.copy(latitude = fix.latitude, longitude = fix.longitude, name = MY_LOCATION) else it },
                        selectedId = existing.id,
                    )
                }
                showSelected(refreshIfOlderThan = null)
            } else {
                addPlace(SavedLocation(UUID.randomUUID().toString(), MY_LOCATION, fix.latitude, fix.longitude, isDeviceLocation = true))
            }
            onDone(true)
        }
    }

    fun setUnits(units: UnitSystem) = viewModelScope.launch { store.update { it.copy(units = units) } }
    fun setTheme(theme: MiniTheme) = viewModelScope.launch { store.update { it.copy(theme = theme) } }

    fun setAlerts(enabled: Boolean, minutes: Int = store.settings.value.alertCheckMinutes) {
        viewModelScope.launch {
            store.update { it.copy(alertsEnabled = enabled, alertCheckMinutes = minutes) }
            MiniAlertWorker.sync(getApplication(), store.settings.value)
        }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    companion object {
        const val MY_LOCATION = "My location"

        /** Mini downloads only what it shows: about 25 KB per refresh. */
        val PLAN = FetchPlan(
            sections = setOf(Section.CURRENT_CONDITIONS, Section.ALERTS, Section.DAILY_FORECAST, Section.HOURLY_FORECAST, Section.FORECAST_DETAILS),
            observationStations = 2,
        )
    }
}
