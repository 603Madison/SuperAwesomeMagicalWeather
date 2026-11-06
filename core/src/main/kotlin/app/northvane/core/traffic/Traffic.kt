package app.northvane.core.traffic

import app.northvane.core.json.InstantSerializer
import app.northvane.core.json.NorthvaneJson
import app.northvane.core.json.arr
import app.northvane.core.json.asObj
import app.northvane.core.json.bool
import app.northvane.core.json.obj
import app.northvane.core.json.str
import app.northvane.core.model.GeoPoint
import app.northvane.core.net.HttpStatusException
import app.northvane.core.net.InvalidCredentialsException
import app.northvane.core.net.ParseException
import app.northvane.core.net.RateLimitedException
import app.northvane.core.net.fetch
import app.northvane.core.net.toUserMessage
import app.northvane.core.repo.attempt
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

@Serializable
enum class TrafficEventType(val label: String) {
    CRASH("Crash or incident"),
    CLOSURE("Road closed"),
    WORK_ZONE("Work zone"),
    CONGESTION("Congestion"),
    SPECIAL_EVENT("Special event"),
    ROAD_CONDITIONS("Road conditions"),
    OTHER("Traffic notice"),
}

@Serializable
enum class LaneImpact(val label: String) {
    ALL_CLOSED("All lanes closed"),
    SOME_CLOSED("Lanes closed"),
    OPEN("Lanes open"),
    UNKNOWN(""),
}

@Serializable
data class TrafficEvent(
    val id: String,
    val type: TrafficEventType,
    val road: String?,
    val direction: String?,
    val description: String?,
    val impact: LaneImpact,
    @Serializable(InstantSerializer::class) val start: Instant?,
    @Serializable(InstantSerializer::class) val end: Instant?,
    val latitude: Double,
    val longitude: Double,
    /** Distance from the selected location to the nearest point of the event. */
    val distanceKm: Double,
    /** Simplified line for drawing on the map (work zones span a stretch of road). */
    val path: List<List<Double>> = emptyList(),
    val source: String,
) {
    fun isActive(now: Instant): Boolean = (start?.let { !it.isAfter(now) } ?: true) && (end?.isAfter(now) ?: true)
}

@Serializable
data class TrafficSnapshot(
    val events: List<TrafficEvent>,
    val sources: List<String>,
    @Serializable(InstantSerializer::class) val fetchedAt: Instant,
    val issues: List<String> = emptyList(),
)

/** One entry in USDOT's Work Zone Data Exchange (WZDx) feed registry. */
@Serializable
data class WzdxFeed(val organization: String, val states: String, val url: String)

/**
 * Road events from government sources:
 *
 * - **Work zones** from state DOT feeds in the USDOT **WZDx** standard, found
 *   through USDOT's public feed registry (ITS DataHub). Only keyless feeds are
 *   used; New Hampshire, Vermont and Maine publish one together.
 * - **Crashes, closures and incidents** from **New England 511** (NHDOT,
 *   MaineDOT and VTrans), which needs a free developer key.
 *
 * Live traffic *speeds* aren't published by any government source, so the
 * optional flow layer uses TomTom's traffic tiles with the user's own key
 * ([tomTomFlowTiles]).
 */
