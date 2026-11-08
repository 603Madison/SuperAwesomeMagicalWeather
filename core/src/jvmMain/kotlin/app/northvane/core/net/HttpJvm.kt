package app.northvane.core.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import okhttp3.Cache
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.net.InetAddress
import java.util.concurrent.TimeUnit

actual typealias HttpEngine = OkHttpClient

/** The common [Request] as an OkHttp request, tagged when it may use plain HTTP on the LAN. */
fun Request.toOkHttp(): okhttp3.Request = okhttp3.Request.Builder()
    .url(url)
    .apply {
        headers.forEach { (n, v) -> header(n, v) }
        body?.let { method(method, it.bytes.toRequestBody(it.contentType.toMediaType())) }
        if (lanCleartext) tag(LanCleartextAllowed::class.java, LanCleartextAllowed)
    }
    .build()

actual suspend fun HttpEngine.fetch(request: Request, maxBytes: Long): HttpResponse =
    runInterruptible(Dispatchers.IO) {
        newCall(request.toOkHttp()).execute().use { response ->
            val source = response.body.source()
            if (source.request(maxBytes + 1)) throw ResponseTooLargeException(maxBytes)
            HttpResponse(
                code = response.code,
                body = source.buffer.readUtf8(),
                lastModified = response.headers.getDate("Last-Modified")?.toInstant()?.let { app.northvane.core.time.Instant.fromEpochMilliseconds(it.toEpochMilli()) },
            )
        }
    }

actual suspend fun HttpEngine.fetchBytes(request: Request, maxBytes: Long): HttpBytes =
    runInterruptible(Dispatchers.IO) {
        newCall(request.toOkHttp()).execute().use { response ->
            val source = response.body.source()
            if (source.request(maxBytes + 1)) throw ResponseTooLargeException(maxBytes)
            HttpBytes(response.code, source.buffer.readByteArray())
        }
    }

actual fun HttpEngine.withTimeouts(readSeconds: Long, callSeconds: Long): HttpEngine =
    newBuilder().readTimeout(readSeconds, TimeUnit.SECONDS).callTimeout(callSeconds, TimeUnit.SECONDS).build()

internal actual fun platformNetworkMessage(t: Throwable): String? = when (t) {
    is java.net.UnknownHostException -> "Couldn't reach the server (no connection or unknown host)"
    is java.net.SocketTimeoutException, is java.io.InterruptedIOException -> "The request timed out"
    is javax.net.ssl.SSLException -> "Secure connection failed (certificate or TLS problem)"
    is java.net.ConnectException -> "Connection refused"
    else -> null
}

object HttpClients {
    /**
     * Base client. Timeouts are generous because satellite links (e.g.
     * T-Satellite) have high, variable latency; redirects between http and
     * https are never followed.
     */
    fun create(userAgent: String, cacheDir: File?, cacheBytes: Long = 8L * 1024 * 1024): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(false)
            .addInterceptor(CleartextGuardInterceptor())
            // Again on every hop, so a redirect can't take a request to plain HTTP on the internet.
            .addNetworkInterceptor(CleartextGuardInterceptor())
            .addInterceptor(DefaultHeadersInterceptor(userAgent))
            .apply { if (cacheDir != null) cache(Cache(cacheDir, cacheBytes)) }
            .build()

    /** Client for user-configured local servers: no shared HTTP cache, LAN-only DNS for cleartext. */
    fun localServerClients(base: OkHttpClient): LocalServerClients = LocalServerClients(
        https = base.newBuilder().cache(null).build(),
        // LAN only, end to end: private addresses from DNS, sockets that can't reach anything else
        // (redirects included), and no proxy in between.
        cleartext = base.newBuilder().cache(null).dns(PrivateNetworkDns()).socketFactory(PrivateNetworkSocketFactory())
            .proxy(java.net.Proxy.NO_PROXY).build(),
    )
}

/** Private-network addresses, including loopback. */
fun UrlPolicy.isPrivateAddress(address: InetAddress): Boolean = isPrivate(address.address)

/** Private-network addresses other than this device's own loopback. */
fun UrlPolicy.isLocalNetworkAddress(address: InetAddress): Boolean = !address.isLoopbackAddress && isPrivate(address.address)
