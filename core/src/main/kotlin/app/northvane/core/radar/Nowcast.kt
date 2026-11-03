package app.northvane.core.radar

import java.time.Instant
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan

/** A forecast radar image computed on the device, drawn over [west, east] × [south, north] (Web Mercator). */
class NowcastImage(
    val width: Int,
    val height: Int,
    /** ARGB pixels, row by row from the top-left. */
    val pixels: IntArray,
    val north: Double,
    val south: Double,
    val west: Double,
    val east: Double,
)

/** A block of Web Mercator tiles at one zoom, stitched into one image. */
data class TileArea(val zoom: Int, val x0: Int, val y0: Int, val cols: Int, val rows: Int) {
    val width: Int get() = cols * TILE
    val height: Int get() = rows * TILE
    val north: Double get() = tileLat(y0, zoom)
    val south: Double get() = tileLat(y0 + rows, zoom)
    val west: Double get() = tileLon(x0, zoom)
    val east: Double get() = tileLon(x0 + cols, zoom)

    fun tiles(): List<Triple<Int, Int, Int>> = (0 until rows).flatMap { r -> (0 until cols).map { c -> Triple(zoom, x0 + c, y0 + r) } }

    companion object {
        const val TILE = 256

        /** Tiles around a point, with the point near the middle. */
        fun around(latitude: Double, longitude: Double, zoom: Int = 7, cols: Int = 3, rows: Int = 4): TileArea {
            val n = 1 shl zoom
            val x = (longitude + 180.0) / 360.0 * n
            val latRad = Math.toRadians(latitude.coerceIn(-85.0, 85.0))
            val y = (1.0 - ln(tan(latRad) + 1.0 / kotlin.math.cos(latRad)) / PI) / 2.0 * n
            val x0 = floor(x - cols / 2.0).toInt().coerceIn(0, n - cols)
            val y0 = floor(y - rows / 2.0).toInt().coerceIn(0, n - rows)
            return TileArea(zoom, x0, y0, cols, rows)
        }

        fun tileLon(x: Int, zoom: Int): Double = x.toDouble() / (1 shl zoom) * 360.0 - 180.0

        fun tileLat(y: Int, zoom: Int): Double = Math.toDegrees(atan(sinh(PI * (1 - 2.0 * y / (1 shl zoom)))))

        /** Fills an XYZ template, or a `{bbox-epsg-3857}` one (as MapLibre does for WMS-style services). */
        fun tileUrl(template: String, z: Int, x: Int, y: Int): String {
            if ("{bbox-epsg-3857}" in template) {
                val size = 2 * PI * 6378137.0 / (1 shl z)
                val minX = -PI * 6378137.0 + x * size
                val maxY = PI * 6378137.0 - y * size
                val bbox = "%.4f,%.4f,%.4f,%.4f".format(java.util.Locale.ROOT, minX, maxY - size, minX + size, maxY)
                return template.replace("{bbox-epsg-3857}", bbox)
            }
            return template.replace("{z}", z.toString()).replace("{x}", x.toString()).replace("{y}", y.toString())
        }
    }
}

/**
 * Radar nowcast by extrapolation: measure how echoes moved between two recent
 * frames, then carry the latest frame forward along that motion. This is how
 * short-range "next hour" radar works; it beats weather models for the first
 * hour or two because it starts from what the radar actually sees, but it
 * can't grow or dissipate storms.
 */
object Nowcast {
    private const val FINE = 4 // pixels per cell for block refinement
    private const val COARSE = 8 // pixels per cell for the overall motion
    private const val BLOCK = 32 // fine cells per motion block (128 px)
    private const val SEARCH = 14 // coarse cells each way (~110 px)
    private const val REFINE = 4 // fine cells each way around the overall motion
    private const val MOTION_GAIN = 0.06 // correlation gain over standing still needed for full motion

    /** Motion in pixels per minute, one vector per block (blocks of [blockPx] pixels). */
    class Motion(val blocksX: Int, val blocksY: Int, val blockPx: Int, val u: DoubleArray, val v: DoubleArray, val confidence: Double) {
        /** The same field at [factor] of the speed, for motion measured over one interval only. */
        fun scaled(factor: Double) = Motion(blocksX, blocksY, blockPx, DoubleArray(u.size) { u[it] * factor }, DoubleArray(v.size) { v[it] * factor }, confidence * factor)

        /** Average motion, for display and tests. */
        val meanU: Double get() = u.average()
        val meanV: Double get() = v.average()
    }

