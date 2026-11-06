package app.northvane.weather.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.northvane.core.model.SavedLocation
import app.northvane.core.model.WeatherSnapshot
import app.northvane.core.net.toUserMessage
import app.northvane.core.owm.PlaceResult
import app.northvane.core.util.Solar
import app.northvane.weather.AppContainer
import app.northvane.weather.data.AppSettings
import app.northvane.weather.data.LocationStore
import app.northvane.weather.data.SecretStore
import app.northvane.weather.data.AiSummary
import app.northvane.weather.net.LocalNetworkAccess
import app.northvane.core.ai.AiConfig
import app.northvane.core.ai.WeatherBrief
import app.northvane.core.model.Section
import app.northvane.core.repo.attempt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.northvane.weather.data.SatelliteFeature
import app.northvane.core.garden.FrostClimate
import app.northvane.weather.net.NetworkStatus
import app.northvane.weather.net.isLimited
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class MainUiState(
    val ready: Boolean = false,
    val locations: List<SavedLocation> = emptyList(),
    val selected: SavedLocation? = null,
    val snapshot: WeatherSnapshot? = null,
    val refreshing: Boolean = false,
    val error: String? = null,
    val settings: AppSettings = AppSettings(),
    val network: NetworkStatus = NetworkStatus(),
    val liteMode: Boolean = false,
    /** Whether a Google Pollen API key is saved (the key itself never leaves the secret store). */
    val pollenKeySet: Boolean = false,
    /** Frost-date climatology for the selected place (garden and heating cards). */
    val frostClimate: FrostClimate? = null,
    val ai: AiUiState = AiUiState(),
) {
    /**
     * Under satellite limits, cards only show data downloaded in the last 12
     * hours unless the user chose to see stale data.
     */
    fun shows(section: Section, now: Instant = Instant.now()): Boolean {
        if (!liteMode || settings.showStaleData) return true
        val age = snapshot?.ageOf(section, now) ?: return false
        return age < STALE_AFTER
    }

    companion object {
        val STALE_AFTER: Duration = Duration.ofHours(12)
    }
}

data class AiUiState(val summary: AiSummary? = null, val loading: Boolean = false, val error: String? = null)

