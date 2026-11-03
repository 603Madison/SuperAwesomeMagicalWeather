package app.northvane.core

import app.northvane.core.ai.AiClient
import app.northvane.core.ai.AiConfig
import app.northvane.core.ai.AiProvider
import app.northvane.core.ai.WeatherBrief
import app.northvane.core.eccc.EcccClient
import app.northvane.core.model.AlertCategory
import app.northvane.core.model.AlertSeverity
import app.northvane.core.model.DataSource
import app.northvane.core.model.SavedLocation
import app.northvane.core.model.Section
import app.northvane.core.model.UnitSystem
import app.northvane.core.model.WeatherCondition
import app.northvane.core.net.HttpClients
import app.northvane.core.net.InsecureConfigurationException
import app.northvane.core.nws.NwsClient
import app.northvane.core.owm.OwmClient
import app.northvane.core.repo.CachedNwsPoint
import app.northvane.core.repo.FetchPlan
import app.northvane.core.repo.NwsPointCache
import app.northvane.core.repo.SourceConfig
import app.northvane.core.repo.WeatherRepository
import app.northvane.core.weewx.WeeWxClient
import kotlinx.coroutines.test.runTest
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CanadaAndAiTest {
    private fun resource(name: String) = requireNotNull(javaClass.getResource(name)) { "missing $name" }.readText()
    private val captured = Instant.parse("2026-10-29T20:10:00Z")

    // --- Environment Canada ---

    @Test
    fun parsesFrederictonCityPage() {
        val f = EcccClient.parse(resource("/eccc/site_nb29.json"), captured)
        assertEquals("nb-29", f.siteId)
        assertEquals("Fredericton, NB", f.placeName)
        assertEquals("America/Moncton", f.zone.id)
        val c = assertNotNull(f.current)
        assertEquals(DataSource.ECCC, c.source)
        assertNotNull(c.temperatureC)
        assertEquals(47.0, c.humidityPercent)
        // A reported wind chill is ignored on a mild day.
        assertTrue(kotlin.math.abs(c.feelsLikeC!! - c.temperatureC!!) < 3, "feels ${c.feelsLikeC} vs ${c.temperatureC}")
        assertEquals(null, EcccClient.feelsLike(16.5, -5.0, null))
        assertEquals(-12.0, EcccClient.feelsLike(-3.0, -12.0, null))
        assertEquals(24, f.hourly.size)
        assertTrue(f.hourly.all { it.temperatureC != null && it.precipitationChance != null })
        // "Tonight" first, then Friday + Friday night pairs.
        assertEquals("Tonight", f.daily.first().name)
        assertEquals(LocalDate.parse("2026-10-29"), f.daily.first().date)
        assertEquals(9.0, f.daily.first().lowC)
        val friday = f.daily[1]
        assertEquals(LocalDate.parse("2026-10-30"), friday.date)
        assertEquals(17.0, friday.highC)
        assertEquals(4.0, friday.lowC)
        assertEquals(WeatherCondition.SHOWERS, friday.condition)
        assertEquals(40, friday.precipitationChance)
        assertTrue(f.daily.size >= 6)
        assertNotNull(f.sunrise)
    }

    @Test
    fun parsesCanadianWarnings() {
        val f = EcccClient.parse(resource("/eccc/site_nt20.json"), captured)
        assertEquals(2, f.alerts.size)
        val wind = f.alerts.first { it.event == "Wind Warning" }
        assertEquals(AlertCategory.WARNING, wind.category)
        assertEquals(AlertSeverity.MODERATE, wind.severity)
        assertNotNull(wind.expires)
        assertTrue(f.alerts.any { it.event == "Storm Surge Warning" })
    }

    @Test
    fun nearestSiteAndIconCodes() {
        val sites = EcccClient.parseSites(resource("/eccc/sites_nb.json"))
        assertEquals(setOf("nb-29", "nb-11"), sites.map { it.first }.toSet())
        assertEquals(WeatherCondition.SNOW, EcccClient.condition(17))
        assertEquals(WeatherCondition.CLEAR, EcccClient.condition(30))
        assertTrue(EcccClient.mayBeInCanada(app.northvane.core.model.GeoPoint(45.96, -66.65)))
        assertFalse(EcccClient.mayBeInCanada(app.northvane.core.model.GeoPoint(51.5, -0.12)))
    }

    private class MemoryCache : NwsPointCache {
        val map = mutableMapOf<String, CachedNwsPoint>()
        override suspend fun get(key: String) = map[key]
        override suspend fun put(key: String, value: CachedNwsPoint) { map[key] = value }
    }

    @Test
    fun canadianPlacesUseEnvironmentCanada() = runTest {
        val fake = FakeNetwork().apply {
            on("api.weather.gov/points/", code = 404, body = """{"status":404}""")
            on("api.weather.gc.ca/collections/citypageweather-realtime/items?", body = resource("/eccc/sites_nb.json"))
            on("api.weather.gc.ca/collections/citypageweather-realtime/items/nb-29", body = resource("/eccc/site_nb29.json"))
        }
        val base = HttpClients.create("t", null).newBuilder().addInterceptor(fake).build()
        val repo = WeatherRepository(
            NwsClient(base, { "SuperAwesomeMagicalWeather/test" }), OwmClient(base), WeeWxClient(HttpClients.localServerClients(base)), MemoryCache(),
            clock = { captured }, eccc = EcccClient(base),
        )
        val snapshot = repo.fetch(SavedLocation("fr", "Fredericton", 45.96, -66.64), SourceConfig(), FetchPlan.FULL)
        assertEquals(DataSource.ECCC, snapshot.forecastSource)
        assertEquals(DataSource.ECCC, snapshot.current?.source)
        assertEquals("America/Moncton", snapshot.timeZoneId)
        assertEquals("Fredericton, NB", snapshot.placeName)
        assertTrue(snapshot.daily.isNotEmpty() && snapshot.hourly.isNotEmpty())
        assertTrue(snapshot.alertsAvailable)
        assertTrue(snapshot.issues.none { it.message.contains("OpenWeatherMap") }, "${snapshot.issues}")
        assertTrue(Section.DAILY_FORECAST in snapshot.sectionTimes && Section.ALERTS in snapshot.sectionTimes)
        // Background alert checks fall back to Environment Canada too.
        fake.on("api.weather.gov/alerts/active", code = 400, body = """{"title":"Bad Request"}""")
        assertTrue(repo.alerts(app.northvane.core.model.GeoPoint(45.96, -66.64)).isEmpty())
        assertTrue(fake.requests.last().url.encodedPath.endsWith("/items/nb-29"))
    }

    // --- AI summary ---

    @Test
    fun parsesCompletionsAndStripsReasoning() {
        val openAi = """{"id":"x","choices":[{"index":0,"message":{"role":"assistant","content":"<think>Let me look.</think>\n**Mild and dry** today with a breezy afternoon."}}]}"""
        assertEquals("Mild and dry today with a breezy afternoon.", AiClient.parseCompletion(openAi))
        val ollamaNative = """{"model":"llama3.2","message":{"role":"assistant","content":"Rain arrives tonight."}}"""
        assertEquals("Rain arrives tonight.", AiClient.parseCompletion(ollamaNative))
        assertEquals(listOf("llama3.2:3b", "qwen2.5:7b"), AiClient.parseModels("""{"models":[{"name":"qwen2.5:7b"},{"name":"llama3.2:3b"}]}"""))
        assertEquals(listOf("gpt-4o-mini"), AiClient.parseModels("""{"object":"list","data":[{"id":"gpt-4o-mini"}]}"""))
    }

    @Test
    fun sendsChatRequestsSafely() = runTest {
        val fake = FakeNetwork().apply {
            on("ai.example.com/api/chat/completions", body = """{"choices":[{"message":{"content":"Sunny and cool."}}]}""")
        }
        val base = HttpClients.create("t", null).newBuilder().addInterceptor(fake).build()
        val ai = AiClient(HttpClients.localServerClients(base))
        val text = ai.complete(AiConfig(AiProvider.OPEN_WEBUI, "https://ai.example.com/", "llama3.2", "sk-test"), listOf("user" to "hi"))
        assertEquals("Sunny and cool.", text)
        val request = fake.requests.single()
        assertEquals("Bearer sk-test", request.header("Authorization"))
        assertEquals("POST", request.method)
        // A key is never sent over plain HTTP, even on the LAN.
        assertFailsWith<InsecureConfigurationException> {
            ai.complete(AiConfig(AiProvider.OLLAMA, "http://192.168.1.20:11434", "llama3.2", "secret"), listOf("user" to "hi"))
        }
        // Plain HTTP to a public address is refused.
        assertFailsWith<InsecureConfigurationException> {
            ai.complete(AiConfig(AiProvider.OLLAMA, "http://8.8.8.8:11434", "llama3.2", null), listOf("user" to "hi"))
        }
    }

    @Test
    fun briefIncludesTheEssentials() {
        val f = EcccClient.parse(resource("/eccc/site_nb29.json"), captured)
        val snapshot = app.northvane.core.model.WeatherSnapshot(
            locationId = "fr", fetchedAt = captured, timeZoneId = f.zone.id, current = f.current, hourly = f.hourly, daily = f.daily,
        )
        val prompt = WeatherBrief.prompt(snapshot, "Fredericton, NB", UnitSystem.METRIC, captured)
        assertTrue(prompt.contains("Fredericton, NB") && prompt.contains("Now:") && prompt.contains("Next 18 hours") && prompt.contains("°C"), prompt)
        assertTrue(prompt.lines().size in 4..12, prompt)
        assertTrue(WeatherBrief.messages(snapshot, "x", UnitSystem.IMPERIAL, captured).first().first == "system")
    }
}
