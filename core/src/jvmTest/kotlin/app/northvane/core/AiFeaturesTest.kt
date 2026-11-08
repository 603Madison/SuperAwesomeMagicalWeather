package app.northvane.core

import app.northvane.core.ai.AiClient
import app.northvane.core.ai.AiConfig
import app.northvane.core.ai.AiPrompts
import app.northvane.core.ai.AiProvider
import app.northvane.core.ai.AiRequest
import app.northvane.core.ai.AiText
import app.northvane.core.ai.ClaudeClient
import app.northvane.core.ai.ClaudeModels
import app.northvane.core.model.AlertCategory
import app.northvane.core.model.AlertSeverity
import app.northvane.core.model.WeatherAlert
import app.northvane.core.net.HttpClients
import app.northvane.core.net.InvalidCredentialsException
import app.northvane.core.net.WeatherApiException
import app.northvane.core.nws.Discussions
import app.northvane.core.nws.NwsClient
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import app.northvane.core.time.*

class AiFeaturesTest {
    private fun resource(name: String) = requireNotNull(javaClass.getResource(name)) { "missing $name" }.readText()
    private val zone = ZoneId.of("America/New_York")

    /** A stand-in for api.anthropic.com on localhost, so the real SDK is exercised end to end. */
    private class StubAnthropic {
        data class Seen(val method: String, val path: String, val headers: Map<String, String>, val body: String)

        val seen = CopyOnWriteArrayList<Seen>()
        var status = 200
        var messageBody = """{"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5",""" +
            """"content":[{"type":"text","text":"Rain tonight, then **cooler**.\n- Bring a jacket"}],""" +
            """"stop_reason":"end_turn","stop_sequence":null,"usage":{"input_tokens":10,"output_tokens":5}}"""
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                val body = ex.requestBody.readBytes().decodeToString()
                seen += Seen(ex.requestMethod, ex.requestURI.toString(), ex.requestHeaders.mapValues { it.value.joinToString(",") }.mapKeys { it.key.lowercase() }, body)
                val (code, reply) = when {
                    ex.requestURI.path.startsWith("/v1/models") ->
                        200 to """{"data":[{"type":"model","id":"claude-opus-5-5","display_name":"Claude Opus 5.5","created_at":"2026-09-01T00:00:00Z"},""" +
                            """{"type":"model","id":"claude-haiku-5-5","display_name":"Claude Haiku 5.5","created_at":"2026-09-01T00:00:00Z"}],""" +
                            """"has_more":false,"first_id":"claude-opus-5-5","last_id":"claude-haiku-5-5"}"""
                    status != 200 -> status to """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}"""
                    else -> 200 to messageBody
                }
                val bytes = reply.toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(code, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            start()
        }
        val url = "http://127.0.0.1:${server.address.port}"
        fun stop() = server.stop(0)
    }

    private val stub = StubAnthropic()

    @AfterTest
    fun stopStub() = stub.stop()

    private fun client() = AiClient(HttpClients.localServerClients(HttpClients.create("t", null)), ClaudeClient(stub.url))

    @Test
    fun asksClaudeThroughTheSdk() = runTest {
        val config = AiConfig(AiProvider.CLAUDE, "", "claude-opus-5-5", "sk-ant-test")
        assertTrue(config.isComplete)
        val text = client().complete(config, AiRequest("Be brief.", "Will it rain?"))
        assertEquals("Rain tonight, then cooler.\n• Bring a jacket", text)
        val call = stub.seen.single()
        assertEquals("POST", call.method)
        assertTrue(call.path.startsWith("/v1/messages"), call.path)
        assertEquals("Bearer sk-ant-test", call.headers["authorization"] ?: call.headers["x-api-key"]?.let { "Bearer $it" }, call.headers.toString())
        assertNotNull(call.headers["anthropic-version"])
        assertTrue(call.headers["anthropic-beta"].orEmpty().contains("server-side-fallback-2026-07-01"), call.headers.toString())
        val body = call.body
        assertTrue(body.contains("\"model\":\"claude-opus-5-5\"") && body.contains("\"system\":\"Be brief.\""), body)
        assertTrue(body.contains("\"fallbacks\":\"default\"") && body.contains("\"effort\":\"low\""), body)
        assertFalse(body.contains("temperature"), body)
    }

