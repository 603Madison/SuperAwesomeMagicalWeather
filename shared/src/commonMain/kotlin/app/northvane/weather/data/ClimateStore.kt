package app.northvane.weather.data

import app.northvane.core.repo.attempt
import app.northvane.core.garden.FrostClimate
import app.northvane.core.history.AcisClient
import app.northvane.core.json.NorthvaneJson
import app.northvane.core.model.GeoPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.northvane.core.util.fmt
import app.northvane.core.time.*

/**
 * Frost-date climatology per place (about 5 km grid), computed once from 30
 * years of NOAA daily lows and kept on the device for a year.
 */
class ClimateStore(private val files: TextFiles, private val acis: AcisClient) {
    private fun round(v: Double) = kotlin.math.floor(v * 20 + 0.5) / 20.0

    private fun file(point: GeoPoint) =
        files.path("climate", "frost_%.2f_%.2f.json".fmt(round(point.latitude), round(point.longitude)))

    suspend fun cached(point: GeoPoint): FrostClimate? = withContext(Dispatchers.Default) {
        runCatching { NorthvaneJson.decodeFromString(FrostClimate.serializer(), files.read(file(point))!!) }.getOrNull()
    }

    /** Cached value if under a year old, otherwise downloads and computes it. */
    suspend fun get(point: GeoPoint, allowDownload: Boolean): FrostClimate? {
        val cached = cached(point)
        if (cached != null && Duration.between(cached.computedAt, kotlin.time.Clock.System.now()) < Duration.ofDays(365)) return cached
        if (!allowDownload) return cached
        val lows = attempt { acis.dailyLows(point, LocalDate.now().year - 1) }.getOrNull() ?: return cached
        val climate = FrostClimate.compute(lows, kotlin.time.Clock.System.now()) ?: return cached
        withContext(Dispatchers.Default) {
            files.write(file(point), NorthvaneJson.encodeToString(FrostClimate.serializer(), climate))
        }
        return climate
    }
}
