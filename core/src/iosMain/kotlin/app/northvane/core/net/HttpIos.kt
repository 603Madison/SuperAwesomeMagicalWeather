package app.northvane.core.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.engine.darwin.DarwinHttpRequestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.content.ByteArrayContent
import io.ktor.utils.io.readRemaining
import kotlinx.io.IOException
import kotlinx.io.readByteArray

/**
 * NSURLSession through Ktor. App Transport Security already refuses plain
 * HTTP except to the local network; the checks here add the app's own rules:
 * plain HTTP only when the request is marked for the user's LAN server, only
 * to private addresses, and never with credentials.
 */
actual class HttpEngine internal constructor(
    internal val client: HttpClient,
    internal val userAgent: String,
    internal val readSeconds: Long,
    internal val callSeconds: Long,
) {
    companion object {
        fun create(userAgent: String): HttpEngine = HttpEngine(
            HttpClient(Darwin) {
                expectSuccess = false
                followRedirects = true
                install(HttpTimeout) {
                    connectTimeoutMillis = 20_000
                    socketTimeoutMillis = 45_000
                    requestTimeoutMillis = 90_000
                }
                engine {
                    configureRequest { setAllowsCellularAccess(true) }
                }
            },
            userAgent, readSeconds = 45, callSeconds = 90,
        )

        /** Clients for the user's own servers (WeeWX, a home AI box). */
        fun localServerClients(base: HttpEngine): LocalServerClients = LocalServerClients(base, base)
    }
}

private fun guard(request: Request) {
    val url = HttpUrl.parse(request.url) ?: throw IOException("Blocked request to an invalid URL")
    if (url.isHttps) return
    if (!request.lanCleartext) throw IOException("Blocked insecure (http) request")
    if (UrlPolicy.isIpLiteral(url.host) && !UrlPolicy.isPrivateIpLiteral(url.host)) throw IOException("Blocked insecure request to a public address")
    if (!UrlPolicy.isIpLiteral(url.host) && !UrlPolicy.looksLikeLocalNetworkHost(url.host)) {
        throw IOException("Plain HTTP is only allowed for servers on your local network")
    }
    if (request.header("Authorization") != null) throw IOException("Blocked credentials over insecure connection")
}

private class Raw(val code: Int, val bytes: ByteArray, val lastModified: String?)

private suspend fun HttpEngine.execute(request: Request, maxBytes: Long): Raw {
    guard(request)
    val engine = this
    return client.prepareRequest(request.url) {
        method = HttpMethod.parse(request.method)
        if (request.header("User-Agent") == null) headers.append("User-Agent", engine.userAgent)
        request.headers.forEach { (n, v) -> headers.append(n, v) }
        request.body?.let { setBody(ByteArrayContent(it.bytes, ContentType.parse(it.contentType))) }
        timeout {
            socketTimeoutMillis = engine.readSeconds * 1000
            requestTimeoutMillis = engine.callSeconds * 1000
        }
    }.execute { response ->
        val source = response.bodyAsChannel().readRemaining(maxBytes + 1)
        val bytes = source.readByteArray()
        if (bytes.size > maxBytes) throw ResponseTooLargeException(maxBytes)
        Raw(response.status.value, bytes, response.headers["Last-Modified"])
    }
}

actual suspend fun HttpEngine.fetch(request: Request, maxBytes: Long): HttpResponse {
    val raw = execute(request, maxBytes)
    return HttpResponse(raw.code, raw.bytes.decodeToString(), raw.lastModified?.let(::parseHttpDate))
}

actual suspend fun HttpEngine.fetchBytes(request: Request, maxBytes: Long): HttpBytes {
    val raw = execute(request, maxBytes)
    return HttpBytes(raw.code, raw.bytes)
}

actual fun HttpEngine.withTimeouts(readSeconds: Long, callSeconds: Long): HttpEngine =
    HttpEngine(client, userAgent, readSeconds, callSeconds)

internal actual fun platformNetworkMessage(t: Throwable): String? = when (t) {
    is HttpRequestTimeoutException -> "The request timed out"
    is DarwinHttpRequestException -> when (t.origin.code) {
        -1001L -> "The request timed out"
        -1003L, -1009L, -1004L, -1005L -> "Couldn't reach the server (no connection or unknown host)"
        -1200L, -1201L, -1202L, -1203L, -1204L, -1205L, -1206L -> "Secure connection failed (certificate or TLS problem)"
        -1022L -> "Plain HTTP is only allowed for servers on your local network"
        else -> null
    }
    else -> null
}
