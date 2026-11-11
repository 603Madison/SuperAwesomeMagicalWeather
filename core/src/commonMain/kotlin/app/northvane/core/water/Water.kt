package app.northvane.core.water

import app.northvane.core.json.InstantSerializer
import app.northvane.core.json.NorthvaneJson
import app.northvane.core.json.arr
import app.northvane.core.json.asObj
import app.northvane.core.json.dbl
import app.northvane.core.json.obj
import app.northvane.core.json.str
import app.northvane.core.model.GeoPoint
import app.northvane.core.net.HttpStatusException
import app.northvane.core.net.ParseException
import app.northvane.core.net.WeatherApiException
import app.northvane.core.net.fetch
import app.northvane.core.repo.attempt
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import app.northvane.core.net.HttpUrl.Companion.toHttpUrl
import app.northvane.core.net.HttpEngine
import app.northvane.core.net.Request
import app.northvane.core.util.SyncMap
import app.northvane.core.time.*
import app.northvane.core.util.fmt

/** The nearest USGS stream gauge's latest readings. */
@Serializable
data class RiverGauge(
    val siteId: String,
    val name: String,
    val distanceKm: Double,
    val dischargeCfs: Double? = null,
    val gageHeightFt: Double? = null,
    val waterTempC: Double? = null,
    @Serializable(InstantSerializer::class) val observedAt: Instant? = null,
    /** Change in gauge height over the last ~6 hours, in feet (positive: rising). */
    val heightChangeFt: Double? = null,
) {
    /** Rising fast enough to matter for paddlers: half a foot in six hours. */
    val rising: Boolean get() = (heightChangeFt ?: 0.0) >= 0.5
}

@Serializable
data class TideEvent(@Serializable(InstantSerializer::class) val time: Instant, val high: Boolean, val heightM: Double)

/**
 * High and low tides at the nearest tide station, from about half a day ago
 * (so the tide now can be worked out) to two days ahead. NOAA in the US, the
 * Canadian Hydrographic Service in Canada.
 */
@Serializable
data class TideOutlook(
    val stationId: String,
    val name: String,
    val distanceKm: Double,
    val events: List<TideEvent>,
    val waterTempC: Double? = null,
    val source: TideSource = TideSource.NOAA,
) {
    /** Close enough that the station's tides are this place's tides. */
    val nearby: Boolean get() = distanceKm <= NEARBY_KM

    companion object {
        /** Tides change a lot within a few miles up an estuary or around a headland. */
        const val NEARBY_KM = 15.0
    }
}

@Serializable
enum class TideSource(val label: String) { NOAA("NOAA"), CHS("Canadian Hydrographic Service") }

@Serializable
data class WaterReport(
    @Serializable(InstantSerializer::class) val fetchedAt: Instant,
    val river: RiverGauge? = null,
    val tide: TideOutlook? = null,
) {
    /** Measured water temperature nearby, preferring the river gauge. */
    val waterTempC: Double? get() = river?.waterTempC ?: tide?.waterTempC
}

/**
 * Rivers from the USGS Water Data API (the OGC service that replaces the old
 * WaterServices) and tides from NOAA Tides & Currents. Both are free and need
 * no key. One refresh is about 15–30 KB.
 */
