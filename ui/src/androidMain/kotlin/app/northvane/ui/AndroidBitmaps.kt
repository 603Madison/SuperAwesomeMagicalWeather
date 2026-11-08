package app.northvane.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import app.northvane.core.model.WeatherCondition

/**
 * The same glyph as [WeatherIcon], drawn into a bitmap of [sizePx] pixels,
 * for places that can't host Compose (home-screen widgets, notifications).
 */
fun weatherIconBitmap(condition: WeatherCondition, isDay: Boolean, sizePx: Int, onDark: Boolean = true): android.graphics.Bitmap {
    val image = androidx.compose.ui.graphics.ImageBitmap(sizePx, sizePx)
    val canvas = androidx.compose.ui.graphics.Canvas(image)
    val light = if (onDark) Color(0xFFF4F7FB) else Color(0xFFC9D3E1)
    val dark = if (onDark) Color(0xFFC3CCD8) else Color(0xFF8E9BAF)
    androidx.compose.ui.graphics.drawscope.CanvasDrawScope().draw(
        androidx.compose.ui.unit.Density(1f),
        androidx.compose.ui.unit.LayoutDirection.Ltr,
        canvas,
        Size(sizePx.toFloat(), sizePx.toFloat()),
    ) { drawCondition(condition, isDay, light, dark) }
    return image.asAndroidBitmap()
}

/** A rounded rectangle filled with [colors] top to bottom, as a widget background. */
fun gradientBitmap(colors: List<Color>, widthPx: Int, heightPx: Int, cornerPx: Float): android.graphics.Bitmap {
    val bitmap = android.graphics.Bitmap.createBitmap(widthPx.coerceAtLeast(1), heightPx.coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        shader = android.graphics.LinearGradient(
            0f, 0f, 0f, heightPx.toFloat(),
            colors.map { it.toArgb() }.toIntArray(), null, android.graphics.Shader.TileMode.CLAMP,
        )
    }
    canvas.drawRoundRect(0f, 0f, widthPx.toFloat(), heightPx.toFloat(), cornerPx, cornerPx, paint)
    return bitmap
}