class TrafficClient(
    private val http: OkHttpClient,
    private val registryUrl: String = REGISTRY_URL,
    private val ne511Base: String = NE511_BASE,
) {
    suspend fun registry(): List<WzdxFeed> = parseRegistry(get(registryUrl, "USDOT WZDx registry", 1024L * 1024))

    /** Keyless WZDx feeds covering the US state [stateCode] (e.g. "ME"). */
    fun feedsFor(stateCode: String?, registry: List<WzdxFeed>): List<WzdxFeed> {
        val name = STATES[stateCode?.uppercase()] ?: return emptyList()
        return registry.filter { feed -> feed.states.lowercase().split(',').any { it.trim() == name } }
    }

    suspend fun workZones(feed: WzdxFeed, center: GeoPoint, radiusKm: Double, now: Instant): List<TrafficEvent> =
        parseWzdx(get(feed.url, feed.organization, 12L * 1024 * 1024), center, radiusKm, now, feed.organization)

    suspend fun ne511Events(apiKey: String, center: GeoPoint, radiusKm: Double, now: Instant): List<TrafficEvent> {
        val url = ne511Base.toHttpUrl().newBuilder()
            .addPathSegments("api/v2/get/event")
            .addQueryParameter("format", "json")
            .addQueryParameter("key", apiKey)
            .build()
        return parse511(get(url.toString(), "New England 511", 8L * 1024 * 1024, noStore = true), center, radiusKm, now, "New England 511")
    }

    private suspend fun get(url: String, service: String, maxBytes: Long, noStore: Boolean = false): String {
        val request = Request.Builder().url(url).apply { if (noStore) header("Cache-Control", "no-store") }.build()
        val response = http.fetch(request, maxBytes)
        return when {
            response.code in 200..299 -> response.body
            response.code == 401 || response.code == 403 ||
                (response.code == 400 && response.body.contains("Invalid Key", ignoreCase = true)) -> throw InvalidCredentialsException(service)
            response.code == 429 -> throw RateLimitedException(service)
            else -> throw HttpStatusException(response.code, service)
        }
    }

    companion object {
        const val REGISTRY_URL = "https://datahub.transportation.gov/resource/69qe-yiui.json?\$limit=500"
        const val NE511_BASE = "https://newengland511.org"
        /** States New England 511 covers. */
        val NE511_STATES = setOf("NH", "VT", "ME")

        fun looks511Key(key: String) = key.trim().matches(Regex("^[A-Za-z0-9-]{16,64}$"))
        fun looksTomTomKey(key: String) = key.trim().matches(Regex("^[A-Za-z0-9]{24,40}$"))

        /**
         * TomTom raster traffic-flow tiles (colored by speed relative to free
         * flow). The key has to be in the tile URL; tiles are fetched by the map.
         */
        fun tomTomFlowTiles(apiKey: String, dark: Boolean): String =
            "https://api.tomtom.com/traffic/map/4/tile/flow/${if (dark) "relative0-dark" else "relative0"}/{z}/{x}/{y}.png" +
                "?key=$apiKey&tileSize=256"

        fun parseRegistry(json: String): List<WzdxFeed> {
            val rows = runCatching { NorthvaneJson.parseToJsonElement(json) as? JsonArray }.getOrNull()
                ?: throw ParseException("Unexpected response from the USDOT WZDx registry")
            return rows.mapNotNull { it.asObj() }.mapNotNull { r ->
                val url = r.obj("url")?.str("url")?.trim().orEmpty()
                val keyless = r.bool("needapikey") == false || r.str("needapikey") == "false"
                val active = r.bool("active") != false && r.str("active") != "false"
                if (!keyless || !active || !url.startsWith("https://") || url.contains("key=", ignoreCase = true) && url.endsWith("=")) {
                    return@mapNotNull null
                }
                // Feeds are public web services: never an address or a local-network name from the registry.
                val host = url.toHttpUrlOrNull()?.host ?: return@mapNotNull null
                if (app.northvane.core.net.UrlPolicy.isIpLiteral(host) || app.northvane.core.net.UrlPolicy.looksLikeLocalNetworkHost(host)) {
                    return@mapNotNull null
                }
                WzdxFeed(r.str("issuingorganization") ?: "State DOT", r.str("state").orEmpty(), url)
            }
        }

        /** WZDx v3/v4 GeoJSON → events near [center], current or starting within a week. */
        fun parseWzdx(json: String, center: GeoPoint, radiusKm: Double, now: Instant, source: String): List<TrafficEvent> {
            val root = runCatching { NorthvaneJson.parseToJsonElement(json).asObj() }.getOrNull()
                ?: throw ParseException("Unexpected response from $source")
            return root.arr("features").orEmpty().mapNotNull { f ->
                val feature = f.asObj() ?: return@mapNotNull null
                val props = feature.obj("properties") ?: return@mapNotNull null
                val core = props.obj("core_details") ?: props // v3 has the fields directly on properties
                // [lon, lat] pairs; feeds sometimes swap them, and one bad point mustn't drop the whole feed.
                val points = coordinates(feature.obj("geometry"))
                    .filter { it.size >= 2 && it[1] in -90.0..90.0 && it[0] in -180.0..180.0 }
                if (points.isEmpty()) return@mapNotNull null
                val nearest = points.minBy { center.distanceKm(GeoPoint(it[1], it[0])) }
                val distance = center.distanceKm(GeoPoint(nearest[1], nearest[0]))
                if (distance > radiusKm) return@mapNotNull null
                val start = instant(props.str("start_date"))
                val end = instant(props.str("end_date"))
                if (end != null && end.isBefore(now)) return@mapNotNull null
                if (start != null && start.isAfter(now.plus(Duration.ofDays(7)))) return@mapNotNull null
                val roads = core.arr("road_names")?.mapNotNull { (it as? JsonPrimitive)?.content } ?: listOfNotNull(core.str("road_name"))
                val impact = when (props.str("vehicle_impact")) {
                    "all-lanes-closed" -> LaneImpact.ALL_CLOSED
                    "some-lanes-closed", "some-lanes-closed-merge-left", "some-lanes-closed-merge-right",
                    "alternating-one-way", "some-lanes-closed-split" -> LaneImpact.SOME_CLOSED
                    "all-lanes-open" -> LaneImpact.OPEN
                    else -> LaneImpact.UNKNOWN
                }
                val eventType = core.str("event_type")
                TrafficEvent(
                    id = feature.str("id") ?: props.str("road_event_id") ?: "${roads.firstOrNull()}-$start",
                    type = when {
                        impact == LaneImpact.ALL_CLOSED -> TrafficEventType.CLOSURE
                        eventType == "detour" || eventType == "restriction" -> TrafficEventType.OTHER
                        else -> TrafficEventType.WORK_ZONE
                    },
                    road = roads.joinToString(" / ").ifEmpty { null },
                    direction = core.str("direction")?.takeIf { it != "unknown" && it != "undefined" },
                    description = core.str("description")?.trim()?.take(800),
                    impact = impact,
                    start = start,
                    end = end,
                    latitude = nearest[1],
                    longitude = nearest[0],
                    distanceKm = distance,
                    path = simplify(points),
                    source = source,
                )
            }.sortedBy { it.distanceKm }
        }

        /** New England 511 (IBI 511 platform) events. Field names are matched case-insensitively. */
        fun parse511(json: String, center: GeoPoint, radiusKm: Double, now: Instant, source: String): List<TrafficEvent> {
            val rows = runCatching { NorthvaneJson.parseToJsonElement(json) as? JsonArray }.getOrNull()
                ?: throw ParseException("Unexpected response from $source")
            return rows.mapNotNull { it.asObj() }.mapNotNull { o ->
                fun field(name: String): JsonElement? = o.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
                fun text(name: String) = (field(name) as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }
                fun number(name: String) = (field(name) as? JsonPrimitive)?.doubleOrNull
                fun time(name: String): Instant? {
                    val p = field(name) as? JsonPrimitive ?: return null
                    p.longOrNull?.let { return if (it > 100_000_000_000L) Instant.ofEpochMilli(it) else Instant.ofEpochSecond(it) }
                    return instant(p.content)
                }
                val lat = number("Latitude") ?: return@mapNotNull null
                val lon = number("Longitude") ?: return@mapNotNull null
                if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return@mapNotNull null
                val distance = center.distanceKm(GeoPoint(lat, lon))
                if (distance > radiusKm) return@mapNotNull null
                val end = time("PlannedEndDate")
                if (end != null && end.isBefore(now)) return@mapNotNull null
                val kind = (text("EventType").orEmpty() + " " + text("EventSubType").orEmpty()).lowercase()
                val closed = text("IsFullClosure").equals("true", ignoreCase = true)
                TrafficEvent(
                    id = text("ID") ?: text("Id") ?: "$lat,$lon",
                    type = when {
                        closed || "closure" in kind -> TrafficEventType.CLOSURE
                        "accident" in kind || "incident" in kind || "crash" in kind -> TrafficEventType.CRASH
                        "roadwork" in kind || "construction" in kind || "maintenance" in kind -> TrafficEventType.WORK_ZONE
                        "congestion" in kind -> TrafficEventType.CONGESTION
                        "special" in kind -> TrafficEventType.SPECIAL_EVENT
                        "weather" in kind || "winter" in kind || "condition" in kind -> TrafficEventType.ROAD_CONDITIONS
                        else -> TrafficEventType.OTHER
                    },
                    road = text("RoadwayName"),
                    direction = text("DirectionOfTravel")?.takeIf { !it.equals("unknown", true) && !it.equals("none", true) },
                    description = text("Description")?.take(800),
                    impact = when {
                        closed -> LaneImpact.ALL_CLOSED
                        text("LanesAffected")?.contains("closed", ignoreCase = true) == true -> LaneImpact.SOME_CLOSED
                        else -> LaneImpact.UNKNOWN
                    },
                    start = time("StartDate") ?: time("Reported"),
                    end = end,
                    latitude = lat,
                    longitude = lon,
                    distanceKm = distance,
                    source = source,
                )
            }.sortedBy { it.distanceKm }
        }

        private fun instant(text: String?): Instant? =
            text?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() ?: runCatching { Instant.parse(it) }.getOrNull() }

        /** [lon, lat] pairs from Point, LineString or MultiPoint geometry. */
        private fun coordinates(geometry: JsonObject?): List<List<Double>> {
            val coords = geometry?.arr("coordinates") ?: return emptyList()
            fun pair(e: JsonElement): List<Double>? = (e as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull }?.takeIf { it.size >= 2 }
            return when (geometry.str("type")) {
                "Point" -> listOfNotNull(pair(coords))
                "LineString", "MultiPoint" -> coords.mapNotNull(::pair)
                "MultiLineString" -> coords.flatMap { line -> (line as? JsonArray).orEmpty().mapNotNull(::pair) }
                else -> emptyList()
            }
        }

        /** Keep at most ~40 points of a line; enough to draw it on the map. */
        private fun simplify(points: List<List<Double>>): List<List<Double>> {
            if (points.size <= 40) return points.map { it.take(2) }
            val step = points.size / 39.0
            return (0 until 40).map { points[(it * step).toInt().coerceAtMost(points.lastIndex)].take(2) }
        }

        private val STATES = mapOf(
            "AL" to "alabama", "AK" to "alaska", "AZ" to "arizona", "AR" to "arkansas", "CA" to "california",
            "CO" to "colorado", "CT" to "connecticut", "DE" to "delaware", "FL" to "florida", "GA" to "georgia",
            "HI" to "hawaii", "ID" to "idaho", "IL" to "illinois", "IN" to "indiana", "IA" to "iowa",
            "KS" to "kansas", "KY" to "kentucky", "LA" to "louisiana", "ME" to "maine", "MD" to "maryland",
            "MA" to "massachusetts", "MI" to "michigan", "MN" to "minnesota", "MS" to "mississippi", "MO" to "missouri",
            "MT" to "montana", "NE" to "nebraska", "NV" to "nevada", "NH" to "new hampshire", "NJ" to "new jersey",
            "NM" to "new mexico", "NY" to "new york", "NC" to "north carolina", "ND" to "north dakota", "OH" to "ohio",
            "OK" to "oklahoma", "OR" to "oregon", "PA" to "pennsylvania", "RI" to "rhode island", "SC" to "south carolina",
            "SD" to "south dakota", "TN" to "tennessee", "TX" to "texas", "UT" to "utah", "VT" to "vermont",
            "VA" to "virginia", "WA" to "washington", "WV" to "west virginia", "WI" to "wisconsin", "WY" to "wyoming",
            "DC" to "district of columbia",
        )
    }
}

