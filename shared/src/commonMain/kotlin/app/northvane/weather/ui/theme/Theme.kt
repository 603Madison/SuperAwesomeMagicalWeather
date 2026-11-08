package app.northvane.weather.ui.theme

import app.northvane.weather.ui.*
import app.northvane.ui.lexendFamily
import app.northvane.ui.withFamily
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.northvane.weather.data.ThemeMode

// The brand color schemes are shared with Mini (:ui).
internal val LightColors = app.northvane.ui.BrandLightColors
internal val DarkColors = app.northvane.ui.BrandDarkColors

private val BaseTypography = Typography()

private val NorthvaneTypography = Typography(
    displayLarge = BaseTypography.displayLarge.copy(fontWeight = FontWeight.Light, fontSize = 88.sp, lineHeight = 92.sp, letterSpacing = (-2).sp),
    headlineSmall = BaseTypography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = BaseTypography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = BaseTypography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = BaseTypography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
)

val TemperatureStyle: TextStyle
    @Composable get() = MaterialTheme.typography.displayLarge

@Composable
fun isAppInDarkTheme(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun NorthvaneTheme(
    darkTheme: Boolean,
    dynamicColor: Boolean,
    theme: AppTheme = AppTheme.NORTHVANE,
    weather: ThemeWeather = ThemeWeather(),
    customAccent: Long = CustomAccents.first(),
    customBackground: CustomBackground = CustomBackground.DUSK,
    customDimPercent: Int = 35,
    content: @Composable () -> Unit,
) {
    val wallpaper = if (dynamicColor) platformColorScheme(darkTheme) else null
    val base = if (darkTheme) DarkColors else LightColors
    val photo = theme == AppTheme.CUSTOM && customBackground == CustomBackground.PHOTO
    val p = palette(theme, darkTheme, weather, customAccent, customBackground)
    val colors = when {
        wallpaper != null -> wallpaper
        theme == AppTheme.NORTHVANE -> base
        else -> schemeFrom(base, p, darkTheme, translucentCards = photo)
    }
    val backdrop = if (wallpaper != null) {
        Backdrop(Brush.verticalGradient(listOf(colors.background, colors.background)), colors.background)
    } else {
        Backdrop(
            brush = Brush.verticalGradient(listOf(p.backgroundTop, p.backgroundBottom)),
            base = p.backgroundBottom,
            photo = photo,
            dim = customDimPercent.coerceIn(0, 80) / 100f,
            hero = p.hero,
        )
    }
    CompositionLocalProvider(LocalIsDark provides darkTheme, LocalBackdrop provides backdrop) {
        MaterialTheme(colorScheme = colors, typography = NorthvaneTypography.withFamily(lexendFamily()), content = content)
    }
}

/**
 * Red scheme for the Today screen while an NWS warning is in effect. Only
 * warnings trigger it; watches, advisories and statements leave the normal theme.
 */
@Composable
fun WarningTheme(active: Boolean, content: @Composable () -> Unit) {
    if (!active) {
        content()
        return
    }
    val base = MaterialTheme.colorScheme
    val dark = LocalIsDark.current
    val scheme = if (dark) {
        base.copy(
            primary = Color(0xFFFF8A80), onPrimary = Color(0xFF5C0000),
            primaryContainer = Color(0xFF6B1515), onPrimaryContainer = Color(0xFFFFDAD6),
            secondaryContainer = Color(0xFF4A1515), onSecondaryContainer = Color(0xFFFFDAD6),
            background = Color(0xFF1A0B0D), surface = Color(0xFF1A0B0D),
            surfaceVariant = Color(0xFF3D1F22), onSurfaceVariant = Color(0xFFE0B8B5),
            surfaceContainerLow = Color(0xFF211012), surfaceContainer = Color(0xFF2A1215),
            surfaceContainerHigh = Color(0xFF331619), surfaceContainerHighest = Color(0xFF3D1B1F),
            outline = Color(0xFF6B3A3A), outlineVariant = Color(0xFF4A2628),
        )
    } else {
        base.copy(
            primary = Color(0xFFC62828), onPrimary = Color.White,
            primaryContainer = Color(0xFFFFDAD6), onPrimaryContainer = Color(0xFF410002),
            secondaryContainer = Color(0xFFFFE3E0), onSecondaryContainer = Color(0xFF5C0F0F),
            background = Color(0xFFFFF1F0), surface = Color(0xFFFFF1F0),
            surfaceVariant = Color(0xFFF9DEDC), onSurfaceVariant = Color(0xFF6E4B49),
            surfaceContainerLow = Color(0xFFFFF8F7), surfaceContainer = Color(0xFFFFFBFA),
            surfaceContainerHigh = Color(0xFFFCE9E7), surfaceContainerHighest = Color(0xFFF8DEDB),
            outline = Color(0xFFE3B5B1), outlineVariant = Color(0xFFF3D3D0),
        )
    }
    MaterialTheme(colorScheme = scheme, typography = MaterialTheme.typography, content = content)
}
