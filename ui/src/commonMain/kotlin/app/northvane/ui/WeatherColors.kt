package app.northvane.ui

import androidx.compose.ui.graphics.Color
import app.northvane.core.model.AlertCategory
import app.northvane.core.model.WeatherCondition

/** Background gradient for the conditions hero card. */
fun skyGradient(condition: WeatherCondition, isDay: Boolean, dark: Boolean): List<Color> {
    val colors = when {
        condition == WeatherCondition.THUNDERSTORM || condition == WeatherCondition.TORNADO ||
            condition == WeatherCondition.HURRICANE || condition == WeatherCondition.TROPICAL_STORM ->
            listOf(Color(0xFF2B3447), Color(0xFF4B3F6B))
        condition.isPrecipitation && condition in setOf(
            WeatherCondition.SNOW, WeatherCondition.BLIZZARD, WeatherCondition.SLEET,
            WeatherCondition.FREEZING_RAIN, WeatherCondition.RAIN_SNOW,
        ) -> if (isDay) listOf(Color(0xFF7F9CC0), Color(0xFFB8CBE0)) else listOf(Color(0xFF2F3E57), Color(0xFF51637E))
        condition.isPrecipitation -> if (isDay) listOf(Color(0xFF4F6D8F), Color(0xFF7E95AD)) else listOf(Color(0xFF1F2A3D), Color(0xFF3A4A63))
        condition in setOf(WeatherCondition.OVERCAST, WeatherCondition.MOSTLY_CLOUDY, WeatherCondition.FOG,
            WeatherCondition.HAZE, WeatherCondition.SMOKE, WeatherCondition.DUST) ->
            if (isDay) listOf(Color(0xFF6E8299), Color(0xFFA3B2C4)) else listOf(Color(0xFF232C3B), Color(0xFF3D4859))
        isDay -> listOf(Color(0xFF2F7BE0), Color(0xFF6FB7F2))
        else -> listOf(Color(0xFF0F1B3D), Color(0xFF283D6E))
    }
    // Deepen in dark theme so the card sits comfortably on a near-black background.
    return if (dark) colors.map { it.copy(red = it.red * 0.62f, green = it.green * 0.64f, blue = it.blue * 0.72f) } else colors
}

/** Hero card background while a warning is in effect. */
fun warningGradient(dark: Boolean): List<Color> =
    if (dark) listOf(Color(0xFF5E1212), Color(0xFF8E2424)) else listOf(Color(0xFFA51D1D), Color(0xFFE04B4B))

fun alertColor(category: AlertCategory): Color = when (category) {
    AlertCategory.WARNING -> Color(0xFFD32F2F)
    AlertCategory.WATCH -> Color(0xFFEF6C00)
    AlertCategory.ADVISORY -> Color(0xFFE0A800)
    AlertCategory.STATEMENT -> Color(0xFF1E88E5)
    AlertCategory.OTHER -> Color(0xFF607D8B)
}

/** NWS-style reflectivity palette (dBZ), used for the radar legend. */
val RadarLegend: List<Pair<Int, Color>> = listOf(
    5 to Color(0xFF04E9E7), 10 to Color(0xFF019FF4), 15 to Color(0xFF0300F4),
    20 to Color(0xFF02FD02), 25 to Color(0xFF01C501), 30 to Color(0xFF008E00),
    35 to Color(0xFFFDF802), 40 to Color(0xFFE5BC00), 45 to Color(0xFFFD9500),
    50 to Color(0xFFFD0000), 55 to Color(0xFFD40000), 60 to Color(0xFFBC0000),
    65 to Color(0xFFF800FD), 70 to Color(0xFF9854C6),
)
