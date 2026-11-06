package app.northvane.weather.ui.traffic

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.northvane.core.model.SavedLocation
import app.northvane.core.net.toUserMessage
import app.northvane.core.repo.attempt
import app.northvane.core.traffic.TrafficClient
import app.northvane.core.traffic.TrafficSnapshot
import app.northvane.core.traffic.WzdxFeed
import app.northvane.weather.AppContainer
import app.northvane.weather.data.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant

data class TrafficUiState(
    val loading: Boolean = false,
    val snapshot: TrafficSnapshot? = null,
    val error: String? = null,
    /** "locationId@radius", so a change of place or radius reloads. */
    val loadedFor: String? = null,
    val hasNe511Key: Boolean = false,
    /** TomTom flow tile template, when the user has a key and the layer is on. */
    val flowTiles: String? = null,
)

class TrafficViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(TrafficUiState())
    val state: StateFlow<TrafficUiState> = _state.asStateFlow()
    private var job: Job? = null
    private var jobKey: String? = null

    // The USDOT registry changes rarely; keep it for the app session.
    private val registryLock = Mutex()
    private var registry: List<WzdxFeed>? = null
    private var registryAt: Instant? = null

    private suspend fun registry(): List<WzdxFeed> = registryLock.withLock {
        val cached = registry
        if (cached != null && Duration.between(registryAt, Instant.now()) < Duration.ofDays(1)) return cached
        container.trafficClient.registry().also {
            registry = it
            registryAt = Instant.now()
        }
    }

    fun load(location: SavedLocation, stateCode: String?, radiusKm: Double, flowLayer: Boolean, dark: Boolean, force: Boolean = false) {
        val key = "${location.id}@${location.latitude},${location.longitude}@$radiusKm"
        val s = _state.value
        val fresh = s.snapshot?.let { Duration.between(it.fetchedAt, Instant.now()) < Duration.ofMinutes(5) } == true
        if (!force && s.loadedFor == key && fresh) {
            refreshFlow(flowLayer, dark)
            return
        }
        // The same load already running: let it finish. A different place or radius replaces it.
        if (job?.isActive == true && !force && jobKey == key) return
        job?.cancel()
        jobKey = key
        job = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val (ne511, tomTom) = withContext(Dispatchers.IO) {
                container.secrets.get(SecretStore.NE511_API_KEY) to container.secrets.get(SecretStore.TOMTOM_API_KEY)
            }
            val result = attempt {
                container.traffic.load(stateCode, location.point, radiusKm, ne511, { registry() }, Instant.now())
            }
            result.getOrNull()?.let { container.trafficCache.write(location.id, it) }
            // On failure keep showing this place's events: the ones on screen may be another place's.
            val fallback = if (result.isSuccess) null else if (_state.value.loadedFor == key) _state.value.snapshot else container.trafficCache.read(location.id)
            _state.update {
                it.copy(
                    loading = false,
                    snapshot = result.getOrNull() ?: fallback,
                    error = result.exceptionOrNull()?.toUserMessage(),
                    loadedFor = key,
                    hasNe511Key = ne511 != null,
                    flowTiles = tomTom?.takeIf { flowLayer }?.let { k -> TrafficClient.tomTomFlowTiles(k, dark) },
                )
            }
        }
    }

    /** Shows the last saved events for a place without downloading (satellite with traffic off). */
    fun loadCached(location: SavedLocation) {
        viewModelScope.launch {
            val cached = container.trafficCache.read(location.id) ?: return@launch
            _state.update { it.copy(snapshot = cached, loadedFor = null, flowTiles = null, error = null) }
        }
    }

    private fun refreshFlow(flowLayer: Boolean, dark: Boolean) {
        viewModelScope.launch {
            val tomTom = withContext(Dispatchers.IO) { container.secrets.get(SecretStore.TOMTOM_API_KEY) }
            _state.update { it.copy(flowTiles = tomTom?.takeIf { flowLayer }?.let { k -> TrafficClient.tomTomFlowTiles(k, dark) }) }
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { TrafficViewModel(container) }
        }

        /** "Portland, ME" → "ME". */
        fun stateCodeOf(placeName: String?): String? =
            placeName?.let { Regex(""",\s*([A-Z]{2})\s*$""").find(it)?.groupValues?.get(1) }
    }
}
