package app.northvane.core

import app.northvane.core.alerts.AlertClassifier
import app.northvane.core.alerts.AlertTracker
import app.northvane.core.alerts.NotifiedAlerts
import app.northvane.core.model.AlertCategory
import app.northvane.core.model.Units
import app.northvane.core.nws.NwsParsers
import app.northvane.core.radar.RadarService
import app.northvane.core.radar.RadarSource
import app.northvane.core.util.Solar
import kotlinx.coroutines.test.runTest
import app.northvane.core.net.HttpEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import app.northvane.core.time.*

class AlertsAndRadarTest {

    @Test
    fun categorizesEvents() {
        assertEquals(AlertCategory.WARNING, AlertClassifier.categorize("Tornado Warning"))
        assertEquals(AlertCategory.WARNING, AlertClassifier.categorize("Extreme Wind Warning"))
        assertEquals(AlertCategory.WATCH, AlertClassifier.categorize("Severe Thunderstorm Watch"))
        assertEquals(AlertCategory.ADVISORY, AlertClassifier.categorize("Winter Weather Advisory"))
        assertEquals(AlertCategory.STATEMENT, AlertClassifier.categorize("Special Weather Statement"))
        assertEquals(AlertCategory.STATEMENT, AlertClassifier.categorize("Hazardous Weather Outlook"))
        assertEquals(AlertCategory.OTHER, AlertClassifier.categorize("Air Quality Alert"))
    }

    @Test
    fun trackerNotifiesOnceAndRecognizesUpdates() {
        val tracker = AlertTracker()
        val alerts = NwsParsers.parseAlerts(Nws.alerts)
        val all = AlertCategory.entries.toSet()
        // Pretend an earlier message in the warning's thread was already notified.
        val prior = NotifiedAlerts(mapOf("loc|urn:oid:2.49.0.1.840.0.aaa.0|urn:oid:2.49.0.1.840.0.aaa.0" to NOW.toEpochMilli()))
        val (first, state) = tracker.select("loc", alerts, all, prior, NOW)
        assertEquals(listOf("Severe Thunderstorm Warning", "Tornado Watch", "Wind Advisory"), first.map { it.alert.event })
        assertTrue(first[0].isUpdateOfNotified)
        assertFalse(first[1].isUpdateOfNotified)

        val (second, _) = tracker.select("loc", alerts, all, state, NOW.plusSeconds(900))
        assertTrue(second.isEmpty(), "already-notified alerts are not repeated")

        val (otherLocation, _) = tracker.select("other", alerts, all, state, NOW)
        assertEquals(3, otherLocation.size, "tracking is per location")
    }

    @Test
    fun trackerRespectsCategoriesExpiryAndRetention() {
        val tracker = AlertTracker(retention = Duration.ofDays(1))
        val alerts = NwsParsers.parseAlerts(Nws.alerts)
        val (onlyWarnings, _) = tracker.select("loc", alerts, setOf(AlertCategory.WARNING), NotifiedAlerts(), NOW)
        assertEquals(listOf("Severe Thunderstorm Warning"), onlyWarnings.map { it.alert.event })

        val later = Instant.parse("2026-10-30T06:00:00Z") // everything has ended
        val (expired, state) = tracker.select("loc", alerts, AlertCategory.entries.toSet(), NotifiedAlerts(), later)
        assertTrue(expired.isEmpty())
        assertEquals(3, state.entries.size, "expired alerts are remembered but not notified")

        val (_, pruned) = tracker.select("loc", emptyList(), AlertCategory.entries.toSet(), state, later.plus(Duration.ofDays(2)))
        assertTrue(pruned.entries.isEmpty(), "old entries are pruned")
    }

    @Test
    fun hrrrRunEstimateAndFrames() {
        // Observed live: at 13:52Z the newest published run was 11:00Z, and the 12:00Z run's tiles returned 503.
        assertEquals(Instant.parse("2026-10-29T10:00:00Z"), RadarService.estimateHrrrRun(Instant.parse("2026-10-29T13:52:00Z")))
        assertEquals(Instant.parse("2026-10-29T09:00:00Z"), RadarService.estimateHrrrRun(Instant.parse("2026-10-29T12:10:00Z")))

        val frames = RadarService.hrrrFrames(
            init = Instant.parse("2026-10-29T10:00:00Z"),
            now = Instant.parse("2026-10-29T12:10:00Z"),
            horizonMinutes = 60,
        )
        assertEquals(
            listOf("12:15", "12:30", "12:45", "13:00"),
            frames.map { it.time.toString().substring(11, 16) },
        )
        assertTrue(frames.all { it.isForecast })
        assertEquals(
            "https://mesonet.agron.iastate.edu/cache/tile.py/1.0.0/hrrr::REFD-F0135-202610291000/{z}/{x}/{y}.png",
            frames.first().tileUrl,
        )
    }

    @Test
    fun parsesIemModelTimestamps() {
        assertEquals(Instant.parse("2026-10-29T11:00:00Z"), RadarService.parseIemTime("2026-10-29T11:00:00Z"))
        assertEquals(Instant.parse("2026-10-29T11:00:00Z"), RadarService.parseIemTime("2026-10-29 11:00:00"))
    }

