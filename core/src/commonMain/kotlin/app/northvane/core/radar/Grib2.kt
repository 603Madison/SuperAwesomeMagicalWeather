package app.northvane.core.radar

import app.northvane.core.net.ParseException
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan
import app.northvane.core.util.toRadians

/** One decoded GRIB2 field on a Lambert conformal grid, rows from south to north. */
class GribField(val grid: LambertGrid, val values: FloatArray) {
    operator fun get(i: Int, j: Int): Float = values[j * grid.nx + i]
}

/**
 * Lambert conformal conic grid (GRIB2 grid template 3.30), as used by NOAA's
 * HRRR. [toGrid] turns a latitude/longitude into fractional grid indices.
 */
class LambertGrid(
    val nx: Int,
    val ny: Int,
    private val lat1: Double,
    private val lon1: Double,
    private val lov: Double,
    private val latin1: Double,
    private val latin2: Double,
    private val dx: Double,
    private val dy: Double,
    private val radius: Double,
) {
    private val n: Double
    private val f: Double
    private val x0: Double
    private val y0: Double

    init {
        val p1 = toRadians(latin1)
        val p2 = toRadians(latin2)
        n = if (kotlin.math.abs(latin1 - latin2) < 1e-9) {
            sin(p1)
        } else {
            ln(cos(p1) / cos(p2)) / ln(tan(PI / 4 + p2 / 2) / tan(PI / 4 + p1 / 2))
        }
        f = cos(p1) * tan(PI / 4 + p1 / 2).pow(n) / n
        val (x, y) = project(lat1, lon1)
        x0 = x
        y0 = y
    }

    private fun project(lat: Double, lon: Double): Pair<Double, Double> {
        val rho = radius * f / tan(PI / 4 + toRadians(lat) / 2).pow(n)
        var dLon = lon - lov
        while (dLon > 180) dLon -= 360
        while (dLon < -180) dLon += 360
        val theta = n * toRadians(dLon)
        return rho * sin(theta) to -rho * cos(theta)
    }

    /** Fractional (i, j) for a point; outside the grid when not in 0..nx-1 / 0..ny-1. */
    fun toGrid(lat: Double, lon: Double): Pair<Double, Double> {
        val (x, y) = project(lat, lon)
        return (x - x0) / dx to (y - y0) / dy
    }
}

/**
 * A small GRIB2 reader for what NOAA's HRRR publishes: grid template 3.30
 * (Lambert conformal), product template 4.0, and data representation 5.0
 * (simple packing), 5.2 (complex packing) or 5.3 (complex packing with
 * spatial differencing), without a bitmap.
 */
object Grib2 {
    private const val EARTH_RADIUS_SHAPE_6 = 6_371_229.0

    /**
     * The data comes from the network, so every size in it is checked before
     * anything is allocated: a damaged or hostile file fails with a
     * [ParseException] instead of exhausting memory.
     */
    fun decode(bytes: ByteArray): GribField = try {
        decodeChecked(bytes)
    } catch (e: IndexOutOfBoundsException) {
        throw ParseException("Truncated GRIB2 section")
    }

    private fun decodeChecked(bytes: ByteArray): GribField {
        if (bytes.size < 16 || bytes.decodeToString(0, 4) != "GRIB" || bytes[7].toInt() != 2) {
            throw ParseException("Not a GRIB2 message")
        }
        var pos = 16
        var grid: LambertGrid? = null
        var drs: ByteArray? = null
        var drsTemplate = -1
        var points = 0
        var data: ByteArray? = null
        while (pos + 4 <= bytes.size) {
            if (bytes.decodeToString(pos, pos + 4) == "7777") break
            val length = u32(bytes, pos).toInt()
            if (length < 5 || length > bytes.size - pos) throw ParseException("Truncated GRIB2 message")
            val section = bytes.copyOfRange(pos, pos + length)
            when (section[4].toInt()) {
                3 -> grid = lambert(section)
                5 -> {
                    points = checkedCount(u32(section, 5), MAX_POINTS, "point count")
                    drsTemplate = u16(section, 9)
                    drs = section
                }
                6 -> if (u8(section, 5) != 255) throw ParseException("GRIB2 bitmaps are not supported")
                7 -> data = section
            }
            pos += length
        }
        val g = grid ?: throw ParseException("GRIB2 message has no grid")
        val d = drs ?: throw ParseException("GRIB2 message has no data representation")
        val payload = data ?: throw ParseException("GRIB2 message has no data")
        if (points.toLong() != g.nx.toLong() * g.ny) throw ParseException("GRIB2 point count doesn't match the grid")
        val values = when (drsTemplate) {
            0 -> simple(d, payload, points)
            2, 3 -> complex(d, payload, points, spatial = drsTemplate == 3)
            else -> throw ParseException("GRIB2 data template 5.$drsTemplate is not supported")
        }
        return GribField(g, values)
    }