    @Test
    fun leavesOutWhatOlderModelsReject() = runTest {
        client().complete(AiConfig(AiProvider.CLAUDE, "", "claude-haiku-4-5", "k"), AiRequest("s", "u"))
        val call = stub.seen.single()
        assertFalse(call.body.contains("fallbacks") || call.body.contains("effort"), call.body)
        assertFalse(call.headers["anthropic-beta"].orEmpty().contains("fallback"))
    }

    @Test
    fun listsClaudeModelsAndReportsErrors() = runTest {
        val config = AiConfig(AiProvider.CLAUDE, "", "x", "k")
        assertEquals(listOf("claude-opus-5-5", "claude-haiku-5-5"), client().models(config))
        stub.status = 401
        assertFailsWith<InvalidCredentialsException> { client().complete(config, AiRequest("s", "u")) }
        stub.status = 200
        stub.messageBody = """{"id":"m","type":"message","role":"assistant","model":"x","content":[],"stop_reason":"refusal","stop_sequence":null,"usage":{"input_tokens":1,"output_tokens":0}}"""
        assertFailsWith<WeatherApiException> { client().complete(config, AiRequest("s", "u")) }
        // No key, no request.
        assertFailsWith<InvalidCredentialsException> { client().complete(config.copy(apiKey = null), AiRequest("s", "u")) }
        assertFalse(config.copy(apiKey = "").isComplete)
    }

    @Test
    fun knowsWhatEachModelAccepts() {
        listOf("claude-opus-5-5", "claude-sonnet-5-5", "claude-haiku-5-5", "claude-fable-5-1", "claude-opus-4-6", "claude-opus-4-5", "claude-opus-4-5-20251101")
            .forEach { assertTrue(ClaudeModels.supportsEffort(it), it) }
        listOf("claude-haiku-4-5", "claude-sonnet-4-5", "claude-haiku-4-5-20251001", "claude-3-7-sonnet-20250219", "gpt-4o")
            .forEach { assertFalse(ClaudeModels.supportsEffort(it), it) }
        assertTrue(ClaudeModels.supportsFallbacks("claude-opus-5-5"))
        assertFalse(ClaudeModels.supportsFallbacks("claude-haiku-5-5"))
        assertEquals(ClaudeModels.BASE_URL, AiProvider.CLAUDE.baseUrl("http://ignored"))
    }

    @Test
    fun readsTheForecastDiscussion() {
        val (id, time) = assertNotNull(Discussions.parseLatest(resource("/afd/afd_list.json")))
        assertEquals("65440572-1405-41f6-90d5-89f400024657", id)
        assertEquals(Instant.parse("2026-11-06T18:30:00Z"), time)
        val d = Discussions.parseProduct(resource("/afd/afd_product.json"), "GYX")
        assertTrue(d.text.startsWith(".WHAT HAS CHANGED..."), d.text.take(80))
        assertTrue(d.text.contains("KEY MESSAGE 1") && d.text.contains(".MARINE..."))
        // The pilots' section and the sign-off are dropped.
        assertFalse(d.text.contains("AVIATION") || d.text.contains("LLWS") || d.text.contains("Arnott"), d.text)
        assertEquals("GYX", Discussions.office(" gyx"))
        assertEquals(null, Discussions.office("../x"))
        val request = AiPrompts.discussion(d, "Portland, ME", zone)
        assertTrue(request.user.contains("Portland, ME") && request.user.contains("Fri Nov 6, 1:30 PM"), request.user.take(200))
    }

    @Test
    fun fetchesTheDiscussionFromWeatherGov() = runTest {
        val fake = FakeNetwork().apply {
            on("/products/types/AFD/locations/GYX", body = resource("/afd/afd_list.json"))
            on("/products/65440572", body = resource("/afd/afd_product.json"))
        }
        val nws = NwsClient(HttpClients.create("t", null).newBuilder().addInterceptor(fake).build(), { "test" })
        val (id, _) = assertNotNull(nws.latestDiscussionId("GYX"))
        assertEquals("GYX", nws.discussion("GYX", id).office)
        assertFailsWith<IllegalArgumentException> { nws.discussion("GYX", "../../alerts") }
    }

