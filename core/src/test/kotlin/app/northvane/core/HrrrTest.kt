package app.northvane.core

import app.northvane.core.net.HttpClients
import app.northvane.core.radar.HrrrForecast
import app.northvane.core.radar.RadarService
import app.northvane.core.radar.RadarSource
import app.northvane.core.radar.ReflectivityPalette
import app.northvane.core.radar.TileArea
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import java.awt.image.BufferedImage
import java.time.Duration
import java.time.Instant
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HrrrTest {
    private val grib = requireNotNull(javaClass.getResource("/hrrr/refd1km_t13z_f015.grib2")).readBytes()
    private val init = Instant.parse("2026-11-03T13:00:00Z")
    private val portland = 43.66 to -70.26

    private fun client(fake: FakeNetwork) = HttpClients.create("test-agent", cacheDir = null).newBuilder().addInterceptor(fake).build()

    /** An index whose composite reflectivity entry for 15 minutes spans the whole fixture (served for any range). */
    private val index = """
        1:0:d=2026110313:REFC:entire atmosphere:15 min fcst:
        2:${grib.size}:d=2026110313:RETOP:cloud top:15 min fcst:
    """.trimIndent()

    private fun indexFor(minutes: Int, next: Int = grib.size) = """
        1:0:d=x:REFC:entire atmosphere:$minutes min fcst:
        2:$next:d=x:RETOP:cloud top:$minutes min fcst:
    """.trimIndent()

    @Test
    fun paletteMatchesTheRadarService() {
        assertEquals(0, ReflectivityPalette.argb(2f)) // too weak to draw
        assertEquals(0xFF8F97B4.toInt(), ReflectivityPalette.argb(5f))
        assertEquals(0xFF0ED614.toInt(), ReflectivityPalette.argb(29f)) // bright green
        assertEquals(0xFFFFE200.toInt(), ReflectivityPalette.argb(44f)) // yellow
        assertEquals(0xFFFFFFFF.toInt(), ReflectivityPalette.argb(80f))
        val mid = ReflectivityPalette.argb(11.5f) // between two anchors
        assertTrue(mid != ReflectivityPalette.argb(9f) && mid != ReflectivityPalette.argb(14f))
    }

    @Test
    fun frameTimesAndFiles() {
        val now = Instant.parse("2026-11-03T14:52:00Z")
        val times = HrrrForecast.frameTimes(Instant.parse("2026-11-03T14:47:00Z"), now, now.plus(Duration.ofHours(3)))
        assertEquals(Instant.parse("2026-11-03T15:00:00Z"), times.first())
        assertEquals(15L, Duration.between(times[0], times[1]).toMinutes())
        assertEquals(30L, Duration.between(times[times.size - 2], times.last()).toMinutes()) // past two hours
        assertEquals(1, HrrrForecast.fileHour(15))
        assertEquals(1, HrrrForecast.fileHour(60))
        assertEquals(2, HrrrForecast.fileHour(75))
    }

    @Test
    fun eachFrameComesFromTheNewestRunThatHasIt() = runTest {
        val fake = FakeNetwork().apply {
            on("example.test", code = 404, body = "")
            // 15Z has published its first hour only; 14Z has its second and third hours.
            on("hrrr.t15z.wrfsubhf01.grib2.idx", body = indexFor(15) + "\n" + indexFor(30) + "\n" + indexFor(45) + "\n" + indexFor(60))
            on("hrrr.t14z.wrfsubhf02.grib2.idx", body = indexFor(75) + "\n" + indexFor(90) + "\n" + indexFor(105) + "\n" + indexFor(120))
            on("hrrr.t14z.wrfsubhf03.grib2.idx", body = indexFor(135) + "\n" + indexFor(150) + "\n" + indexFor(165) + "\n" + indexFor(180))
        }
        val hrrr = HrrrForecast(client(fake), base = "https://example.test")
        val now = Instant.parse("2026-11-03T15:58:00Z")
        val times = listOf("16:00", "16:15", "16:30", "17:00", "17:15", "18:30").map { Instant.parse("2026-11-03T$it:00Z") }
        val runs = hrrr.runsFor(now, times)
        val t15 = Instant.parse("2026-11-03T15:00:00Z")
        val t14 = Instant.parse("2026-11-03T14:00:00Z")
        assertEquals(t15, runs[times[0]]) // 16:00 is in 15Z's first hour
        assertEquals(t14, runs[times[1]]) // 16:15 isn't out from 15Z yet, so 14Z
        assertEquals(t14, runs[times[3]]) // 17:00: 14Z's third hour
        assertNull(runs[times[4]]) // 17:15 would need 14Z's fourth hour, not published
        assertNull(runs[times[5]])
    }

    @Test
    fun rendersRealHrrrDataOverTheMap() = runTest {
        val fake = FakeNetwork().apply {
            on("wrfsubhf01.grib2.idx", body = index)
            onBytes("wrfsubhf01.grib2", grib)
        }
        val area = TileArea.around(portland.first, portland.second, zoom = 6, cols = 3, rows = 3)
        val frames = HrrrForecast(client(fake), base = "https://example.test").frames(init.plusSeconds(60), listOf(init.plusSeconds(15 * 60)), area)
        assertEquals(1, frames.size)
        val frame = frames.single()
        assertTrue(frame.isForecast)
        assertEquals(init, frame.run)
        val image = frame.image!!
        assertEquals(768, image.width)
        val pixels = image.argb()
        val drawn = pixels.count { it != 0 }
        assertTrue(drawn in 1000 until pixels.size / 2, "$drawn pixels")
        // Only palette colors, fully opaque.
        assertTrue(pixels.filter { it != 0 }.all { (it ushr 24) == 0xFF })
        // The byte range comes from the index.
        assertTrue(fake.requests.any { it.header("Range") == "bytes=0-${grib.size - 1}" })
    }

    /** Live: newest NOAA run for Portland, ME, saved as pictures for a look. */
    @Test
    fun liveNewestRun() = runBlocking {
        assumeTrue(System.getProperty("northvane.live") == "true")
        val http = HttpClients.create("Northvane-test", cacheDir = null)
        val now = Instant.now()
        val area = TileArea.around(portland.first, portland.second, zoom = 7, cols = 4, rows = 4) // as the app does
        val service = RadarService(http)
        val latest = service.pastFrames(RadarSource.NOAA_MRMS, now).last()
        val started = System.nanoTime()
        val decode = { bytes: ByteArray ->
            ImageIO.read(bytes.inputStream())?.let { img -> img.getRGB(0, 0, img.width, img.height, null, 0, img.width) }
        }
        val frames = service.futureFrames(now, 120, after = latest.time, area = area, decodePng = decode)
        println("Latest radar ${latest.time}; ${frames.size} model frames from runs ${frames.mapNotNull { it.run }.distinct()} " +
            "calibrated=${frames.count { it.calibrated }} in ${(System.nanoTime() - started) / 1_000_000} ms")
        assertTrue(frames.isNotEmpty() && frames.all { it.image != null })
        val out = java.io.File("build/hrrr").apply { mkdirs() }
        frames.forEachIndexed { k, f ->
            val img = BufferedImage(area.width, area.height, BufferedImage.TYPE_INT_ARGB)
            img.setRGB(0, 0, area.width, area.height, f.image!!.argb(), 0, area.width)
            ImageIO.write(img, "png", java.io.File(out, "frame%02d.png".format(k)))
        }
    }
}
