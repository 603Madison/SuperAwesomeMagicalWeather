package app.northvane.core.net

import app.northvane.core.net.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Rules for URLs the *user* types in (their WeeWX server). Built-in services
 * are all fixed HTTPS endpoints and never pass through here.
 *
 * - Only http/https.
 * - No credentials embedded in the URL (they would end up in logs/history);
 *   credentials go in dedicated, encrypted fields instead.
 * - Plain HTTP only for hosts on a private network (RFC 1918, loopback,
 *   link-local, CGNAT/Tailscale, IPv6 ULA, or a hostname that resolves to one
 *   of those — enforced at connect time on Android by PrivateNetworkDns).
 * - Credentials are never sent over plain HTTP, even on a LAN.
 */
object UrlPolicy {

    sealed interface Validation {
        data class Valid(val url: HttpUrl, val isCleartext: Boolean) : Validation
        data class Invalid(val reason: String) : Validation
    }

    fun validateUserServerUrl(raw: String, sendsCredentials: Boolean): Validation {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return Validation.Invalid("Enter a URL")
        if (trimmed.length > 2048) return Validation.Invalid("URL is too long")
        if (!trimmed.startsWith("https://", ignoreCase = true) &&
            !trimmed.startsWith("http://", ignoreCase = true)
        ) {
            return Validation.Invalid("URL must start with https:// or http://")
        }
        val url = trimmed.toHttpUrlOrNull() ?: return Validation.Invalid("Not a valid URL")
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) {
            return Validation.Invalid("Don't put a username or password in the URL; use the login fields instead")
        }
        if (isObscureNumericHost(url.host)) {
            return Validation.Invalid("Write the server's address as four numbers, like 192.168.1.20, or use its name")
        }
        if (url.isHttps) return Validation.Valid(url, isCleartext = false)

        if (sendsCredentials) {
            return Validation.Invalid("Credentials are only sent over HTTPS. Use an https:// URL or remove the login.")
        }
        if (isIpLiteral(url.host) && !isPrivateIpLiteral(url.host)) {
            return Validation.Invalid("Plain HTTP is only allowed for servers on your local network. Use HTTPS for public addresses.")
        }
        return Validation.Valid(url, isCleartext = true)
    }

    /**
     * Whether a host name *looks* local without resolving it: a private (non-loopback)
     * address literal, or an mDNS/home-router style name. Hostnames on public
     * domains may still resolve to LAN addresses (split DNS); callers that can
     * do a lookup should also check the resolved addresses.
     */
    fun looksLikeLocalNetworkHost(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        if (isIpLiteral(h)) {
            val bytes = parseIp(h) ?: return false
            return !isLoopback(bytes) && isPrivate(bytes)
        }
        return '.' !in h || LOCAL_SUFFIXES.any { h.endsWith(it) }
    }

    /**
     * A host that's a number but not a plain dotted IPv4 address ("134744072",
     * "0x7f.1", "127.1"). Resolvers read these as IP addresses, so they could
     * pass for a local name while pointing anywhere.
     */
    fun isObscureNumericHost(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        if (h.isEmpty() || ':' in h || IPV4.matches(h)) return false
        val labels = h.split('.')
        return labels.last().let { it.isNotEmpty() && (it.all(Char::isDigit) || (it.startsWith("0x") && it.drop(2).all { c -> c.isDigit() || c in 'a'..'f' })) }
    }

    private val LOCAL_SUFFIXES = listOf(".local", ".lan", ".home.arpa", ".internal", ".localdomain", ".home")

    fun isIpLiteral(host: String): Boolean =
        IPV4.matches(host) || (host.contains(':') && host.all { it.isLetterOrDigit() || it == ':' || it == '.' || it == '%' })

    fun isPrivateIpLiteral(host: String): Boolean {
        if (!isIpLiteral(host)) return false
        val bytes = parseIp(host) ?: return false
        return isPrivate(bytes)
    }

    /** Private-network address bytes (4 or 16): loopback, link-local, RFC 1918, CGNAT, IPv6 ULA. */
    fun isPrivate(b: ByteArray): Boolean {
        fun u(i: Int) = b[i].toInt() and 0xFF
        return when (b.size) {
            4 -> u(0) == 127 || u(0) == 10 || (u(0) == 172 && u(1) in 16..31) || (u(0) == 192 && u(1) == 168) ||
                (u(0) == 169 && u(1) == 254) || (u(0) == 100 && (u(1) and 0xC0) == 64)
            16 -> isLoopback(b) || (u(0) == 0xFE && (u(1) and 0xC0) == 0x80) || (u(0) == 0xFE && (u(1) and 0xC0) == 0xC0) ||
                (u(0) and 0xFE) == 0xFC || mappedV4(b)?.let { isPrivate(it) } == true
            else -> false
        }
    }

    fun isLoopback(b: ByteArray): Boolean = when (b.size) {
        4 -> (b[0].toInt() and 0xFF) == 127
        16 -> (0 until 15).all { b[it].toInt() == 0 } && b[15].toInt() == 1 || mappedV4(b)?.let { isLoopback(it) } == true
        else -> false
    }

    private fun mappedV4(b: ByteArray): ByteArray? =
        if ((0 until 10).all { b[it].toInt() == 0 } && b[10].toInt() == -1 && b[11].toInt() == -1) b.copyOfRange(12, 16) else null

    /** The bytes of an IPv4 or IPv6 literal, or null. */
    fun parseIp(text: String): ByteArray? {
        val s = text.removePrefix("[").removeSuffix("]").substringBefore('%')
        if (IPV4.matches(s)) {
            val parts = s.split('.').map { it.toIntOrNull() ?: return null }
            if (parts.any { it > 255 }) return null
            return ByteArray(4) { parts[it].toByte() }
        }
        if (':' !in s) return null
        // IPv6, possibly with an embedded IPv4 tail and one "::".
        var head = s
        var v4: ByteArray? = null
        val lastColon = s.lastIndexOf(':')
        if ('.' in s.substring(lastColon + 1)) {
            v4 = parseIp(s.substring(lastColon + 1)) ?: return null
            head = s.substring(0, lastColon + 1) + "0:0"
        }
        val halves = head.split("::")
        if (halves.size > 2) return null
        fun groups(part: String) = if (part.isEmpty()) emptyList() else part.split(':')
        val left = groups(halves[0])
        val right = if (halves.size == 2) groups(halves[1]) else emptyList()
        val missing = 8 - left.size - right.size
        if ((halves.size == 1 && missing != 0) || missing < 0) return null
        val all = left + List(if (halves.size == 2) missing else 0) { "0" } + right
        if (all.size != 8) return null
        val out = ByteArray(16)
        all.forEachIndexed { i, g ->
            if (g.isEmpty() || g.length > 4) return null
            val v = g.toIntOrNull(16) ?: return null
            out[i * 2] = (v shr 8).toByte()
            out[i * 2 + 1] = v.toByte()
        }
        v4?.copyInto(out, 12)
        return out
    }

    private val IPV4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
}
