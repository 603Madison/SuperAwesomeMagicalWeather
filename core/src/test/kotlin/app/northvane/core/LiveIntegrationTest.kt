package app.northvane.core

import app.northvane.core.model.DataSource
import app.northvane.core.model.SavedLocation
import app.northvane.core.net.HttpClients
import app.northvane.core.net.fetch
import app.northvane.core.nws.NwsClient
import app.northvane.core.owm.OwmClient
import app.northvane.core.radar.RadarService
import app.northvane.core.radar.RadarSource
import app.northvane.core.repo.CachedNwsPoint
import app.northvane.core.repo.FetchPlan
import app.northvane.core.repo.NwsPointCache
import app.northvane.core.repo.SourceConfig
import app.northvane.core.repo.WeatherRepository
import app.northvane.core.weewx.WeeWxClient
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.Assume.assumeTrue
import org.junit.Before
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end checks against the real services. Skipped unless run with
 * `-Pnorthvane.live=true` (`./gradlew :core:test -Pnorthvane.live=true`),
 * so normal builds stay offline and deterministic.
 */
class LiveIntegrationTest {
    @Before
    fun onlyWhenRequested() = assumeTrue(System.getProperty("northvane.live") == "true")

    private val http = HttpClients.create("SuperAwesomeMagicalWeather/test (integration tests)", cacheDir = null)

    private val cache = object : NwsPointCache {
        val map = mutableMapOf<String, CachedNwsPoint>()
        override suspend fun get(key: String) = map[key]
        override suspend fun put(key: String, value: CachedNwsPoint) { map[key] = value }
    }

    @Test
    fun fullRefreshFromNws() = runBlocking {
        val repo = WeatherRepository(
            NwsClient(http, { "SuperAwesomeMagicalWeather/test (integration tests)" }),
            OwmClient(http),
            WeeWxClient(HttpClients.localServerClients(http)),
            cache,
        )
        val snapshot = repo.fetch(SavedLocation("ks", "Kansas", 39.7456, -97.0892), SourceConfig(), FetchPlan.FULL)
        val current = assertNotNull(snapshot.current)
        println("current: ${current.source} ${current.stationName} ${current.temperatureC}°C ${current.description}")
        println("hourly=${snapshot.hourly.size} daily=${snapshot.daily.size} alerts=${snapshot.alerts.size} issues=${snapshot.issues}")
        assertTrue(current.source == DataSource.NWS || current.source == DataSource.NWS_FORECAST_ESTIMATE)
        assertTrue(snapshot.hourly.size >= 24)
        assertTrue(snapshot.daily.size >= 6)
        assertEquals("America/Chicago", snapshot.timeZoneId)
        assertTrue(snapshot.daily.take(5).all { it.detailSource == app.northvane.core.model.DetailSource.GRID }, "grid details")
        assertTrue(snapshot.hourly.take(12).all { it.windGustKph != null && it.skyCoverPercent != null })
        assertTrue(snapshot.daily.all { it.outageRisk != null })
        println("outage: " + snapshot.daily.map { "${it.date}=${it.outageRisk?.level}(${it.outageRisk?.score})" })
    }

    @Test
    fun radarFramesReturnImages() = runBlocking {
        val radar = RadarService(http)
        val now = Instant.now()
        val past = radar.pastFrames(RadarSource.NOAA_MRMS, now)
        val iem = radar.pastFrames(RadarSource.IEM_NEXRAD, now)
        val future = radar.futureFrames(now, 120)
        println("NOAA frames: ${past.map { it.time }}")
        println("HRRR frames: ${future.map { it.time }}")
        assertTrue(past.size >= 5, "NOAA frames: ${past.size}")
        assertTrue(future.size >= 6, "HRRR frames: ${future.size}")
        // One z=4 tile over the central US for each source must come back as a PNG.
        val bbox = "-12523442.714243278,2504688.542848654,-10018754.171394622,5009377.085697312"
        val urls = listOf(
            past.last().tileUrl.replace("{bbox-epsg-3857}", bbox),
            iem.last().tileUrl.replace("{z}/{x}/{y}", "4/3/6"),
            future.first().tileUrl.replace("{z}/{x}/{y}", "4/3/6"),
        )
        for (url in urls) {
            val response = http.fetch(Request.Builder().url(url).build())
            assertEquals(200, response.code, url)
            assertTrue(response.body.take(8).contains("PNG"), "not a PNG: $url")
        }
    }

