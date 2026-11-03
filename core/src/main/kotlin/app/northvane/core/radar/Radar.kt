package app.northvane.core.radar

import app.northvane.core.json.NorthvaneJson
import app.northvane.core.json.arr
import app.northvane.core.json.asObj
import app.northvane.core.json.long
import app.northvane.core.json.obj
import app.northvane.core.json.str
import app.northvane.core.net.fetch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

@Serializable
enum class RadarSource(val label: String, val attribution: String) {
    NOAA_MRMS("NOAA MRMS (official)", "Radar: NOAA/NWS MRMS"),
    IEM_NEXRAD("NEXRAD composite (Iowa Env. Mesonet)", "Radar: NWS NEXRAD via Iowa Environmental Mesonet"),
}

data class RadarFrame(
    val time: Instant,
    val isForecast: Boolean,
    /** XYZ (or WMS-style `{bbox-epsg-3857}`) tile URL template for MapLibre; empty for an [image] frame. */
    val tileUrl: String,
    /** A forecast rendered on the device from model data, instead of map tiles. */
    val image: RadarImage? = null,
    /** For model frames: when the model run started. */
    val run: Instant? = null,
    /** For model frames: adjusted to match the newest radar (see [Calibration]). */
    val calibrated: Boolean = false,
)

/**
 * Builds radar animation frames.
 *
 * Past frames come from the NWS/NOAA MRMS time-enabled image service
 * (default) or IEM's NEXRAD composite tiles. The future cast is NOAA's HRRR
 * model, read from NOAA's open data and drawn on the device ([HrrrForecast]);
 * IEM's pre-rendered HRRR tiles are the fallback.
 */
