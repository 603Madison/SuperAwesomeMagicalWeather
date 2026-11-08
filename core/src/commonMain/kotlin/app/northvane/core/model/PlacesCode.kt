package app.northvane.core.model

import app.northvane.core.util.randomUuid
import kotlin.io.encoding.Base64
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Saved places packed into a short text for a QR code, so the main app can
 * hand them to Mini without typing on a keypad: "SAMW1:" and a base64url list
 * of name, latitude and longitude (4 decimals, about 11 m). Nothing else
 * (no keys, no settings) goes in.
 */
object PlacesCode {
    private const val PREFIX = "SAMW1:"
    private const val MAX_PLACES = 12

    fun encode(places: List<SavedLocation>): String {
        val body = places.take(MAX_PLACES).joinToString("\n") { p ->
            val name = p.displayName.replace('\n', ' ').replace('|', '/').take(40)
            "$name|${fmt(p.latitude)}|${fmt(p.longitude)}"
        }
        return PREFIX + Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(body.encodeToByteArray())
    }

    /** The places in [text], or null if it isn't one of these codes. Bad entries are skipped. */
    fun decode(text: String): List<SavedLocation>? {
        val trimmed = text.trim()
        if (!trimmed.startsWith(PREFIX) || trimmed.length > 4_000) return null
        val body = runCatching { Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).decode(trimmed.removePrefix(PREFIX)).decodeToString() }.getOrNull() ?: return null
        return body.lines().take(MAX_PLACES).mapNotNull { line ->
            val parts = line.split('|')
            if (parts.size != 3) return@mapNotNull null
            val lat = parts[1].toDoubleOrNull() ?: return@mapNotNull null
            val lon = parts[2].toDoubleOrNull() ?: return@mapNotNull null
            val name = parts[0].filter { it >= ' ' }.trim().take(40).ifEmpty { "Place" }
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return@mapNotNull null
            SavedLocation(id = randomUuid(), name = name, latitude = lat, longitude = lon)
        }
    }

    /** Places from [incoming] not already within about 1 km of one in [existing]. */
    fun newOnes(incoming: List<SavedLocation>, existing: List<SavedLocation>): List<SavedLocation> =
        incoming.filter { p -> existing.none { abs(it.latitude - p.latitude) < 0.01 && abs(it.longitude - p.longitude) < 0.01 } }

    private fun fmt(v: Double) = ((v * 10_000).roundToLong() / 10_000.0).toString()
}
