package app.northvane.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** A bar spanning the week's temperature range, filled between this day's low and high. */
@Composable
fun TemperatureRange(low: Double?, high: Double?, min: Double, max: Double, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier.height(6.dp)) {
        val r = size.height / 2
        drawRoundRect(track, cornerRadius = CornerRadius(r, r))
        val span = (max - min).takeIf { it > 0.1 } ?: return@Canvas
        val lo = ((low ?: high ?: return@Canvas) - min) / span
        val hi = ((high ?: low ?: return@Canvas) - min) / span
        val start = (lo * size.width).toFloat()
        val end = (hi * size.width).toFloat().coerceAtLeast(start + size.height)
        drawRoundRect(
            brush = Brush.horizontalGradient(listOf(Color(0xFF5AA9F7), Color(0xFFF7B955), Color(0xFFF2784B)), startX = 0f, endX = size.width),
            topLeft = Offset(start, 0f),
            size = Size(end - start, size.height),
            cornerRadius = CornerRadius(r, r),
        )
    }
}
