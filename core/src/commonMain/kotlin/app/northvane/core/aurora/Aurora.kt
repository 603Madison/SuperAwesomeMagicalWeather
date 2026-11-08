package app.northvane.core.aurora

import app.northvane.core.json.NorthvaneJson
import app.northvane.core.json.asObj
import app.northvane.core.json.dbl
import app.northvane.core.json.str
import app.northvane.core.model.AuroraNight
import app.northvane.core.model.AuroraReport
import app.northvane.core.model.AuroraView
import app.northvane.core.model.HourlyForecast
import app.northvane.core.model.KpForecast
import app.northvane.core.model.KpPoint
import app.northvane.core.net.HttpStatusException
import app.northvane.core.net.ParseException
import app.northvane.core.net.fetch
import app.northvane.core.util.Solar
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import app.northvane.core.net.HttpEngine
import app.northvane.core.net.Request
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import app.northvane.core.time.*
import app.northvane.core.util.toRadians
import app.northvane.core.util.toDegrees

/**
 * NOAA Space Weather Prediction Center's planetary K-index: three days of
 * observed and estimated values plus a three-day forecast in 3-hour blocks.
 * Free, keyless and about 7 KB; the same for every location.
 */
class AuroraClient(
    private val http: HttpEngine,
    private val url: String = DEFAULT_URL,
) {
    suspend fun forecast(now: Instant): KpForecast {
        val response = http.fetch(Request.Builder().url(url).build(), maxBytes = 512L * 1024)
        if (response.code !in 200..299) throw HttpStatusException(response.code, "NOAA Space Weather Prediction Center")
        return KpForecast(parse(response.body), now)
    }

    companion object {
        const val DEFAULT_URL = "https://services.swpc.noaa.gov/products/noaa-planetary-k-index-forecast.json"

        /** Accepts the current list of objects and the older table form (a header row, then rows). */
        fun parse(json: String): List<KpPoint> {
            val root = runCatching { NorthvaneJson.parseToJsonElement(json) as? JsonArray }.getOrNull()
                ?: throw ParseException("Unexpected response from NOAA's space weather service")
            val points = root.mapNotNull { row ->
                val o = row.asObj()
                if (o != null) {
                    point(o.str("time_tag"), o.dbl("kp") ?: o.str("kp")?.toDoubleOrNull(), o.str("observed"))
                } else {
                    val cells = (row as? JsonArray)?.map { (it as? JsonPrimitive)?.content } ?: return@mapNotNull null
                    point(cells.getOrNull(0), cells.getOrNull(1)?.toDoubleOrNull(), cells.getOrNull(2))
                }
            }
            if (points.isEmpty() && root.isNotEmpty()) throw ParseException("Unexpected response from NOAA's space weather service")
            return points.sortedBy { it.start }
        }

        private fun point(time: String?, kp: Double?, kind: String?): KpPoint? {
            if (time == null || kp == null || !kp.isFinite()) return null
            // Times are UTC without a zone ("2026-11-03T06:00:00", or with a space in older files).
            val start = runCatching { LocalDateTime.parse(time.trim().replace(' ', 'T')).toInstant(ZoneOffset.UTC) }.getOrNull() ?: return null
            val k = when (kind?.lowercase()) {
                "observed" -> KpPoint.Kind.OBSERVED
                "estimated" -> KpPoint.Kind.ESTIMATED
                else -> KpPoint.Kind.PREDICTED
            }
            return KpPoint(start, kp.coerceIn(0.0, 9.0), k)
        }
    }
}

/**
 * Turns the K-index into what you'd see from a given place. The auroral
 * oval's equatorward edge sits near geomagnetic latitude 66.5° at Kp 0 and
 * moves about 2° toward the equator per Kp step. Aurora high in the sky can
 * be seen low on the horizon from about 3° farther away, and phone cameras
 * pick up faint glow about 5° away.
 */
object AuroraAdvisor {
    // Geomagnetic north pole (IGRF-14 dipole, 2025).
    private const val POLE_LAT = 80.8
    private const val POLE_LON = -72.8
    private const val HORIZON_REACH = 3.0
    private const val CAMERA_REACH = 5.0
    private val BLOCK = Duration.ofHours(3)

    fun geomagneticLatitude(latitude: Double, longitude: Double): Double {
        val lat = toRadians(latitude)
        val pole = toRadians(POLE_LAT)
        val dLon = toRadians(longitude - POLE_LON)
        return toDegrees(asin(sin(lat) * sin(pole) + cos(lat) * cos(pole) * cos(dLon)))
    }

    /** Geomagnetic latitude of the oval's equatorward edge for [kp]. */
    fun ovalEdge(kp: Double): Double = 66.5 - 2.045 * kp

    fun view(kp: Double, geomagneticLatitude: Double): AuroraView {
        val lat = abs(geomagneticLatitude)
        val edge = ovalEdge(kp)
        return when {
            lat >= edge -> AuroraView.OVERHEAD
            lat >= edge - HORIZON_REACH -> AuroraView.HORIZON
            lat >= edge - CAMERA_REACH -> AuroraView.CAMERA
            else -> AuroraView.NONE
        }
    }

