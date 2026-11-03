package app.northvane.core.radar

import java.time.Instant
import kotlin.math.abs

/** The newest radar over a [TileArea], as NOAA's colored image ([width]×[height] ARGB pixels). */
class ObservedRadar(val time: Instant, val width: Int, val height: Int, val argb: IntArray)

/**
 * Quantile mapping of model reflectivity to the radar: at a time both cover,
 * the model's value at each percentile is matched to the radar's value at the
 * same percentile. Applied to later frames of the same run, this corrects the
 * model's tendency (on a given day) to draw too little or too much rain, or
 * rain too weak or too strong, without moving anything: where the model puts
 * rain is unchanged.
 */
class Calibration private constructor(private val model: FloatArray, private val radar: FloatArray) {
    // A frame has about a million pixels: look up the mapping in 0.25 dBZ steps instead of searching each time.
    private val lut = FloatArray(LUT_SIZE) { exact(LUT_MIN + it * LUT_STEP) }

    fun apply(dbz: Float): Float {
        if (dbz.isNaN()) return dbz
        val pos = (dbz - LUT_MIN) / LUT_STEP
        if (pos < 0 || pos >= LUT_SIZE - 1) return exact(dbz)
        val i = pos.toInt()
        val t = pos - i
        return lut[i] * (1 - t) + lut[i + 1] * t
    }

    private fun exact(dbz: Float): Float {
        val mapped = when {
            dbz <= model.first() -> radar.first()
            dbz >= model.last() -> dbz + (radar.last() - model.last())
            else -> {
                var hi = model.indices.first { model[it] >= dbz }
                if (hi == 0) hi = 1
                val lo = hi - 1
                val t = (dbz - model[lo]) / (model[hi] - model[lo])
                radar[lo] + (radar[hi] - radar[lo]) * t
            }
        }
        // A day's correction is never allowed to rewrite the forecast wholesale.
        return mapped.coerceIn(dbz - MAX_SHIFT, dbz + MAX_SHIFT)
    }

    companion object {
        private const val MAX_SHIFT = 15f
        private const val LUT_MIN = -20f
        private const val LUT_STEP = 0.25f
        private const val LUT_SIZE = 401 // -20 to 80 dBZ
        private const val NO_ECHO = -10f
        /** Below this share of pixels with rain in either field, there's too little to calibrate against. */
        private const val MIN_COVER = 0.01
        private val levels = (0..200).map { it / 200.0 }

        /**
         * Builds a calibration from the model and the radar over the same
         * pixels (NaN where the model has no data), or null when it can't be
         * trusted.
         */
        fun build(model: FloatArray, radar: FloatArray): Calibration? {
            require(model.size == radar.size)
            val pairs = model.indices.filter { !model[it].isNaN() }
            if (pairs.size < 1000) return null
            val m = FloatArray(pairs.size) { model[pairs[it]] }.also { it.sort() }
            val r = FloatArray(pairs.size) { radar[pairs[it]] }.also { it.sort() }
            fun cover(sorted: FloatArray) = 1.0 - sorted.indexOfFirst { it >= ReflectivityPalette.MIN_DBZ }.let { if (it < 0) sorted.size else it } / sorted.size.toDouble()
            if (cover(m) < MIN_COVER || cover(r) < MIN_COVER) return null
            val mq = FloatArray(levels.size) { m[((m.size - 1) * levels[it]).toInt()] }
            val rq = FloatArray(levels.size) { r[((r.size - 1) * levels[it]).toInt()] }
            // Strictly increasing model quantiles so interpolation is defined (ties sit in the no-echo floor).
            for (i in 1 until mq.size) if (mq[i] <= mq[i - 1]) mq[i] = mq[i - 1] + 1e-4f
            return Calibration(mq, rq)
        }

        /** Radar reflectivity from NOAA's colors (the inverse of [ReflectivityPalette]); transparent is no echo. */
        fun radarDbz(argb: IntArray): FloatArray {
            val cache = HashMap<Int, Float>()
            return FloatArray(argb.size) { k ->
                val c = argb[k]
                if ((c ushr 24) < 40) NO_ECHO else cache.getOrPut(c and 0xFFFFFF) { nearestDbz(c) }
            }
        }

        private fun nearestDbz(color: Int): Float {
            var best = Int.MAX_VALUE
            var bestIndex = 1
            for (index in 1 until ReflectivityPalette.size) {
                val p = ReflectivityPalette.color(index)
                val dr = ((p shr 16) and 0xFF) - ((color shr 16) and 0xFF)
                val dg = ((p shr 8) and 0xFF) - ((color shr 8) and 0xFF)
                val db = (p and 0xFF) - (color and 0xFF)
                val d = dr * dr + dg * dg + db * db
                if (d < best) { best = d; bestIndex = index }
            }
            return ReflectivityPalette.dbz(bestIndex)
        }

        /** True when the radar and the model step are close enough in time to compare. */
        fun matches(radar: Instant, model: Instant): Boolean = abs(java.time.Duration.between(radar, model).toMinutes()) <= 10
    }
}
