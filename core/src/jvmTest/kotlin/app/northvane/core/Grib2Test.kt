package app.northvane.core

import app.northvane.core.json.NorthvaneJson
import app.northvane.core.json.arr
import app.northvane.core.json.asObj
import app.northvane.core.json.dbl
import app.northvane.core.json.int
import app.northvane.core.radar.Grib2
import kotlin.math.abs
import app.northvane.core.net.ParseException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Checked against ECMWF eccodes' decoding of the same HRRR message (values, grid and lat/lon). */
class Grib2Test {
    private val bytes = requireNotNull(javaClass.getResource("/hrrr/refd1km_t13z_f015.grib2")).readBytes()
    private val expected = NorthvaneJson.parseToJsonElement(
        requireNotNull(javaClass.getResource("/hrrr/refd1km_t13z_f015.expected.json")).readText(),
    ).asObj()!!

    @Test
    fun decodesHrrrReflectivityExactly() {
        val field = Grib2.decode(bytes)
        assertEquals(expected.int("nx"), field.grid.nx)
        assertEquals(expected.int("ny"), field.grid.ny)
        assertEquals(expected.dbl("min")!!, field.values.min().toDouble(), 1e-4)
        assertEquals(expected.dbl("max")!!, field.values.max().toDouble(), 1e-4)
        assertEquals(expected.dbl("sum")!!, field.values.sumOf { it.toDouble() }, 1.0)
        for (s in expected.arr("samples")!!.map { it.asObj()!! }) {
            assertEquals(s.dbl("value")!!, field[s.int("i")!!, s.int("j")!!].toDouble(), 1e-4, "at ${s.int("i")},${s.int("j")}")
        }
    }

    @Test
    fun projectsLatitudeLongitudeOntoTheGrid() {
        val grid = Grib2.decode(bytes).grid
        for (s in expected.arr("samples")!!.map { it.asObj()!! }.filter { it.dbl("lat") != null }) {
            val (i, j) = grid.toGrid(s.dbl("lat")!!, s.dbl("lon")!!)
            assertTrue(abs(i - s.int("i")!!) < 0.01 && abs(j - s.int("j")!!) < 0.01, "($i, $j) vs ${s.int("i")},${s.int("j")}")
        }
    }

    /** Offset of GRIB2 section [number] in the fixture. */
    private fun sectionAt(number: Int): Int {
        var pos = 16
        while (true) {
            val length = java.nio.ByteBuffer.wrap(bytes, pos, 4).int
            if (bytes[pos + 4].toInt() == number) return pos
            pos += length
        }
    }

    private fun corrupted(section: Int, offset: Int, vararg value: Int) =
        bytes.copyOf().also { b -> value.forEachIndexed { k, v -> b[sectionAt(section) + offset + k] = v.toByte() } }

    @Test
    fun rejectsSizesThatWouldExhaustMemory() {
        // A point count of 4 billion, a grid of 65535 x 65535, and as many groups as there are points + 1.
        assertFailsWith<ParseException> { Grib2.decode(corrupted(5, 5, 0xFF, 0xFF, 0xFF, 0xFF)) }
        assertFailsWith<ParseException> { Grib2.decode(corrupted(3, 30, 0, 0, 0xFF, 0xFF)) }
        assertFailsWith<ParseException> { Grib2.decode(corrupted(5, 31, 0x7F, 0xFF, 0xFF, 0xFF)) }
        // Values packed wider than 32 bits.
        assertFailsWith<ParseException> { Grib2.decode(corrupted(5, 19, 60)) }
    }

    @Test
    fun damagedFilesFailCleanly() {
        val random = java.util.Random(7)
        repeat(300) { k ->
            val damaged = if (k % 2 == 0) {
                bytes.copyOf(random.nextInt(bytes.size))
            } else {
                // Flip bytes in the headers (sections 1 to 5), where sizes and layouts live.
                bytes.copyOf().also { b -> repeat(3) { b[16 + random.nextInt(sectionAt(6) - 16)] = random.nextInt(256).toByte() } }
            }
            try {
                Grib2.decode(damaged)
            } catch (e: ParseException) {
                // Expected for most damage.
            }
        }
    }
}
