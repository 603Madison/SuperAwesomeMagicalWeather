package app.northvane.weather.ui.radar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.northvane.core.model.GeoPoint
import app.northvane.core.radar.RadarFrame
import app.northvane.core.radar.RadarSource
import app.northvane.core.repo.attempt
import app.northvane.weather.AppContainer
import app.northvane.weather.data.SatelliteFeature
import app.northvane.weather.net.isAllowed
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

data class RadarUiState(
    val frames: List<RadarFrame> = emptyList(),
    val index: Int = 0,
    val playing: Boolean = false,
    val loading: Boolean = false,
    val message: String? = null,
    val source: RadarSource = RadarSource.NOAA_MRMS,
    val futureCast: Boolean = true,
    val loadedAt: Instant? = null,
    /** Where the nowcast was computed for. */
    val center: GeoPoint? = null,
) {
    val current: RadarFrame? get() = frames.getOrNull(index)
    val lastPastIndex: Int get() = frames.indexOfLast { !it.isForecast }
}

class RadarViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(RadarUiState())
    val state: StateFlow<RadarUiState> = _state.asStateFlow()
    private var playJob: Job? = null
    private var loadJob: Job? = null

    private var center: GeoPoint? = null

    fun ensureLoaded(at: GeoPoint) {
        val s = _state.value
        val moved = s.center != null && s.center != at
        center = at
        val stale = s.loadedAt == null || Duration.between(s.loadedAt, Instant.now()) > Duration.ofMinutes(5)
        if ((s.frames.isEmpty() || stale || moved) && (loadJob?.isActive != true || moved)) load()
    }

    fun load() {
        stop()
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val settings = container.settings.settings.first()
            val network = container.connectivity.status.value
            val futureAllowed = settings.futureCastEnabled && isAllowed(SatelliteFeature.FUTURE_CAST, settings, network)
            _state.update {
                it.copy(loading = true, source = settings.radarSource, futureCast = settings.futureCastEnabled, message = null)
            }
            val now = Instant.now()
            val at = center
            // The nowcast measures motion over the last half hour; the map shows the newest frames.
            val history = attempt { container.radar.pastFrames(settings.radarSource, now, count = 40) }.getOrDefault(emptyList())
            val past = history.takeLast(PAST_FRAMES)
            val future = if (futureAllowed) {
                // Radar nowcast first (it starts from what the radar sees), then the HRRR model beyond it.
                val nowcast = if (at != null && history.isNotEmpty()) {
                    attempt { container.nowcast.build(history, at, minOf(NOWCAST_MINUTES, settings.futureCastMinutes)) }.getOrDefault(emptyList())
                } else {
                    emptyList()
                }
                val modelAfter = nowcast.lastOrNull()?.time
                val remaining = settings.futureCastMinutes - (modelAfter?.let { Duration.between(now, it).toMinutes().toInt() } ?: 0)
                val model = if (remaining >= 15) {
                    attempt { container.radar.futureFrames(now, settings.futureCastMinutes) }.getOrDefault(emptyList())
                        .filter { modelAfter == null || it.time.isAfter(modelAfter) }
                } else {
                    emptyList()
                }
                nowcast + model
            } else {
                emptyList()
            }
            val frames = past + future
            _state.update {
                it.copy(
                    frames = frames,
                    index = (past.size - 1).coerceAtLeast(0),
                    loading = false,
                    loadedAt = now,
                    center = at,
                    message = if (frames.isEmpty()) "Radar is unavailable right now. Try again shortly." else null,
                )
            }
        }
    }

    fun setSource(source: RadarSource) {
        viewModelScope.launch {
            container.settings.setRadarSource(source)
            load()
        }
    }

    fun setSatelliteLayer(enabled: Boolean) {
        viewModelScope.launch { container.settings.setSatelliteLayer(enabled) }
    }

    fun seek(index: Int) {
        stop()
        _state.update { it.copy(index = index.coerceIn(0, (it.frames.size - 1).coerceAtLeast(0))) }
    }

    fun togglePlay() = if (_state.value.playing) stop() else play()

    private fun play() {
        if (_state.value.frames.size < 2) return
        _state.update { it.copy(playing = true) }
        playJob = viewModelScope.launch {
            while (isActive) {
                val s = _state.value
                // Linger on the newest frame so the loop reads clearly.
                delay(if (s.index == s.frames.lastIndex) 1_600L else 650L)
                _state.update { it.copy(index = if (it.index >= it.frames.lastIndex) 0 else it.index + 1) }
            }
        }
    }

    fun stop() {
        playJob?.cancel()
        playJob = null
        _state.update { it.copy(playing = false) }
    }

    companion object {
        /** Extrapolated radar is better than the model for about the first hour or two. */
        const val NOWCAST_MINUTES = 90
        private const val PAST_FRAMES = 7

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { RadarViewModel(container) }
        }
    }
}
