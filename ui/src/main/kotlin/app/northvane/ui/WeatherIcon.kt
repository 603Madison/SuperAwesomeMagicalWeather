package app.northvane.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.northvane.core.model.WeatherCondition
import app.northvane.core.model.WeatherCondition.*

private val Sun = Color(0xFFFFC83D)
private val SunHot = Color(0xFFFF9F1C)
private val Moon = Color(0xFFE8ECF5)
private val Drop = Color(0xFF4A9BEF)
private val Flake = Color(0xFFBFE0FF)
private val Bolt = Color(0xFFFFB020)
private val Mist = Color(0xFFAAB6C6)

/**
 * Flat weather glyphs drawn with Canvas primitives so they scale cleanly and
 * share one visual language. [onDark] picks cloud tones for colored or dark
 * backgrounds versus light surfaces.
 */
@Composable
fun WeatherIcon(
    condition: WeatherCondition,
    isDay: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    onDark: Boolean = false,
) {
    val cloudLight = if (onDark) Color(0xFFF4F7FB) else Color(0xFFC9D3E1)
    val cloudDark = if (onDark) Color(0xFFC3CCD8) else Color(0xFF8E9BAF)
    Canvas(
        modifier
            .size(size)
            .semantics { contentDescription = condition.label },
    ) {
        drawCondition(condition, isDay, cloudLight, cloudDark)
    }
}

private fun DrawScope.drawCondition(c: WeatherCondition, isDay: Boolean, light: Color, dark: Color) {
    val w = size.width
    when (c) {
        CLEAR, UNKNOWN -> celestial(isDay, Offset(w * 0.5f, w * 0.5f), w * 0.24f)
        HOT -> sun(Offset(w * 0.5f, w * 0.5f), w * 0.26f, SunHot)
        COLD -> flake(Offset(w * 0.5f, w * 0.5f), w * 0.3f, Flake, w * 0.06f)
        MOSTLY_CLEAR -> {
            celestial(isDay, Offset(w * 0.42f, w * 0.42f), w * 0.22f)
            cloud(Rect(w * 0.42f, w * 0.52f, w * 0.98f, w * 0.88f), light)
        }
        PARTLY_CLOUDY -> {
            celestial(isDay, Offset(w * 0.38f, w * 0.36f), w * 0.2f)
            cloud(Rect(w * 0.18f, w * 0.38f, w * 0.96f, w * 0.88f), light)
        }
        MOSTLY_CLOUDY -> {
            celestial(isDay, Offset(w * 0.3f, w * 0.3f), w * 0.16f)
            cloud(Rect(w * 0.06f, w * 0.26f, w * 0.98f, w * 0.88f), light)
        }
        OVERCAST -> {
            cloud(Rect(w * 0.3f, w * 0.12f, w * 0.98f, w * 0.6f), dark)
            cloud(Rect(w * 0.02f, w * 0.34f, w * 0.86f, w * 0.88f), light)
        }
        FOG, HAZE, SMOKE, DUST -> {
            if (c != FOG) celestial(isDay, Offset(w * 0.5f, w * 0.34f), w * 0.18f)
            else cloud(Rect(w * 0.12f, w * 0.08f, w * 0.88f, w * 0.52f), light)
            val tint = if (c == DUST) Color(0xFFC9A66B) else Mist
            mistLines(tint, listOf(0.6f, 0.74f, 0.88f))
        }
        WINDY -> windLines(light)
        DRIZZLE -> rainCloud(light, Drop, drops = 2, short = true)
        SHOWERS -> {
            celestial(isDay, Offset(w * 0.32f, w * 0.26f), w * 0.16f)
            rainCloud(light, Drop, drops = 3)
        }
        RAIN -> rainCloud(light, Drop, drops = 3)
        HEAVY_RAIN -> rainCloud(dark, Drop, drops = 5)
        SNOW -> snowCloud(light, flakes = 3)
        BLIZZARD -> {
            snowCloud(dark, flakes = 3)
            windLines(Mist, top = 0.0f, scale = 0.45f)
        }
        RAIN_SNOW, SLEET, FREEZING_RAIN -> {
            cloud(Rect(w * 0.06f, w * 0.06f, w * 0.94f, w * 0.6f), light)
            drop(Offset(w * 0.3f, w * 0.72f), w)
            flake(Offset(w * 0.52f, w * 0.8f), w * 0.08f, Flake, w * 0.035f)
            if (c == FREEZING_RAIN) {
                drawCircle(Flake, w * 0.045f, Offset(w * 0.74f, w * 0.74f))
            } else {
                drop(Offset(w * 0.74f, w * 0.72f), w)
            }
        }
        THUNDERSTORM -> {
            cloud(Rect(w * 0.06f, w * 0.04f, w * 0.94f, w * 0.58f), dark)
            bolt(w)
            drop(Offset(w * 0.24f, w * 0.72f), w)
            drop(Offset(w * 0.78f, w * 0.72f), w)
        }
        TORNADO -> {
            val widths = listOf(0.8f, 0.64f, 0.5f, 0.36f, 0.24f)
            widths.forEachIndexed { i, fraction ->
                val y = w * (0.2f + i * 0.15f)
                val shift = w * 0.04f * i
                drawLine(light, Offset(w * (0.5f - fraction / 2) + shift, y), Offset(w * (0.5f + fraction / 2) + shift, y), w * 0.08f, StrokeCap.Round)
            }
        }
        HURRICANE, TROPICAL_STORM -> {
            val stroke = Stroke(width = w * 0.1f, cap = StrokeCap.Round)
            drawArc(dark, 200f, 220f, false, Offset(w * 0.15f, w * 0.15f), Size(w * 0.7f, w * 0.7f), style = stroke)
            drawArc(light, 20f, 220f, false, Offset(w * 0.15f, w * 0.15f), Size(w * 0.7f, w * 0.7f), style = stroke)
            drawCircle(if (c == HURRICANE) Color(0xFFE53935) else Bolt, w * 0.09f, Offset(w * 0.5f, w * 0.5f))
        }
    }
}

