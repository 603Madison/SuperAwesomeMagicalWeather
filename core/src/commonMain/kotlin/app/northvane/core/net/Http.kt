package app.northvane.core.net

import app.northvane.core.time.Instant
import kotlin.io.encoding.Base64

class HttpResponse(
    val code: Int,
    val body: String,
    val lastModified: Instant?,
)

class HttpBytes(val code: Int, val bytes: ByteArray) {
    val isSuccessful: Boolean get() = code in 200..299
}

/**
 * The platform's HTTP stack: OkHttp on Android (with the app's cleartext
 * guards, cache and timeouts), NSURLSession on iOS.
 */
expect class HttpEngine

/**
 * Executes [request], honoring coroutine cancellation, and reads at most
 * [maxBytes] of the body so a misbehaving server can't exhaust memory.
 */
expect suspend fun HttpEngine.fetch(request: Request, maxBytes: Long = 4L * 1024 * 1024): HttpResponse

/** As [fetch], for binary bodies (images, GRIB byte ranges); throws [ResponseTooLargeException] past [maxBytes]. */
expect suspend fun HttpEngine.fetchBytes(request: Request, maxBytes: Long): HttpBytes

/** The same engine with longer timeouts, for slow servers such as a home AI box. */
expect fun HttpEngine.withTimeouts(readSeconds: Long, callSeconds: Long): HttpEngine

class LocalServerClients(val https: HttpEngine, val cleartext: HttpEngine)

/**
 * Request marker for a user-configured local-network server that may use
 * plain HTTP. Requests without it are HTTPS-only.
 */
object LanCleartextAllowed

class RequestBody(val bytes: ByteArray, val contentType: String)

/** A request, built the way OkHttp builds one. */
class Request private constructor(
    val url: String,
    val method: String,
    val headers: List<Pair<String, String>>,
    val body: RequestBody?,
    val lanCleartext: Boolean,
    /** Lower-case names of headers that carry a key, token or password ([Builder.secretHeader], and Authorization). */
    val secretHeaders: Set<String>,
) {
    fun header(name: String): String? = headers.lastOrNull { it.first.equals(name, ignoreCase = true) }?.second

    /**
     * Whether the request carries credentials. Such requests never go over
     * plain HTTP and never follow redirects, so a server can't pass a key on
     * to another host.
     */
    val carriesSecrets: Boolean get() = secretHeaders.isNotEmpty()

    /** This request sent on to [url] after a redirect: same headers, and GET without a body unless [keepMethod]. */
    internal fun redirectedTo(url: String, keepMethod: Boolean): Request =
        if (keepMethod) Request(url, method, headers, body, lanCleartext, secretHeaders)
        else Request(url, "GET", headers.filterNot { it.first.equals("Content-Type", ignoreCase = true) }, null, lanCleartext, secretHeaders)

    class Builder {
        private var url: String? = null
        private var method = "GET"
        private val headers = mutableListOf<Pair<String, String>>()
        private var body: RequestBody? = null
        private var lan = false
        private val secrets = mutableSetOf<String>()

        fun url(url: String) = apply { this.url = url }
        fun url(url: HttpUrl) = apply { this.url = url.toString() }

        /** Sets [name], replacing any earlier value. */
        fun header(name: String, value: String) = apply {
            require(name.isNotEmpty() && name.all { it.code in 0x21..0x7E && it != ':' }) { "Bad header name" }
            require(value.none { it == '\r' || it == '\n' }) { "Bad header value" }
            headers.removeAll { it.first.equals(name, ignoreCase = true) }
            headers += name to value
        }

        /** Sets [name] to a key, token or password: never sent over plain HTTP or along a redirect. */
        fun secretHeader(name: String, value: String) = apply {
            header(name, value)
            secrets += name.lowercase()
        }

        fun post(body: RequestBody) = apply { method = "POST"; this.body = body }
        fun post(body: String, contentType: String) = post(RequestBody(body.encodeToByteArray(), contentType))

        /** Marks the request as one to the user's own server on the LAN, which may use plain HTTP. */
        fun allowLanCleartext() = apply { lan = true }

        fun build(): Request {
            val names = headers.map { it.first.lowercase() }.toSet()
            val secret = names.filter { it in secrets || it == "authorization" }.toSet()
            return Request(requireNotNull(url) { "url == null" }, method, headers.toList(), body, lan, secret)
        }
    }
}

object Credentials {
    /** The HTTP Basic authorization value for [user] and [password]. */
    fun basic(user: String, password: String): String =
        "Basic " + Base64.encode("$user:$password".encodeToByteArray())
}

/** Parses an HTTP-date ("Wed, 21 Oct 2015 07:28:00 GMT"), as in Last-Modified. */
fun parseHttpDate(value: String): Instant? {
    val m = Regex("""^\w{3}, (\d{1,2}) (\w{3}) (\d{4}) (\d{2}):(\d{2}):(\d{2}) GMT$""").matchEntire(value.trim()) ?: return null
    val (d, mon, y, h, mi, s) = m.destructured
    val month = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec").indexOf(mon) + 1
    if (month == 0) return null
    return runCatching {
        Instant.parse("$y-${month.toString().padStart(2, '0')}-${d.padStart(2, '0')}T$h:$mi:${s}Z")
    }.getOrNull()
}