    private fun lambert(s: ByteArray): LambertGrid {
        val template = u16(s, 12)
        if (template != 30) throw ParseException("GRIB2 grid template 3.$template is not supported")
        val shape = u8(s, 14)
        val radius = if (shape == 6) EARTH_RADIUS_SHAPE_6 else 6_367_470.0
        val nx = checkedCount(u32(s, 30), MAX_EDGE, "grid width")
        val ny = checkedCount(u32(s, 34), MAX_EDGE, "grid height")
        if (u32(s, 55) == 0L || u32(s, 59) == 0L) throw ParseException("GRIB2 grid spacing is zero")
        return LambertGrid(
            nx = nx,
            ny = ny,
            lat1 = signed32(s, 38) / 1e6,
            lon1 = signed32(s, 42) / 1e6,
            lov = signed32(s, 51) / 1e6,
            latin1 = signed32(s, 65) / 1e6,
            latin2 = signed32(s, 69) / 1e6,
            dx = u32(s, 55) / 1e3,
            dy = u32(s, 59) / 1e3,
            radius = radius,
        ).also {
            // Only +i east and +j north, rows consecutive (HRRR's scanning mode 64).
            if (u8(s, 64) and 0xE0 != 0x40) throw ParseException("GRIB2 scanning mode ${u8(s, 64)} is not supported")
        }
    }

    private class Scale(s: ByteArray) {
        val reference = Float.fromBits(u32(s, 11).toInt()).toDouble()
        val binary = signed16(s, 15)
        val decimal = signed16(s, 17)
        val bits = u8(s, 19)
        private val e = 2.0.pow(binary)
        private val d = 10.0.pow(-decimal)
        fun value(x: Long): Float = ((reference + x * e) * d).toFloat()
    }

    private fun simple(s: ByteArray, data: ByteArray, points: Int): FloatArray {
        val scale = Scale(s)
        checkWidth(scale.bits, "value")
        val bits = BitReader(data, 5)
        return FloatArray(points) { scale.value(if (scale.bits == 0) 0 else bits.read(scale.bits)) }
    }

