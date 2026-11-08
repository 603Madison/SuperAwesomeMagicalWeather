package app.northvane.core.nws

import app.northvane.core.model.CurrentConditions
import app.northvane.core.model.DailyForecast
import app.northvane.core.model.GeoPoint
import app.northvane.core.model.HourlyForecast
import app.northvane.core.model.WeatherAlert
import app.northvane.core.net.HttpStatusException
import app.northvane.core.net.OutsideNwsCoverageException
import app.northvane.core.net.fetch
import kotlinx.coroutines.delay
import app.northvane.core.net.HttpEngine
import app.northvane.core.net.Request
import app.northvane.core.time.*

/**
 * Client for api.weather.gov. Free and keyless; NWS asks that every request
 * carries a User-Agent identifying the app (optionally with a contact address).
 */
class NwsClient(
    private val http: HttpEngine,
    private val userAgent: () -> String,
    private val baseUrl: String = DEFAULT_BASE_URL,
) {
    suspend fun point(point: GeoPoint): NwsPoint =
        NwsParsers.parsePoint(get("$baseUrl/points/${point.nwsKey()}"))

    suspend fun stations(point: NwsPoint): List<NwsStation> =
        NwsParsers.parseStations(get(requireNwsUrl(point.stationsUrl)))

    suspend fun latestObservation(station: NwsStation): CurrentConditions =
        NwsParsers.parseObservation(
            get("$baseUrl/stations/${station.id.encodePathSegment()}/observations/latest"),
            station.name,
        )

    suspend fun hourly(point: NwsPoint): List<HourlyForecast> =
        NwsParsers.parseHourly(get(requireNwsUrl(point.hourlyUrl)))

    suspend fun daily(point: NwsPoint): List<DailyForecast> =
        NwsParsers.parseDaily(get(requireNwsUrl(point.forecastUrl)), zoneOf(point))

    /** Raw numeric forecast (gusts, precipitation amounts, sky cover…); ~10 KB compressed. */
    suspend fun gridData(point: NwsPoint): NwsGridData =
        NwsGridData.parse(get(requireNwsUrl(point.gridDataUrl ?: "$baseUrl/gridpoints/${point.gridId}/${point.gridX},${point.gridY}")))

    suspend fun alerts(point: GeoPoint): List<WeatherAlert> =
        NwsParsers.parseAlerts(get("$baseUrl/alerts/active?point=${point.nwsKey()}"))

    /** The discussion with this ID; reused when the newest one is already known. */
    suspend fun discussion(gridId: String, id: String): ForecastDiscussion {
        val office = requireNotNull(Discussions.office(gridId)) { "Not an NWS office" }
        require(id.matches(Regex("^[A-Za-z0-9-]{8,64}$"))) { "Not a product ID" }
        return Discussions.parseProduct(get("$baseUrl/products/$id"), office)
    }

    /** Just the newest discussion's ID and time (about 1 KB), to tell whether a new one is out. */
    suspend fun latestDiscussionId(gridId: String): Pair<String, Instant>? {
        val office = Discussions.office(gridId) ?: return null
        return Discussions.parseLatest(get("$baseUrl/products/types/AFD/locations/$office"))
    }

    /**
     * The points response contains follow-up URLs. Only follow ones on the
     * NWS API host, so a tampered or unexpected response can't redirect us
     * (and our User-Agent) elsewhere.
     */
    private fun requireNwsUrl(url: String): String {
        require(url.startsWith("$baseUrl/")) { "Unexpected URL in weather.gov response" }
        return url
    }

    private suspend fun get(url: String): String {
        var attempt = 0
        while (true) {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/geo+json")
                .header("User-Agent", userAgent())
                .build()
            val response = http.fetch(request)
            when {
                response.code in 200..299 -> return response.body
                response.code == 404 && url.contains("/points/") -> throw OutsideNwsCoverageException()
                // weather.gov returns sporadic 5xx under load; one quick retry usually succeeds.
                response.code >= 500 && attempt == 0 -> {
                    attempt++
                    delay(1_500)
                }
                else -> throw HttpStatusException(response.code, "weather.gov")
            }
        }
    }

    private fun String.encodePathSegment(): String =
        filter { it.isLetterOrDigit() || it == '_' || it == '-' }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.weather.gov"

        fun zoneOf(point: NwsPoint): ZoneId = runCatching { ZoneId.of(point.timeZone) }.getOrDefault(ZoneId.of("UTC"))

        /** NWS-recommended User-Agent form: product/version (contact). */
        fun userAgent(appVersion: String, contact: String?, platform: String = "Android"): String {
            val safeContact = contact?.trim()?.filter { it.code in 0x21..0x7E && it != '(' && it != ')' }?.take(100)
            return if (safeContact.isNullOrEmpty()) {
                "SuperAwesomeMagicalWeather/$appVersion ($platform weather app)"
            } else {
                "SuperAwesomeMagicalWeather/$appVersion ($safeContact)"
            }
        }
    }
}
