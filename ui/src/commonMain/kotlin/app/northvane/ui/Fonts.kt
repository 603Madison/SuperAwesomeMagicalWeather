package app.northvane.ui

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import app.northvane.ui.generated.resources.Res
import app.northvane.ui.generated.resources.lexend
import org.jetbrains.compose.resources.Font

/**
 * Lexend, the app's typeface on every platform (SIL Open Font License; see
 * ui/licenses/Lexend-OFL.txt). One variable font file covers every weight.
 */
@Composable
fun lexendFamily(): FontFamily = FontFamily(
    listOf(FontWeight.Light, FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold).map { w ->
        Font(Res.font.lexend, weight = w, variationSettings = FontVariation.Settings(FontVariation.weight(w.weight)))
    },
)

/** [base] with every style set in [family]. */
fun Typography.withFamily(family: FontFamily): Typography {
    fun TextStyle.f() = copy(fontFamily = family)
    return copy(
        displayLarge = displayLarge.f(), displayMedium = displayMedium.f(), displaySmall = displaySmall.f(),
        headlineLarge = headlineLarge.f(), headlineMedium = headlineMedium.f(), headlineSmall = headlineSmall.f(),
        titleLarge = titleLarge.f(), titleMedium = titleMedium.f(), titleSmall = titleSmall.f(),
        bodyLarge = bodyLarge.f(), bodyMedium = bodyMedium.f(), bodySmall = bodySmall.f(),
        labelLarge = labelLarge.f(), labelMedium = labelMedium.f(), labelSmall = labelSmall.f(),
    )
}

/** Material's type scale in Lexend. */
@Composable
fun lexendTypography(): Typography = Typography().withFamily(lexendFamily())