/** Activity-scoped state shared by the Today, Radar and Alerts tabs. */
class MainViewModel(private val container: AppContainer) : ViewModel() {
    private val snapshots = MutableStateFlow<Map<String, WeatherSnapshot>>(emptyMap())
    private val refreshing = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)
    private val climates = MutableStateFlow<Map<String, FrostClimate>>(emptyMap())
    private val aiStates = MutableStateFlow<Map<String, AiUiState>>(emptyMap())
    private val extras = combine(climates, aiStates) { c, a -> c to a }
    private var refreshJob: Job? = null
    private var refreshingId: String? = null

    private val inputs = combine(
        container.locations.locations,
        container.settings.settings,
        container.connectivity.status,
    ) { locations, settings, network -> Triple(locations, settings, network) }

    val state: StateFlow<MainUiState> = combine(inputs, snapshots, refreshing, error, extras) { input, snaps, isRefreshing, err, extra ->
        val (climateMap, aiMap) = extra
        val (locations, settings, network) = input
        val selected = locations.firstOrNull { it.id == settings.selectedLocationId } ?: locations.firstOrNull()
        MainUiState(
            ready = true,
            locations = locations,
            selected = selected,
            snapshot = selected?.let { snaps[it.id] },
            refreshing = isRefreshing,
            error = err,
            settings = settings,
            network = network,
            liteMode = isLimited(settings, network),
            pollenKeySet = settings.pollenEnabled && container.secrets.has(SecretStore.POLLEN_API_KEY),
            frostClimate = selected?.let { climateMap[it.id] },
            ai = selected?.let { aiMap[it.id] } ?: AiUiState(),
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, MainUiState())

    init {
        // AI summary: written once per forecast download (at most every 45 minutes), on the user's own server.
        viewModelScope.launch {
            state.map { st -> Triple(st.selected, st.snapshot?.fetchedAt, with(st.settings) { listOf(aiEnabled, aiProvider, aiBaseUrl, aiModel, units) }) }
                .distinctUntilChanged()
                .collect { (location, fetchedAt, _) -> if (location != null && fetchedAt != null) summarize(location, force = false) }
        }
        // Frost dates are only downloaded for people who use the garden or heating cards.
        viewModelScope.launch {
            state.map { Triple(it.selected, it.settings.gardenEnabled || it.settings.heatingEnabled, it.liteMode) }
                .distinctUntilChanged { a, b -> a.first?.id == b.first?.id && a.second == b.second && a.third == b.third }
                .collect { (location, wanted, lite) ->
                    if (location == null || !wanted || climates.value.containsKey(location.id)) return@collect
                    val allowed = !lite || SatelliteFeature.GARDEN_CLIMATE in state.value.settings.satelliteFeatures
                    container.climate.get(location.point, allowDownload = allowed)?.let { c -> climates.update { it + (location.id to c) } }
                }
        }
        viewModelScope.launch {
            state.map { it.selected }.filterNotNull().distinctUntilChanged { a, b -> a.id == b.id }.collect { location ->
                error.value = null
                loadCachedThenMaybeRefresh(location)
            }
        }
    }

    private suspend fun loadCachedThenMaybeRefresh(location: SavedLocation) {
        val cached = snapshots.value[location.id] ?: container.weather.cached(location.id)?.also { snapshot ->
            snapshots.update { it + (location.id to snapshot) }
        }
        val interval = container.weather.refreshInterval(container.weather.currentPlan(container.settings.settings.first()))
        if (cached == null || Duration.between(cached.fetchedAt, Instant.now()) > interval) refresh(location)
    }

    /** Called when the app returns to the foreground. */
    fun onForeground() {
        val location = state.value.selected ?: return
        viewModelScope.launch { loadCachedThenMaybeRefresh(location) }
    }

    fun refresh(requested: SavedLocation? = state.value.selected) {
        val location: SavedLocation = requested ?: return
        if (refreshJob?.isActive == true) {
            if (refreshingId == location.id) return
            refreshJob?.cancel()
        }
        refreshingId = location.id
        refreshJob = viewModelScope.launch {
            refreshing.value = true
            error.value = null
            try {
                var target: SavedLocation = location
                if (location.isDeviceLocation) {
                    container.deviceLocation.current()?.let { p ->
                        if (Solar.distanceKm(location.latitude, location.longitude, p.latitude, p.longitude) > 1.0) {
                            target = location.copy(latitude = p.latitude, longitude = p.longitude)
                            // Update only: if the place was removed meanwhile, it stays removed.
                            container.locations.updateLocation(target.id) { it.copy(latitude = p.latitude, longitude = p.longitude) }
                        }
                    }
                }
                val snapshot = container.weather.refresh(target)
                // Removed while it was loading: don't bring it back.
                if (state.value.locations.none { it.id == target.id }) {
                    container.weatherCache.delete(target.id)
                    return@launch
                }
                snapshots.update { it + (target.id to snapshot) }
                val place = snapshot.placeName
                if (target.isDeviceLocation && place != null && place != target.name) {
                    container.locations.updateLocation(target.id) { it.copy(name = place) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error.value = e.toUserMessage()
            } finally {
                // A cancelled refresh finishing late must not hide the spinner of the one that replaced it.
                if (refreshJob === coroutineContext[kotlinx.coroutines.Job]) refreshing.value = false
            }
        }
    }

    fun selectLocation(id: String) {
        viewModelScope.launch {
            container.settings.setSelectedLocation(id)
            // The widget follows the place on the Today screen.
            app.northvane.weather.widget.WeatherWidget.updateAll(container.app)
        }
    }

    fun addPlace(place: PlaceResult) {
        viewModelScope.launch {
            val location = SavedLocation(UUID.randomUUID().toString(), place.name, place.latitude, place.longitude)
            container.locations.upsertLocation(location)
            container.settings.setSelectedLocation(location.id)
        }
    }

    /** Requires location permission (precise or approximate) to already be granted. */
    fun useDeviceLocation(onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val point = container.deviceLocation.current()
            if (point == null) {
                error.value = "Couldn't determine your location. Check that location services are on."
                onDone(false)
                return@launch
            }
            val existing = state.value.locations.firstOrNull { it.isDeviceLocation }
            val location = SavedLocation(
                id = LocationStore.DEVICE_LOCATION_ID,
                name = existing?.name ?: "Current location",
                latitude = point.latitude,
                longitude = point.longitude,
                isDeviceLocation = true,
            )
            container.locations.upsertLocation(location)
            container.settings.setSelectedLocation(location.id)
            onDone(true)
            refresh(location)
        }
    }

    fun removeLocation(id: String) {
        if (refreshingId == id) refreshJob?.cancel()
        viewModelScope.launch {
            container.locations.removeLocation(id)
            container.weatherCache.delete(id)
            snapshots.update { it - id }
        }
    }

    fun renameLocation(id: String, nickname: String?) {
        viewModelScope.launch { container.locations.setNickname(id, nickname) }
    }

    fun setLocationNotify(id: String, enabled: Boolean) {
        viewModelScope.launch { container.locations.setNotify(id, enabled) }
    }

    /** Marks a crop planted today (harvest timing then counts from now). */
    fun markPlanted(crop: app.northvane.core.garden.Crop) {
        viewModelScope.launch {
            val plan = state.value.settings.gardenPlan
            container.settings.setGardenPlan(plan.map { if (it.crop == crop) it.copy(plantedOn = java.time.LocalDate.now()) else it })
        }
    }

    /** Asks the user's AI server for a short summary of the selected place's forecast. */
    fun summarize(location: SavedLocation? = state.value.selected, force: Boolean = true) {
        location ?: return
        val st = state.value
        val settings = st.settings
        val snapshot = snapshots.value[location.id] ?: return
        if (!settings.aiEnabled || settings.aiBaseUrl.isBlank() || settings.aiModel.isBlank()) return
        if (st.liteMode && SatelliteFeature.AI_SUMMARY !in settings.satelliteFeatures) return
        viewModelScope.launch {
            val existing = aiStates.value[location.id]?.summary ?: container.aiSummaries.read(location.id)
            val now = Instant.now()
            val recent = existing != null && existing.model == settings.aiModel &&
                (existing.forecastTime == snapshot.fetchedAt || Duration.between(existing.createdAt, now) < Duration.ofMinutes(45))
            if (!force && recent) {
                aiStates.update { it + (location.id to AiUiState(existing)) }
                return@launch
            }
            if (LocalNetworkAccess.needsPermissionFor(container.app, settings.aiBaseUrl)) {
                aiStates.update { it + (location.id to AiUiState(existing, error = "Allow local network access for your AI server in Settings › AI summary")) }
                return@launch
            }
            aiStates.update { it + (location.id to AiUiState(existing, loading = true)) }
            val key = withContext(Dispatchers.IO) { container.secrets.get(SecretStore.AI_API_KEY) }
            val config = AiConfig(settings.aiProvider, settings.aiBaseUrl, settings.aiModel, key)
            val result = attempt { container.ai.complete(config, WeatherBrief.messages(snapshot, location.displayName, settings.units, now)) }
            result.onSuccess { text ->
                val summary = AiSummary(location.id, text, settings.aiModel, snapshot.fetchedAt, Instant.now())
                container.aiSummaries.write(summary)
                aiStates.update { it + (location.id to AiUiState(summary)) }
            }.onFailure { e ->
                aiStates.update { it + (location.id to AiUiState(existing, error = e.toUserMessage())) }
            }
        }
    }

    fun moveLocation(id: String, offset: Int) {
        viewModelScope.launch { container.locations.move(id, offset) }
    }

    fun setFollowRestrictedData(follow: Boolean) {
        viewModelScope.launch { container.settings.setFollowRestrictedData(follow) }
    }

    fun setHourlyExpanded(expanded: Boolean) {
        viewModelScope.launch { container.settings.setHourlyExpanded(expanded) }
    }

    /** Laundry is out on the line at the selected place: watch the forecast for rain. */
    fun laundryOut(out: Boolean) {
        viewModelScope.launch {
            container.settings.setLaundryOut(if (out) state.value.selected?.id else null)
            if (out) app.northvane.weather.work.LaundryWatchWorker.start(container.app) else app.northvane.weather.work.LaundryWatchWorker.stop(container.app)
        }
    }

    fun dismissError() {
        error.value = null
    }

    fun markNotificationPromptShown() {
        viewModelScope.launch { container.settings.setNotificationPromptShown() }
    }

    /** Run an alert check right away (e.g. just after notification permission is granted). */
    fun checkAlertsNow() = container.alertScheduler.checkNow()

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { MainViewModel(container) }
        }
    }
}