    /** Darkness: the sun more than 12° below the horizon (nautical twilight). */
    private fun dark(latitude: Double, longitude: Double, t: Instant) = Solar.elevationDegrees(latitude, longitude, t) < -12.0

    fun report(
        forecast: KpForecast,
        latitude: Double,
        longitude: Double,
        hourly: List<HourlyForecast>,
        zone: ZoneId,
        now: Instant,
    ): AuroraReport {
        val magLat = geomagneticLatitude(latitude, longitude)
        val points = forecast.points
        val today = now.atZone(zone).toLocalDate()
        val last = points.lastOrNull()?.start?.plus(BLOCK) ?: now
        val nights = generateSequence(today) { it.plusDays(1) }
            .takeWhile { !it.atTime(18, 0).atZone(zone).toInstant().isAfter(last) }
            .take(4)
            .mapNotNull { night(it, points, latitude, longitude, magLat, hourly, zone, now) }
            .toList()
        val current = points.lastOrNull { !it.start.isAfter(now) && it.start.plus(BLOCK).isAfter(now) }
        val nowView = if (current != null && dark(latitude, longitude, now)) view(current.kp, magLat) else AuroraView.NONE
        val gps = points.any { it.kp >= 7.0 && it.start.plus(BLOCK).isAfter(now) && it.start.isBefore(now.plus(Duration.ofHours(24))) }
        return AuroraReport(nights, magLat, current?.kp, nowView, gps)
    }

    private fun night(
        date: LocalDate,
        points: List<KpPoint>,
        latitude: Double,
        longitude: Double,
        magLat: Double,
        hourly: List<HourlyForecast>,
        zone: ZoneId,
        now: Instant,
    ): AuroraNight? {
        val dusk = date.atTime(16, 0).atZone(zone).toInstant()
        val dawn = date.plusDays(1).atTime(8, 0).atZone(zone).toInstant()
        // Dark, still-ahead times in 30-minute steps.
        val darkTimes = generateSequence(dusk) { it.plus(Duration.ofMinutes(30)) }
            .takeWhile { it.isBefore(dawn) }
            .filter { it.plus(Duration.ofMinutes(30)).isAfter(now) && dark(latitude, longitude, it) }
            .toList()
        if (darkTimes.isEmpty()) return null
        val overlapping = points.filter { p -> darkTimes.any { t -> !t.isBefore(p.start) && t.isBefore(p.start.plus(BLOCK)) } }
        val peak = overlapping.maxByOrNull { it.kp } ?: return null
        val clouds = darkTimes.mapNotNull { t -> hourly.firstOrNull { h -> !t.isBefore(h.time) && t.isBefore(h.time.plus(Duration.ofHours(1))) }?.skyCoverPercent }
            .takeIf { it.size >= darkTimes.size / 3 }
            ?.average()?.roundToInt()
        val peakAt = maxOf(peak.start, darkTimes.first())
        return AuroraNight(date, peak.kp, view(peak.kp, magLat), peakAt, clouds)
    }
}

/** Something worth a notification: aurora visible now, or expected on a coming night. */
data class AuroraHeadsUp(
    /** Identifies the night (and whether it's happening now), to notify once per night and level. */
    val key: String,
    val now: Boolean,
    val night: AuroraNight?,
    val view: AuroraView,
    val kp: Double,
)

object AuroraAlerts {
    /** Nights whose best viewing is within this far ahead get a heads-up. */
    val LEAD: Duration = Duration.ofHours(30)
    /** Skip nights the hourly forecast says will be overcast. */
    private const val OVERCAST = 85

    /** The one event to notify about, if any reaches [threshold]. Happening now beats later nights. */
    fun pick(report: AuroraReport, threshold: AuroraView, now: Instant, zone: ZoneId): AuroraHeadsUp? {
        if (report.nowView >= threshold && report.nowKp != null) {
            // Evenings belong to today's night; small hours to the night before.
            val night = now.atZone(zone).let { if (it.hour < 12) it.toLocalDate().minusDays(1) else it.toLocalDate() }
            return AuroraHeadsUp("now-$night", true, report.nights.firstOrNull { it.date == night }, report.nowView, report.nowKp)
        }
        val night = report.nights.firstOrNull { n ->
            n.view >= threshold && (n.cloudPercent ?: 0) < OVERCAST &&
                (n.peakAt ?: n.date.atTime(21, 0).atZone(zone).toInstant()).isBefore(now.plus(LEAD))
        } ?: return null
        return AuroraHeadsUp("night-${night.date}", false, night, night.view, night.maxKp)
    }

    /** Notify for a key the first time, and again only if the expected view improves. */
    fun isNew(headsUp: AuroraHeadsUp, alreadyNotified: AuroraView?): Boolean =
        alreadyNotified == null || headsUp.view > alreadyNotified
}
