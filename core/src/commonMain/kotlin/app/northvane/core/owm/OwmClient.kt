package app.northvane.core.owm

import app.northvane.core.model.CurrentConditions
import app.northvane.core.model.GeoPoint
import app.northvane.core.net.HttpStatusException
import app.northvane.core.net.InvalidCredentialsException
import app.northvane.core.net.RateLimitedException
import app.northvane.core.net.fetch
import app.northvane.core.net.HttpUrl
import app.northvane.core.net.HttpUrl.Companion.toHttpUrl
import app.northvane.core.net.HttpEngine
import app.northvane.core.net.Request

/**
 * OpenWeatherMap, used only as a fallback when NWS/WeeWX can't provide data
 * and only when the user has entered their own API key. Uses the free-tier
 * 2.5 endpoints.
 */
class OwmClient(
    private val http: HttpEngine,
    private val baseUrl: String = DEFAULT_BASE_URL,
) {
    suspend fun current(point: GeoPoint, apiKey: String): CurrentConditions =
        OwmParsers.parseCurrent(get(url("data/2.5/weather", point, apiKey)))

    suspend fun forecast(point: GeoPoint, apiKey: String): OwmForecast =
        OwmParsers.parseForecast(get(url("data/2.5/forecast", point, apiKey)))

    suspend fun geocode(query: String, apiKey: String): List<PlaceResult> {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("geo/1.0/direct")
            .addQueryParameter("q", query.take(100))
            .addQueryParameter("limit", "5")
            .addQueryParameter("appid", apiKey)
            .build()
        return OwmParsers.parseGeocode(get(url))
    }

    private fun url(path: String, point: GeoPoint, apiKey: String): HttpUrl {
        val p = point.rounded()
        return baseUrl.toHttpUrl().newBuilder()
            .addPathSegments(path)
            .addQueryParameter("lat", GeoPoint.format(p.latitude))
            .addQueryParameter("lon", GeoPoint.format(p.longitude))
            .addQueryParameter("units", "metric")
            .addQueryParameter("appid", apiKey)
            .build()
    }

    private suspend fun get(url: HttpUrl): String {
        // Response caching is disabled: the key is part of the URL, which is the HTTP cache key.
        val request = Request.Builder().url(url).header("Cache-Control", "no-store").build()
        val response = http.fetch(request)
        return when (response.code) {
            in 200..299 -> response.body
            401 -> throw InvalidCredentialsException("OpenWeatherMap")
            429 -> throw RateLimitedException("OpenWeatherMap")
            else -> throw HttpStatusException(response.code, "OpenWeatherMap")
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.openweathermap.org"

        /** OWM keys are 32 hex characters; allow some slack for future formats. */
        fun looksLikeApiKey(key: String): Boolean = key.trim().matches(Regex("^[A-Za-z0-9]{16,64}$"))
    }
}