    /** Fraction of pixels with an echo in each [cell]×[cell] block. */
    internal fun echo(pixels: IntArray, width: Int, height: Int, cell: Int): Array<DoubleArray> {
        val cw = width / cell
        val ch = height / cell
        return Array(ch) { cy ->
            DoubleArray(cw) { cx ->
                var n = 0
                for (y in cy * cell until (cy + 1) * cell) {
                    val row = y * width
                    for (x in cx * cell until (cx + 1) * cell) if ((pixels[row + x] ushr 24) > 40) n++
                }
                n.toDouble() / (cell * cell)
            }
        }
    }

    /** Normalized correlation of later[y][x] with earlier[y - dy][x - dx] over a region. */
    private fun correlation(
        earlier: Array<DoubleArray>,
        later: Array<DoubleArray>,
        dx: Int,
        dy: Int,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
    ): Double {
        var ab = 0.0
        var aa = 0.0
        var bb = 0.0
        val h = earlier.size
        val w = earlier[0].size
        for (y in maxOf(y0, dy) until minOf(y1, h + dy)) {
            val sy = y - dy
            if (sy !in 0 until h) continue
            val laterRow = later[y]
            val earlierRow = earlier[sy]
            for (x in maxOf(x0, dx) until minOf(x1, w + dx)) {
                val sx = x - dx
                if (sx !in 0 until w) continue
                val a = laterRow[x]
                val b = earlierRow[sx]
                ab += a * b
                aa += a * a
                bb += b * b
            }
        }
        return if (aa == 0.0 || bb == 0.0) 0.0 else ab / sqrt(aa * bb)
    }

    fun motion(earlier: IntArray, later: IntArray, width: Int, height: Int, minutes: Double): Motion {
        require(minutes > 0)
        // Overall motion on a coarse grid.
        val ce = echo(earlier, width, height, COARSE)
        val cl = echo(later, width, height, COARSE)
        val cw = cl[0].size
        val chh = cl.size
        val still = correlation(ce, cl, 0, 0, 0, 0, cw, chh)
        var best = 0.0
        var gdx = 0
        var gdy = 0
        for (dy in -SEARCH..SEARCH) for (dx in -SEARCH..SEARCH) {
            // A slight preference for small moves breaks ties on featureless fields.
            val c = correlation(ce, cl, dx, dy, 0, 0, cw, chh) - 0.0005 * (dx * dx + dy * dy)
            if (c > best) { best = c; gdx = dx; gdy = dy }
        }
        // Refine per block on a finer grid around the overall motion.
        val fe = echo(earlier, width, height, FINE)
        val fl = echo(later, width, height, FINE)
        val fw = fl[0].size
        val fh = fl.size
        val bx = maxOf(1, fw / BLOCK)
        val by = maxOf(1, fh / BLOCK)
        val scale = COARSE / FINE
        val u = DoubleArray(bx * by)
        val v = DoubleArray(bx * by)
        for (j in 0 until by) for (i in 0 until bx) {
            val x0 = i * BLOCK
            val y0 = j * BLOCK
            val x1 = if (i == bx - 1) fw else x0 + BLOCK
            val y1 = if (j == by - 1) fh else y0 + BLOCK
            var amount = 0.0
            for (y in y0 until y1) for (x in x0 until x1) amount += fl[y][x]
            var dxBest = gdx * scale
            var dyBest = gdy * scale
            if (amount / ((x1 - x0) * (y1 - y0)) >= 0.03) {
                var blockBest = correlation(fe, fl, dxBest, dyBest, x0, y0, x1, y1)
                for (dy in -REFINE..REFINE) for (dx in -REFINE..REFINE) {
                    val c = correlation(fe, fl, gdx * scale + dx, gdy * scale + dy, x0, y0, x1, y1) - 0.002 * (dx * dx + dy * dy)
                    if (c > blockBest + 0.01) { blockBest = c; dxBest = gdx * scale + dx; dyBest = gdy * scale + dy }
                }
            }
            u[j * bx + i] = dxBest * FINE / minutes
            v[j * bx + i] = dyBest * FINE / minutes
        }
        // Widespread rain that mostly fills in and fades matches itself almost as well standing
        // still; only trust motion that clearly beats "no motion", and scale it down otherwise.
        val confidence = ((best - still) / MOTION_GAIN).coerceIn(0.0, 1.0)
        for (k in u.indices) { u[k] *= confidence; v[k] *= confidence }
        return Motion(bx, by, BLOCK * FINE, smooth(u, bx, by), smooth(v, bx, by), confidence)
    }

    /** Averages each block with its neighbors so neighboring blocks don't tear the image apart. */
    private fun smooth(values: DoubleArray, w: Int, h: Int): DoubleArray = DoubleArray(values.size) { k ->
        val i = k % w
        val j = k / w
        var sum = 0.0
        var n = 0
        for (dj in -1..1) for (di in -1..1) {
            val x = i + di
            val y = j + dj
            if (x in 0 until w && y in 0 until h) {
                val weight = if (di == 0 && dj == 0) 2 else 1
                sum += values[y * w + x] * weight
                n += weight
            }
        }
        sum / n
    }

