package app.northvane.core

import app.northvane.core.net.HttpClients
import app.northvane.core.radar.Calibration
import app.northvane.core.radar.HrrrForecast
import app.northvane.core.radar.ObservedRadar
import app.northvane.core.radar.ReflectivityPalette
import app.northvane.core.radar.TileArea
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import app.northvane.core.time.*

class CalibrationTest {
    private fun cover(values: FloatArray) = values.count { it >= ReflectivityPalette.MIN_DBZ }.toDouble() / values.size

    @Test
    fun readsReflectivityBackFromRadarColors() {
        for (dbz in listOf(5f, 12f, 20.5f, 33f, 45f, 52f)) {
            val back = Calibration.radarDbz(intArrayOf(ReflectivityPalette.argb(dbz)))[0]
            assertEquals(dbz.toDouble(), back.toDouble(), 0.6, "$dbz")
        }
        assertEquals(-10f, Calibration.radarDbz(intArrayOf(0))[0]) // transparent: no echo
    }

    /** A model field that draws too little rain: 25% at 20 dBZ, a weak 0 dBZ fringe, dry elsewhere. */
    private fun model(n: Int = 10_000) = FloatArray(n) { k ->
        when {
            k < n / 4 -> 20f + (k % 7)
            k < n * 4 / 10 -> 0f + (k % 3)
            else -> -10f
        }
    }

    @Test
    fun matchesTheRadarsRainArea() {
        val m = model()
        // The radar has rain over 40% of the area.
        val radar = FloatArray(m.size) { k -> if (k < m.size * 4 / 10) 18f + (k % 9) else -10f }
        val cal = Calibration.build(m, radar)!!
        val calibrated = FloatArray(m.size) { cal.apply(m[it]) }
        assertEquals(0.25, cover(m), 0.01)
        assertEquals(0.40, cover(calibrated), 0.02)
        // The order of values is kept: stronger stays stronger.
        assertTrue(cal.apply(25f) >= cal.apply(20f))
        assertTrue(cal.apply(-10f) < ReflectivityPalette.MIN_DBZ)
        // Below 0 dBZ nothing turns into rain, however much more rain the radar has.
        assertEquals(-3f, cal.apply(-3f))
    }

    @Test
    fun noCalibrationWithoutEnoughRainOrData() {
        val dry = FloatArray(10_000) { -10f }
        assertNull(Calibration.build(model(), dry))
        assertNull(Calibration.build(dry, FloatArray(10_000) { 20f }))
        assertNull(Calibration.build(model(500), FloatArray(500) { 20f })) // too few pixels
        // Pixels outside the model grid (NaN) are ignored.
        val m = model()
        m.fill(Float.NaN, 0, 100)
        assertTrue(Calibration.build(m, FloatArray(m.size) { k -> if (k < 4000) 20f else -10f }) != null)
    }

    @Test
    fun correctionIsCapped() {
        val m = FloatArray(10_000) { k -> if (k < 3000) 10f + (k % 5) else -10f }
        val r = FloatArray(10_000) { k -> if (k < 3000) 55f + (k % 5) else -10f } // radar 45 dBZ stronger
        val cal = Calibration.build(m, r)!!
        assertEquals(22f, cal.apply(12f), 0.01f) // 12 + at most 10
    }

    @Test
    fun framesAreCalibratedAgainstTheRadarAtTheMatchingStep() = runTest {
        val grib = requireNotNull(javaClass.getResource("/hrrr/refd1km_t13z_f015.grib2")).readBytes()
        val index = "1:0:d=x:REFC:entire atmosphere:15 min fcst:\n2:${grib.size}:d=x:RETOP:cloud top:15 min fcst:\n"
        val fake = FakeNetwork().apply {
            on("example.test", code = 404, body = "")
            on("hrrr.t13z.wrfsubhf01.grib2.idx", body = index)
            onBytes("hrrr.t13z.wrfsubhf01.grib2", grib)
        }
        val http = HttpClients.create("test-agent", cacheDir = null).newBuilder().addInterceptor(fake).build()
        val hrrr = HrrrForecast(http, base = "https://example.test")
        val init = Instant.parse("2026-11-03T13:00:00Z")
        val step = init.plusSeconds(15 * 60)
        val area = TileArea.around(43.66, -70.26, zoom = 6, cols = 2, rows = 2)
        val now = init.plusSeconds(60)
        val plain = hrrr.frames(now, listOf(step), area).single()
        assertFalse(plain.calibrated)
        // "Radar" = the model's own picture with stronger rain: calibration brightens the frame.
        val stronger = plain.image!!.argb().let { px ->
            val w = area.width / 2
            IntArray(w * (area.height / 2)) { k -> px[((k / w) * 2) * area.width + (k % w) * 2].let { c -> if (c == 0) 0 else ReflectivityPalette.argb(Calibration.radarDbz(intArrayOf(c))[0] + 8f) } }
        }
        val observed = ObservedRadar(step.plusSeconds(120), area.width / 2, area.height / 2, stronger)
        val calibrated = hrrr.frames(now, listOf(step), area, observed).single()
        assertTrue(calibrated.calibrated)
        fun meanIndex(f: app.northvane.core.radar.RadarFrame) = f.image!!.indices.filter { it.toInt() != 0 }.map { it.toInt() and 0xFF }.average()
        assertTrue(meanIndex(calibrated) > meanIndex(plain) + 8, "${meanIndex(plain)} -> ${meanIndex(calibrated)}")
        // A radar picture from too far from the model step isn't used.
        val late = ObservedRadar(step.plusSeconds(30 * 60), observed.width, observed.height, stronger)
        assertFalse(hrrr.frames(now, listOf(step), area, late).single().calibrated)
    }
}
