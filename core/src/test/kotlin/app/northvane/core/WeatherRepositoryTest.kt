package app.northvane.core

import app.northvane.core.model.DataSource
import app.northvane.core.model.SavedLocation
import app.northvane.core.model.WeatherCondition
import app.northvane.core.net.HttpClients
import app.northvane.core.nws.NwsClient
import app.northvane.core.owm.OwmClient
import app.northvane.core.repo.CachedNwsPoint
import app.northvane.core.history.AcisClient
import app.northvane.core.pollen.PollenClient
import app.northvane.core.pollen.PollenPricing
import app.northvane.core.model.DetailSource
import app.northvane.core.model.OutageLevel
import app.northvane.core.model.Section
import app.northvane.core.repo.FetchPlan
import app.northvane.core.repo.NwsPointCache
import app.northvane.core.repo.SourceConfig
import app.northvane.core.repo.WeatherRepository
import app.northvane.core.repo.WeatherUnavailableException
import app.northvane.core.weewx.WeeWxAuth
import app.northvane.core.weewx.WeeWxClient
import app.northvane.core.weewx.WeeWxSecret
import app.northvane.core.weewx.WeeWxStation
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WeatherRepositoryTest {

    private val kansas = SavedLocation("home", "Home", 39.74561, -97.08923)
    private val london = SavedLocation("ldn", "London", 51.5085, -0.1257)

    private class MemoryCache : NwsPointCache {
        val map = mutableMapOf<String, CachedNwsPoint>()
        override suspend fun get(key: String) = map[key]
        override suspend fun put(key: String, value: CachedNwsPoint) { map[key] = value }
    }

    private fun FakeNetwork.nwsHappyPath() {
        on("/points/39.7456,-97.0892", body = Nws.point)
        on("/gridpoints/TOP/32,81/stations", body = Nws.stations)
        on("/stations/KMYZ/observations/latest", body = Nws.observation())
        on("/gridpoints/TOP/32,81/forecast/hourly", body = Nws.hourly)
        on("/gridpoints/TOP/32,81/forecast", body = Nws.daily)
        on("/alerts/active?point=39.7456,-97.0892", body = Nws.alerts)
        on("/gridpoints/TOP/32,81", body = Nws.grid)
    }

    /** A station that stopped reporting days ago must not be shown as "current". */
    @Test
    fun staleObservationLosesToTheEstimate() {
        val now = Instant.parse("2026-11-06T18:00:00Z")
        val hour = app.northvane.core.model.HourlyForecast(
            now, 10.0, app.northvane.core.model.WeatherCondition.CLEAR, true, "Sunny", 0, 5.0, "W", humidityPercent = 50.0,
        )
        fun obs(hoursOld: Long) = app.northvane.core.model.CurrentConditions(
            now.minus(java.time.Duration.ofHours(hoursOld)), app.northvane.core.model.DataSource.NWS, temperatureC = -3.0,
        )
        val r = repo(FakeNetwork())
        assertEquals(app.northvane.core.model.DataSource.NWS_FORECAST_ESTIMATE, r.chooseCurrent(null, obs(48), null, listOf(hour), now)?.source)
        assertEquals(-3.0, r.chooseCurrent(null, obs(4), null, listOf(hour), now)?.temperatureC)
        assertEquals(-3.0, r.chooseCurrent(null, obs(1), null, listOf(hour), now)?.temperatureC)
    }

    private fun repo(fake: FakeNetwork, cache: NwsPointCache = MemoryCache(), extras: Boolean = false): WeatherRepository {
        val base = HttpClients.create("test-agent", cacheDir = null).newBuilder().addInterceptor(fake).build()
        return WeatherRepository(
            nws = NwsClient(base, { "SuperAwesomeMagicalWeather/test (tests)" }),
            owm = OwmClient(base),
            weewx = WeeWxClient(HttpClients.localServerClients(base)),
            pointCache = cache,
            clock = { NOW },
            acis = AcisClient(base).takeIf { extras },
            pollen = PollenClient(base).takeIf { extras },
            airNow = app.northvane.core.air.AirNowClient(base).takeIf { extras },
            epaUv = app.northvane.core.air.EpaUvClient(base).takeIf { extras },
            openMeteoAir = app.northvane.core.air.OpenMeteoAirClient(base).takeIf { extras },
        )
    }

    private fun resource(name: String) = requireNotNull(javaClass.getResource(name)).readText()

    @Test
    fun historyAndPollenAreFetchedAndOutlooksAnnotated() = runTest {
        val fake = FakeNetwork().apply {
            nwsHappyPath()
            on("data.rcc-acis.org/GridData", body = resource("/live/acis_grid_portland.json"))
            on("data.rcc-acis.org/MultiStnData", body = resource("/live/acis_snwd_whites.json"))
            on("pollen.googleapis.com/v1/forecast:lookup", body = resource("/pollen/google_forecast.json"))
            airRoutes()
        }
        val key = "AIzaSyA-test-key-0123456789abcdefghijk"
        val snapshot = repo(fake, extras = true).fetch(kansas, SourceConfig(pollenApiKey = key), FetchPlan.FULL)
        assertEquals(76, snapshot.history!!.days.size)
        assertEquals(2, snapshot.pollen!!.days.size)
        assertTrue(snapshot.daily.all { it.activities.isNotEmpty() && it.mud != null })
        assertTrue(snapshot.issues.isEmpty(), "${snapshot.issues}")
        val pollenRequest = fake.requests.single { it.url.host == "pollen.googleapis.com" }
        assertEquals(key, pollenRequest.header("X-Goog-Api-Key"))
        assertFalse(pollenRequest.url.toString().contains(key), "key must not be in the URL")
        assertEquals("39.75", pollenRequest.url.queryParameter("location.latitude"))
        assertTrue(fake.requests.single { it.url.encodedPath == "/GridData" }.method == "POST")
    }

    @Test
    fun pollenNeedsKeyAndPlan() = runTest {
        val fake = FakeNetwork().apply { nwsHappyPath() }
        repo(fake, extras = true).fetch(kansas, SourceConfig(), FetchPlan.FULL)
        assertTrue(fake.requests.none { it.url.host == "pollen.googleapis.com" })
        val fake2 = FakeNetwork().apply { nwsHappyPath() }
        repo(fake2, extras = true).fetch(kansas, SourceConfig(pollenApiKey = "AIzaSyA-test-key-0123456789abcdefghijk"), FetchPlan(Section.entries.toSet() - Section.POLLEN - Section.HISTORY))
        assertTrue(fake2.requests.none { it.url.host == "pollen.googleapis.com" || it.url.host == "data.rcc-acis.org" })
    }

    @Test
    fun pollenKeyErrorsAreExplained() = runTest {
        val fake = FakeNetwork().apply {
            nwsHappyPath()
            on("pollen.googleapis.com", code = 400, body = """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT","details":[{"reason":"API_KEY_INVALID"}]}}""")
        }
        val snapshot = repo(fake, extras = true).fetch(kansas, SourceConfig(pollenApiKey = "AIzaSyA-test-key-0123456789abcdefghijk"), FetchPlan.FULL)
        assertNull(snapshot.pollen)
        assertTrue(snapshot.issues.any { it.source == DataSource.GOOGLE_POLLEN && "rejected" in it.message }, "${snapshot.issues}")
        // ACIS 404s here (no route): reported, but forecasts still work.
        assertTrue(snapshot.daily.isNotEmpty())
    }

    private fun FakeNetwork.airRoutes() {
        on("www.airnowapi.org/aq/observation", body = resource("/air/airnow_current.json"))
        on("www.airnowapi.org/aq/forecast", body = resource("/air/airnow_forecast.json"))
        on("data.epa.gov/efservice/getEnvirofactsUVHOURLY/ZIP/66952", body = resource("/air/epa_uv_hourly.json"))
        on("data.epa.gov/efservice/getEnvirofactsUVDAILY/ZIP/66952", body = resource("/air/epa_uv_daily.json"))
        on("air-quality-api.open-meteo.com", body = resource("/air/openmeteo_aq.json"))
    }

    @Test
    fun airNowAndEpaUvWithKeyAndZip() = runTest {
        val fake = FakeNetwork().apply { nwsHappyPath(); airRoutes() }
        val key = "0123ABCD-4567-89EF-0123-456789ABCDEF"
        val snapshot = repo(fake, extras = true).fetch(kansas.copy(postalCode = "66952"), SourceConfig(airNowApiKey = key), FetchPlan.FULL)
        assertEquals(app.northvane.core.model.AirSource.AIRNOW, snapshot.airQuality!!.source)
        assertEquals(54, snapshot.airQuality!!.current!!.aqi)
        assertEquals("EPA UV Index", snapshot.uv!!.source)
        assertTrue(fake.requests.none { it.url.host == "air-quality-api.open-meteo.com" }, "no model needed")
        val airNow = fake.requests.first { it.url.host == "www.airnowapi.org" }
        assertEquals("no-store", airNow.header("Cache-Control"))
        assertEquals("39.7456", airNow.url.queryParameter("latitude"))
    }

    @Test
    fun withoutKeyOrZipOpenMeteoFillsIn() = runTest {
        val fake = FakeNetwork().apply { nwsHappyPath(); airRoutes() }
        val snapshot = repo(fake, extras = true).fetch(kansas, SourceConfig(), FetchPlan.FULL)
        assertEquals(app.northvane.core.model.AirSource.OPEN_METEO, snapshot.airQuality!!.source)
        assertEquals("Open-Meteo (CAMS)", snapshot.uv!!.source)
        assertTrue(fake.requests.none { it.url.host == "www.airnowapi.org" || it.url.host == "data.epa.gov" })
    }

    @Test
    fun badAirNowKeyIsReportedAndModelUsed() = runTest {
        val fake = FakeNetwork().apply {
            nwsHappyPath(); airRoutes()
            on("www.airnowapi.org/aq/observation", code = 401, body = """{"WebServiceError":[{"Message":"Invalid API key"}]}""")
        }
        val snapshot = repo(fake, extras = true).fetch(kansas, SourceConfig(airNowApiKey = "0123ABCD-4567-89EF-0123-456789ABCDEF"), FetchPlan.FULL)
        assertEquals(app.northvane.core.model.AirSource.OPEN_METEO, snapshot.airQuality!!.source)
        assertTrue(snapshot.issues.any { it.source == DataSource.AIRNOW && "rejected" in it.message }, "${snapshot.issues}")
    }

    @Test
    fun pollenPricing() {
        assertEquals(124, PollenPricing.maxMonthlyRequests(1))
        assertEquals(0.0, PollenPricing.maxMonthlyCostUsd(5))
        assertEquals(12.0, PollenPricing.maxMonthlyCostUsd(50), 1e-9)
    }

    @Test
    fun parsesPollenForecast() {
        val days = PollenClient.parse(resource("/pollen/google_forecast.json"))
        val first = days.first()
        assertEquals(java.time.LocalDate.parse("2027-05-12"), first.date)
        assertEquals("TREE", first.worst!!.code)
        assertEquals(4, first.worst!!.index)
        assertNull(first.types.single { it.code == "WEED" }.index)
        assertFalse(first.types.single { it.code == "WEED" }.inSeason)
        assertEquals(listOf("Birch", "Oak", "Grasses"), first.topPlants.map { it.name })
        assertTrue(first.tips.isNotEmpty())
        assertTrue(PollenClient.looksLikeApiKey("AIzaSyA-test-key-0123456789abcdefghijk"))
        assertFalse(PollenClient.looksLikeApiKey("short"))
    }

    @Test
    fun nwsOnly() = runTest {
        val fake = FakeNetwork().apply { nwsHappyPath() }
        val snapshot = repo(fake).fetch(kansas, SourceConfig(), FetchPlan.FULL)

        val current = assertNotNull(snapshot.current)
        assertEquals(DataSource.NWS, current.source)
        assertEquals(12.2, current.temperatureC!!, 1e-9)
        assertEquals("Linn, KS", snapshot.placeName)
        assertEquals("America/Chicago", snapshot.timeZoneId)
        assertEquals(DataSource.NWS, snapshot.forecastSource)
        assertEquals(3, snapshot.hourly.size)
        assertEquals(2, snapshot.daily.size)
        assertEquals(3, snapshot.alerts.size)
        assertTrue(snapshot.issues.isEmpty(), "issues: ${snapshot.issues}")
        assertNotNull(snapshot.sunrise)
        assertTrue(fake.requests.all { it.url.host == "api.weather.gov" })
        assertTrue(fake.requests.all { it.header("User-Agent") == "SuperAwesomeMagicalWeather/test (tests)" })
        assertTrue(fake.requests.none { it.url.host.contains("openweathermap") })
    }

    @Test
    fun pointMetadataIsCached() = runTest {
        val cache = MemoryCache()
        val fake = FakeNetwork().apply { nwsHappyPath() }
        val repository = repo(fake, cache)
        repository.fetch(kansas, SourceConfig(), FetchPlan.FULL)
        fake.requests.clear()
        repository.fetch(kansas, SourceConfig(), FetchPlan.FULL)
        assertTrue(fake.requests.none { it.url.encodedPath.startsWith("/points/") })
        assertTrue(fake.requests.none { it.url.encodedPath.endsWith("/stations") })
    }

    @Test
    fun liteModeSkipsHourly() = runTest {
        val fake = FakeNetwork().apply { nwsHappyPath() }
        val plan = FetchPlan(
            sections = setOf(Section.CURRENT_CONDITIONS, Section.ALERTS, Section.DAILY_FORECAST),
            observationStations = 1,
            limited = true,
        )
        val snapshot = repo(fake).fetch(kansas, SourceConfig(), plan)
        assertTrue(snapshot.liteMode)
        assertTrue(snapshot.hourly.isEmpty())
        assertEquals(2, snapshot.daily.size)
        assertTrue(fake.requests.none { it.url.encodedPath.endsWith("/forecast/hourly") })
        assertTrue(fake.requests.none { it.url.encodedPath == "/gridpoints/TOP/32,81" })
        assertEquals(
            setOf(Section.HOURLY_FORECAST, Section.FORECAST_DETAILS, Section.LOCAL_STATION, Section.OWM_FALLBACK, Section.HISTORY, Section.POLLEN, Section.AIR_QUALITY, Section.AURORA, Section.SMOKE, Section.WATER),
            snapshot.skippedSections,
        )
        // Without grid data, numbers come from the forecast text (the fixture states winds).
        assertEquals(DetailSource.TEXT, snapshot.daily.first().detailSource)
    }

    @Test
    fun everySectionOffStillSucceeds() = runTest {
        val fake = FakeNetwork().apply { nwsHappyPath() }
        val snapshot = repo(fake).fetch(kansas, SourceConfig(), FetchPlan(sections = emptySet(), limited = true))
        assertTrue(fake.requests.isEmpty(), "nothing downloaded")
        assertEquals(Section.entries.toSet(), snapshot.skippedSections)
    }

    @Test
    fun gridDataEnrichesForecastAndDrivesOutageRisk() = runTest {
        val fake = FakeNetwork().apply {
            nwsHappyPath()
        }
        val snapshot = repo(fake).fetch(kansas, SourceConfig(), FetchPlan.FULL)
        val today = snapshot.daily.first()
        assertEquals(DetailSource.GRID, today.detailSource)
        assertEquals(96.0, today.gustMaxKph!!, 1e-9)
        assertEquals(12.0, today.precipitationMm!!, 1e-9)
        assertEquals(OutageLevel.VERY_HIGH, today.outageRisk!!.level, "60 mph gusts with leaves on")
        val hour = snapshot.hourly.first()
        assertEquals(96.0, hour.windGustKph!!, 1e-9)
        assertEquals(2.0, hour.precipitationMm!!, 1e-9) // 12 mm over 6 h
        assertEquals(90, hour.skyCoverPercent)
    }

    @Test
    fun weewxOverridesCurrentConditions() = runTest {
        val fake = FakeNetwork().apply {
            nwsHappyPath()
            on("https://wx.example.com/northvane.json", body = WeeWx.northvaneJson)
        }
        val station = WeeWxStation(
            id = "s1", name = "Backyard", url = "https://wx.example.com/northvane.json",
            auth = WeeWxAuth.BASIC, username = "me", latitude = 39.78, longitude = -97.05, radiusKm = 8.0,
        )
        val snapshot = repo(fake).fetch(kansas, SourceConfig(station, WeeWxSecret("hunter2")), FetchPlan.FULL)

        val current = assertNotNull(snapshot.current)
        assertEquals(DataSource.WEEWX, current.source)
        assertEquals(10.5, current.temperatureC!!, 1e-9)
        assertEquals("Partly Cloudy", current.description, "sky condition filled from NWS")
        assertEquals(WeatherCondition.PARTLY_CLOUDY, current.condition)
        assertEquals(16.09, current.visibilityKm!!, 1e-6, "visibility filled from NWS")
        assertEquals(DataSource.NWS, current.supplementedFrom)
        val weewxRequest = fake.requests.single { it.url.host == "wx.example.com" }
        assertEquals("Basic bWU6aHVudGVyMg==", weewxRequest.header("Authorization"))
    }

    @Test
    fun weewxCoversLocationsWithinRadius() {
        val station = WeeWxStation(id = "s1", name = "Backyard", url = "https://wx.example.com/x.json", latitude = 39.78, longitude = -97.05)
        assertTrue(station.covers(kansas)) // ~5 km away
        assertFalse(station.copy(radiusKm = 2.0).covers(kansas))
        assertFalse(station.covers(london))
        // Legacy pairing still works until the station gets a position.
        val legacy = WeeWxStation(id = "s2", name = "Old", url = "https://wx.example.com/y.json", linkedLocationId = "home")
        assertTrue(legacy.covers(kansas))
        assertFalse(legacy.covers(london))
        val near = station.copy(id = "near", latitude = 39.746, longitude = -97.089, radiusKm = 25.0)
        assertEquals("near", WeeWxStation.forLocation(listOf(station, near), kansas)!!.id)
        assertNull(WeeWxStation.forLocation(listOf(station.copy(enabled = false)), kansas))
    }

    @Test
    fun weewxForOtherLocationIsIgnored() = runTest {
        val fake = FakeNetwork().apply { nwsHappyPath() }
        val station = WeeWxStation(id = "s1", name = "Cabin", url = "https://wx.example.com/x.json", latitude = 44.27, longitude = -71.3)
        val snapshot = repo(fake).fetch(kansas, SourceConfig(station), FetchPlan.FULL)
        assertEquals(DataSource.NWS, snapshot.current!!.source)
        assertTrue(fake.requests.none { it.url.host == "wx.example.com" })
    }

    @Test
    fun staleWeewxFallsBackToNws() = runTest {
        val old = WeeWx.northvaneJson.replace("\"time\": ${NOW.epochSecond - 120}", "\"time\": ${NOW.epochSecond - 7200}")
        val fake = FakeNetwork().apply {
            nwsHappyPath()
            on("https://wx.example.com/northvane.json", body = old)
        }
        val station = WeeWxStation(id = "s1", name = "Backyard", url = "https://wx.example.com/northvane.json", linkedLocationId = "home")
        val snapshot = repo(fake).fetch(kansas, SourceConfig(station), FetchPlan.FULL)
        assertEquals(DataSource.NWS, snapshot.current!!.source)
        assertTrue(snapshot.issues.any { it.source == DataSource.WEEWX && "minutes old" in it.message })
    }

    @Test
    fun cleartextWeewxWithPasswordIsRefused() = runTest {
        val fake = FakeNetwork().apply {
            nwsHappyPath()
            on("http://192.168.1.50/northvane.json", body = WeeWx.northvaneJson)
        }
        val station = WeeWxStation(
            id = "s1", name = "Backyard", url = "http://192.168.1.50/northvane.json",
            auth = WeeWxAuth.BASIC, username = "me", linkedLocationId = "home",
        )
        val snapshot = repo(fake).fetch(kansas, SourceConfig(station, WeeWxSecret("pw")), FetchPlan.FULL)
        assertEquals(DataSource.NWS, snapshot.current!!.source)
        assertTrue(fake.requests.none { it.url.host == "192.168.1.50" }, "nothing was sent")
        assertTrue(snapshot.issues.any { it.source == DataSource.WEEWX && "HTTPS" in it.message })
    }

    @Test
    fun cleartextWeewxOnLanWithoutAuthWorks() = runTest {
        val fake = FakeNetwork().apply {
            nwsHappyPath()
            on("http://192.168.1.50/northvane.json", body = WeeWx.northvaneJson)
        }
        val station = WeeWxStation(id = "s1", name = "Backyard", url = "http://192.168.1.50/northvane.json", linkedLocationId = "home")
        val snapshot = repo(fake).fetch(kansas, SourceConfig(station), FetchPlan.FULL)
        assertEquals(DataSource.WEEWX, snapshot.current!!.source)
    }

    @Test
    fun blockedLocalStationIsSkippedWithReason() = runTest {
        val fake = FakeNetwork().apply {
            nwsHappyPath()
            on("http://192.168.1.50/northvane.json", body = WeeWx.northvaneJson)
        }
        val station = WeeWxStation(id = "s1", name = "Backyard", url = "http://192.168.1.50/northvane.json", linkedLocationId = "home")
        val config = SourceConfig(station, weewxBlockedReason = "Allow Nearby devices")
        val snapshot = repo(fake).fetch(kansas, config, FetchPlan.FULL)
        assertEquals(DataSource.NWS, snapshot.current!!.source)
        assertTrue(fake.requests.none { it.url.host == "192.168.1.50" })
        assertTrue(snapshot.issues.any { it.source == DataSource.WEEWX && it.message == "Allow Nearby devices" })
    }

    @Test
    fun staleNwsObservationUsesNextStation() = runTest {
        val fake = FakeNetwork().apply {
            nwsHappyPath()
            on("/stations/KMYZ/observations/latest", body = Nws.observation(tempC = "null"))
            on("/stations/KCNK/observations/latest", body = Nws.observation(tempC = "8.0"))
        }
        val snapshot = repo(fake).fetch(kansas, SourceConfig(), FetchPlan.FULL)
        assertEquals(8.0, snapshot.current!!.temperatureC!!, 1e-9)
    }

    @Test
    fun observationsDownFallsBackToForecastEstimate() = runTest {
        val fake = FakeNetwork().apply {
            nwsHappyPath()
            on("/stations/KMYZ/observations/latest", code = 503, body = "{}")
        }
        val snapshot = repo(fake).fetch(kansas, SourceConfig(), FetchPlan.FULL)
        val current = snapshot.current!!
        assertEquals(DataSource.NWS_FORECAST_ESTIMATE, current.source)
        assertEquals(12.22, current.temperatureC!!, 0.01)
    }

    @Test
    fun owmFillsGapsOnlyWhenConfigured() = runTest {
        val fake = FakeNetwork().apply {
            nwsHappyPath()
            on("/stations/KMYZ/observations/latest", code = 503, body = "{}")
            on("/gridpoints/TOP/32,81/forecast", code = 500, body = "{}")
            on("/gridpoints/TOP/32,81/forecast/hourly", code = 500, body = "{}")
            on("api.openweathermap.org/data/2.5/weather", body = Owm.current)
            on("api.openweathermap.org/data/2.5/forecast", body = Owm.forecast)
        }
        val snapshot = repo(fake).fetch(kansas, SourceConfig(owmApiKey = "0123456789abcdef0123456789abcdef"), FetchPlan.FULL)
        assertEquals(DataSource.OWM, snapshot.current!!.source)
        assertEquals(DataSource.OWM, snapshot.forecastSource)
        assertEquals(3, snapshot.alerts.size, "alerts still come from NWS")
        val owmRequest = fake.requests.first { it.url.host == "api.openweathermap.org" }
        assertEquals("39.7456", owmRequest.url.queryParameter("lat"))
        assertEquals("no-store", owmRequest.header("Cache-Control"))
    }

    @Test
    fun outsideUsUsesOwm() = runTest {
        val fake = FakeNetwork().apply {
            on("/points/51.5085,-0.1257", code = 404, body = "{}")
            on("api.openweathermap.org/data/2.5/weather", body = Owm.current)
            on("api.openweathermap.org/data/2.5/forecast", body = Owm.forecast)
        }
        val snapshot = repo(fake).fetch(london, SourceConfig(owmApiKey = "0123456789abcdef0123456789abcdef"), FetchPlan.FULL)
        assertEquals(DataSource.OWM, snapshot.current!!.source)
        assertEquals("London", snapshot.placeName)
        assertFalse(snapshot.alertsAvailable)
        assertNull(snapshot.radarStation)
    }

    @Test
    fun outsideUsWithoutKeyExplainsWhatToDo() = runTest {
        val fake = FakeNetwork().apply { on("/points/", code = 404, body = "{}") }
        val error = assertFailsWith<WeatherUnavailableException> {
            repo(fake).fetch(london, SourceConfig(), FetchPlan.FULL)
        }
        assertTrue(error.issues.any { "OpenWeatherMap API key" in it.message }, "${error.issues}")
    }
}