/**
 * Gathers events for a place from every source that covers it. The WZDx
 * registry rarely changes, so callers pass a cached copy via [registry].
 */
class TrafficService(private val client: TrafficClient) {
    suspend fun load(
        stateCode: String?,
        center: GeoPoint,
        radiusKm: Double,
        ne511Key: String?,
        registry: suspend () -> List<WzdxFeed>,
        now: Instant,
    ): TrafficSnapshot = coroutineScope {
        val issues = mutableListOf<String>()
        val sources = mutableListOf<String>()
        val feeds = attempt { client.feedsFor(stateCode, registry()) }
            .onFailure { issues += "Work zones: ${it.toUserMessageCompat()}" }
            .getOrDefault(emptyList())
        val zoneJobs = feeds.map { feed -> async { feed to attempt { client.workZones(feed, center, radiusKm, now) } } }
        val ne511Job = ne511Key?.takeIf { stateCode?.uppercase() in TrafficClient.NE511_STATES }?.let { key ->
            async { attempt { client.ne511Events(key, center, radiusKm, now) } }
        }
        val events = mutableListOf<TrafficEvent>()
        for (job in zoneJobs) {
            val (feed, result) = job.await()
            result.onSuccess { events += it; sources += "${feed.organization} (WZDx)" }
                .onFailure { issues += "${feed.organization}: ${it.toUserMessageCompat()}" }
        }
        ne511Job?.await()?.let { result ->
            result.onSuccess { events += it; sources += "New England 511" }
                .onFailure { issues += "New England 511: ${it.toUserMessageCompat()}" }
        }
        if (feeds.isEmpty() && ne511Job == null && issues.isEmpty()) {
            issues += "No free government traffic feed covers this state yet."
        }
        TrafficSnapshot(events.distinctBy { it.id }.sortedBy { it.distanceKm }, sources, now, issues)
    }

    private fun Throwable.toUserMessageCompat() = toUserMessage()
}