    @Test
    fun alertPromptKeepsTheFacts() {
        val alert = WeatherAlert(
            id = "a1", threadId = "a1", urgency = "Expected", certainty = "Likely", event = "Winter Weather Advisory", category = AlertCategory.ADVISORY, severity = AlertSeverity.MINOR,
            headline = "WINTER WEATHER ADVISORY IN EFFECT FROM 7 PM THIS EVENING TO 10 AM EST SATURDAY",
            description = "* WHAT...Snow expected above 1500 feet. Total snow accumulations of 2 to 4 inches.", instruction = "Slow down and use caution while traveling.",
            onset = Instant.parse("2026-11-07T00:00:00Z"), ends = Instant.parse("2026-11-07T15:00:00Z"),
        )
        val request = AiPrompts.alert(alert, "Bethel, ME", zone, Instant.parse("2026-11-06T20:00:00Z"))
        assertTrue(request.user.contains("Starts: Fri Nov 6, 7:00 PM") && request.user.contains("2 to 4 inches") && request.user.contains("Bethel, ME"), request.user)
        assertTrue(request.system.contains("only to part of its area"))
    }

    @Test
    fun cleansReplies() {
        assertEquals("• One\n• Two", AiText.clean("<think>hm</think>\n- One\n* Two"))
        assertEquals("Hi", AiText.clean("## Hi\n\n\n\n"))
    }

    @Test
    fun questionsSeeTheAppsOwnOutlooks() {
        val now = Instant.parse("2026-11-06T21:00:00Z")
        val hours = (0 until 30).map { i ->
            app.northvane.core.model.HourlyForecast(
                time = now.plusSeconds(3600L * i), temperatureC = 8.0, condition = app.northvane.core.model.WeatherCondition.OVERCAST,
                isDaytime = false, shortForecast = "Cloudy", precipitationChance = 10, windSpeedKph = 8.0, windDirection = "N",
                humidityPercent = 80.0, skyCoverPercent = 92,
            )
        }
        val kp = app.northvane.core.model.KpForecast(
            (0 until 8).map { i -> app.northvane.core.model.KpPoint(now.plusSeconds(3L * 3600 * i), 5.3, app.northvane.core.model.KpPoint.Kind.PREDICTED) },
            now,
        )
        val snapshot = app.northvane.core.model.WeatherSnapshot(
            locationId = "p", fetchedAt = now, timeZoneId = "America/New_York", hourly = hours, latitude = 43.66, aurora = kp,
            smoke = app.northvane.core.radar.SmokeOutlook(now, now, listOf(app.northvane.core.radar.SmokeHour(now.plusSeconds(7200), 40.0))),
        )
        val request = AiPrompts.question("When are we getting northern lights?", snapshot, "Portland, ME", app.northvane.core.model.UnitSystem.IMPERIAL, now, longitude = -70.26)
        assertTrue(request.user.contains("Northern lights (NOAA Kp forecast):") && request.user.contains("Tonight: Kp 5.3"), request.user)
        assertTrue(request.user.contains("likely hidden") && request.user.contains("Wildfire smoke"), request.user)
        assertTrue(request.system.contains("northern lights"))
    }

    @Test
    fun parsesBriefReplies() {
        val brief = app.northvane.core.ai.AiBrief.parse(
            AiText.clean("**HEADLINE:** Soaking rain tonight, 1.5–2 in near the coast\n- Thunder possible this evening.\n- Drier and cooler Sunday afternoon\nCONFIDENCE: Medium | front position still uncertain"),
        )
        assertEquals("Soaking rain tonight, 1.5–2 in near the coast", brief.headline)
        assertEquals(listOf("Thunder possible this evening", "Drier and cooler Sunday afternoon"), brief.points)
        assertEquals(app.northvane.core.ai.AiBrief.Confidence.MEDIUM, brief.confidence)
        assertEquals("front position still uncertain", brief.confidenceReason)
        // Free text (an older stored reply, or a model that ignored the format) stays readable.
        assertEquals("Rain tonight.", app.northvane.core.ai.AiBrief.parse("Rain tonight.").headline)
    }
}
