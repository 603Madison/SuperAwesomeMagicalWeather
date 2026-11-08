package app.northvane.weather.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import app.northvane.core.model.Units
import app.northvane.core.model.WeatherCondition

enum class ThemeKind { STATIC, DYNAMIC, CUSTOM }

/** The app's color themes. Static ones are fixed; dynamic ones follow the current weather. */
enum class AppTheme(val label: String, val description: String, val kind: ThemeKind) {
    NORTHVANE("SuperAwesomeMagicalWeather", "Clean, bright blue", ThemeKind.STATIC),
    HARBOR("Harbor", "Sea glass and Casco Bay fog", ThemeKind.STATIC),
    EVERGREEN("Evergreen", "White pine and granite", ThemeKind.STATIC),
    MAPLE("Maple", "Sugar maple reds and amber", ThemeKind.STATIC),
    LUPINE("Lupine", "Roadside lupine purples", ThemeKind.STATIC),
    AURORA("Aurora", "Northern lights over a night sky", ThemeKind.STATIC),
    DRIFTWOOD("Driftwood", "Warm sand and weathered gray", ThemeKind.STATIC),
    LIVE_SKY("Live sky", "Follows the sky: sunny blues, storm grays, snowy whites, night navy", ThemeKind.DYNAMIC),
    TEMPERATURE("Temperature", "Shifts from icy blue to summer amber with the temperature", ThemeKind.DYNAMIC),
    CUSTOM("Custom", "Your accent color and your own background", ThemeKind.CUSTOM),
}

/** Gradient choices for the custom theme when no photo is used. */
enum class CustomBackground(val label: String, val light: List<Long>, val dark: List<Long>) {
    DAWN("Dawn", listOf(0xFFFFE3D3, 0xFFF3E9FF), listOf(0xFF2A1B2E, 0xFF0F0B17)),
    OCEAN("Ocean", listOf(0xFFCDEBF2, 0xFFF1FAFB), listOf(0xFF0D2A33, 0xFF061318)),
    MEADOW("Meadow", listOf(0xFFDDF0D3, 0xFFF6FBF2), listOf(0xFF142A17, 0xFF09140B)),
    DUSK("Dusk", listOf(0xFFD9D6F5, 0xFFFFE7E0), listOf(0xFF1B1838, 0xFF0B0A19)),
    SLATE("Slate", listOf(0xFFE1E5EA, 0xFFF7F8FA), listOf(0xFF1C2128, 0xFF0C0F13)),
    PHOTO("My photo", listOf(0xFFF5F7FB, 0xFFF5F7FB), listOf(0xFF0B1220, 0xFF0B1220)),
}

/** Accent colors offered for the custom theme. */
val CustomAccents: List<Long> = listOf(
    0xFF2563EB, 0xFF0E9F9F, 0xFF2E8B57, 0xFF6B8E23, 0xFFC9A227, 0xFFE07B2A,
    0xFFD23B3B, 0xFFC2185B, 0xFF8E44AD, 0xFF5B5BD6, 0xFF546E7A, 0xFF8D6E63,
)

/** Seeds for one theme in one brightness. */
data class Palette(
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
    val backgroundTop: Color,
    val backgroundBottom: Color,
    /** Hero card gradient; null keeps the weather-condition sky gradient. */
    val hero: List<Color>? = null,
)

/** What the root draws behind every screen. */
data class Backdrop(val brush: Brush, val base: Color, val photo: Boolean = false, val dim: Float = 0f, val hero: List<Color>? = null)

val LocalBackdrop = staticCompositionLocalOf { Backdrop(Brush.verticalGradient(listOf(Color.White, Color.White)), Color.White) }
val LocalIsDark = staticCompositionLocalOf { false }

/** Conditions the dynamic themes react to. */
data class ThemeWeather(val condition: WeatherCondition = WeatherCondition.CLEAR, val isDay: Boolean = true, val temperatureC: Double? = null)

private fun c(v: Long) = Color(v)

