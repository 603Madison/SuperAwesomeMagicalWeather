package app.northvane.core.history

import app.northvane.core.repo.attempt
import app.northvane.core.json.NorthvaneJson
import app.northvane.core.json.arr
import app.northvane.core.json.asObj
import app.northvane.core.json.dbl
import app.northvane.core.json.obj
import app.northvane.core.json.str
import app.northvane.core.model.GeoPoint
import app.northvane.core.model.ObservedDay
import app.northvane.core.model.Units
import app.northvane.core.model.WeatherHistory
import app.northvane.core.net.HttpStatusException
import app.northvane.core.net.ParseException
import app.northvane.core.net.WeatherApiException
import app.northvane.core.net.fetch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import app.northvane.core.net.HttpEngine
import app.northvane.core.net.Request
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import app.northvane.core.time.*
import app.northvane.core.util.fmt
import app.northvane.core.util.toRadians

/**
 * Recent observed weather from NOAA's Regional Climate Centers (ACIS). Free
 * and keyless. The gridded analysis interpolates thousands of COOP, CoCoRaHS
 * and airport reports into daily highs, lows and precipitation for any point
 * in the contiguous US; snow depth comes from the nearest reporting station.
 */
class AcisClient(
    private val http: HttpEngine,
    private val baseUrl: String = DEFAULT_BASE_URL,
) {
    suspend fun history(point: GeoPoint, today: LocalDate, days: Int = DEFAULT_DAYS, includeSnowDepth: Boolean, now: Instant): WeatherHistory {
        val p = point.rounded()
        val grid = post(
            "GridData",
            buildJsonObject {
                put("loc", "${GeoPoint.format(p.longitude)},${GeoPoint.format(p.latitude)}")
                put("sdate", today.minusDays(days.toLong()).toString())
                put("edate", today.toString())
                put("grid", "1")
                put("elems", "maxt,mint,pcpn")
                put("meta", "elev")
            }.toString(),
        )
        val (observed, elevationFt) = parseGrid(grid)
        val snow = if (includeSnowDepth) {
            attempt { parseSnowDepth(post("MultiStnData", snowRequest(p, today)), p) }.getOrNull()
        } else {
            null
        }
        return WeatherHistory(
            days = observed,
            snowDepthMm = snow?.first,
            snowDepthStation = snow?.second,
            elevationM = elevationFt?.let { it * 0.3048 },
            fetchedAt = now,
        )
    }

    /**
     * Daily lows (°F) for the last [years] full years from the PRISM grid,
     * for frost-date climatology. About 40 KB compressed; cache the result.
     */
    suspend fun dailyLows(point: GeoPoint, lastYear: Int, years: Int = 30): List<Pair<LocalDate, Double>> {
        val p = point.rounded()
        val body = buildJsonObject {
            put("loc", "${GeoPoint.format(p.longitude)},${GeoPoint.format(p.latitude)}")
            put("sdate", "${lastYear - years + 1}-01-01")
            put("edate", "$lastYear-12-31")
            put("grid", "21")
            put("elems", "mint")
        }.toString()
        return parseDailyLows(post("GridData", body, maxBytes = 2L * 1024 * 1024))
    }

    private fun snowRequest(p: GeoPoint, today: LocalDate): String = buildJsonObject {
        // ~25 km box; reports come from airports, COOP observers and CoCoRaHS volunteers.
        val dLat = 0.25
        val dLon = 0.25 / cos(toRadians(p.latitude)).coerceAtLeast(0.2)
        put("bbox", "%.3f,%.3f,%.3f,%.3f".fmt(p.longitude - dLon, p.latitude - dLat, p.longitude + dLon, p.latitude + dLat))
        put("sdate", today.minusDays(2).toString())
        put("edate", today.toString())
        put("elems", "snwd")
        put("meta", "name,ll")
    }.toString()

    private suspend fun post(endpoint: String, body: String, maxBytes: Long = 512L * 1024): String {
        val request = Request.Builder()
            .url("$baseUrl/$endpoint")
            .post(body, JSON)
            .build()
        val response = http.fetch(request, maxBytes = maxBytes)
        if (response.code !in 200..299) throw HttpStatusException(response.code, "NOAA ACIS")
        return response.body
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://data.rcc-acis.org"
        /** Enough for fall foliage (cool nights since September) and spring thaw (winter freeze). */
        const val DEFAULT_DAYS = 60
        private const val JSON = "application/json"
        private const val MISSING = -999.0

        fun parseGrid(json: String): Pair<List<ObservedDay>, Double?> {
            val root = runCatching { NorthvaneJson.parseToJsonElement(json).asObj() }.getOrNull()
                ?: throw ParseException("Unexpected response from NOAA ACIS")
            root.str("error")?.let { throw HistoryUnavailableException() }
            val rows = root.arr("data") ?: throw HistoryUnavailableException()
            val days = rows.mapNotNull { row ->
                val cells = row as? JsonArray ?: return@mapNotNull null
                val date = runCatching { LocalDate.parse((cells[0] as JsonPrimitive).content) }.getOrNull() ?: return@mapNotNull null
                fun value(i: Int) = (cells.getOrNull(i) as? JsonPrimitive)?.content?.toDoubleOrNull()?.takeIf { it > MISSING }
                ObservedDay(
                    date = date,
                    highC = value(1)?.let(Units::fToC),
                    lowC = value(2)?.let(Units::fToC),
                    precipMm = value(3)?.let(Units::inchesToMm),
                )
            }.filter { it.highC != null || it.lowC != null || it.precipMm != null }
            if (days.isEmpty()) throw HistoryUnavailableException()
            // Coastal cells over water report -32768 (missing).
            return days to root.obj("meta")?.dbl("elev")?.takeIf { it in -300.0..21_000.0 }
        }

        fun parseDailyLows(json: String): List<Pair<LocalDate, Double>> {
            val root = runCatching { NorthvaneJson.parseToJsonElement(json).asObj() }.getOrNull()
                ?: throw ParseException("Unexpected response from NOAA ACIS")
            root.str("error")?.let { throw HistoryUnavailableException() }
            return root.arr("data").orEmpty().mapNotNull { row ->
                val cells = row as? JsonArray ?: return@mapNotNull null
                val date = runCatching { LocalDate.parse((cells[0] as JsonPrimitive).content) }.getOrNull() ?: return@mapNotNull null
                val v = (cells.getOrNull(1) as? JsonPrimitive)?.content?.toDoubleOrNull()?.takeIf { it > MISSING } ?: return@mapNotNull null
                date to v
            }
        }

        /** Most recent snow depth at the nearest station that reported one: (mm, station name). */
        internal fun parseSnowDepth(json: String, near: GeoPoint): Pair<Double, String?>? {
            val stations = NorthvaneJson.parseToJsonElement(json).asObj()?.arr("data") ?: return null
            return stations.mapNotNull { element ->
                val s = element.asObj() ?: return@mapNotNull null
                val meta = s.obj("meta") ?: return@mapNotNull null
                val ll = meta.arr("ll")?.mapNotNull { (it as? JsonPrimitive)?.content?.toDoubleOrNull() }
                    ?.takeIf { it.size == 2 } ?: return@mapNotNull null
                val latest = s.arr("data")?.reversed()?.firstNotNullOfOrNull { row ->
                    val cell = ((row as? JsonArray)?.firstOrNull() as? JsonPrimitive)?.content
                    when (cell) {
                        null, "M", "S", "" -> null
                        "T" -> 0.0
                        else -> cell.removeSuffix("A").toDoubleOrNull()
                    }
                } ?: return@mapNotNull null
                val distance = hypot(ll[1] - near.latitude, (ll[0] - near.longitude) * cos(toRadians(near.latitude)))
                Triple(distance, Units.inchesToMm(latest), meta.str("name"))
            }.minByOrNull { abs(it.first) }?.let { it.second to it.third }
        }
    }
}

/** ACIS's gridded analysis covers the contiguous US only (not Alaska, Hawaii or territories). */
class HistoryUnavailableException : WeatherApiException("Recent weather isn't available for this location")
