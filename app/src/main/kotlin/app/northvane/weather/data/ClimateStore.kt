package app.northvane.weather.data

import android.content.Context
import app.northvane.core.garden.FrostClimate
import app.northvane.core.history.AcisClient
import app.northvane.core.json.NorthvaneJson
import app.northvane.core.model.GeoPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import app.northvane.core.time.*

/**
 * Frost-date climatology per place (about 5 km grid), computed once from 30
 * years of NOAA daily lows and kept on the device for a year.
 */
class ClimateStore(context: Context, private val acis: AcisClient) {
    private val dir = File(context.applicationContext.noBackupFilesDir, "climate")

    private fun file(point: GeoPoint) =
        File(dir, String.format(Locale.ROOT, "frost_%.2f_%.2f.json", Math.round(point.latitude * 20) / 20.0, Math.round(point.longitude * 20) / 20.0))

    suspend fun cached(point: GeoPoint): FrostClimate? = withContext(Dispatchers.IO) {
        runCatching { NorthvaneJson.decodeFromString(FrostClimate.serializer(), file(point).readText()) }.getOrNull()
    }

    /** Cached value if under a year old, otherwise downloads and computes it. */
    suspend fun get(point: GeoPoint, allowDownload: Boolean): FrostClimate? {
        val cached = cached(point)
        if (cached != null && Duration.between(cached.computedAt, kotlin.time.Clock.System.now()) < Duration.ofDays(365)) return cached
        if (!allowDownload) return cached
        val lows = runCatching { acis.dailyLows(point, LocalDate.now().year - 1) }.getOrNull() ?: return cached
        val climate = FrostClimate.compute(lows, kotlin.time.Clock.System.now()) ?: return cached
        withContext(Dispatchers.IO) {
            dir.mkdirs()
            file(point).writeText(NorthvaneJson.encodeToString(FrostClimate.serializer(), climate))
        }
        return climate
    }
}
