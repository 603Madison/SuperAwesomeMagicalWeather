package app.northvane.core

import app.northvane.core.model.GeoPoint
import app.northvane.core.net.HttpClients
import app.northvane.core.nowcast.NowcastClient
import app.northvane.core.outlook.PrecipKind
import app.northvane.core.outlook.RainSoonDetector
import app.northvane.core.radar.Grib2
import app.northvane.core.radar.HrrrSmoke
import app.northvane.core.radar.SmokeLevel
import app.northvane.core.water.WaterClient
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WaterAndSmokeTest {
    private fun resource(name: String) = requireNotNull(javaClass.getResource(name)) { "missing $name" }.readText()
    private val portland = GeoPoint(43.66, -70.26)
    private val now = Instant.parse("2026-11-06T20:00:00Z")

    private fun client(fake: FakeNetwork) = HttpClients.create("test-agent", cacheDir = null).newBuilder().addInterceptor(fake).build()

    @Test
    fun picksTheNearestActiveRiverGauge() {
        val gauge = assertNotNull(WaterClient.parseLatest(resource("/water/usgs_latest.json"), portland, now))
        // The Presumpscot at Westbrook: active and closest. Gauges retired decades ago are skipped.
        assertEquals("USGS-01064140", gauge.siteId)
        assertEquals(448.0, gauge.dischargeCfs)
        assertEquals(2.75, gauge.gageHeightFt)
        assertTrue(gauge.distanceKm < 10, "${gauge.distanceKm} km")
        assertEquals("Royal River at Yarmouth, Maine", WaterClient.parseSiteName(resource("/water/usgs_site.json")))
        assertEquals(0.59, WaterClient.parseChange(resource("/water/usgs_series.json"))!!, 0.001)
        // Nothing within reach far inland.
        assertNull(WaterClient.parseLatest(resource("/water/usgs_latest.json"), GeoPoint(44.9, -69.0), now))
    }

    @Test
    fun readsTides() {
        val events = WaterClient.parsePredictions(resource("/water/tide_predictions.json"))
        assertEquals(7, events.size)
        assertEquals(Instant.parse("2026-11-07T02:04:00Z"), events.first().time)
        assertTrue(events.first().high)
        assertEquals(12.0, WaterClient.parseLatestValue(resource("/water/tide_water_temp.json")))
    }

    @Test
    fun buildsTheWaterReport() = runTest {
        val fake = FakeNetwork().apply {
            on("latest-continuous", body = resource("/water/usgs_latest.json"))
            on("monitoring-locations", body = resource("/water/usgs_site.json"))
            on("collections/continuous/items", body = resource("/water/usgs_series.json"))
            on("tidepredstations.json", body = resource("/water/tide_stations.json"))
            on("product=predictions", body = resource("/water/tide_predictions.json"))
            on("product=water_temperature", body = resource("/water/tide_water_temp.json"))
        }
        val report = WaterClient(client(fake)).report(portland, now)
        val river = assertNotNull(report.river)
        assertTrue(river.rising)
        val tide = assertNotNull(report.tide)
        assertEquals("8418150", tide.stationId)
        assertEquals("Portland", tide.name)
        assertEquals(12.0, tide.waterTempC)
        assertEquals(12.0, report.waterTempC)
    }

    @Test
    fun readsTheFifteenMinuteNowcast() {
        val steps = NowcastClient.parse(resource("/nowcast/minutely15.json"))
        assertEquals(8, steps.size)
        val soon = assertNotNull(RainSoonDetector.detect(steps, steps.first().time))
        assertEquals(steps[2].time, soon.start)
        assertEquals(PrecipKind.RAIN, soon.kind)
    }

    @Test
    fun smokeLevels() {
        assertEquals(SmokeLevel.NONE, SmokeLevel.of(2.0))
        assertEquals(SmokeLevel.MODERATE, SmokeLevel.of(20.0))
        assertEquals(SmokeLevel.SENSITIVE, SmokeLevel.of(40.0))
        assertEquals(SmokeLevel.HAZARDOUS, SmokeLevel.of(300.0))
    }

    /** The smoke field is read the same way as the radar one: the HRRR test field stands in for it. */
    @Test
    fun readsSmokeAtThePlace() = runTest {
        val grib = requireNotNull(javaClass.getResource("/hrrr/refd1km_t13z_f015.grib2")).readBytes()
        assertNotNull(HrrrSmoke.sample(Grib2.decode(grib), portland.latitude, portland.longitude))
        val index = "1:0:d=x:MASSDEN:8 m above ground:N hour fcst:\n2:${grib.size}:d=x:COLMD:entire atmosphere:N hour fcst:"
        val fake = FakeNetwork().apply {
            onBytes(".grib2", grib)
            on(".grib2.idx", body = index)
        }
        val outlook = HrrrSmoke(client(fake), base = "https://example.test").outlook(portland, now)
        assertEquals(Instant.parse("2026-11-06T18:00:00Z"), outlook.run)
        assertEquals(4, outlook.hours.size)
        assertEquals(now.plusSeconds(3 * 3600), outlook.hours.first().time)
        assertTrue(outlook.hours.all { it.microgramsPerM3 >= 0 })
    }

    /** Live: real HRRR smoke, USGS and NOAA tide data for Portland, ME. */
    @Test
    fun liveSmokeAndWater() = kotlinx.coroutines.runBlocking {
        org.junit.Assume.assumeTrue(System.getProperty("northvane.live") == "true")
        val http = HttpClients.create("SuperAwesomeMagicalWeather-test", cacheDir = null)
        val started = System.nanoTime()
        val smoke = HrrrSmoke(http).outlook(portland, Instant.now())
        println("Smoke run ${smoke.run}: " + smoke.hours.joinToString { "${it.time} %.1f µg/m³".format(it.microgramsPerM3) } +
            " in ${(System.nanoTime() - started) / 1_000_000} ms")
        assertEquals(4, smoke.hours.size)
        assertTrue(smoke.hours.all { it.microgramsPerM3 in 0.0..2000.0 })
        val water = WaterClient(http).report(portland, Instant.now())
        println("River: ${water.river}\nTide: ${water.tide}")
        assertNotNull(water.river ?: water.tide)
        Unit
    }
}