private fun DrawScope.celestial(isDay: Boolean, center: Offset, radius: Float) =
    if (isDay) sun(center, radius, Sun) else moon(center, radius)

private fun DrawScope.sun(center: Offset, radius: Float, color: Color) {
    val rayWidth = radius * 0.2f
    for (i in 0 until 8) {
        val angle = Math.toRadians(i * 45.0)
        val dx = kotlin.math.cos(angle).toFloat()
        val dy = kotlin.math.sin(angle).toFloat()
        drawLine(
            color,
            Offset(center.x + dx * radius * 1.35f, center.y + dy * radius * 1.35f),
            Offset(center.x + dx * radius * 1.7f, center.y + dy * radius * 1.7f),
            rayWidth,
            StrokeCap.Round,
        )
    }
    drawCircle(color, radius, center)
}

private fun DrawScope.moon(center: Offset, radius: Float) {
    val disc = Path().apply { addOval(Rect(center, radius)) }
    val bite = Path().apply { addOval(Rect(Offset(center.x + radius * 0.55f, center.y - radius * 0.35f), radius * 0.9f)) }
    drawPath(Path.combine(PathOperation.Difference, disc, bite), Moon)
}

/** A cloud fitted to [bounds]: three overlapping lobes on a rounded base. */
private fun DrawScope.cloud(bounds: Rect, color: Color) {
    val w = bounds.width
    val h = bounds.height
    fun x(f: Float) = bounds.left + w * f
    fun y(f: Float) = bounds.top + h * f
    drawRoundRect(color, Offset(x(0.06f), y(0.52f)), Size(w * 0.88f, h * 0.46f), CornerRadius(h * 0.23f))
    drawCircle(color, w * 0.2f, Offset(x(0.3f), y(0.6f)))
    drawCircle(color, w * 0.26f, Offset(x(0.54f), y(0.48f)))
    drawCircle(color, w * 0.17f, Offset(x(0.76f), y(0.66f)))
}

