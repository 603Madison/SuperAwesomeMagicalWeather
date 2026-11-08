package app.northvane.core.alerts

import app.northvane.core.model.GeoPoint
import app.northvane.core.model.Units
import app.northvane.core.model.WeatherAlert
import app.northvane.core.util.Solar
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import app.northvane.core.util.toRadians
import app.northvane.core.util.toDegrees

/** Where a place stands relative to an alert's drawn area. */
sealed interface AlertSpot {
    /** The place is inside the area. */
    data object Inside : AlertSpot

    /** The area is [km] away, in direction [bearingDeg] from the place (0 = north). */
    data class Outside(val km: Double, val bearingDeg: Double) : AlertSpot {
        val compass: String get() = Units.compass(bearingDeg)
    }
}

/**
 * NWS storm-based alert areas: parsing the GeoJSON geometry, and whether a
 * place is inside one or how far away it is. Alerts without an area cover
 * whole counties or zones, so for them there is nothing more precise to say.
 */
object AlertAreas {
    private const val MAX_POINTS = 2_000

    /** Outer rings of a Polygon or MultiPolygon, or null. Bounded, so a huge geometry can't run away. */
    fun parse(geometry: JsonElement?): List<List<GeoPoint>>? {
        val g = geometry as? JsonObject ?: return null
        val type = (g["type"] as? JsonPrimitive)?.content
        val coords = g["coordinates"] as? JsonArray ?: return null
        val polygons: List<JsonArray> = when (type) {
            "Polygon" -> listOf(coords)
            "MultiPolygon" -> coords.mapNotNull { it as? JsonArray }
            else -> return null
        }
        var total = 0
        val rings = polygons.mapNotNull { poly ->
            val outer = poly.firstOrNull() as? JsonArray ?: return@mapNotNull null
            outer.mapNotNull { pt ->
                val pair = pt as? JsonArray ?: return@mapNotNull null
                val lon = (pair.getOrNull(0) as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
                val lat = (pair.getOrNull(1) as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
                if (++total > MAX_POINTS || lat !in -90.0..90.0 || lon !in -180.0..180.0) null else GeoPoint(lat, lon)
            }.takeIf { it.size >= 3 }
        }
        return rings.takeIf { it.isNotEmpty() }
    }

    /** Inside or how far away; null when the alert has no drawn area. */
    fun spot(alert: WeatherAlert, place: GeoPoint): AlertSpot? {
        val rings = alert.area ?: return null
        if (rings.any { contains(it, place) }) return AlertSpot.Inside
        var best: Pair<Double, GeoPoint>? = null
        rings.forEach { ring ->
            ring.zipWithNext().forEach { (a, b) ->
                val c = nearestOnSegment(place, a, b)
                val d = Solar.distanceKm(place.latitude, place.longitude, c.latitude, c.longitude)
                if (best == null || d < best!!.first) best = d to c
            }
        }
        val (km, target) = best ?: return null
        return AlertSpot.Outside(km, bearing(place, target))
    }

    /** Ray casting on longitude/latitude, fine at the size of a warning. */
    fun contains(ring: List<GeoPoint>, p: GeoPoint): Boolean {
        var inside = false
        var j = ring.lastIndex
        for (i in ring.indices) {
            val a = ring[i]
            val b = ring[j]
            if ((a.latitude > p.latitude) != (b.latitude > p.latitude) &&
                p.longitude < (b.longitude - a.longitude) * (p.latitude - a.latitude) / (b.latitude - a.latitude) + a.longitude
            ) inside = !inside
            j = i
        }
        return inside
    }

    /** The closest point on segment a–b, in a local flat projection around p. */
    private fun nearestOnSegment(p: GeoPoint, a: GeoPoint, b: GeoPoint): GeoPoint {
        val k = cos(toRadians(p.latitude))
        val ax = (a.longitude - p.longitude) * k
        val ay = a.latitude - p.latitude
        val bx = (b.longitude - p.longitude) * k
        val by = b.latitude - p.latitude
        val dx = bx - ax
        val dy = by - ay
        val len = dx * dx + dy * dy
        val t = if (len == 0.0) 0.0 else max(0.0, min(1.0, -(ax * dx + ay * dy) / len))
        return GeoPoint(a.latitude + (b.latitude - a.latitude) * t, a.longitude + (b.longitude - a.longitude) * t)
    }

    private fun bearing(from: GeoPoint, to: GeoPoint): Double {
        val k = cos(toRadians(from.latitude))
        val deg = toDegrees(atan2((to.longitude - from.longitude) * k, to.latitude - from.latitude))
        return (deg + 360) % 360
    }
}

/** "You're in the warning area" or "Warning area 6 mi east of you"; null without a drawn area. */
fun AlertSpot.describe(alert: WeatherAlert, distance: (Double) -> String): String {
    val kind = when {
        alert.event.endsWith("Warning", ignoreCase = true) -> "warning"
        alert.event.endsWith("Watch", ignoreCase = true) -> "watch"
        alert.event.endsWith("Advisory", ignoreCase = true) -> "advisory"
        else -> "alert"
    }
    return when (this) {
        AlertSpot.Inside -> "You're in the $kind area"
        is AlertSpot.Outside -> if (km < 1.5) "You're at the edge of the $kind area" else
            "${kind.replaceFirstChar { it.uppercase() }} area ${distance(km)} ${direction(compass)} of you"
    }
}

private fun direction(compass: String): String = when (compass) {
    "N" -> "north"; "NNE", "NE", "ENE" -> "northeast"; "E" -> "east"; "ESE", "SE", "SSE" -> "southeast"
    "S" -> "south"; "SSW", "SW", "WSW" -> "southwest"; "W" -> "west"; else -> "northwest"
}
