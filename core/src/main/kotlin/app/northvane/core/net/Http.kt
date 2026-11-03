package app.northvane.core.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit

class HttpResponse(
    val code: Int,
    val body: String,
    val lastModified: Instant?,
)

/**
 * Executes [request] off the main thread, honoring coroutine cancellation, and
 * reads at most [maxBytes] of the body so a misbehaving server can't exhaust
 * memory.
 */
suspend fun OkHttpClient.fetch(request: Request, maxBytes: Long = 4L * 1024 * 1024): HttpResponse =
    runInterruptible(Dispatchers.IO) {
        newCall(request).execute().use { response ->
            val source = response.body.source()
            if (source.request(maxBytes + 1)) throw ResponseTooLargeException(maxBytes)
            HttpResponse(
                code = response.code,
                body = source.buffer.readUtf8(),
                lastModified = response.headers.getDate("Last-Modified")?.toInstant(),
            )
        }
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

class LocalServerClients(val https: OkHttpClient, val cleartext: OkHttpClient)