private fun DrawScope.drop(top: Offset, w: Float) {
    drawLine(Drop, top, Offset(top.x - w * 0.05f, top.y + w * 0.14f), w * 0.055f, StrokeCap.Round)
}

private fun DrawScope.rainCloud(cloudColor: Color, dropColor: Color, drops: Int, short: Boolean = false) {
    val w = size.width
    cloud(Rect(w * 0.06f, w * 0.06f, w * 0.94f, w * 0.62f), cloudColor)
    val length = if (short) 0.08f else 0.15f
    for (i in 0 until drops) {
        val x = w * (0.28f + i * (0.46f / (drops - 1).coerceAtLeast(1)))
        val y = w * (0.7f + if (i % 2 == 1) 0.06f else 0f)
        drawLine(dropColor, Offset(x, y), Offset(x - w * 0.05f, y + w * length), w * 0.055f, StrokeCap.Round)
    }
}

private fun DrawScope.snowCloud(cloudColor: Color, flakes: Int) {
    val w = size.width
    cloud(Rect(w * 0.06f, w * 0.06f, w * 0.94f, w * 0.62f), cloudColor)
    for (i in 0 until flakes) {
        val x = w * (0.28f + i * 0.22f)
        val y = w * (0.76f + if (i % 2 == 1) 0.08f else 0f)
        flake(Offset(x, y), w * 0.08f, Flake, w * 0.035f)
    }
}

private fun DrawScope.flake(center: Offset, radius: Float, color: Color, stroke: Float) {
    for (i in 0 until 3) {
        val angle = Math.toRadians(90.0 + i * 60.0)
        val dx = (kotlin.math.cos(angle) * radius).toFloat()
        val dy = (kotlin.math.sin(angle) * radius).toFloat()
        drawLine(color, Offset(center.x - dx, center.y - dy), Offset(center.x + dx, center.y + dy), stroke, StrokeCap.Round)
    }
}

private fun DrawScope.bolt(w: Float) {
    val path = Path().apply {
        moveTo(w * 0.55f, w * 0.42f)
        lineTo(w * 0.38f, w * 0.7f)
        lineTo(w * 0.5f, w * 0.7f)
        lineTo(w * 0.42f, w * 0.96f)
        lineTo(w * 0.66f, w * 0.62f)
        lineTo(w * 0.53f, w * 0.62f)
        lineTo(w * 0.62f, w * 0.42f)
        close()
    }
    drawPath(path, Bolt)
}

private fun DrawScope.mistLines(color: Color, rows: List<Float>) {
    val w = size.width
    rows.forEachIndexed { i, row ->
        val inset = if (i % 2 == 0) 0.12f else 0.2f
        drawLine(color, Offset(w * inset, w * row), Offset(w * (1 - inset), w * row), w * 0.07f, StrokeCap.Round)
    }
}

private fun DrawScope.windLines(color: Color, top: Float = 0.2f, scale: Float = 1f) {
    val w = size.width
    val stroke = Stroke(width = w * 0.065f, cap = StrokeCap.Round)
    val rows = listOf(0.25f to 0.62f, 0.5f to 0.82f, 0.75f to 0.5f)
    rows.forEach { (y, length) ->
        val yy = w * (top + (y - 0.2f) * scale + if (scale < 1f) 0.55f else 0f)
        drawLine(color, Offset(w * 0.1f, yy), Offset(w * (0.1f + length * 0.75f), yy), stroke.width, StrokeCap.Round)
        val r = w * 0.08f * scale.coerceAtLeast(0.6f)
        drawArc(
            color, 90f, -270f, false,
            Offset(w * (0.1f + length * 0.75f) - r, yy - 2 * r),
            Size(2 * r, 2 * r),
            style = stroke,
        )
    }
}

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
