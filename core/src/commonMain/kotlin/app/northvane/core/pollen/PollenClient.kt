package app.northvane.core.pollen

import app.northvane.core.json.NorthvaneJson
import app.northvane.core.json.arr
import app.northvane.core.json.asObj
import app.northvane.core.json.bool
import app.northvane.core.json.int
import app.northvane.core.json.obj
import app.northvane.core.json.str
import app.northvane.core.model.GeoPoint
import app.northvane.core.model.PollenDay
import app.northvane.core.model.PollenForecast
import app.northvane.core.model.PollenReading
import app.northvane.core.net.HttpStatusException
import app.northvane.core.net.InvalidCredentialsException
import app.northvane.core.net.ParseException
import app.northvane.core.net.RateLimitedException
import app.northvane.core.net.WeatherApiException
import app.northvane.core.net.fetch
import app.northvane.core.net.HttpUrl.Companion.toHttpUrl
import app.northvane.core.net.HttpEngine
import app.northvane.core.net.Request
import app.northvane.core.util.plainDecimal
import app.northvane.core.time.*

/** Identifies the app to Google when the user restricts their key to Android apps. */
data class AndroidKeyRestriction(val packageName: String, val certSha1: String)

/**
 * Google Maps Platform Pollen API: 5-day tree/grass/weed forecasts (Universal
 * Pollen Index 0–5) on a 1 km grid, plus the main plant species. No NOAA
 * product covers pollen, so this needs the user's own key; see
 * [PollenPricing] for what that costs.
 *
 * The key is sent in a header (never in the URL, so it can't land in caches
 * or logs), and the location is rounded to ~1 km, the grid's own resolution.
 */
class PollenClient(
    private val http: HttpEngine,
    private val baseUrl: String = DEFAULT_BASE_URL,
) {
    suspend fun forecast(point: GeoPoint, apiKey: String, now: Instant, restriction: AndroidKeyRestriction? = null): PollenForecast {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("v1/forecast:lookup")
            .addQueryParameter("location.latitude", coarse(point.latitude))
            .addQueryParameter("location.longitude", coarse(point.longitude))
            .addQueryParameter("days", "5")
            .addQueryParameter("languageCode", "en")
            .addQueryParameter("plantsDescription", "false")
            .build()
        val request = Request.Builder()
            .url(url)
            .secretHeader("X-Goog-Api-Key", apiKey)
            .header("Cache-Control", "no-store")
            .apply {
                if (restriction != null) {
                    header("X-Android-Package", restriction.packageName)
                    header("X-Android-Cert", restriction.certSha1)
                }
            }
            .build()
        val response = http.fetch(request, maxBytes = 1024L * 1024)
        return when (response.code) {
            in 200..299 -> PollenForecast(parse(response.body), now)
            400 -> if (response.body.contains("API_KEY_INVALID") || response.body.contains("API key not valid")) {
                throw InvalidCredentialsException("Google Pollen API")
            } else {
                throw HttpStatusException(400, "Google Pollen API")
            }
            401 -> throw InvalidCredentialsException("Google Pollen API")
            403 -> throw WeatherApiException(
                "Google refused the pollen request: enable the Pollen API for this key and check its restrictions",
            )
            429 -> throw RateLimitedException("Google Pollen API")
            else -> throw HttpStatusException(response.code, "Google Pollen API")
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://pollen.googleapis.com"

        /** Google API keys are "AIza" + 35 URL-safe characters; allow slack for other formats. */
        fun looksLikeApiKey(key: String): Boolean = key.trim().matches(Regex("^[A-Za-z0-9_-]{30,64}$"))

        private fun coarse(v: Double): String =
            plainDecimal(v, 2)

        fun parse(json: String): List<PollenDay> {
            val root = runCatching { NorthvaneJson.parseToJsonElement(json).asObj() }.getOrNull()
                ?: throw ParseException("Unexpected response from the Google Pollen API")
            return root.arr("dailyInfo").orEmpty().mapNotNull { element ->
                val day = element.asObj() ?: return@mapNotNull null
                val d = day.obj("date") ?: return@mapNotNull null
                val date = runCatching { LocalDate.of(d.int("year")!!, d.int("month")!!, d.int("day")!!) }.getOrNull()
                    ?: return@mapNotNull null
                val types = day.arr("pollenTypeInfo").orEmpty().mapNotNull { it.asObj()?.let(::reading) }
                val tips = day.arr("pollenTypeInfo").orEmpty()
                    .mapNotNull { it.asObj() }
                    .filter { (it.obj("indexInfo")?.int("value") ?: 0) >= 3 }
                    .flatMap { o -> o.arr("healthRecommendations").orEmpty().mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } }
                    .distinct()
                    .take(3)
                PollenDay(
                    date = date,
                    types = types,
                    plants = day.arr("plantInfo").orEmpty().mapNotNull { it.asObj()?.let(::reading) },
                    tips = tips,
                )
            }
        }

        private fun reading(o: kotlinx.serialization.json.JsonObject): PollenReading? {
            val code = o.str("code") ?: return null
            val index = o.obj("indexInfo")
            return PollenReading(
                code = code,
                name = o.str("displayName") ?: code.lowercase().replaceFirstChar { it.uppercase() },
                index = index?.int("value")?.coerceIn(0, 5),
                category = index?.str("category"),
                inSeason = o.bool("inSeason") ?: (index != null),
            )
        }
    }
}

/**
 * What a Pollen API key costs, shown where the user enters it. Google's
 * pay-as-you-go price list (checked October 2026): 5,000 free requests per
 * billing account each month, then US$10 per 1,000 requests. SuperAwesomeMagicalWeather asks for
 * pollen at most once per [MIN_REFRESH_HOURS] per saved location, so normal
 * use stays inside the free allowance.
 */
object PollenPricing {
    const val FREE_REQUESTS_PER_MONTH = 5_000
    const val USD_PER_1000 = 10.0
    const val MIN_REFRESH_HOURS = 6L
    const val PRICE_CHECKED = "October 2026"

    /** Upper bound on requests per month for [locations] saved places. */
    fun maxMonthlyRequests(locations: Int): Int = (locations.coerceAtLeast(1) * 31 * 24 / MIN_REFRESH_HOURS).toInt()

    /** Worst-case monthly cost in US dollars beyond the free allowance. */
    fun maxMonthlyCostUsd(locations: Int): Double =
        ((maxMonthlyRequests(locations) - FREE_REQUESTS_PER_MONTH).coerceAtLeast(0)) * USD_PER_1000 / 1000.0
}
