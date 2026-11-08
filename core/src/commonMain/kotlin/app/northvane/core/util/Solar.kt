package app.northvane.core.util

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import app.northvane.core.time.*
import app.northvane.core.util.toRadians
import app.northvane.core.util.toDegrees

/**
 * Low-precision solar position (Astronomical Almanac algorithm), accurate to
 * a minute or two — enough for day/night styling and sunrise/sunset display
 * without another network request.
 */
object Solar {
    private const val HORIZON_DEG = -0.833 // refraction + solar disc radius

    fun elevationDegrees(latitude: Double, longitude: Double, instant: Instant): Double {
        val jd = instant.epochSecond / 86400.0 + 2440587.5
        val n = jd - 2451545.0
        val meanLongitude = normalizeDegrees(280.460 + 0.9856474 * n)
        val meanAnomaly = toRadians(normalizeDegrees(357.528 + 0.9856003 * n))
        val eclipticLongitude = toRadians(
            meanLongitude + 1.915 * sin(meanAnomaly) + 0.020 * sin(2 * meanAnomaly),
        )
        val obliquity = toRadians(23.439 - 0.0000004 * n)
        val rightAscension = atan2(cos(obliquity) * sin(eclipticLongitude), cos(eclipticLongitude))
        val declination = asin(sin(obliquity) * sin(eclipticLongitude))
        val gmstHours = normalize(18.697374558 + 24.06570982441908 * n, 24.0)
        val hourAngle = toRadians(gmstHours * 15.0 + longitude) - rightAscension
        val lat = toRadians(latitude)
        val elevation = asin(sin(lat) * sin(declination) + cos(lat) * cos(declination) * cos(hourAngle))
        return toDegrees(elevation)
    }

    fun isDaylight(latitude: Double, longitude: Double, instant: Instant): Boolean =
        elevationDegrees(latitude, longitude, instant) > HORIZON_DEG

    /** Sunrise and sunset on [date] in [zone]; null when the sun doesn't rise or set that day. */
    fun sunTimes(latitude: Double, longitude: Double, date: LocalDate, zone: ZoneId): Pair<Instant?, Instant?> {
        val start = date.atStartOfDay(zone).toInstant()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant()
        var sunrise: Instant? = null
        var sunset: Instant? = null
        var t = start
        var previous = elevationDegrees(latitude, longitude, t) - HORIZON_DEG
        val step = Duration.ofMinutes(5)
        while (t.isBefore(end)) {
            val next = t.plus(step)
            val value = elevationDegrees(latitude, longitude, next) - HORIZON_DEG
            if (previous <= 0 && value > 0 && sunrise == null) sunrise = refine(latitude, longitude, t, next, rising = true)
            if (previous > 0 && value <= 0 && sunset == null) sunset = refine(latitude, longitude, t, next, rising = false)
            previous = value
            t = next
        }
        return sunrise to sunset
    }

    private fun refine(lat: Double, lon: Double, from: Instant, to: Instant, rising: Boolean): Instant {
        var lo = from
        var hi = to
        repeat(12) {
            val mid = lo.plus(Duration.between(lo, hi).dividedBy(2))
            val above = elevationDegrees(lat, lon, mid) > HORIZON_DEG
            if (above == rising) hi = mid else lo = mid
        }
        return hi
    }

    /** Great-circle distance in kilometres (haversine). */
    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = toRadians(lat2 - lat1)
        val dLon = toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(toRadians(lat1)) * cos(toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 6371.0 * 2 * atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
    }

    private fun normalizeDegrees(v: Double) = normalize(v, 360.0)
    private fun normalize(v: Double, range: Double) = ((v % range) + range) % range
}
