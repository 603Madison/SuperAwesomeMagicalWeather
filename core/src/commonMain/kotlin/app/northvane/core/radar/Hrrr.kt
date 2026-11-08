package app.northvane.core.radar

import app.northvane.core.net.HttpStatusException
import app.northvane.core.net.fetch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import app.northvane.core.net.fetchBytes
import app.northvane.core.net.ResponseTooLargeException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import app.northvane.core.net.HttpEngine
import app.northvane.core.net.Request
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.roundToInt
import app.northvane.core.time.*
import app.northvane.core.util.fmt
import app.northvane.core.util.toDegrees

/**
 * A forecast radar image rendered on the device, drawn over [west, east] ×
 * [south, north] (Web Mercator). Stored as one byte per pixel (an index into
 * [ReflectivityPalette]) to keep several frames small in memory.
 */
class RadarImage(
    val width: Int,
    val height: Int,
    /** Palette index per pixel, row by row from the top-left; 0 is transparent. */
    val indices: ByteArray,
    val north: Double,
    val south: Double,
    val west: Double,
    val east: Double,
) {
    /** ARGB pixels for drawing. */
    fun argb(): IntArray = IntArray(indices.size) { ReflectivityPalette.color(indices[it].toInt() and 0xFF) }
}

/**
 * The radar colors of NOAA's MRMS reflectivity service, by dBZ, so forecast
 * frames look like the past radar. Measured by pairing the service's colors
 * with IEM's NEXRAD composite values; the red-to-magenta end (above 50 dBZ)
 * follows the usual NWS scale.
 */
object ReflectivityPalette {
    /** Weaker returns aren't drawn (the radar service starts about here). */
    const val MIN_DBZ = 5.0

    private val anchors = listOf(
        5.0 to 0x8F97B4, 9.0 to 0x687AA9, 14.0 to 0x435E9F, 18.5 to 0x5EADCF, 23.5 to 0x52D6A2,
        29.0 to 0x0ED614, 39.0 to 0x095E09, 42.0 to 0x6B9305, 44.0 to 0xFFE200, 47.0 to 0xFFAA00,
        50.0 to 0xFF2300, 55.0 to 0xC80000, 60.0 to 0xBE008C, 65.0 to 0xE68CFF, 70.0 to 0xFFFFFF,
    )

    // Index 0 is transparent; then 0.5 dBZ steps from MIN_DBZ to 75 dBZ (fits in a byte).
    private val table = IntArray(((75.0 - MIN_DBZ) * 2).toInt() + 2) { k -> if (k == 0) 0 else colorAt(MIN_DBZ + (k - 1) / 2.0) }

    /** Palette index for a reflectivity; 0 when too weak to draw. */
    fun index(dbz: Float): Int {
        if (dbz.isNaN() || dbz < MIN_DBZ) return 0
        return (1 + ((dbz - MIN_DBZ) * 2).toInt()).coerceAtMost(table.lastIndex)
    }

    fun color(index: Int): Int = table[index.coerceIn(0, table.lastIndex)]

    /** Number of palette entries, including transparent index 0. */
    val size: Int get() = table.size

    /** The reflectivity a palette index stands for (index ≥ 1). */
    fun dbz(index: Int): Float = (MIN_DBZ + (index - 1) / 2.0).toFloat()

    fun argb(dbz: Float): Int = color(index(dbz))

