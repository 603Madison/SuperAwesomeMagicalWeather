package app.northvane.core

import app.northvane.core.radar.ObservedRadar
import app.northvane.core.radar.RadarBlend
import app.northvane.core.radar.ReflectivityPalette
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import app.northvane.core.time.*

class RadarBlendTest {
    private val w = 160
    private val h = 120
    private val t0 = Instant.parse("2026-11-06T20:00:00Z")

    /** A radar picture with a rain blob (30 dBZ, a 40 dBZ core) centred at ([cx], [cy]). */
    private fun scene(cx: Int, cy: Int): IntArray = IntArray(w * h) { k ->
        val x = k % w
        val y = k / w
        val d2 = (x - cx) * (x - cx) + (y - cy) * (y - cy)
        when {
            d2 < 36 -> ReflectivityPalette.argb(40f)
            d2 < 400 -> ReflectivityPalette.argb(30f)
            else -> 0
        }
    }

    @Test
    fun measuresTheMotionBetweenScans() {
        val earlier = ObservedRadar(t0, w, h, scene(60, 50))
        val latest = ObservedRadar(t0.plusSeconds(20 * 60), w, h, scene(72, 58)) // 12 px east, 8 px south in 20 minutes
        val blend = RadarBlend.build(latest, earlier)
        assertEquals(12.0 / 20, blend.vx, 0.06)
        assertEquals(8.0 / 20, blend.vy, 0.06)
        // Thirty minutes on, the core has moved another 18 px east and 12 south.
        val core = blend.valueAt(72 + 18, 58 + 12, w, h, 30.0)
        assertTrue(core >= 35f, "core $core")
        assertTrue(blend.valueAt(72, 58, w, h, 30.0) < 35f)
    }

    @Test
    fun handsOverFromRadarToModel() {
        val blend = RadarBlend.build(ObservedRadar(t0, w, h, scene(80, 60)), null)
        assertEquals(1f, blend.weight(0.0))
        assertEquals(0.5f, blend.weight(60.0))
        assertEquals(0f, blend.weight(150.0))
        // At first the radar wins; rain the model lacks fades out rather than ghosting at half strength.
        assertEquals(30f, RadarBlend.mix(Float.NaN, 30f, 1f), 0.01f)
        val half = RadarBlend.mix(-10f, 30f, 0.5f)
        assertTrue(half in 26f..28f, "half $half")
        assertEquals(20f, RadarBlend.mix(20f, 30f, 0f), 0.01f)
        assertEquals(20f, RadarBlend.mix(20f, Float.NaN, 0.7f), 0.01f)
    }

    @Test
    fun noMotionWithoutEnoughRain() {
        val empty = IntArray(w * h)
        assertEquals(null, RadarBlend.estimateMotion(FloatArray(w * h) { -10f }, FloatArray(w * h) { -10f }, w, h))
        val blend = RadarBlend.build(ObservedRadar(t0.plusSeconds(1200), w, h, empty), ObservedRadar(t0, w, h, empty))
        assertNotNull(blend)
        assertEquals(0.0, blend.vx)
    }
}