    @Test
    fun outlooksFromLiveNoaaHistory() = runBlocking {
        val repo = WeatherRepository(
            NwsClient(http, { "SuperAwesomeMagicalWeather/test (integration tests)" }),
            OwmClient(http),
            WeeWxClient(HttpClients.localServerClients(http)),
            cache,
            acis = app.northvane.core.history.AcisClient(http),
        )
        // Portland, ME: the user's target region.
        val snapshot = repo.fetch(SavedLocation("pwm", "Portland", 43.6591, -70.2568), SourceConfig(), FetchPlan.FULL)
        val history = assertNotNull(snapshot.history, "ACIS history: ${snapshot.issues}")
        println("history: ${history.days.size} days, elevation ${history.elevationM} m, snow ${history.snowDepthMm} mm at ${history.snowDepthStation}")
        assertTrue(history.days.size >= 60)
        println("mud: " + snapshot.daily.map { "${it.date}=${it.mud?.level}" })
        println("foliage: ${snapshot.foliage?.today} peak ${snapshot.foliage?.estimatedPeak}")
        println("hiking: " + snapshot.daily.map { d -> "${d.date}=${d.activities.first { it.activity == app.northvane.core.model.Activity.HIKING }.score}" })
        assertTrue(snapshot.daily.all { it.mud != null && it.activities.isNotEmpty() })
    }

    @Test
    fun satelliteImageryTile() = runBlocking {
        val bbox = "-8237642,5009377,-7514065,5732953"
        for (imagery in listOf(app.northvane.core.radar.SatelliteImagery.VISIBLE, app.northvane.core.radar.SatelliteImagery.INFRARED)) {
            val url = app.northvane.core.radar.SatelliteImagery.tileUrl(imagery).replace("{bbox-epsg-3857}", bbox)
            val bytes = http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                assertEquals(200, r.code, url)
                r.body.bytes()
            }
            assertTrue(bytes.size > 1000 && bytes[1] == 'P'.code.toByte(), "PNG for $imagery")
        }
    }

    @Test
    fun airQualityUvAndTrafficLive() = runBlocking {
        val now = Instant.now()
        val zone = java.time.ZoneId.of("America/New_York")
        val uv = app.northvane.core.air.EpaUvClient(http).forecast("04101", zone, now)
        println("EPA UV: max ${uv.todayMax} hours ${uv.hourly.size}")
        assertTrue(uv.hourly.isNotEmpty())
        val (air, modelUv) = app.northvane.core.air.OpenMeteoAirClient(http).fetch(app.northvane.core.model.GeoPoint(43.66, -70.26), now)
        println("Open-Meteo AQI ${air.current?.aqi} ${air.current?.pollutant}; days ${air.days.size}; UV max ${modelUv.todayMax}")
        assertNotNull(air.current)
        val client = app.northvane.core.traffic.TrafficClient(http)
        val traffic = app.northvane.core.traffic.TrafficService(client)
            .load("ME", app.northvane.core.model.GeoPoint(43.6591, -70.2568), 40.0, null, { client.registry() }, now)
        println("Traffic: ${traffic.events.size} events from ${traffic.sources}; issues ${traffic.issues}")
        traffic.events.take(5).forEach { println("  ${it.type} ${it.road} ${it.direction} ${"%.1f".format(it.distanceKm)} km") }
        assertTrue(traffic.sources.isNotEmpty())
    }

    @Test
    fun environmentCanadaLive() = runBlocking {
        val client = app.northvane.core.eccc.EcccClient(http)
        val site = client.nearestSite(app.northvane.core.model.GeoPoint(45.96, -66.64))
        val f = client.forecast(site, Instant.now())
        println("ECCC $site ${f.placeName}: now ${f.current?.temperatureC}°C ${f.current?.description}; days ${f.daily.map { "${it.name} ${it.highC}/${it.lowC}" }}; alerts ${f.alerts.map { it.event }}")
        assertEquals("nb-29", site)
        assertTrue(f.daily.size >= 5 && f.hourly.size >= 20)
    }
}
