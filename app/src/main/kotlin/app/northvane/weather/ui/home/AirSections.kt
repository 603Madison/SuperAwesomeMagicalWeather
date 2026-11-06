package app.northvane.weather.ui.home

import app.northvane.weather.ui.components.WeatherCard
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.northvane.core.model.AirQuality
import app.northvane.core.model.AllergyDay
import app.northvane.core.model.AllergyLevel
import app.northvane.core.model.AqiCategory
import app.northvane.core.model.UvForecast
import app.northvane.core.model.UvLevel
import app.northvane.core.outlook.OutlookText
import app.northvane.core.util.UnitFormatter
import app.northvane.weather.ui.TimeFormat
import java.time.Instant
import kotlin.math.roundToInt

internal fun aqiColor(category: AqiCategory): Color = when (category) {
    AqiCategory.GOOD -> Color(0xFF3FA34D)
    AqiCategory.MODERATE -> Color(0xFFD4B000)
    AqiCategory.SENSITIVE -> Color(0xFFEF7D1A)
    AqiCategory.UNHEALTHY -> Color(0xFFD32F2F)
    AqiCategory.VERY_UNHEALTHY -> Color(0xFF8E3FA0)
    AqiCategory.HAZARDOUS -> Color(0xFF7E0023)
}

internal fun uvColor(level: UvLevel): Color = when (level) {
    UvLevel.LOW -> Color(0xFF3FA34D)
    UvLevel.MODERATE -> Color(0xFFD4B000)
    UvLevel.HIGH -> Color(0xFFEF7D1A)
    UvLevel.VERY_HIGH -> Color(0xFFD32F2F)
    UvLevel.EXTREME -> Color(0xFF8E3FA0)
}

internal fun allergyColor(level: AllergyLevel): Color = when (level) {
    AllergyLevel.LOW -> Color(0xFF3FA34D)
    AllergyLevel.MODERATE -> Color(0xFFD4B000)
    AllergyLevel.HIGH -> Color(0xFFEF7D1A)
    AllergyLevel.VERY_HIGH -> Color(0xFFD32F2F)
}

@Composable
private fun AirCard(content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) { Column(Modifier.padding(16.dp)) { content() } }
}

@Composable
private fun Small(text: String, modifier: Modifier = Modifier) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)

private fun aqiAdvice(category: AqiCategory): String = when (category) {
    AqiCategory.GOOD -> "Air quality is good. Enjoy being outside."
    AqiCategory.MODERATE -> "Unusually sensitive people should consider easing off long, hard outdoor exercise."
    AqiCategory.SENSITIVE -> "People with asthma, heart or lung disease, children and older adults should reduce long or hard outdoor exertion."
    AqiCategory.UNHEALTHY -> "Everyone should cut back on long or hard outdoor exertion; sensitive groups should avoid it."
    AqiCategory.VERY_UNHEALTHY -> "Avoid long or hard outdoor exertion. Sensitive groups should stay indoors."
    AqiCategory.HAZARDOUS -> "Health warning: everyone should avoid outdoor activity."
}

@Composable
internal fun AirQualitySection(air: AirQuality, time: TimeFormat) {
    val current = air.current
    val category = current?.category ?: air.days.firstOrNull()?.category ?: return
    WeatherCard("Air quality") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                current?.aqi?.toString() ?: "--",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.SemiBold,
                color = aqiColor(category),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(category.label, style = MaterialTheme.typography.titleMedium)
                Small(listOfNotNull("AQI", current?.pollutant?.let { pollutantName(it) }, current?.area).joinToString(" · "))
            }
        }
        Spacer(Modifier.height(10.dp))
        AqiScale(current?.aqi ?: category.max.coerceAtMost(300))
        Small(aqiAdvice(category), Modifier.padding(top = 8.dp))
        val ahead = air.days.filter { it.date.isAfter(time.today()) }.take(4)
        if (ahead.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth()) {
                ahead.forEach { d ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                        Text(time.weekday(d.date), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            d.aqi?.toString() ?: d.category?.label?.substringBefore(' ') ?: "--",
                            style = MaterialTheme.typography.labelLarge,
                            color = d.category?.let(::aqiColor) ?: MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
        air.days.firstOrNull { it.actionDay }?.let { Small("Air Quality Action Day ${time.weekday(it.date)}.", Modifier.padding(top = 6.dp)) }
        air.days.firstOrNull { it.discussion != null }?.discussion?.let { Small(it, Modifier.padding(top = 4.dp)) }
        Small(air.source.label, Modifier.padding(top = 8.dp))
    }
}

private fun pollutantName(code: String) = when (code.uppercase()) {
    "O3", "OZONE" -> "ozone"
    "PM2.5" -> "fine particles (PM2.5)"
    "PM10" -> "particles (PM10)"
    else -> code
}

/** AQI 0–300 on the EPA color scale with a marker. */
@Composable
private fun AqiScale(aqi: Int) {
    val marker = MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.fillMaxWidth().height(12.dp)) {
        val h = size.height * 0.45f
        val top = (size.height - h) / 2
        drawRoundRect(
            Brush.horizontalGradient(AqiCategory.entries.take(5).map { aqiColor(it) }),
            topLeft = Offset(0f, top),
            size = Size(size.width, h),
            cornerRadius = CornerRadius(h / 2, h / 2),
        )
        val x = size.width * (aqi.coerceIn(0, 300) / 300f)
        drawCircle(Color.White, size.height / 2, Offset(x, size.height / 2))
        drawCircle(marker, size.height / 2, Offset(x, size.height / 2), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
    }
}

@Composable
internal fun UvSection(uv: UvForecast, time: TimeFormat) {
    val now = Instant.now()
    val current = uv.at(now) ?: return
    val level = UvLevel.of(current)
    val peak = uv.peak()
    val todayMax = uv.todayMax ?: peak?.index ?: current
    WeatherCard("UV index") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                current.roundToInt().toString(),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.SemiBold,
                color = uvColor(level),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("${level.label} now", style = MaterialTheme.typography.titleMedium)
                Small(
                    "Today's peak ${todayMax.roundToInt()} (${UvLevel.of(todayMax).label.lowercase()})" +
                        (peak?.let { " around ${time.hour(it.time)}" } ?: ""),
                )
            }
        }
        if (uv.hourly.size > 3) {
            Spacer(Modifier.height(12.dp))
            UvBars(uv, now, time)
        }
        Small(uvAdvice(todayMax, uv, time), Modifier.padding(top = 8.dp))
        if (uv.alert) Small("EPA UV Alert: higher than normal UV today.", Modifier.padding(top = 4.dp))
        Small(uv.source, Modifier.padding(top = 8.dp))
    }
}