class RadarService(
    private val http: OkHttpClient,
    private val noaaBase: String = NOAA_MRMS_BASE,
    private val iemBase: String = IEM_BASE,
    private val hrrr: HrrrForecast = HrrrForecast(http),
) {
    suspend fun pastFrames(source: RadarSource, now: Instant, count: Int = 7): List<RadarFrame> = when (source) {
        RadarSource.NOAA_MRMS -> noaaFrames(now, count)
        RadarSource.IEM_NEXRAD -> iemNexradFrames(now, count)
    }

    /**
     * Model frames after [after] (the newest radar scan) out to [horizonMinutes]
     * from now, over [area]: the newest HRRR run straight from NOAA, or IEM's
     * tiles of an older run if NOAA's data can't be reached.
     */
    suspend fun futureFrames(
        now: Instant,
        horizonMinutes: Int = 120,
        after: Instant = now,
        area: TileArea? = null,
        /** Decodes a PNG to ARGB pixels (platform code); enables calibration against the radar. */
        decodePng: ((ByteArray) -> IntArray?)? = null,
    ): List<RadarFrame> {
        val until = now.plus(Duration.ofMinutes(horizonMinutes.toLong()))
        if (area != null) {
            val observed = if (decodePng != null) runCatchingNonCancel { observedRadar(area, after, decodePng) }.getOrNull() else null
            val direct = runCatchingNonCancel {
                hrrr.frames(now, HrrrForecast.frameTimes(after, now, until), area, observed)
            }.getOrNull().orEmpty()
            if (direct.isNotEmpty()) return direct
        }
        val init = latestHrrrRun(now)
        return hrrrFrames(init, now, horizonMinutes, iemBase).filter { it.time.isAfter(after) }
    }

    /**
     * NOAA's radar over the whole [area] at [time], as one image at half the
     * map resolution: what the model is calibrated against. Always NOAA's
     * service, whose colors [Calibration.radarDbz] knows.
     */
    private suspend fun observedRadar(area: TileArea, time: Instant, decodePng: (ByteArray) -> IntArray?): ObservedRadar? {
        val world = 2 * Math.PI * 6378137.0
        val tile = world / (1 shl area.zoom)
        val minX = -world / 2 + area.x0 * tile
        val maxY = world / 2 - area.y0 * tile
        val bbox = "%.2f,%.2f,%.2f,%.2f".format(java.util.Locale.ROOT, minX, maxY - area.rows * tile, minX + area.cols * tile, maxY)
        val width = area.width / 2
        val height = area.height / 2
        val url = noaaTileUrl(noaaBase, time).replace("{bbox-epsg-3857}", bbox).replace("size=256,256", "size=$width,$height")
        val bytes = runInterruptible(kotlinx.coroutines.Dispatchers.IO) {
            http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val source = response.body.source()
                if (source.request(4L * 1024 * 1024 + 1)) return@use null
                source.buffer.readByteArray()
            }
        } ?: return null
        val argb = decodePng(bytes)?.takeIf { it.size == width * height } ?: return null
        return ObservedRadar(time, width, height, argb)
    }

    // --- NOAA MRMS (mapservices.weather.noaa.gov ArcGIS ImageServer) ---

    /**
     * Each MRMS raster is valid from its scan time until the next scan, and the
     * catalog holds separate rasters per region (CONUS, Alaska, Hawaii, …)
     * scanned at slightly different times. Frames therefore use a time *window*
     * ending at a CONUS scan time, with a mosaic rule that draws the newest
     * raster on top: a single instant would render the previous scan and leave
     * other regions blank on the latest frame.
     */
    private suspend fun noaaFrames(now: Instant, count: Int): List<RadarFrame> {
        val scans = runCatchingNonCancel { noaaScanTimes() }.getOrNull()
            ?.filter { it <= now.plusSeconds(60) && it >= now.minus(Duration.ofMinutes(90)) }
            ?.let(::clusterScans)
            ?.takeLast(count)
            .orEmpty()
        val frameTimes = scans.ifEmpty {
            // Catalog unavailable: 10-minute windows ending now still pick up the newest scans.
            val latest = now.truncatedTo(ChronoUnit.MINUTES)
            (count - 1 downTo 0).map { latest.minus(Duration.ofMinutes(10L * it)) }
        }
        return frameTimes.map { RadarFrame(it, isForecast = false, tileUrl = noaaTileUrl(noaaBase, it)) }
    }

    /** Recent CONUS scan times from the image catalog (newest first from the server). */
    private suspend fun noaaScanTimes(): List<Instant> {
        val info = NorthvaneJson.parseToJsonElement(get("$noaaBase?f=json")).asObj()
        val field = info?.obj("timeInfo")?.str("startTimeField") ?: return emptyList()
        suspend fun query(where: String): List<Instant> {
            val queryUrl = "$noaaBase/query".toHttpUrl().newBuilder()
                .addQueryParameter("where", where)
                .addQueryParameter("outFields", field)
                .addQueryParameter("returnGeometry", "false")
                .addQueryParameter("orderByFields", "$field DESC")
                .addQueryParameter("resultRecordCount", "40")
                .addQueryParameter("f", "json")
                .build()
                .toString()
            val result = NorthvaneJson.parseToJsonElement(get(queryUrl)).asObj() ?: return emptyList()
            return result.arr("features").orEmpty().mapNotNull { feature ->
                val attributes = feature.asObj()?.obj("attributes") ?: return@mapNotNull null
                (attributes[field] as? JsonPrimitive)?.longOrNull?.let(Instant::ofEpochMilli)
            }
        }
        // Prefer the CONUS cadence; fall back to every region's scans if the filter isn't supported.
        return runCatchingNonCancel { query("idp_subset='CONUS'") }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: query("1=1")
    }

    // --- IEM NEXRAD composite ---

    private fun iemNexradFrames(now: Instant, count: Int): List<RadarFrame> {
        // IEM publishes the latest composite plus layers 5..50 minutes old.
        val latest = now.truncatedTo(ChronoUnit.MINUTES).let {
            it.minus(Duration.ofMinutes((it.atOffset(ZoneOffset.UTC).minute % 5).toLong()))
        }
        val offsets = (0..50 step 10).toList().reversed().takeLast(count.coerceIn(1, 6))
        return offsets.map { minutesAgo ->
            val layer = if (minutesAgo == 0) "nexrad-n0q-900913" else "nexrad-n0q-900913-m%02dm".format(minutesAgo)
            RadarFrame(
                time = latest.minus(Duration.ofMinutes(minutesAgo.toLong())),
                isForecast = false,
                tileUrl = "$iemBase/cache/tile.py/1.0.0/$layer/{z}/{x}/{y}.png",
            )
        }
    }

    // --- HRRR future cast (via IEM) ---

    private suspend fun latestHrrrRun(now: Instant): Instant {
        val reported = runCatchingNonCancel {
            val json = NorthvaneJson.parseToJsonElement(get("$iemBase/data/gis/images/4326/hrrr/refd_1080.json")).asObj()
            json?.str("model_init_utc")?.let(::parseIemTime)
        }.getOrNull()
        return reported?.takeIf { !it.isAfter(now) && Duration.between(it, now) < Duration.ofHours(8) }
            ?: estimateHrrrRun(now)
    }

    private suspend fun get(url: String): String {
        val response = http.fetch(Request.Builder().url(url).build(), maxBytes = 1024 * 1024)
        if (response.code !in 200..299) throw java.io.IOException("HTTP ${response.code}")
        return response.body
    }

    companion object {
        const val NOAA_MRMS_BASE =
            "https://mapservices.weather.noaa.gov/eventdriven/rest/services/radar/radar_base_reflectivity_time/ImageServer"
        const val IEM_BASE = "https://mesonet.agron.iastate.edu"

        private val IEM_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmm").withZone(ZoneOffset.UTC)

        /** Window long enough to contain one scan from every region (they run every ~2–8 minutes). */
        private val NOAA_WINDOW: Duration = Duration.ofMinutes(10)

        // Draw the newest raster in the window on top (largest distance from 0 first).
        private val NOAA_NEWEST_FIRST: String = URLEncoder.encode(
            """{"mosaicMethod":"esriMosaicAttribute","sortField":"idp_validtime","sortValue":"0","ascending":false}""",
            "UTF-8", // Charset overload needs Android 13+; the name overload works everywhere.
        )

        fun noaaTileUrl(base: String, time: Instant): String =
            "$base/exportImage?bbox={bbox-epsg-3857}&bboxSR=3857&imageSR=3857&size=256,256" +
                "&format=png&transparent=true&f=image" +
                "&time=${time.minus(NOAA_WINDOW).toEpochMilli()},${time.toEpochMilli()}" +
                "&mosaicRule=$NOAA_NEWEST_FIRST"

        /**
         * Collapses near-simultaneous catalog times (one scan reported by
         * several regions seconds apart) into one frame per scan, oldest first.
         */
        fun clusterScans(times: List<Instant>): List<Instant> {
            val frames = mutableListOf<Instant>()
            for (t in times.distinct().sortedDescending()) {
                if (frames.isEmpty() || Duration.between(t, frames.last()) >= Duration.ofMinutes(3)) frames += t
            }
            return frames.reversed()
        }

        /**
         * Fallback when IEM's run metadata is unreachable. Runs take a while to
         * be processed and published (a run is often ~3 hours old when it
         * appears), and tiles for an unpublished run fail, so stay well behind.
         * HRRR runs extend 18 hours, so an older run still covers the next few hours.
         */
        fun estimateHrrrRun(now: Instant): Instant =
            now.minus(Duration.ofHours(3)).truncatedTo(ChronoUnit.HOURS)

        /** Frames every 15 minutes from the next quarter hour out to [horizonMinutes]. */
        fun hrrrFrames(init: Instant, now: Instant, horizonMinutes: Int, iemBase: String = IEM_BASE): List<RadarFrame> {
            val nowMinute = now.truncatedTo(ChronoUnit.MINUTES)
            val minuteOfHour = nowMinute.atOffset(ZoneOffset.UTC).minute
            val firstTarget = nowMinute.plus(Duration.ofMinutes((15 - minuteOfHour % 15).toLong()))
            val frames = mutableListOf<RadarFrame>()
            var target = firstTarget
            while (Duration.between(now, target).toMinutes() <= horizonMinutes) {
                val forecastMinute = Duration.between(init, target).toMinutes()
                if (forecastMinute in 0..1080 && forecastMinute % 15 == 0L) {
                    val layer = "hrrr::REFD-F%04d-%s".format(forecastMinute, IEM_STAMP.format(init))
                    frames += RadarFrame(target, isForecast = true, tileUrl = "$iemBase/cache/tile.py/1.0.0/$layer/{z}/{x}/{y}.png", run = init)
                }
                target = target.plus(Duration.ofMinutes(15))
            }
            return frames
        }

        fun parseIemTime(value: String): Instant? =
            runCatching { Instant.parse(value) }.getOrNull()
                ?: runCatching { Instant.parse(value.trim().replace(' ', 'T').let { if (it.endsWith("Z")) it else "${it}Z" }) }.getOrNull()
    }
}