fun palette(theme: AppTheme, dark: Boolean, weather: ThemeWeather, customAccent: Long, customBackground: CustomBackground): Palette = when (theme) {
    AppTheme.NORTHVANE -> if (dark) Palette(c(0xFF8DB4FF), c(0xFF7CCBF2), c(0xFFFFB86B), c(0xFF0F1A2E), c(0xFF0B1220))
    else Palette(c(0xFF2563EB), c(0xFF0E7FB8), c(0xFFB45309), c(0xFFEAF1FD), c(0xFFF5F7FB))
    AppTheme.HARBOR -> if (dark) Palette(c(0xFF6FD3CF), c(0xFF8FB8C9), c(0xFFF2B880), c(0xFF0D2E31), c(0xFF061416), hero(0xFF0A3A40, 0xFF1D6066))
    else Palette(c(0xFF0F7C80), c(0xFF3F6E80), c(0xFFB86B2B), c(0xFFCBE7E6), c(0xFFF2F8F8), hero(0xFF0F5E66, 0xFF3F9EA3))
    AppTheme.EVERGREEN -> if (dark) Palette(c(0xFF7FCB95), c(0xFFA6B8AE), c(0xFFE0C27A), c(0xFF12291B), c(0xFF07120C), hero(0xFF14301F, 0xFF2C5A3B))
    else Palette(c(0xFF2E6B45), c(0xFF5D7366), c(0xFF9A6B1F), c(0xFFD3E6D8), c(0xFFF4F8F4), hero(0xFF1F4D33, 0xFF4F8A60))
    AppTheme.MAPLE -> if (dark) Palette(c(0xFFFF8F6B), c(0xFFF2C46B), c(0xFFC9A0DC), c(0xFF33150E), c(0xFF160907), hero(0xFF5E1A0D, 0xFF8E3A1A))
    else Palette(c(0xFFB83A1E), c(0xFFB7791F), c(0xFF7A4E9C), c(0xFFF8D9C8), c(0xFFFFF7F1), hero(0xFF9C2A14, 0xFFE07B2A))
    AppTheme.LUPINE -> if (dark) Palette(c(0xFFC3A6FF), c(0xFFF2A7D8), c(0xFF8FD3A6), c(0xFF221838), c(0xFF0D0A19), hero(0xFF2C1A55, 0xFF563A8C))
    else Palette(c(0xFF6A3FC4), c(0xFFB0458F), c(0xFF2E7D4F), c(0xFFE2D6F8), c(0xFFFAF7FE), hero(0xFF4B2A8F, 0xFF9A6BD8))
    AppTheme.AURORA -> if (dark) Palette(c(0xFF6EF2B0), c(0xFFB28CFF), c(0xFF6EC8FF), c(0xFF0B2530), c(0xFF05080F), hero(0xFF0B1E3A, 0xFF1B6B5A))
    else Palette(c(0xFF128A5B), c(0xFF6A4BC4), c(0xFF0F78B5), c(0xFFCDEEE0), c(0xFFEAE4FA), hero(0xFF13294B, 0xFF2E8B6A))
    AppTheme.DRIFTWOOD -> if (dark) Palette(c(0xFFE0B98A), c(0xFFB4A99B), c(0xFF9CC3C9), c(0xFF29221A), c(0xFF110E0B), hero(0xFF3D2B1A, 0xFF6B5236))
    else Palette(c(0xFF8A5A2B), c(0xFF6F655B), c(0xFF3F7F87), c(0xFFEADCC8), c(0xFFFAF6F0), hero(0xFF6B4A2B, 0xFFB08C63))
    AppTheme.LIVE_SKY -> skyPalette(weather, dark)
    AppTheme.TEMPERATURE -> temperaturePalette(weather.temperatureC, dark)
    AppTheme.CUSTOM -> {
        val accent = c(customAccent)
        val bg = if (dark) customBackground.dark else customBackground.light
        Palette(
            primary = if (dark) lerp(accent, Color.White, 0.35f) else accent,
            secondary = lerp(accent, if (dark) Color.White else Color.Black, 0.25f),
            tertiary = lerp(accent, Color(0xFFE07B2A), 0.5f),
            backgroundTop = c(bg[0]),
            backgroundBottom = c(bg[1]),
            hero = listOf(lerp(accent, Color.Black, if (dark) 0.55f else 0.3f), lerp(accent, Color.Black, if (dark) 0.25f else 0f)),
        )
    }
}

private fun hero(top: Long, bottom: Long) = listOf(c(top), c(bottom))

/** Colors from the current sky: condition and day or night. */
private fun skyPalette(w: ThemeWeather, dark: Boolean): Palette {
    val cond = w.condition
    data class Sky(val primary: Long, val lightTop: Long, val lightBottom: Long, val darkTop: Long, val darkBottom: Long)
    val sky = when {
        cond == WeatherCondition.THUNDERSTORM || cond == WeatherCondition.TORNADO ||
            cond == WeatherCondition.HURRICANE || cond == WeatherCondition.TROPICAL_STORM ->
            Sky(0xFF7B5EA7, 0xFFD9D3E6, 0xFFF1EEF6, 0xFF1D1729, 0xFF0D0A14)
        cond in setOf(WeatherCondition.SNOW, WeatherCondition.BLIZZARD, WeatherCondition.SLEET, WeatherCondition.FREEZING_RAIN, WeatherCondition.RAIN_SNOW) ->
            Sky(0xFF4A7FB0, 0xFFE3ECF7, 0xFFFAFCFF, 0xFF1A2433, 0xFF0C121B)
        cond.isPrecipitation -> Sky(0xFF2F6F8F, 0xFFCFDCE5, 0xFFEEF3F6, 0xFF13222C, 0xFF0A1218)
        cond in setOf(WeatherCondition.FOG, WeatherCondition.HAZE, WeatherCondition.SMOKE, WeatherCondition.DUST) ->
            Sky(0xFF7A8794, 0xFFE4E6E8, 0xFFF5F6F7, 0xFF1E2124, 0xFF0E1012)
        cond in setOf(WeatherCondition.OVERCAST, WeatherCondition.MOSTLY_CLOUDY) ->
            Sky(0xFF5B6F86, 0xFFDCE2EA, 0xFFF2F4F7, 0xFF1C232D, 0xFF0D1117)
        !w.isDay -> Sky(0xFF7F95F0, 0xFFD9DEF5, 0xFFF3F4FB, 0xFF0B1030, 0xFF04060F)
        else -> Sky(0xFF1E6FD9, 0xFFBFDDFB, 0xFFEEF6FF, 0xFF0E2A4F, 0xFF081426)
    }
    val primary = c(sky.primary).let { if (dark) lerp(it, Color.White, 0.35f) else it }
    return Palette(
        primary = primary,
        secondary = lerp(primary, Color(0xFF7CCBF2), 0.4f),
        tertiary = c(0xFFE0A030),
        backgroundTop = c(if (dark) sky.darkTop else sky.lightTop),
        backgroundBottom = c(if (dark) sky.darkBottom else sky.lightBottom),
    )
}

