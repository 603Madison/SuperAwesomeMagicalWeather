package app.northvane.core

import app.northvane.core.json.NorthvaneJson
import app.northvane.core.json.arr
import app.northvane.core.json.asObj
import app.northvane.core.json.dbl
import app.northvane.core.json.int
import app.northvane.core.radar.Grib2
import kotlin.math.abs
import kotlin.test.Test
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
}
