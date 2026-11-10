package app.northvane.core

import app.northvane.core.net.CleartextGuardInterceptor
import app.northvane.core.net.LanCleartextAllowed
import app.northvane.core.net.PrivateNetworkDns
import app.northvane.core.net.UrlPolicy
import app.northvane.core.net.UrlPolicy.Validation
import app.northvane.core.nws.NwsClient
import okhttp3.Dns
import app.northvane.core.net.HttpEngine
import app.northvane.core.net.fetch
import okhttp3.Request
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SecurityTest {

    private fun valid(url: String, creds: Boolean = false) = UrlPolicy.validateUserServerUrl(url, creds)

    @Test
    fun httpsIsAlwaysAllowed() {
        val v = assertIs<Validation.Valid>(valid("https://weather.example.com/weewx/northvane.json", creds = true))
        assertFalse(v.isCleartext)
    }

    @Test
    fun cleartextOnlyForPrivateNetworks() {
        assertTrue(assertIs<Validation.Valid>(valid("http://192.168.1.20/weewx/northvane.json")).isCleartext)
        assertIs<Validation.Valid>(valid("http://10.0.0.5:8080/realtime.txt"))
        assertIs<Validation.Valid>(valid("http://172.20.1.1/clientraw.txt"))
        assertIs<Validation.Valid>(valid("http://100.101.102.103/x.json")) // Tailscale CGNAT range
        assertIs<Validation.Valid>(valid("http://[fd7a:115c:a1e0::1]/x.json")) // IPv6 ULA
        assertIs<Validation.Valid>(valid("http://weewx.local/x.json")) // checked at DNS time
        assertIs<Validation.Invalid>(valid("http://8.8.8.8/x.json"))
        assertIs<Validation.Invalid>(valid("http://100.200.1.1/x.json")) // outside 100.64/10
        assertIs<Validation.Invalid>(valid("http://172.32.0.1/x.json")) // outside 172.16/12
    }

    @Test
    fun credentialsNeverOverCleartextOrInUrl() {
        assertIs<Validation.Invalid>(valid("http://192.168.1.20/x.json", creds = true))
        assertIs<Validation.Invalid>(valid("https://user:pass@example.com/x.json"))
        assertIs<Validation.Invalid>(valid("ftp://example.com/x"))
        assertIs<Validation.Invalid>(valid("javascript:alert(1)"))
        assertIs<Validation.Invalid>(valid(""))
    }

    @Test
    fun privateDnsDropsPublicAddresses() {
        val fake = Dns { host ->
            when (host) {
                "mixed.example" -> listOf(InetAddress.getByName("203.0.113.5"), InetAddress.getByName("192.168.1.9"))
                else -> listOf(InetAddress.getByName("203.0.113.5"))
            }
        }
        val dns = PrivateNetworkDns(fake)
        assertEquals(listOf(InetAddress.getByName("192.168.1.9")), dns.lookup("mixed.example"))
        assertFailsWith<UnknownHostException> { dns.lookup("public.example") }
    }

    private fun guardedClient(fake: FakeNetwork) = okhttp3.OkHttpClient.Builder()
        .addInterceptor(CleartextGuardInterceptor())
        .addInterceptor(fake)
        .build()

    @Test
    fun guardBlocksUntaggedCleartext() {
        val fake = FakeNetwork().apply { on("/", body = "ok") }
        val client = guardedClient(fake)
        assertFailsWith<IOException> {
            client.newCall(Request.Builder().url("http://192.168.1.2/x").build()).execute()
        }
        val tagged = Request.Builder().url("http://192.168.1.2/x")
            .tag(LanCleartextAllowed::class.java, LanCleartextAllowed).build()
        client.newCall(tagged).execute().use { assertEquals(200, it.code) }
        val public = Request.Builder().url("http://8.8.8.8/x")
            .tag(LanCleartextAllowed::class.java, LanCleartextAllowed).build()
        assertFailsWith<IOException> { client.newCall(public).execute() }
        val withAuth = Request.Builder().url("http://192.168.1.2/x").header("Authorization", "Basic abc")
            .tag(LanCleartextAllowed::class.java, LanCleartextAllowed).build()
        assertFailsWith<IOException> { client.newCall(withAuth).execute() }
        assertEquals(1, fake.requests.size, "only the allowed request reached the network")
    }

    /** A real server on this machine that redirects to a public address: the LAN client must not follow it out. */
    @Test
    fun lanClientCantBeRedirectedToThePublicInternet() {
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { exchange ->
            exchange.responseHeaders.add("Location", "http://203.0.113.7/stolen")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.start()
        try {
            val clients = app.northvane.core.net.HttpClients.localServerClients(
                app.northvane.core.net.HttpClients.create("test", cacheDir = null),
            )
            val request = Request.Builder().url("http://127.0.0.1:${server.address.port}/weewx.json")
                .tag(LanCleartextAllowed::class.java, LanCleartextAllowed).build()
            val error = assertFailsWith<IOException> { clients.cleartext.newCall(request).execute().close() }
            // OkHttp wraps the refusal in its own "Failed to connect"; the reason is underneath.
            val reasons = generateSequence<Throwable>(error) { it.cause }.flatMap { sequenceOf(it) + it.suppressed.asSequence() }
            assertTrue(reasons.any { "local network" in it.message.orEmpty() }, error.stackTraceToString())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun recognizesLikelyLocalNetworkHosts() {
        listOf("192.168.1.20", "10.0.0.5", "fd7a:115c:a1e0::1", "weewx.local", "pi.lan", "nas.home.arpa", "raspberrypi")
            .forEach { assertTrue(UrlPolicy.looksLikeLocalNetworkHost(it), it) }
        listOf("127.0.0.1", "8.8.8.8", "weather.example.com", "api.weather.gov")
            .forEach { assertFalse(UrlPolicy.looksLikeLocalNetworkHost(it), it) }
    }

    @Test
    fun userAgentSanitizesContact() {
        assertEquals("SuperAwesomeMagicalWeather/1.0 (Android weather app)", NwsClient.userAgent("1.0", "  "))
        assertEquals("SuperAwesomeMagicalWeather/1.0 (me@example.com)", NwsClient.userAgent("1.0", "me@example.com\r\nX-Evil: 1"
            .substringBefore('\r')))
        assertEquals("SuperAwesomeMagicalWeather/1.0 (a@b.cX-Evil:1)", NwsClient.userAgent("1.0", "a@b.c\r\nX-Evil: 1"))
    }
    @Test
    fun rejectsNumericHostsThatArentDottedQuads() {
        listOf("http://134744072/x.json", "http://0x7f.1/x.json", "http://127.1/x.json", "http://10.1/x.json")
            .forEach { assertIs<Validation.Invalid>(valid(it), it) }
        assertIs<Validation.Valid>(valid("http://192.168.1.20/x.json"))
        assertIs<Validation.Valid>(valid("http://pi4.lan/x.json"))
    }

    /** A server on loopback that redirects /start to /end and records the headers that reach /end. */
    private fun redirectServer(seen: MutableList<String?>): com.sun.net.httpserver.HttpServer =
        com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/start") { ex -> ex.responseHeaders.add("Location", "/end"); ex.sendResponseHeaders(302, -1); ex.close() }
            createContext("/end") { ex ->
                seen += ex.requestHeaders.getFirst("X-Api-Key")
                val body = "ok".toByteArray(); ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) }
            }
            start()
        }

    @Test
    fun requestsWithKeysDontFollowRedirects() = kotlinx.coroutines.runBlocking {
        val seen = mutableListOf<String?>()
        val server = redirectServer(seen)
        try {
            val http = app.northvane.core.net.HttpClients.create("test", cacheDir = null)
            val base = "http://127.0.0.1:${server.address.port}"
            val plain = app.northvane.core.net.Request.Builder().url("$base/start").allowLanCleartext().build()
            assertEquals(200, http.fetch(plain).code)
            // Over plain HTTP a key isn't sent at all.
            val keyed = app.northvane.core.net.Request.Builder().url("$base/start").allowLanCleartext().secretHeader("X-Api-Key", "k").build()
            assertTrue(keyed.carriesSecrets)
            assertFailsWith<IOException> { http.fetch(keyed) }
            assertEquals(listOf<String?>(null), seen)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun keyedRequestsAreNotRedirected() = kotlinx.coroutines.runBlocking {
        val seen = mutableListOf<String?>()
        val server = redirectServer(seen)
        try {
            // Without the cleartext guard, to see the redirect rule on its own.
            val http = okhttp3.OkHttpClient.Builder().build()
            val keyed = app.northvane.core.net.Request.Builder().url("http://127.0.0.1:${server.address.port}/start")
                .secretHeader("X-Api-Key", "k").build()
            assertEquals(302, http.fetch(keyed).code)
            assertTrue(seen.isEmpty())
            val authorized = app.northvane.core.net.Request.Builder().url("http://x.example/").header("authorization", "Bearer t").build()
            assertTrue(authorized.carriesSecrets)
        } finally {
            server.stop(0)
        }
    }
}
