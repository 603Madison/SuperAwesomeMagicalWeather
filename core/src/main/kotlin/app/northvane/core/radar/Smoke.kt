package app.northvane.core.radar

import app.northvane.core.model.GeoPoint
import app.northvane.core.net.HttpStatusException
import app.northvane.core.net.WeatherApiException
import app.northvane.core.net.fetch
import app.northvane.core.json.InstantSerializer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.floor

/** Near-surface wildfire smoke at one hour, in µg/m³ of fine particles. */
@Serializable
data class SmokeHour(@Serializable(InstantSerializer::class) val time: Instant, val microgramsPerM3: Double)

@Serializable
data class SmokeOutlook(
    @Serializable(InstantSerializer::class) val fetchedAt: Instant,
    /** The HRRR run the hours come from. */
    @Serializable(InstantSerializer::class) val run: Instant,
    val hours: List<SmokeHour>,
) {
    val peak: SmokeHour? get() = hours.maxByOrNull { it.microgramsPerM3 }
}

/**
 * How smoky the air is, on the EPA's PM2.5 scale (2024 breakpoints), since
 * smoke is mostly fine particles. Smoke adds to whatever else is in the air.
 */
enum class SmokeLevel(val label: String, val from: Double) {
    NONE("None", 0.0),
    LIGHT("Light haze", 5.0),
    MODERATE("Moderate", 9.1),
    SENSITIVE("Unhealthy for sensitive groups", 35.5),
    UNHEALTHY("Unhealthy", 55.5),
    VERY_UNHEALTHY("Very unhealthy", 125.5),
    HAZARDOUS("Hazardous", 225.5),
    ;

    companion object {
        fun of(microgramsPerM3: Double): SmokeLevel = entries.last { microgramsPerM3 >= it.from }
    }
}

/**
 * Wildfire smoke forecast from NOAA's HRRR model (its smoke tracer, MASSDEN
 * 8 m above ground), read straight from NOAA's open data on AWS like the
 * future cast. Each forecast hour is a CONUS field of about 480 KB, so only a
 * few hours are fetched: from the newest 00/06/12/18 UTC run, which reaches
 * 48 hours ahead.
 */
class HrrrSmoke(private val http: OkHttpClient, private val base: String = HrrrForecast.AWS_BASE) {

    suspend fun outlook(point: GeoPoint, now: Instant, aheadHours: List<Long> = DEFAULT_HOURS): SmokeOutlook = coroutineScope {
        val run = newestLongRun(now) ?: throw WeatherApiException("No recent HRRR smoke forecast")
        val nowHour = now.truncatedTo(ChronoUnit.HOURS)
        val hours = aheadHours.map { ahead ->
            async {
                val valid = nowHour.plus(Duration.ofHours(ahead))
                val lead = Duration.between(run, valid).toHours()
                if (lead !in 1..48) return@async null
                smokeAt(run, lead.toInt(), point)?.let { SmokeHour(valid, it) }
            }
        }.awaitAll().filterNotNull()
        if (hours.isEmpty()) throw WeatherApiException("No HRRR smoke forecast for this place")
        SmokeOutlook(now, run, hours)
    }

    /** The newest run that goes out 48 hours and has published far enough to cover the next day. */
    private suspend fun newestLongRun(now: Instant): Instant? {
        val latest = now.truncatedTo(ChronoUnit.HOURS).let { it.minus(Duration.ofHours((it.atOffset(ZoneOffset.UTC).hour % 6).toLong())) }
        for (back in 0..2) {
            val run = latest.minus(Duration.ofHours(6L * back))
            val lead = (Duration.between(run, now).toHours() + 25).coerceAtMost(48).toInt()
            if (index(run, lead) != null) return run
        }
        return null
    }

    private suspend fun smokeAt(run: Instant, lead: Int, point: GeoPoint): Double? {
        val entries = index(run, lead) ?: return null
        val i = entries.indexOfFirst { it.field == "MASSDEN" && it.level == "8 m above ground" }
        if (i < 0) return null
        val start = entries[i].offset
        val end = entries.getOrNull(i + 1)?.offset?.minus(1) ?: return null
        val request = Request.Builder().url(fileUrl(run, lead)).header("Range", "bytes=$start-$end").build()
        val bytes = runInterruptible(Dispatchers.IO) {
            http.newCall(request).execute().use { response ->
                if (response.code != 206 && response.code != 200) throw HttpStatusException(response.code, "NOAA HRRR")
                val source = response.body.source()
                if (source.request(MAX_FIELD_BYTES + 1)) throw HttpStatusException(413, "NOAA HRRR")
                source.buffer.readByteArray()
            }
        }
        val field = kotlinx.coroutines.withContext(Dispatchers.Default) { Grib2.decode(bytes) }
        // kg/m³ to µg/m³.
        return sample(field, point.latitude, point.longitude)?.let { it * 1e9 }
    }

    private suspend fun index(run: Instant, lead: Int): List<HrrrForecast.IndexEntry>? {
        val response = http.fetch(Request.Builder().url(fileUrl(run, lead) + ".idx").build(), maxBytes = 256L * 1024)
        if (response.code == 404 || response.code == 403) return null
        if (response.code !in 200..299) throw HttpStatusException(response.code, "NOAA HRRR")
        return HrrrForecast.parseIndex(response.body).takeIf { it.isNotEmpty() }
    }

    private fun fileUrl(run: Instant, lead: Int): String {
        val t = run.atOffset(ZoneOffset.UTC)
        return "%s/hrrr.%s/conus/hrrr.t%02dz.wrfsfcf%02d.grib2".format(base, DAY.format(t), t.hour, lead)
    }

    companion object {
        /** Three hours from now, then through the evening, overnight and tomorrow. */
        val DEFAULT_HOURS = listOf(3L, 9L, 15L, 24L)
        private const val MAX_FIELD_BYTES = 4L * 1024 * 1024
        private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

        /** Bilinear value at a point, or null outside the grid. */
        fun sample(field: GribField, latitude: Double, longitude: Double): Double? {
            val (fi, fj) = field.grid.toGrid(latitude, longitude)
            val i = floor(fi).toInt()
            val j = floor(fj).toInt()
            if (i < 0 || j < 0 || i + 1 >= field.grid.nx || j + 1 >= field.grid.ny) return null
            val ti = fi - i
            val tj = fj - j
            val v = (field[i, j] * (1 - ti) + field[i + 1, j] * ti) * (1 - tj) +
                (field[i, j + 1] * (1 - ti) + field[i + 1, j + 1] * ti) * tj
            return v.coerceAtLeast(0.0)
        }
    }
}