    private fun complex(s: ByteArray, data: ByteArray, points: Int, spatial: Boolean): FloatArray {
        val scale = Scale(s)
        if (u8(s, 22) != 0) throw ParseException("GRIB2 missing-value management is not supported")
        checkWidth(scale.bits, "value")
        val groups = checkedCount(u32(s, 31), points.toLong(), "group count")
        val widthRef = u8(s, 35)
        val widthBits = u8(s, 36)
        val lengthRef = u32(s, 37)
        val lengthIncrement = u8(s, 41)
        val lastLength = u32(s, 42)
        val lengthBits = u8(s, 46)
        val order = if (spatial) u8(s, 47) else 0
        val extraOctets = if (spatial) u8(s, 48) else 0
        checkWidth(widthBits, "group width")
        checkWidth(lengthBits, "group length")
        if (order !in 0..2) throw ParseException("GRIB2 spatial differencing of order $order is not supported")
        if (order > 0 && extraOctets !in 1..4) throw ParseException("GRIB2 spatial differencing uses $extraOctets octets")

        val bits = BitReader(data, 5)
        // Spatial differencing: the first value(s) and the minimum difference, sign and magnitude.
        val firsts = LongArray(order)
        var minimum = 0L
        if (order > 0) {
            for (k in 0 until order) firsts[k] = bits.readSignMagnitude(extraOctets * 8)
            minimum = bits.readSignMagnitude(extraOctets * 8)
        }
        val refs = LongArray(groups) { if (scale.bits == 0) 0 else bits.read(scale.bits) }
        bits.align()
        val widths = IntArray(groups) { (widthRef + if (widthBits == 0) 0 else bits.read(widthBits)).toInt().also { checkWidth(it, "group value") } }
        bits.align()
        val lengths = LongArray(groups) { lengthRef + (if (lengthBits == 0) 0 else bits.read(lengthBits)) * lengthIncrement }
        if (groups > 0) lengths[groups - 1] = lastLength
        bits.align()

        // Undo the spatial differencing as values are unpacked, straight into the output: keeping
        // every raw value first would cost another 15 MB for a CONUS field.
        val out = FloatArray(points)
        var k = 0
        var previous = 0L
        var beforePrevious = 0L
        for (g in 0 until groups) {
            val w = widths[g]
            val count = lengths[g].toInt()
            for (m in 0 until count) {
                if (k >= points) break
                val packed = refs[g] + if (w == 0) 0 else bits.read(w)
                val value = when (order) {
                    1 -> if (k == 0) firsts[0] else packed + minimum + previous
                    2 -> when (k) {
                        0 -> firsts[0]
                        1 -> firsts[1]
                        else -> packed + minimum + 2 * previous - beforePrevious
                    }
                    else -> packed
                }
                beforePrevious = previous
                previous = value
                out[k++] = scale.value(value)
            }
        }
        if (k != points) throw ParseException("GRIB2 groups don't add up to the grid")
        return out
    }

    private class BitReader(private val bytes: ByteArray, startByte: Int) {
        private var bit = startByte.toLong() * 8

        fun read(n: Int): Long {
            var value = 0L
            var remaining = n
            while (remaining > 0) {
                val byteIndex = (bit ushr 3).toInt()
                if (byteIndex >= bytes.size) throw ParseException("GRIB2 data ended early")
                val offset = (bit and 7).toInt()
                val take = minOf(8 - offset, remaining)
                val chunk = (bytes[byteIndex].toInt() and 0xFF) ushr (8 - offset - take) and ((1 shl take) - 1)
                value = (value shl take) or chunk.toLong()
                bit += take
                remaining -= take
            }
            return value
        }

        fun readSignMagnitude(n: Int): Long {
            val negative = read(1) == 1L
            val magnitude = read(n - 1)
            return if (negative) -magnitude else magnitude
        }

        fun align() {
            bit = (bit + 7) / 8 * 8
        }
    }

    /** The most values a field may hold: HRRR's CONUS grid is 1799 × 1059 (about 1.9 million). */
    private const val MAX_POINTS = 8_000_000L
    private const val MAX_EDGE = 10_000L

    private fun checkedCount(value: Long, max: Long, what: String): Int {
        if (value < 1 || value > max) throw ParseException("GRIB2 $what $value is out of range")
        return value.toInt()
    }

    /** Packed values are at most 32 bits wide; anything wider would overflow. */
    private fun checkWidth(bits: Int, what: String) {
        if (bits !in 0..32) throw ParseException("GRIB2 $what width of $bits bits is not supported")
    }

    // GRIB2 is big-endian; positions are zero-based offsets into a section.
    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun u16(b: ByteArray, i: Int) = (u8(b, i) shl 8) or u8(b, i + 1)
    private fun u32(b: ByteArray, i: Int): Long = (u8(b, i).toLong() shl 24) or (u8(b, i + 1).toLong() shl 16) or (u8(b, i + 2).toLong() shl 8) or u8(b, i + 3).toLong()

    /** GRIB2 signed integers use a sign bit, not two's complement. */
    private fun signed16(b: ByteArray, i: Int): Int {
        val v = u16(b, i)
        return if (v and 0x8000 != 0) -(v and 0x7FFF) else v
    }

    private fun signed32(b: ByteArray, i: Int): Long {
        val v = u32(b, i)
        return if (v and 0x80000000L != 0L) -(v and 0x7FFFFFFFL) else v
    }
}