class WaterClient(
    private val http: HttpEngine,
    private val usgsBase: String = USGS_BASE,
    private val coopsBase: String = COOPS_BASE,
    private val chsBase: String = CHS_BASE,
) {
    /** Site names hardly ever change: looked up once per gauge while the app runs. */
    private val siteNames = SyncMap<String, String>()

    /**
     * Rivers (US only) when [rivers] is set, and tides when [tides] is set:
     * NOAA's in the US, or the Canadian Hydrographic Service's when [canada].
     */
    suspend fun report(point: GeoPoint, now: Instant, rivers: Boolean = true, tides: Boolean = true, canada: Boolean = false): WaterReport = coroutineScope {
        val river = if (rivers && !canada) async { attempt { river(point, now) } } else null
        val tide = if (tides) async { attempt { if (canada) canadianTide(point, now) else tide(point, now) } } else null
        val r = river?.await()
        val t = tide?.await()
        val failures = listOfNotNull(r, t)
        if (failures.isNotEmpty() && failures.all { it.isFailure }) throw failures.first().exceptionOrNull()!!
        WaterReport(now, r?.getOrNull(), t?.getOrNull())
    }

    suspend fun river(point: GeoPoint, now: Instant): RiverGauge? {
        val bbox = "%.3f,%.3f,%.3f,%.3f".fmt(point.longitude - 0.35, point.latitude - 0.25, point.longitude + 0.35, point.latitude + 0.25)
        val url = "$usgsBase/collections/latest-continuous/items".toHttpUrl().newBuilder()
            .addQueryParameter("f", "json")
            .addQueryParameter("bbox", bbox)
            .addQueryParameter("parameter_code", "$DISCHARGE,$GAGE_HEIGHT,$WATER_TEMP")
            .addQueryParameter("limit", "200")
            .build()
        val gauge = parseLatest(get(url.toString(), "USGS"), point, now) ?: return null
        val name = siteNames[gauge.siteId] ?: attempt { siteName(gauge.siteId) }.getOrNull()?.also { siteNames[gauge.siteId] = it }
        val change = if (gauge.gageHeightFt != null) attempt { heightChange(gauge.siteId) }.getOrNull() else null
        return gauge.copy(name = name ?: gauge.name, heightChangeFt = change)
    }

    private suspend fun siteName(siteId: String): String? {
        val url = "$usgsBase/collections/monitoring-locations/items".toHttpUrl().newBuilder()
            .addQueryParameter("f", "json").addQueryParameter("id", siteId).build()
        return parseSiteName(get(url.toString(), "USGS"))
    }

    private suspend fun heightChange(siteId: String): Double? {
        val url = "$usgsBase/collections/continuous/items".toHttpUrl().newBuilder()
            .addQueryParameter("f", "json")
            .addQueryParameter("monitoring_location_id", siteId)
            .addQueryParameter("parameter_code", GAGE_HEIGHT)
            .addQueryParameter("time", "PT6H")
            .addQueryParameter("properties", "time,value")
            .addQueryParameter("skipGeometry", "true")
            .addQueryParameter("limit", "100")
            .build()
        return parseChange(get(url.toString(), "USGS"))
    }

    suspend fun tide(point: GeoPoint, now: Instant): TideOutlook? {
        val stations = "$coopsBase/mdapi/prod/webapi/tidepredstations.json".toHttpUrl().newBuilder()
            .addQueryParameter("lat", "%.4f".fmt(point.latitude))
            .addQueryParameter("lon", "%.4f".fmt(point.longitude))
            .addQueryParameter("radius", "25") // miles
            .build()
        val station = parseNearestStation(get(stations.toString(), "NOAA Tides"), point) ?: return null
        val predictions = "$coopsBase/api/prod/datagetter".toHttpUrl().newBuilder()
            .addQueryParameter("product", "predictions")
            .addQueryParameter("datum", "MLLW")
            .addQueryParameter("station", station.id)
            // From before the last high or low, so the tide now can be worked out.
            .addQueryParameter("begin_date", BEGIN.format(now.minusSeconds(LOOKBACK_HOURS * 3600L).atOffset(ZoneOffset.UTC)))
            .addQueryParameter("range", "${LOOKBACK_HOURS + 48}")
            .addQueryParameter("interval", "hilo")
            .addQueryParameter("units", "metric")
            .addQueryParameter("time_zone", "gmt")
            .addQueryParameter("format", "json")
            .addQueryParameter("application", "SuperAwesomeMagicalWeather")
            .build()
        val events = parsePredictions(get(predictions.toString(), "NOAA Tides"))
        // Reference stations have sensors; subordinate ones are predictions only.
        val temp = if (station.reference) attempt { waterTemp(station.id) }.getOrNull() else null
        return TideOutlook(station.id, station.name, station.distanceKm, events, temp)
    }

    /** Canadian stations with tide predictions; the list is large (~750 KB), so it's read once per run. */
    private var canadianStations: List<TideStation>? = null

    suspend fun canadianTide(point: GeoPoint, now: Instant): TideOutlook? {
        val stations = canadianStations ?: run {
            val url = "$chsBase/api/v1/stations".toHttpUrl().newBuilder().addQueryParameter("time-series-code", "wlp-hilo").build()
            val response = http.fetch(Request.Builder().url(url.toString()).build(), maxBytes = 3L * 1024 * 1024)
            if (response.code !in 200..299) throw HttpStatusException(response.code, "Canadian Hydrographic Service")
            parseCanadianStations(response.body).also { canadianStations = it }
        }
        val station = stations.map { it.copy(distanceKm = point.distanceKm(GeoPoint(it.latitude, it.longitude))) }
            .filter { it.distanceKm <= MAX_TIDE_KM }.minByOrNull { it.distanceKm } ?: return null
        val iso = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
        val url = "$chsBase/api/v1/stations/${station.id}/data".toHttpUrl().newBuilder()
            .addQueryParameter("time-series-code", "wlp-hilo")
            .addQueryParameter("from", iso.format(now.minusSeconds(LOOKBACK_HOURS * 3600L).atOffset(ZoneOffset.UTC)))
            .addQueryParameter("to", iso.format(now.plusSeconds(48 * 3600L).atOffset(ZoneOffset.UTC)))
            .build()
        val events = parseCanadianPredictions(get(url.toString(), "Canadian Hydrographic Service"))
        return TideOutlook(station.id, station.name, station.distanceKm, events, source = TideSource.CHS)
    }

    private suspend fun waterTemp(stationId: String): Double? {
        val url = "$coopsBase/api/prod/datagetter".toHttpUrl().newBuilder()
            .addQueryParameter("product", "water_temperature")
            .addQueryParameter("date", "latest")
            .addQueryParameter("station", stationId)
            .addQueryParameter("units", "metric")
            .addQueryParameter("time_zone", "gmt")
            .addQueryParameter("format", "json")
            .addQueryParameter("application", "SuperAwesomeMagicalWeather")
            .build()
        return parseLatestValue(get(url.toString(), "NOAA Tides"))
    }

    private suspend fun get(url: String, service: String): String {
        val response = http.fetch(Request.Builder().url(url).build(), maxBytes = 512L * 1024)
        if (response.code !in 200..299) throw HttpStatusException(response.code, service)
        return response.body
    }

    internal data class TideStation(
        val id: String,
        val name: String,
        val distanceKm: Double,
        val reference: Boolean,
        val latitude: Double = 0.0,
        val longitude: Double = 0.0,
    )

    companion object {
        const val USGS_BASE = "https://api.waterdata.usgs.gov/ogcapi/v0"
        const val COOPS_BASE = "https://api.tidesandcurrents.noaa.gov"
        const val CHS_BASE = "https://api-iwls.dfo-mpo.gc.ca"
        /** Hours of past predictions to fetch: enough to include the last high or low. */
        private const val LOOKBACK_HOURS = 13
        private const val DISCHARGE = "00060"
        private const val GAGE_HEIGHT = "00065"
        private const val WATER_TEMP = "00010"
        /** Farther than this, a gauge says little about the water here. */
        private const val MAX_RIVER_KM = 30.0
        private const val MAX_TIDE_KM = 40.0
        private val BEGIN: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd HH:mm")
        private val COOPS_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

        private fun root(json: String, service: String) =
            runCatching { NorthvaneJson.parseToJsonElement(json).asObj() }.getOrNull() ?: throw ParseException("Unexpected response from $service")

        /** The nearest stream gauge with a discharge or height reading from the last two days. */
        fun parseLatest(json: String, point: GeoPoint, now: Instant): RiverGauge? {
            data class Reading(val site: String, val code: String, val value: Double, val time: Instant, val at: GeoPoint)
            val readings = root(json, "USGS").arr("features").orEmpty().mapNotNull { f ->
                val feature = f.asObj() ?: return@mapNotNull null
                val p = feature.obj("properties") ?: return@mapNotNull null
                val coords = feature.obj("geometry")?.arr("coordinates")?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull }
                val at = coords?.takeIf { it.size >= 2 && it[1] in -90.0..90.0 && it[0] in -180.0..180.0 }?.let { GeoPoint(it[1], it[0]) }
                    ?: return@mapNotNull null
                val time = p.str("time")?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() } ?: return@mapNotNull null
                // Many gauges in the list were retired decades ago.
                if (Duration.between(time, now) > Duration.ofDays(2)) return@mapNotNull null
                val value = p.str("value")?.toDoubleOrNull() ?: return@mapNotNull null
                Reading(p.str("monitoring_location_id") ?: return@mapNotNull null, p.str("parameter_code") ?: return@mapNotNull null, value, time, at)
            }
            val site = readings.filter { it.code == DISCHARGE || it.code == GAGE_HEIGHT }
                .minByOrNull { point.distanceKm(it.at) }
                ?.takeIf { point.distanceKm(it.at) <= MAX_RIVER_KM }
                ?: return null
            val mine = readings.filter { it.site == site.site }
            fun value(code: String) = mine.filter { it.code == code }.maxByOrNull { it.time }?.value
            return RiverGauge(
                siteId = site.site,
                name = site.site.removePrefix("USGS-"),
                distanceKm = point.distanceKm(site.at),
                dischargeCfs = value(DISCHARGE),
                gageHeightFt = value(GAGE_HEIGHT),
                waterTempC = value(WATER_TEMP),
                observedAt = mine.maxOf { it.time },
            )
        }

        fun parseSiteName(json: String): String? = root(json, "USGS").let { r ->
            (r.arr("features")?.firstOrNull()?.asObj() ?: r).obj("properties")?.str("monitoring_location_name")
        }?.let(::tidyName)

        /** "ROYAL RIVER AT YARMOUTH, MAINE" style names, made readable. */
        internal fun tidyName(name: String): String =
            if (name.any { it.isLowerCase() }) name else name.lowercase().split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

        /** Last minus first gauge height in a short series. */
        fun parseChange(json: String): Double? {
            val points = root(json, "USGS").arr("features").orEmpty().mapNotNull { f ->
                val p = f.asObj()?.obj("properties") ?: return@mapNotNull null
                val time = p.str("time")?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() } ?: return@mapNotNull null
                val value = p.str("value")?.toDoubleOrNull() ?: return@mapNotNull null
                time to value
            }.sortedBy { it.first }
            if (points.size < 2) return null
            return points.last().second - points.first().second
        }

        internal fun parseNearestStation(json: String, point: GeoPoint): TideStation? =
            root(json, "NOAA Tides").arr("stationList").orEmpty().mapNotNull { s ->
                val o = s.asObj() ?: return@mapNotNull null
                val lat = o.dbl("lat") ?: return@mapNotNull null
                val lon = o.dbl("lon") ?: return@mapNotNull null
                if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return@mapNotNull null
                val id = o.str("stationId")?.takeIf { it.all(Char::isLetterOrDigit) } ?: return@mapNotNull null
                val name = o.str("commonName") ?: o.str("stationName") ?: o.str("name") ?: id
                TideStation(id, tidyName(name), point.distanceKm(GeoPoint(lat, lon)), reference = o.str("stationType") == "R")
            }.filter { it.distanceKm <= MAX_TIDE_KM }.minByOrNull { it.distanceKm }

        fun parsePredictions(json: String): List<TideEvent> {
            val r = root(json, "NOAA Tides")
            r.obj("error")?.str("message")?.let { throw WeatherApiException("NOAA Tides: $it") }
            return r.arr("predictions").orEmpty().mapNotNull { e ->
                val o = e.asObj() ?: return@mapNotNull null
                val time = o.str("t")?.let { runCatching { LocalDateTime.parse(it, COOPS_TIME).toInstant(ZoneOffset.UTC) }.getOrNull() } ?: return@mapNotNull null
                val height = o.str("v")?.toDoubleOrNull() ?: return@mapNotNull null
                TideEvent(time, high = o.str("type") == "H", heightM = height)
            }
        }

        internal fun parseCanadianStations(json: String): List<TideStation> {
            val list = runCatching { NorthvaneJson.parseToJsonElement(json) as? kotlinx.serialization.json.JsonArray }.getOrNull()
                ?: throw ParseException("Unexpected response from the Canadian Hydrographic Service")
            return list.mapNotNull { s ->
                val o = s.asObj() ?: return@mapNotNull null
                val lat = o.dbl("latitude") ?: return@mapNotNull null
                val lon = o.dbl("longitude") ?: return@mapNotNull null
                if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return@mapNotNull null
                // Ids go into a URL path: hex only.
                val id = o.str("id")?.takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F' } } ?: return@mapNotNull null
                val name = o.str("officialName") ?: return@mapNotNull null
                TideStation(id, tidyName(name), 0.0, reference = false, latitude = lat, longitude = lon)
            }
        }

        /** CHS lists the highs and lows without saying which is which: a high is above its neighbours. */
        fun parseCanadianPredictions(json: String): List<TideEvent> {
            val list = runCatching { NorthvaneJson.parseToJsonElement(json) as? kotlinx.serialization.json.JsonArray }.getOrNull()
                ?: throw ParseException("Unexpected response from the Canadian Hydrographic Service")
            val points = list.mapNotNull { e ->
                val o = e.asObj() ?: return@mapNotNull null
                val time = o.str("eventDate")?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return@mapNotNull null
                val value = o.dbl("value") ?: return@mapNotNull null
                time to value
            }.sortedBy { it.first }
            return points.mapIndexed { i, (time, value) ->
                val neighbour = points.getOrNull(i + 1)?.second ?: points.getOrNull(i - 1)?.second ?: value
                TideEvent(time, high = value > neighbour, heightM = value)
            }
        }

        fun parseLatestValue(json: String): Double? =
            root(json, "NOAA Tides").arr("data")?.firstOrNull()?.asObj()?.str("v")?.toDoubleOrNull()
    }
}
