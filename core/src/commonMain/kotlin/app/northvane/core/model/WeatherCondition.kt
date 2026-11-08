package app.northvane.core.model

import kotlinx.serialization.Serializable

/**
 * Normalized sky/precipitation state. [rank] orders conditions by how much they
 * matter to a person glancing at the app, used when a source reports two
 * conditions for one period (e.g. NWS "sct/tsra").
 */
@Serializable
enum class WeatherCondition(val rank: Int, val label: String) {
    CLEAR(0, "Clear"),
    MOSTLY_CLEAR(1, "Mostly clear"),
    PARTLY_CLOUDY(2, "Partly cloudy"),
    MOSTLY_CLOUDY(3, "Mostly cloudy"),
    OVERCAST(4, "Cloudy"),
    WINDY(5, "Windy"),
    HOT(5, "Hot"),
    COLD(5, "Cold"),
    HAZE(6, "Haze"),
    SMOKE(7, "Smoke"),
    DUST(7, "Blowing dust"),
    FOG(8, "Fog"),
    DRIZZLE(9, "Drizzle"),
    SHOWERS(10, "Showers"),
    RAIN(11, "Rain"),
    HEAVY_RAIN(12, "Heavy rain"),
    SNOW(12, "Snow"),
    RAIN_SNOW(13, "Rain and snow"),
    SLEET(14, "Sleet"),
    FREEZING_RAIN(15, "Freezing rain"),
    BLIZZARD(16, "Blizzard"),
    THUNDERSTORM(16, "Thunderstorms"),
    TROPICAL_STORM(18, "Tropical storm"),
    HURRICANE(19, "Hurricane"),
    TORNADO(20, "Tornado"),
    UNKNOWN(-1, "—");

    val isPrecipitation: Boolean
        get() = this in setOf(
            DRIZZLE, SHOWERS, RAIN, HEAVY_RAIN, SNOW, RAIN_SNOW, SLEET,
            FREEZING_RAIN, BLIZZARD, THUNDERSTORM, TROPICAL_STORM, HURRICANE,
        )

    companion object {
        fun mostSignificant(conditions: Iterable<WeatherCondition>): WeatherCondition =
            conditions.maxByOrNull { it.rank } ?: UNKNOWN

        /** Keyword fallback for free-text descriptions ("Light Rain and Fog/Mist"). */
        fun fromText(text: String?): WeatherCondition {
            if (text.isNullOrBlank()) return UNKNOWN
            val t = text.lowercase()
            return when {
                "tornado" in t || "funnel" in t -> TORNADO
                "hurricane" in t -> HURRICANE
                "tropical storm" in t -> TROPICAL_STORM
                "thunder" in t || "t-storm" in t -> THUNDERSTORM
                "blizzard" in t -> BLIZZARD
                "freezing" in t -> FREEZING_RAIN
                "sleet" in t || "ice pellet" in t -> SLEET
                "snow" in t && ("rain" in t || "wintry" in t) -> RAIN_SNOW
                "snow" in t || "flurr" in t -> SNOW
                "heavy rain" in t -> HEAVY_RAIN
                "shower" in t -> SHOWERS
                "drizzle" in t -> DRIZZLE
                "rain" in t -> RAIN
                "fog" in t || "mist" in t -> FOG
                "smoke" in t -> SMOKE
                "dust" in t || "sand" in t -> DUST
                "haze" in t -> HAZE
                "mostly cloudy" in t || "considerable cloud" in t -> MOSTLY_CLOUDY
                "partly" in t -> PARTLY_CLOUDY
                "mostly sunny" in t || "mostly clear" in t || "few clouds" in t -> MOSTLY_CLEAR
                "overcast" in t || "cloudy" in t -> OVERCAST
                "sunny" in t || "clear" in t || "fair" in t -> CLEAR
                "wind" in t || "breezy" in t || "blustery" in t -> WINDY
                "hot" in t -> HOT
                "cold" in t -> COLD
                else -> UNKNOWN
            }
        }
    }
}
