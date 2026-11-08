package app.northvane.core.net

/**
 * An http(s) URL, parsed and built the way OkHttp's HttpUrl does it (same
 * percent-encoding for added path segments and query parameters), so URLs
 * look the same on every platform.
 */
class HttpUrl private constructor(
    val scheme: String,
    val username: String,
    val password: String,
    val host: String,
    val port: Int,
    /** Encoded path, starting with "/". */
    val encodedPath: String,
    /** Encoded query without "?", or null. */
    val encodedQuery: String?,
    private val fragment: String?,
) {
    val isHttps: Boolean get() = scheme == "https"

    fun newBuilder(): Builder = Builder(this)

    /** The first value of query parameter [name], decoded. */
    fun queryParameter(name: String): String? =
        encodedQuery?.split('&')?.map { it.split('=', limit = 2) }
            ?.firstOrNull { percentDecode(it[0]) == name }?.getOrNull(1)?.let { percentDecode(it) }

    override fun toString(): String = buildString {
        append(scheme).append("://")
        if (username.isNotEmpty() || password.isNotEmpty()) {
            append(username)
            if (password.isNotEmpty()) append(':').append(password)
            append('@')
        }
        append(if (':' in host) "[$host]" else host)
        if (port != defaultPort(scheme)) append(':').append(port)
        append(encodedPath)
        encodedQuery?.let { append('?').append(it) }
        fragment?.let { append('#').append(it) }
    }

    override fun equals(other: Any?) = other is HttpUrl && other.toString() == toString()
    override fun hashCode() = toString().hashCode()

    class Builder internal constructor(private val base: HttpUrl) {
        private val path = StringBuilder(base.encodedPath)
        private val query = mutableListOf<String>().apply { base.encodedQuery?.takeIf { it.isNotEmpty() }?.split('&')?.let { addAll(it) } }

        fun addPathSegment(segment: String) = apply {
            if (!path.endsWith("/")) path.append('/')
            path.append(percentEncode(segment, PATH_SEGMENT_ENCODE))
        }

        fun addPathSegments(segments: String) = apply { segments.split('/').filter { it.isNotEmpty() }.forEach { addPathSegment(it) } }

        fun addQueryParameter(name: String, value: String?) = apply {
            query += percentEncode(name, QUERY_COMPONENT_ENCODE) + (value?.let { "=" + percentEncode(it, QUERY_COMPONENT_ENCODE) } ?: "")
        }

        fun setQueryParameter(name: String, value: String?) = apply {
            val encoded = percentEncode(name, QUERY_COMPONENT_ENCODE)
            query.removeAll { it.substringBefore('=') == encoded }
            addQueryParameter(name, value)
        }

        fun build(): HttpUrl = HttpUrl(
            base.scheme, base.username, base.password, base.host, base.port,
            path.toString().ifEmpty { "/" }, if (query.isEmpty()) base.encodedQuery?.takeIf { it.isEmpty() } else query.joinToString("&"), base.fragment,
        )
    }

    companion object {
        private val URL = Regex("""^([A-Za-z][A-Za-z0-9+.-]*)://([^/?#]*)([^?#]*)(?:\?([^#]*))?(?:#(.*))?$""")
        private const val PATH_SEGMENT_ENCODE = " \"<>^`{}|/\\?#"
        private const val QUERY_COMPONENT_ENCODE = " !\"#$&'(),/:;<=>?@[]\\^`{|}~+"

        private fun defaultPort(scheme: String) = if (scheme == "https") 443 else 80

        fun parse(raw: String): HttpUrl? {
            val m = URL.matchEntire(raw.trim()) ?: return null
            val scheme = m.groupValues[1].lowercase()
            if (scheme != "http" && scheme != "https") return null
            val authority = m.groupValues[2]
            val at = authority.lastIndexOf('@')
            val userInfo = if (at >= 0) authority.substring(0, at) else ""
            val hostPort = if (at >= 0) authority.substring(at + 1) else authority
            val (host, port) = if (hostPort.startsWith("[")) {
                val end = hostPort.indexOf(']').takeIf { it > 0 } ?: return null
                val rest = hostPort.substring(end + 1)
                val p = if (rest.startsWith(":")) rest.drop(1).toIntOrNull() ?: return null else if (rest.isEmpty()) defaultPort(scheme) else return null
                hostPort.substring(1, end) to p
            } else {
                val colon = hostPort.lastIndexOf(':')
                if (colon >= 0) {
                    val p = hostPort.substring(colon + 1).let { if (it.isEmpty()) defaultPort(scheme) else it.toIntOrNull() } ?: return null
                    hostPort.substring(0, colon) to p
                } else {
                    hostPort to defaultPort(scheme)
                }
            }
            if (host.isEmpty() || port !in 1..65535) return null
            if (host.any { it.isWhitespace() || it in "<>\"{}|\\^`/" } || ('%' in host && ':' !in host)) return null
            val user = userInfo.substringBefore(':', userInfo)
            val pass = if (':' in userInfo) userInfo.substringAfter(':') else ""
            val path = m.groupValues[3].ifEmpty { "/" }
            if (path.any { it.isWhitespace() }) return null
            val query = if (m.groups[4] != null) m.groupValues[4] else null
            val fragment = if (m.groups[5] != null) m.groupValues[5] else null
            return HttpUrl(scheme, user, pass, host.lowercase(), port, path, query, fragment)
        }

        fun String.toHttpUrlOrNull(): HttpUrl? = parse(this)
        fun String.toHttpUrl(): HttpUrl = parse(this) ?: throw IllegalArgumentException("Expected URL scheme 'http' or 'https'")

        fun percentEncode(s: String, reserved: String): String = buildString {
            for (b in s.encodeToByteArray()) {
                val c = b.toInt() and 0xFF
                if (c < 0x20 || c >= 0x7F || c.toChar() in reserved || c == '%'.code) {
                    append('%').append(HEX[c shr 4]).append(HEX[c and 0xF])
                } else {
                    append(c.toChar())
                }
            }
        }

        fun percentDecode(s: String): String {
            val out = ArrayList<Byte>(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '%' && i + 2 < s.length) {
                    val v = s.substring(i + 1, i + 3).toIntOrNull(16)
                    if (v != null) { out += v.toByte(); i += 3; continue }
                }
                if (c == '+') out += ' '.code.toByte() else c.toString().encodeToByteArray().forEach { out += it }
                i++
            }
            return out.toByteArray().decodeToString()
        }

        private const val HEX = "0123456789ABCDEF"
    }
}