private val TemperatureStops = listOf(
    -10.0 to 0xFF5B5BD6, 15.0 to 0xFF2E7FD9, 32.0 to 0xFF1FA2B8, 50.0 to 0xFF2FA36B,
    65.0 to 0xFFB8961E, 80.0 to 0xFFE07B2A, 95.0 to 0xFFD23B3B,
)

/** Accent for a temperature in °C, from the stops above (°F). */
fun temperatureColor(celsius: Double?): Color {
    val f = celsius?.let(Units::cToF) ?: 55.0
    if (f <= TemperatureStops.first().first) return c(TemperatureStops.first().second)
    for (i in 1 until TemperatureStops.size) {
        val (f0, c0) = TemperatureStops[i - 1]
        val (f1, c1) = TemperatureStops[i]
        if (f <= f1) return lerp(c(c0), c(c1), ((f - f0) / (f1 - f0)).toFloat())
    }
    return c(TemperatureStops.last().second)
}

private fun temperaturePalette(celsius: Double?, dark: Boolean): Palette {
    val accent = temperatureColor(celsius)
    val primary = if (dark) lerp(accent, Color.White, 0.3f) else accent
    return Palette(
        primary = primary,
        secondary = lerp(accent, Color(0xFF607D8B), 0.45f),
        tertiary = temperatureColor(celsius?.let { it + 15 }),
        backgroundTop = if (dark) lerp(c(0xFF0B0F17), accent, 0.3f) else lerp(Color.White, accent, 0.26f),
        backgroundBottom = if (dark) c(0xFF07090F) else lerp(Color.White, accent, 0.05f),
        hero = listOf(lerp(accent, Color.Black, if (dark) 0.55f else 0.3f), lerp(accent, Color.White, if (dark) 0f else 0.1f).let { if (dark) lerp(it, Color.Black, 0.25f) else it }),
    )
}

private fun on(color: Color): Color = if (color.luminance() > 0.45f) Color(0xFF111111) else Color.White

/**
 * A full Material color scheme from a palette: containers and surfaces are
 * tinted toward the theme so cards and sheets match its background.
 */
fun schemeFrom(base: ColorScheme, p: Palette, dark: Boolean, translucentCards: Boolean): ColorScheme {
    val neutral = if (dark) Color(0xFF0B0F17) else Color.White
    val surface = lerp(neutral, p.primary, if (dark) 0.09f else 0.04f)
    val alpha = if (translucentCards) 0.88f else 1f
    fun container(color: Color) = lerp(neutral, color, if (dark) 0.32f else 0.16f)
    return base.copy(
        primary = p.primary, onPrimary = on(p.primary),
        primaryContainer = container(p.primary), onPrimaryContainer = if (dark) lerp(p.primary, Color.White, 0.7f) else lerp(p.primary, Color.Black, 0.55f),
        secondary = p.secondary, onSecondary = on(p.secondary),
        secondaryContainer = container(p.secondary), onSecondaryContainer = if (dark) lerp(p.secondary, Color.White, 0.75f) else lerp(p.secondary, Color.Black, 0.6f),
        tertiary = p.tertiary, onTertiary = on(p.tertiary),
        tertiaryContainer = container(p.tertiary), onTertiaryContainer = if (dark) lerp(p.tertiary, Color.White, 0.7f) else lerp(p.tertiary, Color.Black, 0.55f),
        background = p.backgroundBottom, surface = p.backgroundBottom,
        surfaceVariant = lerp(surface, p.primary, if (dark) 0.12f else 0.08f),
        surfaceContainerLowest = surface.copy(alpha = alpha),
        surfaceContainerLow = surface.copy(alpha = alpha),
        surfaceContainer = surface.copy(alpha = alpha),
        surfaceContainerHigh = lerp(surface, p.primary, if (dark) 0.06f else 0.04f),
        surfaceContainerHighest = lerp(surface, p.primary, if (dark) 0.1f else 0.07f),
        outline = lerp(base.outline, p.primary, 0.2f),
        outlineVariant = lerp(base.outlineVariant, p.primary, 0.12f),
    )
}