private inline fun <T> runCatchingNonCancel(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

/** GOES-East/West satellite imagery from NOAA nowCOAST, drawn under the radar. */
@kotlinx.serialization.Serializable
enum class SatelliteImagery(val label: String, val layer: String?) {
    /** Visible imagery by day, infrared at night (visible is black after dark). */
    AUTO("Auto (visible by day, infrared at night)", null),
    VISIBLE("Visible", "goes_visible_imagery"),
    INFRARED("Infrared (clouds day and night)", "goes_longwave_imagery"),
    WATER_VAPOR("Water vapor", "goes_water_vapor_imagery");

    companion object {
        const val NOWCOAST_WMS = "https://nowcoast.noaa.gov/geoserver/satellite/wms"
        const val ATTRIBUTION = "GOES imagery: NOAA nowCOAST"

        /** Concrete layer for [choice]; AUTO picks by daylight at the map center. */
        fun resolve(choice: SatelliteImagery, daylight: Boolean): SatelliteImagery = when (choice) {
            AUTO -> if (daylight) VISIBLE else INFRARED
            else -> choice
        }

        /** WMS GetMap template for MapLibre raster tiles; nowCOAST serves the latest scan by default. */
        fun tileUrl(imagery: SatelliteImagery, base: String = NOWCOAST_WMS): String {
            val layer = requireNotNull(imagery.layer) { "Resolve AUTO first" }
            return "$base?service=WMS&version=1.3.0&request=GetMap&layers=$layer&styles=&format=image/png" +
                "&transparent=true&crs=EPSG:3857&width=256&height=256&bbox={bbox-epsg-3857}"
        }
    }
}