private fun uvAdvice(max: Double, uv: UvForecast, time: TimeFormat): String {
    val strong = uv.hourly.filter { it.index >= 3 }
    val window = if (strong.size >= 2) " between ${time.hour(strong.first().time)} and ${time.hour(strong.last().time.plusSeconds(3600))}" else ""
    return when (UvLevel.of(max)) {
        UvLevel.LOW -> "Little protection needed. Sunglasses on bright days, especially on snow or water."
        UvLevel.MODERATE -> "Sunscreen SPF 30+, a hat and sunglasses$window. Seek shade at midday."
        UvLevel.HIGH -> "Protection needed$window: SPF 30+, hat, sunglasses, and shade at midday."
        UvLevel.VERY_HIGH -> "Extra protection$window: unprotected skin can burn in about 15 minutes."
        UvLevel.EXTREME -> "Avoid the midday sun$window. Unprotected skin can burn in minutes."
    }
}

@Composable
private fun UvBars(uv: UvForecast, now: Instant, time: TimeFormat) {
    val hours = uv.hourly.filter { it.index > 0.05 || it.time.isAfter(now.minusSeconds(3 * 3600)) }.take(16).ifEmpty { uv.hourly.take(16) }
    val maxScale = maxOf(8.0, hours.maxOf { it.index })
    val track = MaterialTheme.colorScheme.surfaceVariant
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
        hours.forEach { h ->
            val isNow = !h.time.isAfter(now) && h.time.plusSeconds(3600).isAfter(now)
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                Canvas(Modifier.fillMaxWidth().height(44.dp)) {
                    val r = CornerRadius(3.dp.toPx(), 3.dp.toPx())
                    drawRoundRect(track, cornerRadius = r)
                    val hgt = (size.height * (h.index / maxScale)).toFloat()
                    if (hgt > 0) {
                        drawRoundRect(
                            uvColor(UvLevel.of(h.index)).copy(alpha = if (isNow) 1f else 0.8f),
                            topLeft = Offset(0f, size.height - hgt),
                            size = Size(size.width, hgt),
                            cornerRadius = r,
                        )
                    }
                }
                Text(
                    time.hour(h.time).replace(" AM", "a").replace(" PM", "p"),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isNow) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
    }
}

// --- Allergies (shown inside the pollen card) ---

@Composable
internal fun AllergyBlock(
    days: List<AllergyDay>,
    hasProfile: Boolean,
    f: UnitFormatter,
    time: TimeFormat,
    onEditAllergies: () -> Unit,
    onDismissPrompt: (() -> Unit)? = null,
) {
    Text("Your allergies", style = MaterialTheme.typography.titleSmall)
    val today = days.firstOrNull()
    if (hasProfile && today == null) {
        Small("Your allergy score appears once today's pollen forecast is in.")
        return
    }
    if (!hasProfile || today == null) {
        Small("Add what you're allergic to (birch, grass, ragweed, mold…) for a daily allergy score.")
        Row {
            TextButton(onClick = onEditAllergies) { Text("Choose my allergies") }
            if (!hasProfile && onDismissPrompt != null) TextButton(onClick = onDismissPrompt) { Text("Not now") }
        }
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
        ScoreRing(today.score, allergyColor(today.level), Modifier.size(52.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("${today.level.label} today", style = MaterialTheme.typography.titleMedium, color = allergyColor(today.level))
            val drivers = today.drivers.take(3).joinToString(", ") { d ->
                d.allergen.label + " " + OutlookText.pollenCategory(d.index.roundToInt()).lowercase() + if (d.estimated && d.allergen.typeCode != null) "*" else ""
            }
            Small(drivers.ifEmpty { "Nothing you react to is in the air" })
        }
    }
    today.tips.take(3).forEach { tip ->
        Row(Modifier.padding(top = 4.dp)) {
            Small("•")
            Spacer(Modifier.width(6.dp))
            Text(OutlookText.tip(tip, f), style = MaterialTheme.typography.bodySmall)
        }
    }
    val known = days.filter { it.pollenKnown }.take(5)
    if (known.size > 1) {
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth()) {
            known.forEach { d ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                    Text(time.weekday(d.date), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${d.score}", style = MaterialTheme.typography.labelLarge, color = allergyColor(d.level))
                }
            }
        }
    }
    if (today.drivers.any { it.estimated && it.allergen.typeCode != null }) {
        Small("* Estimated from overall tree/grass/weed pollen (this plant isn't reported here).", Modifier.padding(top = 8.dp))
    }
    TextButton(onClick = onEditAllergies) { Text("Edit my allergies") }
}
