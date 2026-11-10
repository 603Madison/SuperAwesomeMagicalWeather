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
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocPointerTo
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.io.readByteArray
import platform.Foundation.NSURLRequestReloadIgnoringLocalCacheData
import platform.posix.AF_INET
import platform.posix.AF_INET6
import platform.posix.AF_UNSPEC
import platform.posix.SOCK_STREAM
import platform.posix.addrinfo
import platform.posix.freeaddrinfo
import platform.posix.getaddrinfo
import platform.posix.sockaddr_in
import platform.posix.sockaddr_in6

/**
 * NSURLSession through Ktor. App Transport Security already refuses plain
 * HTTP except to the local network; the checks here add the app's own rules:
 * plain HTTP only when the request is marked for the user's LAN server, only
 * to private addresses (a host name must resolve to nothing else), and never
 * with credentials. Redirects are followed here rather than by the system, so
 * every hop gets the same checks; requests carrying credentials don't follow
 * them at all. Nothing is kept in the system's URL cache or cookie store.
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
                followRedirects = false
                install(HttpTimeout) {
                    connectTimeoutMillis = 20_000
                    socketTimeoutMillis = 45_000
                    requestTimeoutMillis = 90_000
                }
                engine {
                    configureSession {
                        // Responses may hold API keys in their URLs or the user's own server's data:
                        // keep them out of Library/Caches. No cookies either; no service here needs one.
                        setURLCache(null)
                        setRequestCachePolicy(NSURLRequestReloadIgnoringLocalCacheData)
                        setHTTPCookieStorage(null)
                        setHTTPShouldSetCookies(false)
                    }
                    configureRequest { setAllowsCellularAccess(true) }
                }
            },
            userAgent, readSeconds = 45, callSeconds = 90,
        )

        /** Clients for the user's own servers (WeeWX, a home AI box). */
        fun localServerClients(base: HttpEngine): LocalServerClients = LocalServerClients(base, base)
    }
}

private suspend fun guard(request: Request) {
    val url = HttpUrl.parse(request.url) ?: throw IOException("Blocked request to an invalid URL")
    if (url.isHttps) return
    if (!request.lanCleartext) throw IOException("Blocked insecure (http) request")
    if (request.carriesSecrets) throw IOException("Blocked credentials over insecure connection")
    if (UrlPolicy.isIpLiteral(url.host)) {
        if (!UrlPolicy.isPrivateIpLiteral(url.host)) throw IOException("Blocked insecure request to a public address")
        return
    }
    if (!UrlPolicy.looksLikeLocalNetworkHost(url.host)) throw IOException("Plain HTTP is only allowed for servers on your local network")
    // A local-looking name can still resolve to the internet (a network's search domain, DNS
    // rebinding, a number like "134744072"): every address it resolves to must be private.
    val addresses = withContext(Dispatchers.IO) { resolve(url.host) }
    if (addresses.isEmpty() || !addresses.all { UrlPolicy.isPrivate(it) }) {
        throw IOException("Plain HTTP is only allowed for servers on your local network")
    }
}

/** The IPv4 and IPv6 addresses [host] resolves to, as bytes; empty when it doesn't resolve. */
@OptIn(ExperimentalForeignApi::class)
private fun resolve(host: String): List<ByteArray> = memScoped {
    val hints = alloc<addrinfo>().apply {
        ai_family = AF_UNSPEC
        ai_socktype = SOCK_STREAM
    }
    val result = allocPointerTo<addrinfo>()
    if (getaddrinfo(host, null, hints.ptr, result.ptr) != 0) return@memScoped emptyList()
    val out = mutableListOf<ByteArray>()
    try {
        var node = result.value
        while (node != null) {
            val info = node.pointed
            val address = info.ai_addr
            if (address != null) when (info.ai_family) {
                AF_INET -> {
                    val raw = address.reinterpret<sockaddr_in>().pointed.sin_addr.ptr.reinterpret<ByteVar>()
                    out += ByteArray(4) { raw[it] }
                }
                AF_INET6 -> {
                    val raw = address.reinterpret<sockaddr_in6>().pointed.sin6_addr.ptr.reinterpret<ByteVar>()
                    out += ByteArray(16) { raw[it] }
                }
            }
            node = info.ai_next
        }
    } finally {
        freeaddrinfo(result.value)
    }
    out
}

private class Raw(val code: Int, val bytes: ByteArray, val lastModified: String?, val location: String? = null)

private const val MAX_REDIRECTS = 5

/** Sends [request], following up to [MAX_REDIRECTS] redirects, each checked by [guard]. */
private suspend fun HttpEngine.execute(request: Request, maxBytes: Long): Raw {
    var current = request
    repeat(MAX_REDIRECTS + 1) {
        guard(current)
        val raw = send(current, maxBytes)
        val next = raw.location?.takeIf { raw.code in REDIRECT_CODES }?.let { redirect(current, raw.code, it) } ?: return raw
        current = next
    }
    throw IOException("Too many redirects")
}

private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

/**
 * Where a redirect from [from] to [location] leads, or null when it isn't
 * followed: requests carrying credentials, and https to http (as on Android).
 */
private fun redirect(from: Request, code: Int, location: String): Request? {
    if (from.carriesSecrets) return null
    val base = HttpUrl.parse(from.url) ?: return null
    val target = HttpUrl.parse(resolveUrl(base, location.trim())) ?: return null
    if (base.isHttps && !target.isHttps) return null
    val keepMethod = code == 307 || code == 308 || from.method == "GET" || from.method == "HEAD"
    return from.redirectedTo(target.toString(), keepMethod)
}

/** [location] (absolute, scheme-relative, absolute-path or relative) against [base]. */
internal fun resolveUrl(base: HttpUrl, location: String): String {
    if (location.startsWith("http://", ignoreCase = true) || location.startsWith("https://", ignoreCase = true)) return location
    val origin = base.scheme + "://" + (if (':' in base.host) "[${base.host}]" else base.host) +
        (if (base.port != (if (base.isHttps) 443 else 80)) ":${base.port}" else "")
    return when {
        location.startsWith("//") -> base.scheme + ":" + location
        location.startsWith("/") -> origin + location
        location.startsWith("?") -> origin + base.encodedPath + location
        else -> origin + base.encodedPath.substringBeforeLast('/') + "/" + location
    }
}

private suspend fun HttpEngine.send(request: Request, maxBytes: Long): Raw {
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
        Raw(response.status.value, bytes, response.headers["Last-Modified"], response.headers["Location"])
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
