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
        // A refresh still running is for the place shown before; it must not land on this one.
        refreshJob?.cancel()
        _state.update { it.copy(loading = false) }
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
            // Only show it if this place is still the one on screen.
            if (store.settings.value.selected?.id != place.id) return@launch
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

    /** Places from the main app's QR code; ones already saved (within about 1 km) are skipped. */
    fun importPlaces(text: String?, onDone: (Boolean) -> Unit) {
        val incoming = text?.let { app.northvane.core.model.PlacesCode.decode(it) }
        if (incoming == null) {
            _state.update { it.copy(message = "No code found. Fill the photo with the QR code and try again.") }
            onDone(false)
            return
        }
        viewModelScope.launch {
            var added = emptyList<app.northvane.core.model.SavedLocation>()
            store.update { s ->
                added = app.northvane.core.model.PlacesCode.newOnes(incoming, s.places)
                s.copy(places = s.places + added, selectedId = s.selectedId ?: added.firstOrNull()?.id)
            }
            _state.update { it.copy(message = if (added.isEmpty()) "Those places are already here." else "Added ${added.size} place${if (added.size == 1) "" else "s"}.") }
            showSelected(refreshIfOlderThan = null)
            onDone(added.isNotEmpty())
        }
    }

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
        // One request at a time: pressing OK again while it's looking does nothing.
        if (_state.value.locating) return
        _state.update { it.copy(locating = true, message = null) }
        viewModelScope.launch {
            val fix = attempt { container.deviceLocation.current() }.getOrNull()
            _state.update { it.copy(locating = false, message = if (fix == null) "Couldn't get a location fix. Try near a window, or search instead." else null) }
            if (fix == null) {
                onDone(false)
                return@launch
            }
            // Find-or-add in one store update, so there is only ever one "My location".
            store.update { s ->
                val existing = s.places.firstOrNull { it.isDeviceLocation }
                if (existing != null) {
                    s.copy(
                        places = s.places.map { if (it.id == existing.id) it.copy(latitude = fix.latitude, longitude = fix.longitude, name = MY_LOCATION) else it },
                        selectedId = existing.id,
                    )
                } else {
                    val place = SavedLocation(UUID.randomUUID().toString(), MY_LOCATION, fix.latitude, fix.longitude, isDeviceLocation = true)
                    s.copy(places = s.places + place, selectedId = place.id)
                }
            }
            _state.update { it.copy(results = null) }
            showSelected(refreshIfOlderThan = null)
            onDone(true)
        }
    }

    fun setUnits(units: UnitSystem) = viewModelScope.launch { store.update { it.copy(units = units) } }
    fun setTheme(theme: MiniTheme) = viewModelScope.launch { store.update { it.copy(theme = theme) } }

    fun setAlerts(enabled: Boolean, minutes: Int = store.settings.value.alertCheckMinutes) {
        viewModelScope.launch {
            store.update { it.copy(alertsEnabled = enabled, alertCheckMinutes = minutes) }
            runCatching { MiniAlertWorker.sync(getApplication(), store.settings.value) }
        }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    fun markNotifyAsked() = viewModelScope.launch { store.update { it.copy(notifyAsked = true) } }

    /** Off, then a few morning times, round and round with OK. */
    fun cycleBriefing() = viewModelScope.launch {
        store.update { s ->
            val i = BRIEFING_TIMES.indexOf(s.briefingMinutes)
            s.copy(briefingMinutes = BRIEFING_TIMES[(i + 1) % BRIEFING_TIMES.size])
        }
        runCatching { app.northvane.mini.work.MiniBriefingWorker.schedule(getApplication(), store.settings.value) }
    }

    companion object {
        const val MY_LOCATION = "My location"
        val BRIEFING_TIMES: List<Int?> = listOf(null, 6 * 60, 6 * 60 + 30, 7 * 60, 7 * 60 + 30, 8 * 60, 9 * 60)

        /** Mini downloads only what it shows: about 25 KB per refresh. */
        val PLAN = FetchPlan(
            sections = setOf(Section.CURRENT_CONDITIONS, Section.ALERTS, Section.DAILY_FORECAST, Section.HOURLY_FORECAST, Section.FORECAST_DETAILS),
            observationStations = 2,
        )
    }
}