    private fun colorAt(dbz: Double): Int {
        val upper = anchors.indexOfFirst { it.first >= dbz }
        if (upper == 0) return 0xFF000000.toInt() or anchors[0].second
        if (upper < 0) return 0xFF000000.toInt() or anchors.last().second
        val (d0, c0) = anchors[upper - 1]
        val (d1, c1) = anchors[upper]
        val t = (dbz - d0) / (d1 - d0)
        fun mix(shift: Int) = (((c0 shr shift) and 0xFF) * (1 - t) + ((c1 shr shift) and 0xFF) * t).roundToInt()
        return (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }
}

/**
 * NOAA's HRRR (High-Resolution Rapid Refresh) model straight from NOAA's open
 * data on AWS: simulated composite radar reflectivity (the strongest echo in
 * each column, which is what a radar mosaic shows far from the radars), every
 * 15 minutes at 3 km. Each frame comes from the newest run that has published
 * it, since runs appear an hour or so after they start and fill in over the
 * next hour. Only the needed field is downloaded (byte ranges from the run's
 * index file, ~490 KB per frame), then decoded, projected and colored like the
 * past radar on the device.
 */
class HrrrForecast(
    private val http: HttpEngine,
    private val base: String = AWS_BASE,
) {
    private val gribCache = app.northvane.core.util.LruCache<String, ByteArray>(16)
    /** Recent runs' indexes; old runs drop out instead of piling up while the app runs. */
    private val indexCache = app.northvane.core.util.LruCache<String, List<IndexEntry>>(48)

    internal data class IndexEntry(val offset: Long, val field: String, val level: String, val step: String) {
        /** Composite reflectivity: what radar mosaics show, including rain aloft far from the radars. */
        fun isReflectivity() = field == "REFC" && level == "entire atmosphere"
    }

    /**
     * The newest run that has published each of [times] (runs up to five hours
     * old are considered), or nothing for times no run covers yet.
     */
    suspend fun runsFor(now: Instant, times: List<Instant>): Map<Instant, Instant> {
        val hour = now.truncatedTo(ChronoUnit.HOURS)
        val runs = (0L..5L).map { hour.minus(Duration.ofHours(it)) }
        val missing = HashSet<String>() // files known not to be out yet, within this call
        val chosen = LinkedHashMap<Instant, Instant>()
        for (time in times) {
            for (init in runs) {
                val minutes = Duration.between(init, time).toMinutes()
                if (minutes < 15 || minutes > MAX_SUBHOURLY_MINUTES || minutes % 15 != 0L) continue
                val file = fileHour(minutes)
                val key = "${STAMP.format(init)}/$file"
                if (key in missing) continue
                if (index(init, file)?.any { it.isReflectivity() && it.step == "$minutes min fcst" } == true) {
                    chosen[time] = init
                    break
                }
                missing += key
            }
        }
        return chosen
    }

    /**
     * Forecast frames for [times] from the newest runs that have them, drawn
     * over [area]. With [observed] (the newest radar over the same area),
     * each run is first calibrated against it ([Calibration]).
     */
    suspend fun frames(
        now: Instant,
        times: List<Instant>,
        area: TileArea,
        observed: ObservedRadar? = null,
        /** Carries the newest radar into the first frames; see [RadarBlend]. */
        blend: RadarBlend? = null,
    ): List<RadarFrame> = coroutineScope {
        val runs = runsFor(now, times)
        val mapping = withContext(Dispatchers.Default) { GridMapping(area) }
        val calibrations = if (observed == null) {
            emptyMap()
        } else {
            runs.values.distinct().associateWith { init ->
                // Calibration is optional: any failure (even running out of memory) just skips it, but
                // cancellation still stops the work.
                try {
                    calibrate(init, observed, mapping)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    null
                }
            }
        }
        val limit = Semaphore(3)
        runs.map { (time, init) ->
            async {
                limit.withPermit {
                    val minutes = Duration.between(init, time).toMinutes()
                    val bytes = field(init, minutes) ?: return@withPermit null
                    val calibration = calibrations[init]
                    withContext(Dispatchers.Default) {
                        val image = mapping.render(Grib2.decode(bytes), calibration, blend, blend?.minutesTo(time))
                        RadarFrame(time, isForecast = true, tileUrl = "", image = image, run = init, calibrated = calibration != null)
                    }
                }
            }
        }.awaitAll().filterNotNull()
    }

    /** Compares the run's step nearest the radar's time with the radar itself. */
    private suspend fun calibrate(init: Instant, observed: ObservedRadar, mapping: GridMapping): Calibration? {
        val minutes = kotlin.math.floor(Duration.between(init, observed.time).toMinutes() / 15.0 + 0.5).toLong() * 15
        if (minutes < 15 || minutes > MAX_SUBHOURLY_MINUTES) return null
        if (!Calibration.matches(observed.time, init.plusSeconds(minutes * 60))) return null
        val bytes = field(init, minutes) ?: return null
        return withContext(Dispatchers.Default) {
            val model = mapping.sample(Grib2.decode(bytes))
            // The radar image is coarser than the map pixels; compare at its resolution.
            val w = mapping.width
            val h = mapping.height
            val sampled = FloatArray(observed.width * observed.height) { k ->
                val x = (k % observed.width) * w / observed.width
                val y = (k / observed.width) * h / observed.height
                model[y * w + x]
            }
            Calibration.build(sampled, Calibration.radarDbz(observed.argb))
        }
    }

    /** The REFD 1 km field for one forecast minute, from the cache or by byte range. */
    private suspend fun field(init: Instant, minutes: Long): ByteArray? {
        val key = "${STAMP.format(init)}/$minutes"
        gribCache[key]?.let { return it }
        val file = fileHour(minutes)
        val entries = index(init, file) ?: return null
        val step = "$minutes min fcst"
        val i = entries.indexOfFirst { it.isReflectivity() && it.step == step }
        if (i < 0) return null
        val start = entries[i].offset
        val end = entries.getOrNull(i + 1)?.offset?.minus(1) ?: return null
        val request = Request.Builder().url(fileUrl(init, file)).header("Range", "bytes=$start-$end").build()
        val response = try {
            http.fetchBytes(request, MAX_FIELD_BYTES)
        } catch (e: ResponseTooLargeException) {
            throw HttpStatusException(413, "NOAA HRRR")
        }
        if (response.code != 206 && response.code != 200) throw HttpStatusException(response.code, "NOAA HRRR")
        val bytes = response.bytes
        gribCache[key] = bytes
        return bytes
    }

    private suspend fun index(init: Instant, file: Int): List<IndexEntry>? {
        val url = fileUrl(init, file) + ".idx"
        indexCache[url]?.let { return it }
        val response = http.fetch(Request.Builder().url(url).build(), maxBytes = 256L * 1024)
        if (response.code == 404 || response.code == 403) return null
        if (response.code !in 200..299) throw HttpStatusException(response.code, "NOAA HRRR")
        val entries = parseIndex(response.body)
        if (entries.isEmpty()) return null
        indexCache[url] = entries
        return entries
    }

    private fun fileUrl(init: Instant, file: Int): String {
        val t = init.atOffset(ZoneOffset.UTC)
        return "%s/hrrr.%s/conus/hrrr.t%02dz.wrfsubhf%02d.grib2".fmt(base, DAY.format(t), t.hour, file)
    }

    /**
     * Precomputed grid position for every map pixel, shared by all frames of
     * one run (the grid never changes).
     */
    private class GridMapping(private val area: TileArea) {
        val width: Int get() = area.width
        val height: Int get() = area.height
        private var grid: LambertGrid? = null
        private lateinit var gi: FloatArray
        private lateinit var gj: FloatArray

        private val lock = kotlinx.atomicfu.locks.SynchronizedObject()

        private fun prepare(g: LambertGrid): Unit = kotlinx.atomicfu.locks.synchronized(lock) {
            val current = grid
            if (current != null && current.nx == g.nx && current.ny == g.ny) return@synchronized
            val w = area.width
            val h = area.height
            gi = FloatArray(w * h)
            gj = FloatArray(w * h)
            val worldPx = TileArea.TILE.toDouble() * (1 shl area.zoom)
            val lons = DoubleArray(w) { x -> ((area.x0 * TileArea.TILE + x + 0.5) / worldPx) * 360.0 - 180.0 }
            for (y in 0 until h) {
                val my = (area.y0 * TileArea.TILE + y + 0.5) / worldPx
                val lat = toDegrees(2 * atan(exp(PI * (1 - 2 * my))) - PI / 2)
                for (x in 0 until w) {
                    val (i, j) = g.toGrid(lat, lons[x])
                    gi[y * w + x] = i.toFloat()
                    gj[y * w + x] = j.toFloat()
                }
            }
            grid = g
        }

        /** Reflectivity at every map pixel (NaN outside the model's grid). */
        fun sample(field: GribField): FloatArray {
            val g = field.grid
            prepare(g)
            return FloatArray(area.width * area.height) { k ->
                val i = gi[k]
                val j = gj[k]
                if (i < 0 || j < 0 || i > g.nx - 1 || j > g.ny - 1) return@FloatArray Float.NaN
                // Bilinear between the four surrounding grid points.
                val i0 = floor(i).toInt().coerceAtMost(g.nx - 2)
                val j0 = floor(j).toInt().coerceAtMost(g.ny - 2)
                val fi = i - i0
                val fj = j - j0
                (field[i0, j0] * (1 - fi) + field[i0 + 1, j0] * fi) * (1 - fj) +
                    (field[i0, j0 + 1] * (1 - fi) + field[i0 + 1, j0 + 1] * fi) * fj
            }
        }

        fun render(field: GribField, calibration: Calibration? = null, blend: RadarBlend? = null, minutes: Double? = null): RadarImage {
            val values = sample(field)
            val weight = if (blend != null && minutes != null) blend.weight(minutes) else 0f
            val w = area.width
            val h = area.height
            val pixels = ByteArray(values.size) { k ->
                val v = values[k]
                val model = if (v.isNaN()) v else calibration?.apply(v) ?: v
                val value = if (weight > 0f) RadarBlend.mix(model, blend!!.valueAt(k % w, k / w, w, h, minutes!!), weight) else model
                if (value.isNaN()) 0 else ReflectivityPalette.index(value).toByte()
            }
            return RadarImage(area.width, area.height, pixels, area.north, area.south, area.west, area.east)
        }
    }

    companion object {
        const val AWS_BASE = "https://noaa-hrrr-bdp-pds.s3.amazonaws.com"
        /** Subhourly output runs 18 hours. */
        private const val MAX_SUBHOURLY_MINUTES = 18 * 60L
        private const val MAX_FIELD_BYTES = 4L * 1024 * 1024
        private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
        private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHH").withZone(ZoneOffset.UTC)

        /** Subhourly file N holds the 15, 30, 45 and 60 minute steps of hour N. */
        fun fileHour(minutes: Long): Int = ((minutes + 59) / 60).toInt()

        /** Valid times every 15 minutes after [after], out to [until]; every 30 past two hours to save memory. */
        fun frameTimes(after: Instant, now: Instant, until: Instant): List<Instant> {
            val start = after.truncatedTo(ChronoUnit.MINUTES)
            var t = start.plus(Duration.ofMinutes((15 - start.atOffset(ZoneOffset.UTC).minute % 15).toLong()))
            val times = mutableListOf<Instant>()
            while (!t.isAfter(until)) {
                if (Duration.between(now, t) <= Duration.ofHours(2) || t.atOffset(ZoneOffset.UTC).minute % 30 == 0) times += t
                t = t.plus(Duration.ofMinutes(15))
            }
            return times
        }

        /** wgrib2 index lines: "6:2942557:d=2026110313:REFD:1000 m above ground:15 min fcst:". */
        internal fun parseIndex(text: String): List<IndexEntry> = text.lineSequence().mapNotNull { line ->
            val parts = line.split(':')
            if (parts.size < 6) return@mapNotNull null
            val offset = parts[1].toLongOrNull() ?: return@mapNotNull null
            IndexEntry(offset, parts[3], parts[4], parts[5])
        }.toList()
    }
}
