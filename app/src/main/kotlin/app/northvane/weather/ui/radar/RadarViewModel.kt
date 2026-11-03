package app.northvane.weather.ui.radar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.northvane.core.model.GeoPoint
import app.northvane.core.radar.RadarFrame
import app.northvane.core.radar.RadarSource
import app.northvane.core.radar.TileArea
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
    /** Where model frames were drawn around. */
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
            val past = attempt { container.radar.pastFrames(settings.radarSource, now) }.getOrDefault(emptyList())
            val future = if (futureAllowed) {
                // NOAA's HRRR model, starting right after the newest radar scan, drawn around the location.
                // Zoom-7 pixels (under 1 km) so the map's usual zoom doesn't show blocks; about 11° by 8° around the place.
                val area = at?.let { TileArea.around(it.latitude, it.longitude, zoom = 7, cols = 4, rows = 4) }
                val after = past.lastOrNull()?.time ?: now
                attempt { container.radar.futureFrames(now, settings.futureCastMinutes, after, area, ::decodePng) }.getOrDefault(emptyList())
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
        private const val MAX_IMAGE_EDGE = 2048

        /** ARGB pixels of a PNG, for calibrating the forecast against the radar. */
        private fun decodePng(bytes: ByteArray): IntArray? {
            // Read the size first: a small file can claim a huge picture and exhaust memory when decoded.
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth !in 1..MAX_IMAGE_EDGE || bounds.outHeight !in 1..MAX_IMAGE_EDGE) return null
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            return IntArray(bitmap.width * bitmap.height).also {
                bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                bitmap.recycle()
            }
        }

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { RadarViewModel(container) }
        }
    }
}
