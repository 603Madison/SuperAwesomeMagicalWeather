package app.northvane.core.radar

import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import app.northvane.core.time.*

/**
 * Bridges the newest radar scan and the model. Even a fresh HRRR run often
 * has rain in the wrong place at the moment of the last scan, so going
 * straight from radar to model makes rain jump or vanish. For the first two
 * hours, the radar is carried along its own measured motion and faded out
 * while the model fades in: the near frames follow what is actually falling,
 * later ones are the model's alone.
 */
class RadarBlend private constructor(
    private val dbz: FloatArray,
    private val width: Int,
    private val height: Int,
    val time: Instant,
    /** Motion of the rain, in radar pixels per minute (x east, y south). */
    val vx: Double,
    val vy: Double,
) {
    /** How much of a frame [minutes] after the scan is radar: all of it at first, none from [BLEND_MINUTES]. */
    fun weight(minutes: Double): Float = (1 - minutes / BLEND_MINUTES).coerceIn(0.0, 1.0).toFloat()

    fun minutesTo(t: Instant): Double = Duration.between(time, t).toSeconds() / 60.0

    /**
     * Reflectivity the radar puts at map pixel ([x], [y]) of a [mapWidth] ×
     * [mapHeight] map, [minutes] after the scan; NaN where the moved radar
     * doesn't reach.
     */
    fun valueAt(x: Int, y: Int, mapWidth: Int, mapHeight: Int, minutes: Double): Float {
        val ox = ((x + 0.5) * width / mapWidth - vx * minutes).roundToInt()
        val oy = ((y + 0.5) * height / mapHeight - vy * minutes).roundToInt()
        if (ox !in 0 until width || oy !in 0 until height) return Float.NaN
        return dbz[oy * width + ox]
    }

    companion object {
        const val BLEND_MINUTES = 120.0
        /** Weaker than this counts as no rain when tracking motion. */
        private const val TRACK_DBZ = 15f
        private const val NO_ECHO = -10f

        /**
         * A blend from the [latest] scan, moving at the motion measured
         * against an [earlier] one (still, when there's no earlier scan or no
         * clear motion).
         */
        fun build(latest: ObservedRadar, earlier: ObservedRadar?): RadarBlend {
            val now = Calibration.radarDbz(latest.argb)
            var vx = 0.0
            var vy = 0.0
            if (earlier != null && earlier.width == latest.width && earlier.height == latest.height) {
                val minutes = Duration.between(earlier.time, latest.time).toSeconds() / 60.0
                if (minutes in 5.0..45.0) {
                    estimateMotion(Calibration.radarDbz(earlier.argb), now, latest.width, latest.height)?.let { (dx, dy) ->
                        vx = dx / minutes
                        vy = dy / minutes
                    }
                }
            }
            return RadarBlend(now, latest.width, latest.height, latest.time, vx, vy)
        }

        /**
         * The shift (in pixels) that best carries [previous] onto [current]:
         * a coarse search over the whole picture, then a fine one around it.
         * Null when there's too little rain to tell.
         */
        fun estimateMotion(previous: FloatArray, current: FloatArray, width: Int, height: Int, maxShift: Int = 48): Pair<Int, Int>? {
            fun strength(v: Float) = if (v.isNaN() || v < TRACK_DBZ) 0f else v - TRACK_DBZ + 1
            val a = FloatArray(previous.size) { strength(previous[it]) }
            val b = FloatArray(current.size) { strength(current[it]) }
            if (a.count { it > 0 } < 200 || b.count { it > 0 } < 200) return null
            // Coarse: 4 × 4 blocks, so the search covers the full range cheaply.
            val f = 4
            val cw = width / f
            val ch = height / f
            fun coarse(src: FloatArray) = FloatArray(cw * ch) { k ->
                val cx = k % cw
                val cy = k / cw
                var s = 0f
                for (dy in 0 until f) for (dx in 0 until f) s += src[(cy * f + dy) * width + cx * f + dx]
                s / (f * f)
            }
            val ca = coarse(a)
            val cb = coarse(b)
            val (cx, cy) = bestShift(ca, cb, cw, ch, -maxShift / f..maxShift / f, -maxShift / f..maxShift / f) ?: return null
            return bestShift(a, b, width, height, (cx * f - f)..(cx * f + f), (cy * f - f)..(cy * f + f))
        }

        /** The shift (dx, dy) maximizing the overlap of [a] moved by it with [b], over the given ranges. */
        private fun bestShift(a: FloatArray, b: FloatArray, w: Int, h: Int, xs: IntRange, ys: IntRange): Pair<Int, Int>? {
            var best = 0.0
            var bestShift: Pair<Int, Int>? = null
            for (dy in ys) for (dx in xs) {
                var score = 0.0
                var overlap = 0
                val y0 = maxOf(0, dy)
                val y1 = minOf(h, h + dy)
                val x0 = maxOf(0, dx)
                val x1 = minOf(w, w + dx)
                for (y in y0 until y1) {
                    val rowB = y * w
                    val rowA = (y - dy) * w - dx
                    for (x in x0 until x1) {
                        val vb = b[rowB + x]
                        if (vb == 0f) continue
                        score += a[rowA + x] * vb
                        overlap++
                    }
                }
                // Normalized by the overlapping area, so big shifts aren't favoured just for overlapping less.
                val area = ((y1 - y0).toDouble() * (x1 - x0)).coerceAtLeast(1.0)
                val normalized = score / area
                if (overlap > 0 && normalized > best) {
                    best = normalized
                    bestShift = dx to dy
                }
            }
            return bestShift
        }

        /**
         * Mixes the model's and the moved radar's reflectivity in rain-rate
         * terms (linear Z), so rain the model lacks fades out rather than
         * leaving a ghost at half strength in dBZ.
         */
        fun mix(model: Float, radar: Float, weight: Float): Float {
            if (weight <= 0f || radar.isNaN()) return model
            val m = if (model.isNaN()) NO_ECHO else model
            if (weight >= 1f) return radar
            val z = weight * 10.0.pow(radar / 10.0) + (1 - weight) * 10.0.pow(m / 10.0)
            return (10 * log10(z)).toFloat()
        }
    }
}