    /**
     * The latest frame carried [leadMinutes] ahead, fading slightly with lead
     * time to show growing uncertainty.
     */
    fun extrapolate(latest: IntArray, width: Int, height: Int, motion: Motion, leadMinutes: Double): IntArray {
        val out = IntArray(width * height)
        val fade = (1.0 - leadMinutes / 240.0).coerceIn(0.55, 1.0)
        val half = motion.blockPx / 2.0
        for (y in 0 until height) {
            // Bilinear interpolation between block centers.
            val gy = ((y - half) / motion.blockPx).coerceIn(0.0, (motion.blocksY - 1).toDouble())
            val j0 = gy.toInt()
            val j1 = minOf(j0 + 1, motion.blocksY - 1)
            val fy = gy - j0
            for (x in 0 until width) {
                val gx = ((x - half) / motion.blockPx).coerceIn(0.0, (motion.blocksX - 1).toDouble())
                val i0 = gx.toInt()
                val i1 = minOf(i0 + 1, motion.blocksX - 1)
                val fx = gx - i0
                fun at(arr: DoubleArray): Double {
                    val top = arr[j0 * motion.blocksX + i0] * (1 - fx) + arr[j0 * motion.blocksX + i1] * fx
                    val bottom = arr[j1 * motion.blocksX + i0] * (1 - fx) + arr[j1 * motion.blocksX + i1] * fx
                    return top * (1 - fy) + bottom * fy
                }
                val sx = (x - at(motion.u) * leadMinutes).roundToInt()
                val sy = (y - at(motion.v) * leadMinutes).roundToInt()
                if (sx !in 0 until width || sy !in 0 until height) continue
                val p = latest[sy * width + sx]
                val a = p ushr 24
                if (a == 0) continue
                out[y * width + x] = ((a * fade).roundToInt() shl 24) or (p and 0xFFFFFF)
            }
        }
        return out
    }

    /** Forecast frames every [stepMinutes] after [latestTime], out to [horizonMinutes]. */
    fun frames(
        area: TileArea,
        latest: IntArray,
        latestTime: Instant,
        motion: Motion,
        horizonMinutes: Int,
        stepMinutes: Int = 15,
    ): List<RadarFrame> = (stepMinutes..horizonMinutes step stepMinutes).map { lead ->
        RadarFrame(
            time = latestTime.plusSeconds(lead * 60L),
            isForecast = true,
            tileUrl = "",
            nowcast = NowcastImage(
                area.width, area.height, extrapolate(latest, area.width, area.height, motion, lead.toDouble()),
                area.north, area.south, area.west, area.east,
            ),
        )
    }

    /**
     * Motion measured over two back-to-back intervals, kept only as far as they
     * agree: steady motion is real, while two different answers usually mean
     * rain filling in and fading in place.
     */
    fun combine(first: Motion, second: Motion): Motion {
        val du = first.meanU - second.meanU
        val dv = first.meanV - second.meanV
        val size = maxOf(kotlin.math.hypot(first.meanU, first.meanV), kotlin.math.hypot(second.meanU, second.meanV), 0.2)
        val agreement = (1.0 - kotlin.math.hypot(du, dv) / size).coerceIn(0.0, 1.0)
        // The newer interval says more about what happens next.
        val u = DoubleArray(second.u.size) { (0.35 * first.u.getOrElse(it) { first.meanU } + 0.65 * second.u[it]) * agreement }
        val v = DoubleArray(second.v.size) { (0.35 * first.v.getOrElse(it) { first.meanV } + 0.65 * second.v[it]) * agreement }
        return Motion(second.blocksX, second.blocksY, second.blockPx, u, v, minOf(first.confidence, second.confidence) * agreement)
    }

    /**
     * Earlier frames to measure motion from: about 15 and 30 minutes before
     * the latest (each at least 8 minutes apart), oldest first. The first is
     * null when there's only room for one interval.
     */
    fun pickEarlier(past: List<RadarFrame>): Pair<RadarFrame?, RadarFrame>? {
        val latest = past.lastOrNull() ?: return null
        fun closest(to: RadarFrame, minutes: Long) = past
            .filter { java.time.Duration.between(it.time, to.time).toMinutes() >= 8 }
            .minByOrNull { kotlin.math.abs(java.time.Duration.between(it.time, to.time).toMinutes() - minutes) }
        val middle = closest(latest, 15) ?: return null
        return closest(middle, 15) to middle
    }
}
