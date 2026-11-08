package app.northvane.core.nws

import app.northvane.core.model.WeatherCondition

/**
 * Maps api.weather.gov icon URLs such as
 * `https://api.weather.gov/icons/land/night/tsra_hi,40/rain,60?size=medium`
 * to a condition and day/night flag. When an icon combines two conditions,
 * the more significant one wins.
 */
object NwsIcons {
    data class Parsed(val condition: WeatherCondition, val isDaytime: Boolean)

    fun parse(iconUrl: String?): Parsed? {
        if (iconUrl.isNullOrBlank()) return null
        val path = iconUrl.substringBefore('?')
        val after = path.substringAfter("/icons/", missingDelimiterValue = "")
        if (after.isEmpty()) return null
        // land|marine / day|night / code[,pct] / code[,pct]
        val segments = after.split('/').drop(1)
        if (segments.isEmpty()) return null
        val isDay = segments.first() != "night"
        val codes = segments.drop(1).map { it.substringBefore(',') }
        val condition = WeatherCondition.mostSignificant(codes.mapNotNull(::conditionForCode))
        return Parsed(condition, isDay)
    }

    fun conditionForCode(code: String): WeatherCondition? = when (code) {
        "skc" -> WeatherCondition.CLEAR
        "few" -> WeatherCondition.MOSTLY_CLEAR
        "sct" -> WeatherCondition.PARTLY_CLOUDY
        "bkn" -> WeatherCondition.MOSTLY_CLOUDY
        "ovc" -> WeatherCondition.OVERCAST
        "wind_skc", "wind_few", "wind_sct", "wind_bkn", "wind_ovc" -> WeatherCondition.WINDY
        "snow" -> WeatherCondition.SNOW
        "rain_snow" -> WeatherCondition.RAIN_SNOW
        "rain_sleet", "snow_sleet", "sleet" -> WeatherCondition.SLEET
        "fzra", "rain_fzra", "snow_fzra" -> WeatherCondition.FREEZING_RAIN
        "rain" -> WeatherCondition.RAIN
        "rain_showers", "rain_showers_hi" -> WeatherCondition.SHOWERS
        "tsra", "tsra_sct", "tsra_hi" -> WeatherCondition.THUNDERSTORM
        "tornado" -> WeatherCondition.TORNADO
        "hurricane" -> WeatherCondition.HURRICANE
        "tropical_storm" -> WeatherCondition.TROPICAL_STORM
        "dust" -> WeatherCondition.DUST
        "smoke" -> WeatherCondition.SMOKE
        "haze" -> WeatherCondition.HAZE
        "hot" -> WeatherCondition.HOT
        "cold" -> WeatherCondition.COLD
        "blizzard" -> WeatherCondition.BLIZZARD
        "fog" -> WeatherCondition.FOG
        else -> null
    }
}
