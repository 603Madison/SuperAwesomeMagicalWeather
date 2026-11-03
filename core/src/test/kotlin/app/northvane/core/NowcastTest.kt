package app.northvane.core

import app.northvane.core.net.HttpClients
import app.northvane.core.radar.Nowcast
import app.northvane.core.radar.RadarFrame
import app.northvane.core.radar.RadarService
import app.northvane.core.radar.RadarSource
import app.northvane.core.radar.TileArea
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.Assume.assumeTrue
import java.awt.image.BufferedImage
import java.time.Duration
import java.time.Instant
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NowcastTest {
    private val w = 384
    private val h = 384

    /** An elliptical "storm" of echo pixels centered at (cx, cy). */
    private fun storm(cx: Double, cy: Double, rx: Double = 60.0, ry: Double = 35.0): IntArray = IntArray(w * h) { k ->
        val x = k % w
        val y = k / w
        val d = ((x - cx) / rx).let { it * it } + ((y - cy) / ry).let { it * it }
        if (d <= 1.0) (0xFF shl 24) or 0x22AA44 else 0
    }

    private fun csi(forecast: IntArray, truth: IntArray): Double {
        var hits = 0
        var misses = 0
        var falseAlarms = 0
        for (i in forecast.indices) {
            val f = (forecast[i] ushr 24) > 40
            val t = (truth[i] ushr 24) > 40
            if (f && t) hits++ else if (t) misses++ else if (f) falseAlarms++
        }
        return hits.toDouble() / (hits + misses + falseAlarms).coerceAtLeast(1)
    }

    @Test
    fun measuresMotionAndCarriesTheStormForward() {
        // 2 px/min east and 1 px/min south over 30 minutes.
        val earlier = storm(110.0, 120.0)
        val later = storm(170.0, 150.0)
        val motion = Nowcast.motion(earlier, later, w, h, minutes = 30.0)
        assertEquals(2.0, motion.meanU, 0.35)
        assertEquals(1.0, motion.meanV, 0.35)
        val ahead = Nowcast.extrapolate(later, w, h, motion, leadMinutes = 30.0)
        val truth = storm(230.0, 180.0)
        assertTrue(csi(ahead, truth) > 0.8, "csi ${csi(ahead, truth)}")
        assertTrue(csi(later, truth) < 0.5) // standing still would be much worse
        // Faded a little with lead time, colors kept.
        val pixel = ahead.first { it != 0 }
        assertTrue((pixel ushr 24) in 200..254)
        assertEquals(0x22AA44, pixel and 0xFFFFFF)
    }

    @Test
    fun noEchoesMeansNoMotionAndEmptyFrames() {
        val empty = IntArray(w * h)
        val motion = Nowcast.motion(empty, empty, w, h, 20.0)
        assertEquals(0.0, motion.meanU, 1e-9)
        assertTrue(Nowcast.extrapolate(empty, w, h, motion, 60.0).all { it == 0 })
    }

    @Test
    fun tileAreaMath() {
        val area = TileArea.around(43.66, -70.26, zoom = 7, cols = 3, rows = 4)
        assertTrue(-70.26 in area.west..area.east && 43.66 in area.south..area.north, "$area")
        assertEquals(768, area.width)
        assertEquals(1024, area.height)
        assertEquals(12, area.tiles().size)
        assertEquals("https://x/7/38/46.png", TileArea.tileUrl("https://x/{z}/{x}/{y}.png", 7, 38, 46))
        val wms = TileArea.tileUrl("https://n/exportImage?bbox={bbox-epsg-3857}&f=image", 0, 0, 0)
        assertEquals("https://n/exportImage?bbox=-20037508.3428,-20037508.3428,20037508.3428,20037508.3428&f=image", wms)
    }

    @Test
    fun picksTwoEarlierFramesFifteenMinutesApart() {
        val t = Instant.parse("2026-11-03T12:30:00Z")
        val frames = listOf(50L, 35L, 28L, 14L, 10L, 4L, 0L).map { RadarFrame(t.minus(Duration.ofMinutes(it)), false, "u$it") }
        val (first, middle) = Nowcast.pickEarlier(frames)!!
        assertEquals("u14", middle.tileUrl)
        assertEquals("u28", first!!.tileUrl)
        assertEquals(null, Nowcast.pickEarlier(frames.takeLast(2)))
        assertEquals(null, Nowcast.pickEarlier(frames.takeLast(4))!!.first) // room for one interval only
    }

    @Test
    fun disagreeingMotionIsDamped() {
        val east = Nowcast.motion(storm(110.0, 150.0), storm(140.0, 150.0), w, h, 15.0) // 2 px/min east
        val steady = Nowcast.combine(east, east)
        assertEquals(east.meanU, steady.meanU, 0.01)
        val west = Nowcast.motion(storm(170.0, 150.0), storm(140.0, 150.0), w, h, 15.0)
        assertEquals(0.0, Nowcast.combine(east, west).meanU, 0.01)
    }

    /** Live skill check: forecast the latest radar 30 minutes ahead and score it against what happened. */
    @Test
    fun liveNowcastBeatsPersistenceAndTheModel() = runBlocking {
        assumeTrue(System.getProperty("northvane.live") == "true")
        val http = HttpClients.create("Northvane-test", cacheDir = null)
        val service = RadarService(http)
        val now = Instant.now()
        val source = if (System.getProperty("nowcast.source") == "noaa") RadarSource.NOAA_MRMS else RadarSource.IEM_NEXRAD
        val leadWanted = System.getProperty("nowcast.lead")?.toLong() ?: 30L
        val past = service.pastFrames(source, now, count = 40)
        val latest = past.last()
        val start = past.last { Duration.between(it.time, latest.time).toMinutes() >= leadWanted }
        val (first, middle) = Nowcast.pickEarlier(past.filter { !it.time.isAfter(start.time) })!!
        val lead = Duration.between(start.time, latest.time).toMinutes().toDouble()
        val area = TileArea.around(System.getProperty("nowcast.lat")?.toDouble() ?: 43.66, System.getProperty("nowcast.lon")?.toDouble() ?: -70.26)
        fun mosaic(template: String): IntArray = runBlocking {
            val image = BufferedImage(area.width, area.height, BufferedImage.TYPE_INT_ARGB)
            area.tiles().map { (z, x, y) ->
                async(kotlinx.coroutines.Dispatchers.IO) {
                    val bytes = http.newCall(Request.Builder().url(TileArea.tileUrl(template, z, x, y)).build()).execute().use { it.body.bytes() }
                    Triple(x, y, ImageIO.read(bytes.inputStream()))
                }
            }.awaitAll().forEach { (x, y, tile) ->
                if (tile != null) image.graphics.drawImage(tile, (x - area.x0) * 256, (y - area.y0) * 256, null)
            }
            image.getRGB(0, 0, area.width, area.height, null, 0, area.width)
        }
        val f = first?.let { mosaic(it.tileUrl) }
        val m = mosaic(middle.tileUrl)
        val s = mosaic(start.tileUrl)
        val truth = mosaic(latest.tileUrl)
        fun minutes(a: RadarFrame, b: RadarFrame) = Duration.between(a.time, b.time).toMinutes().toDouble()
        val recentMotion = Nowcast.motion(m, s, area.width, area.height, minutes(middle, start))
        val motion = if (f != null && first != null) {
            Nowcast.combine(Nowcast.motion(f, m, area.width, area.height, minutes(first, middle)), recentMotion)
        } else {
            recentMotion.scaled(0.5) // as the app does with one interval
        }
        val forecast = Nowcast.extrapolate(s, area.width, area.height, motion, lead)
        val model = service.futureFrames(start.time, 30).firstOrNull()?.let { mosaic(it.tileUrl) }
        val echo = truth.count { (it ushr 24) > 40 } * 100.0 / truth.size
        println("Echo coverage %.1f%%, motion (%.2f, %.2f) px/min, confidence %.2f, lead %.0f min".format(echo, motion.meanU, motion.meanV, motion.confidence, lead))
        println("CSI nowcast %.3f · persistence %.3f · HRRR %s".format(csi(forecast, truth), csi(s, truth), model?.let { "%.3f".format(csi(it, truth)) }))
        // Diagnostics: the best single shift in hindsight, and motion measured over the latest interval.
        var bestCsi = 0.0
        var bestShift = 0 to 0
        for (dy in -40..40 step 4) for (dx in -40..40 step 4) {
            val shifted = IntArray(s.size)
            for (y in 0 until area.height) for (x in 0 until area.width) {
                val sx = x - dx
                val sy = y - dy
                if (sx in 0 until area.width && sy in 0 until area.height) shifted[y * area.width + x] = s[sy * area.width + sx]
            }
            val c = csi(shifted, truth)
            if (c > bestCsi) { bestCsi = c; bestShift = dx to dy }
        }
        val recent = Nowcast.motion(s, truth, area.width, area.height, lead)
        println("Hindsight best shift $bestShift csi %.3f; motion start→latest (%.2f, %.2f) px/min".format(bestCsi, recent.meanU, recent.meanV))
        // Pictures of the real nowcast from the latest frame, for a visual check.
        val ahead = Nowcast.frames(area, truth, latest.time, motion, 90)
        val out = java.io.File("build/nowcast").apply { mkdirs() }
        fun save(name: String, px: IntArray) {
            val img = BufferedImage(area.width, area.height, BufferedImage.TYPE_INT_ARGB)
            img.setRGB(0, 0, area.width, area.height, px, 0, area.width)
            ImageIO.write(img, "png", java.io.File(out, "$name.png"))
        }
        save("latest", truth)
        ahead.filter { Duration.between(latest.time, it.time).toMinutes() in setOf(30L, 60L, 90L) }
            .forEach { save("plus" + Duration.between(latest.time, it.time).toMinutes(), it.nowcast!!.pixels) }
        if (echo > 2.0) assertTrue(csi(forecast, truth) >= csi(s, truth) - 0.02)
    }
}