    @Test
    fun noaaFramesFromCatalog() = runTest {
        val base = RadarService.NOAA_MRMS_BASE
        val times = (0 until 10).map { NOW.minus(Duration.ofMinutes(4L + 10 * it)) }
        val fake = FakeNetwork().apply {
            on("ImageServer?f=json", body = """{"timeInfo":{"startTimeField":"start_time","timeExtent":[0,0]}}""")
            on("ImageServer/query", body = """{"features":[${times.joinToString(",") { """{"attributes":{"start_time":${it.toEpochMilli()}}}""" }}]}""")
        }
        val service = RadarService(okhttp3.OkHttpClient.Builder().addInterceptor(fake).build())
        val frames = service.pastFrames(RadarSource.NOAA_MRMS, NOW, count = 7)
        assertEquals(7, frames.size)
        assertEquals(times.first(), frames.last().time, "frames are oldest to newest")
        val url = frames.last().tileUrl
        assertTrue(url.startsWith("$base/exportImage?bbox={bbox-epsg-3857}"))
        val windowStart = times.first().minus(Duration.ofMinutes(10)).toEpochMilli()
        assertTrue(url.contains("&time=$windowStart,${times.first().toEpochMilli()}&"), url)
        assertTrue(url.contains("mosaicRule=%7B%22mosaicMethod%22%3A%22esriMosaicAttribute%22"), url)
        val query = fake.requests.last().url
        assertEquals("start_time DESC", query.queryParameter("orderByFields"))
        assertEquals("idp_subset='CONUS'", query.queryParameter("where"))
    }

    @Test
    fun noaaFramesFallBackWhenCatalogFails() = runTest {
        val fake = FakeNetwork() // every request 404s
        val service = RadarService(okhttp3.OkHttpClient.Builder().addInterceptor(fake).build())
        val frames = service.pastFrames(RadarSource.NOAA_MRMS, Instant.parse("2026-10-29T15:07:30Z"), count = 7)
        assertEquals(7, frames.size)
        assertEquals(Instant.parse("2026-10-29T15:07:00Z"), frames.last().time)
        assertEquals(Instant.parse("2026-10-29T14:07:00Z"), frames.first().time)
    }

    @Test
    fun clustersRegionalScanTimes() {
        // Catalog times observed live: each scan is reported per region, seconds to ~1.5 min apart.
        val observed = listOf(
            "13:44:02", "13:38:58", "13:38:57", "13:37:55", "13:30:58", "13:30:56", "13:30:55", "13:29:58",
            "13:24:59", "13:24:54", "13:24:14", "13:16:57", "13:16:54", "13:15:55",
        ).map { Instant.parse("2026-10-29T${it}Z") }
        assertEquals(
            listOf("13:16:57", "13:24:59", "13:30:58", "13:38:58", "13:44:02"),
            RadarService.clusterScans(observed).map { it.toString().substring(11, 19) },
        )
    }

    @Test
    fun iemNexradLayers() = runTest {
        val service = RadarService(okhttp3.OkHttpClient())
        val frames = service.pastFrames(RadarSource.IEM_NEXRAD, Instant.parse("2026-10-29T15:07:30Z"), count = 6)
        assertEquals(6, frames.size)
        assertTrue(frames.first().tileUrl.contains("/nexrad-n0q-900913-m50m/"))
        assertTrue(frames.last().tileUrl.contains("/nexrad-n0q-900913/"))
        assertEquals(Instant.parse("2026-10-29T15:05:00Z"), frames.last().time)
    }

    @Test
    fun solarTimesForNewYorkSolstice() {
        val zone = ZoneId.of("America/New_York")
        val (sunrise, sunset) = Solar.sunTimes(40.7128, -74.0060, LocalDate.of(2024, 6, 21), zone)
        assertNear(Instant.parse("2024-06-21T09:25:00Z"), sunrise!!)
        assertNear(Instant.parse("2024-06-22T00:31:00Z"), sunset!!)
        assertTrue(Solar.isDaylight(40.7128, -74.0060, Instant.parse("2024-06-21T17:00:00Z")))
        assertFalse(Solar.isDaylight(40.7128, -74.0060, Instant.parse("2024-06-21T05:00:00Z")))
    }

    private fun assertNear(expected: Instant, actual: Instant, tolerance: Duration = Duration.ofMinutes(5)) =
        assertTrue(Duration.between(expected, actual).abs() <= tolerance, "expected ~$expected, was $actual")

    @Test
    fun unitConversions() {
        assertEquals(100.0, Units.fToC(212.0), 1e-9)
        assertEquals("N", Units.compass(359.0))
        assertEquals("SW", Units.compass(225.0))
        assertEquals(225.0, Units.compassToDegrees("sw")!!, 1e-9)
        assertEquals(9.3, Units.dewpointC(20.0, 50.0)!!, 0.1)
        // NWS wind chill table: 30°F with 20 mph wind -> 17°F
        assertEquals(17.0, Units.cToF(Units.feelsLikeC(Units.fToC(30.0), null, Units.mphToKph(20.0))), 0.6)
        // NWS heat index table: 90°F at 60% RH -> 100°F
        assertEquals(100.0, Units.cToF(Units.feelsLikeC(Units.fToC(90.0), 60.0, 5.0)), 1.5)
        assertEquals(50.0, Units.humidityPercent(20.0, Units.dewpointC(20.0, 50.0)!!), 0.01)
    }

    @Test
    fun satelliteImageryTiles() {
        assertEquals(app.northvane.core.radar.SatelliteImagery.VISIBLE, app.northvane.core.radar.SatelliteImagery.resolve(app.northvane.core.radar.SatelliteImagery.AUTO, daylight = true))
        assertEquals(app.northvane.core.radar.SatelliteImagery.INFRARED, app.northvane.core.radar.SatelliteImagery.resolve(app.northvane.core.radar.SatelliteImagery.AUTO, daylight = false))
        val url = app.northvane.core.radar.SatelliteImagery.tileUrl(app.northvane.core.radar.SatelliteImagery.INFRARED)
        assertTrue(url.startsWith("https://nowcoast.noaa.gov/geoserver/satellite/wms?"))
        assertTrue("layers=goes_longwave_imagery" in url && "{bbox-epsg-3857}" in url && "crs=EPSG:3857" in url)
    }
}
