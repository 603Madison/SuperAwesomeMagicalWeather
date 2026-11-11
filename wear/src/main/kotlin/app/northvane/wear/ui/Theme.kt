package app.northvane.wear.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Typography
import app.northvane.ui.lexendFamily

/** The app's blue on black, set in Lexend like every SuperAwesomeMagicalWeather app. */
private val WearColors = ColorScheme(
    primary = Color(0xFFA8C7FA),
    primaryDim = Color(0xFF8AB4F8),
    primaryContainer = Color(0xFF2F6FDB),
    onPrimary = Color(0xFF062E6F),
    onPrimaryContainer = Color(0xFFFFFFFF),
)

/** Wear's type scale with every style in [family] (the curved clock at the top keeps the system font). */
private fun Typography.withFamily(family: FontFamily): Typography {
    fun TextStyle.f() = copy(fontFamily = family)
    return copy(
        displayLarge = displayLarge.f(), displayMedium = displayMedium.f(), displaySmall = displaySmall.f(),
        titleLarge = titleLarge.f(), titleMedium = titleMedium.f(), titleSmall = titleSmall.f(),
        labelLarge = labelLarge.f(), labelMedium = labelMedium.f(), labelSmall = labelSmall.f(),
        bodyLarge = bodyLarge.f(), bodyMedium = bodyMedium.f(), bodySmall = bodySmall.f(), bodyExtraSmall = bodyExtraSmall.f(),
        numeralExtraLarge = numeralExtraLarge.f(), numeralLarge = numeralLarge.f(), numeralMedium = numeralMedium.f(),
        numeralSmall = numeralSmall.f(), numeralExtraSmall = numeralExtraSmall.f(),
    )
}

@Composable
fun WearTheme(content: @Composable () -> Unit) {
    val family = lexendFamily()
    val typography = remember(family) { Typography().withFamily(family) }
    MaterialTheme(colorScheme = WearColors, typography = typography, content = content)
}
