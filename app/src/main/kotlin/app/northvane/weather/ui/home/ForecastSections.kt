package app.northvane.weather.ui.home

import app.northvane.ui.WeatherIcon
import app.northvane.ui.TemperatureRange
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.northvane.core.model.Activity
import app.northvane.core.outlook.HeatType
import app.northvane.core.outlook.OutlookText
import app.northvane.core.model.DailyForecast
import app.northvane.core.model.DetailSource
import app.northvane.core.model.HourlyForecast
import app.northvane.core.model.OutageFactor
import app.northvane.core.model.OutageLevel
import app.northvane.core.model.OutageRisk
import app.northvane.core.model.SavedLocation
import app.northvane.core.model.Units
import app.northvane.core.util.Solar
import app.northvane.core.util.UnitFormatter
import app.northvane.weather.ui.TimeFormat
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private val RainBlue = Color(0xFF3B8EEA)

/** One row of the consolidated hourly card. */
private enum class HourRow(val label: String, val height: Int) {
    TIME("", 20), ICON("", 36), TEMP("", 28), FEELS("Feels", 20), PRECIP("Precip", 30), AMOUNT("Amount", 20),
    WIND("Wind", 20), GUST("Gusts", 20), HUMIDITY("Humidity", 20), DEWPOINT("Dew pt", 20), CLOUDS("Clouds", 20),
}

/**
 * Everything for the next 36 hours in one tall card: a column per hour, with
 * labelled rows (feels like, precipitation chance and amount, wind and gusts,
 * humidity, dew point, cloud cover). Rows without data are left out.
 */
@Composable
internal fun HourlySection(
    hours: List<HourlyForecast>,
    f: UnitFormatter,
    time: TimeFormat,
    expanded: Boolean = true,
    onToggle: (Boolean) -> Unit = {},
) {
    val shown = hours.take(36)
    val compact = setOf(HourRow.TIME, HourRow.ICON, HourRow.TEMP, HourRow.PRECIP)
    val rows = HourRow.entries.filter { expanded || it in compact }.filter { r ->
        when (r) {
            HourRow.TIME, HourRow.ICON, HourRow.TEMP, HourRow.PRECIP -> true
            HourRow.FEELS -> shown.any { h -> h.apparentTemperatureC != null && h.temperatureC != null }
            HourRow.AMOUNT -> shown.any { (it.precipitationMm ?: 0.0) >= 0.05 || (it.snowMm ?: 0.0) >= 1.0 }
            HourRow.WIND -> shown.any { it.windSpeedKph != null }
            HourRow.GUST -> shown.any { it.windGustKph != null }
            HourRow.HUMIDITY -> shown.any { it.humidityPercent != null }
            HourRow.DEWPOINT -> shown.any { it.dewpointC != null }
            HourRow.CLOUDS -> shown.any { it.skyCoverPercent != null }
        }
    }
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    Column {
        SectionTitle("Hourly")
        Card(
            onClick = { onToggle(!expanded) },
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier.animateContentSize(),
        ) {
            Row(Modifier.padding(top = 10.dp)) {
                // Row labels, fixed on the left while the hours scroll.
                Column(Modifier.padding(start = 14.dp, end = 4.dp).width(70.dp)) {
                    rows.forEach { r ->
                        Box(Modifier.height(r.height.dp), contentAlignment = Alignment.CenterStart) {
                            val label = if (r == HourRow.WIND || r == HourRow.GUST) "${r.label} ${f.windUnit}" else r.label
                            if (label.isNotEmpty()) Text(label, style = MaterialTheme.typography.labelSmall, color = secondary, maxLines = 1)
                        }
                    }
                }
                LazyRow(contentPadding = PaddingValues(end = 8.dp)) {
                    items(shown, key = { it.time.toEpochMilli() }) { hour ->
                        val isNow = hour == shown.first() && hour.time.isBefore(Instant.now())
                        HourColumn(hour, rows, if (isNow) "Now" else time.hour(hour.time), f)
                    }
                }
            }
            Text(
                if (expanded) "Tap for less" else "Tap for wind, humidity, clouds and more",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 10.dp),
            )
        }
    }
}

@Composable
private fun HourColumn(hour: HourlyForecast, rows: List<HourRow>, label: String, f: UnitFormatter) {
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    val cell = MaterialTheme.typography.labelMedium
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(54.dp)) {
        rows.forEach { r ->
            Box(Modifier.height(r.height.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                when (r) {
                    HourRow.TIME -> Text(label, style = cell, color = secondary)
                    HourRow.ICON -> WeatherIcon(hour.condition, hour.isDaytime, size = 30.dp)
                    HourRow.TEMP -> Text(f.temperature(hour.temperatureC), style = MaterialTheme.typography.titleMedium)
                    HourRow.FEELS -> {
                        val feels = hour.apparentTemperatureC
                        val differs = feels != null && hour.temperatureC != null && abs(feels - hour.temperatureC!!) >= 1.5
                        Text(f.temperature(feels), style = cell, color = if (differs) MaterialTheme.colorScheme.onSurface else secondary)
                    }
                    HourRow.PRECIP -> PrecipCell(hour.precipitationChance ?: 0)
                    HourRow.AMOUNT -> {
                        val amount = when {
                            (hour.snowMm ?: 0.0) >= 1.0 -> f.snow(hour.snowMm)
                            (hour.iceMm ?: 0.0) >= 0.25 -> "ice"
                            else -> f.precipitationShort(hour.precipitationMm)
                        }
                        Text(amount, style = MaterialTheme.typography.labelSmall, color = RainBlue, maxLines = 1)
                    }
                    HourRow.WIND -> Row(verticalAlignment = Alignment.CenterVertically) {
                        hour.windDirectionDeg?.let { WindArrow(it, Modifier.size(11.dp)) }
                        Spacer(Modifier.width(2.dp))
                        Text(f.windNumber(hour.windSpeedKph), style = cell)
                    }
                    HourRow.GUST -> {
                        val gust = hour.windGustKph
                        val notable = gust != null && gust - (hour.windSpeedKph ?: 0.0) >= 8
                        Text(f.windNumber(gust), style = cell, color = if (notable) MaterialTheme.colorScheme.onSurface else secondary)
                    }
                    HourRow.HUMIDITY -> Text(f.percent(hour.humidityPercent), style = cell)
                    HourRow.DEWPOINT -> Text(f.temperature(hour.dewpointC), style = cell, color = secondary)
                    HourRow.CLOUDS -> Text(hour.skyCoverPercent?.let { "$it%" } ?: "--", style = cell, color = secondary)
                }
            }
        }
    }
}

/** Chance of precipitation as a number over a small bar that fills with the chance. */
@Composable
private fun PrecipCell(chance: Int) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "$chance%",
            style = MaterialTheme.typography.labelMedium,
            color = if (chance >= 10) RainBlue else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Canvas(Modifier.padding(top = 2.dp).width(30.dp).height(4.dp)) {
            val r = CornerRadius(size.height / 2, size.height / 2)
            drawRoundRect(track, cornerRadius = r)
            if (chance > 0) drawRoundRect(RainBlue, size = Size(size.width * chance / 100f, size.height), cornerRadius = r)
        }
    }
}

/** Arrow pointing where the wind blows *to* (meteorological direction is where it comes from). */
@Composable
private fun WindArrow(fromDegrees: Double, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier.rotate((fromDegrees + 180).toFloat())) {
        val w = size.width
        val path = Path().apply {
            moveTo(w / 2, 0f)
            lineTo(w * 0.85f, w * 0.7f)
            lineTo(w / 2, w * 0.55f)
            lineTo(w * 0.15f, w * 0.7f)
            close()
        }
        drawLine(color, Offset(w / 2, w * 0.5f), Offset(w / 2, w), strokeWidth = w * 0.14f, cap = StrokeCap.Round)
        drawPath(path, color)
    }
}

@Composable
internal fun DailySection(
    days: List<DailyForecast>,
    f: UnitFormatter,
    time: TimeFormat,
    showOutage: Boolean,
    activities: Set<Activity> = emptySet(),
    showMud: Boolean = false,
    heatTypes: List<HeatType> = emptyList(),
    showPipes: Boolean = false,
    showIce: Boolean = false,
) {
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    val temps = days.flatMap { listOfNotNull(it.lowC, it.highC) }
    val min = temps.minOrNull() ?: 0.0
    val max = temps.maxOrNull() ?: 1.0
    Column {
        SectionTitle("${days.size}-day forecast")
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
            Column(Modifier.padding(vertical = 4.dp)) {
                days.forEachIndexed { index, day ->
                    val key = day.date.toString()
                    val isOpen = expanded == key
                    // No panel on days with nothing that could knock the power out.
                    val outage = day.outageRisk?.takeIf { showOutage && it.hasRisk }
                    val pipes = day.pipeRisk?.takeIf { showPipes }
                    val ice = day.morningIce?.takeIf { showIce }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { expanded = if (isOpen) null else key }
                            .animateContentSize()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Row(Modifier.width(80.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (day.highC == null && index == 0) day.name else time.dayName(day.date),
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                )
                                if (outage != null && outage.level >= OutageLevel.ELEVATED) {
                                    Spacer(Modifier.width(4.dp))
                                    PowerGlyph(outageColor(outage.level), Modifier.size(14.dp))
                                }
                                // One cold-weather mark: black ice first, then serious pipe risk.
                                if (ice?.blackIce != null) {
                                    Spacer(Modifier.width(3.dp))
                                    IceGlyph(iceColor(ice), Modifier.size(12.dp))
                                } else if (pipes != null && pipes.level >= OutageLevel.HIGH) {
                                    Spacer(Modifier.width(3.dp))
                                    DropGlyph(outageColor(pipes.level), Modifier.size(12.dp))
                                }
                            }
                            WeatherIcon(day.condition, day.isDaytimeCondition, size = 30.dp)
                            Text(
                                day.precipitationChance?.takeIf { it >= 10 }?.let { "$it%" } ?: "",
                                style = MaterialTheme.typography.labelMedium,
                                color = RainBlue,
                                textAlign = TextAlign.End,
                                modifier = Modifier.width(40.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                f.temperature(day.lowC),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.End,
                                modifier = Modifier.width(40.dp),
                            )
                            TemperatureRange(day.lowC, day.highC, min, max, Modifier.weight(1f).padding(horizontal = 10.dp))
                            Text(
                                f.temperature(day.highC),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.width(40.dp),
                            )
                        }
                        if (isOpen) {
                            Spacer(Modifier.height(12.dp))
                            DayDetails(day, f)
                            Spacer(Modifier.height(10.dp))
                            day.dayDetail?.let { DetailParagraph("Day", it) }
                            day.nightDetail?.let { DetailParagraph("Night", it) }
                            if (day.dayDetail == null && day.nightDetail == null) {
                                day.shortForecast?.let { DetailParagraph(null, it) }
                            }
                            outage?.let {
                                Spacer(Modifier.height(4.dp))
                                OutagePanel(it, f, heatTypes)
                            }
                            ice?.let {
                                Spacer(Modifier.height(8.dp))
                                IcePanel(it, f, time)
                            }
                            pipes?.let {
                                Spacer(Modifier.height(8.dp))
                                PipePanel(it, f, heatTypes)
                            }
                        }
                    }
                    if (index < days.lastIndex) {
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

/** Two-column grid of the day's numbers. */
@Composable
private fun DayDetails(day: DailyForecast, f: UnitFormatter) {
    val items = buildList {
        if (day.windMaxKph != null || day.gustMaxKph != null) {
            val dir = day.windDirectionDeg?.let { Units.compass(it) + " " }.orEmpty()
            val gust = day.gustMaxKph?.let { " · gusts ${f.windNumber(it)}" }.orEmpty()
            add("Wind" to "$dir${f.windNumber(day.windMaxKph)} ${f.windUnit}$gust")
        } else {
            day.windSummary?.let { add("Wind" to it) }
        }
        val pop = day.precipitationChance?.let { "$it%" }
        val amount = day.precipitationMm?.let { f.precipitation(it) }
        if (pop != null || amount != null) add("Precipitation" to listOfNotNull(pop, amount).joinToString(" · "))
        day.snowMm?.takeIf { it >= 2.5 }?.let { add("Snow" to f.snow(it)) }
        day.iceMm?.takeIf { it > 0.0 }?.let { add("Ice" to f.precipitation(it)) }
        if (day.humidityMin != null && day.humidityMax != null) {
            add("Humidity" to "${day.humidityMin!!.roundToInt()}–${day.humidityMax!!.roundToInt()}%")
        }
        day.thunderChance?.takeIf { it >= 10 }?.let { add("Thunder" to "$it%") }
        day.skyCoverPercent?.let { add("Cloud cover" to "$it%") }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.chunked(2).forEach { row ->
            Row {
                row.forEach { (label, value) ->
                    Column(Modifier.weight(1f)) {
                        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(value, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        if (day.detailSource == DetailSource.TEXT) {
            Text(
                "Amounts read from the forecast text (detailed data is off for this connection).",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal fun outageColor(level: OutageLevel): Color = when (level) {
    OutageLevel.LOW -> Color(0xFF4CAF7A)
    OutageLevel.ELEVATED -> Color(0xFFE0A800)
    OutageLevel.HIGH -> Color(0xFFEF6C00)
    OutageLevel.VERY_HIGH -> Color(0xFFD32F2F)
}

internal fun describe(factor: OutageFactor, f: UnitFormatter): String = when (factor.kind) {
    OutageFactor.Kind.GUSTS -> "Gusts to ${f.windSpeed(factor.value)}"
    OutageFactor.Kind.SUSTAINED_WIND -> "Sustained wind to ${f.windSpeed(factor.value)}"
    OutageFactor.Kind.LEAVES_ON -> "Trees in leaf catch more wind"
    OutageFactor.Kind.LEAVES_OFF -> "Bare trees catch less wind this time of year"
    OutageFactor.Kind.FROZEN_GROUND -> "Frozen ground holds roots firm"
    OutageFactor.Kind.SOFT_GROUND -> "Thawing, soggy ground lets trees topple"
    OutageFactor.Kind.SATURATED_GROUND -> "${f.precipitation(factor.value)} of rain softens the ground"
    OutageFactor.Kind.ICE -> "${f.precipitation(factor.value)} of ice"
    OutageFactor.Kind.WET_SNOW -> "${f.snow(factor.value)} of heavy, wet snow"
    OutageFactor.Kind.SNOW -> "${f.snow(factor.value)} of snow"
    OutageFactor.Kind.THUNDER -> "Thunderstorms (${factor.value?.roundToInt()}% chance)"
    OutageFactor.Kind.HEAT -> "Heat (high ${f.temperature(factor.value)})"
    OutageFactor.Kind.ALERT -> factor.detail ?: "NWS alert"
}

@Composable
private fun OutagePanel(risk: OutageRisk, f: UnitFormatter, heatTypes: List<HeatType>) {
    val color = outageColor(risk.level)
    val canPrepare = risk.level >= OutageLevel.HIGH
    var showPrep by rememberSaveable(risk) { mutableStateOf(false) }
    Surface(
        onClick = { if (canPrepare) showPrep = !showPrep },
        enabled = canPrepare,
        shape = RoundedCornerShape(14.dp),
        color = color.copy(alpha = 0.12f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp).animateContentSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PowerGlyph(color, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Power outage risk: ${risk.level.label}", style = MaterialTheme.typography.titleSmall, color = color)
            }
            val reasons = risk.factors.take(4).map { describe(it, f) }
            Text(
                if (reasons.isEmpty()) "No significant wind, ice or heavy snow expected." else reasons.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (canPrepare && !showPrep) {
                Text("Tap for how to prepare", style = MaterialTheme.typography.labelMedium, color = color, modifier = Modifier.padding(top = 6.dp))
            }
            if (showPrep) {
                Text("Get ready for an outage", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                OutlookText.outagePrep(risk, heatTypes).forEach { step ->
                    Row(Modifier.padding(vertical = 2.dp)) {
                        Text("•", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.width(6.dp))
                        Text(step, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

/** A small lightning-bolt glyph used for power outage risk. */
@Composable
internal fun PowerGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.62f, 0f)
            lineTo(w * 0.12f, h * 0.58f)
            lineTo(w * 0.46f, h * 0.58f)
            lineTo(w * 0.36f, h)
            lineTo(w * 0.88f, h * 0.38f)
            lineTo(w * 0.54f, h * 0.38f)
            close()
        }
        drawPath(path, color)
    }
}

@Composable
private fun DetailParagraph(label: String?, text: String) {
    Text(
        (label?.let { "$it: " } ?: "") + text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

/**
 * Sunrise/sunset as the sun's path across the sky: a dashed arc for the whole
 * day, a filled arc for the part already travelled, and the sun at its current
 * position. Shows day length and how it changed since yesterday.
 */
@Composable
internal fun SunCard(location: SavedLocation, zone: ZoneId, time: TimeFormat) {
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            now = Instant.now()
        }
    }
    val today = now.atZone(zone).toLocalDate()
    val (rise, set) = remember(location.latitude, location.longitude, today, zone) {
        Solar.sunTimes(location.latitude, location.longitude, today, zone)
    }
    val (yRise, ySet) = remember(location.latitude, location.longitude, today, zone) {
        Solar.sunTimes(location.latitude, location.longitude, today.minusDays(1), zone)
    }
    val tomorrowRise = remember(location.latitude, location.longitude, today, zone) {
        Solar.sunTimes(location.latitude, location.longitude, today.plusDays(1), zone).first
    }

    val sunColor = Color(0xFFFFB627)
    val trackColor = MaterialTheme.colorScheme.outline
    val horizonColor = MaterialTheme.colorScheme.outlineVariant
    val cardColor = MaterialTheme.colorScheme.surfaceContainer
    val fraction: Float? = if (rise != null && set != null && now.isAfter(rise) && now.isBefore(set)) {
        (Duration.between(rise, now).seconds.toFloat() / Duration.between(rise, set).seconds.toFloat())
    } else {
        null
    }

    Column {
        SectionTitle("Sun")
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp)) {
                val headline = when {
                    rise == null || set == null -> if (Solar.isDaylight(location.latitude, location.longitude, now)) "Sun up all day" else "Sun down all day"
                    now.isBefore(rise) -> "Sunrise in ${span(Duration.between(now, rise))}"
                    now.isBefore(set) -> "Sunset in ${span(Duration.between(now, set))}"
                    tomorrowRise != null -> "Sunrise in ${span(Duration.between(now, tomorrowRise))}"
                    else -> "After sunset"
                }
                Text(headline, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                // Easter egg: long-press the sun and it puts on sunglasses.
                var cool by remember { mutableStateOf(false) }
                LaunchedEffect(cool) {
                    if (cool) {
                        delay(4_000)
                        cool = false
                    }
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(96.dp)
                        .pointerInput(Unit) { detectTapGestures(onLongPress = { cool = true }) },
                ) {
                    Canvas(Modifier.matchParentSize()) {
                        val pad = 18.dp.toPx()
                        val baseY = size.height - 10.dp.toPx()
                        val rx = (size.width - 2 * pad) / 2
                        val ry = baseY - 12.dp.toPx()
                        val cx = size.width / 2
                        val topLeft = Offset(cx - rx, baseY - ry)
                        val arcSize = Size(2 * rx, 2 * ry)
                        drawLine(horizonColor, Offset(0f, baseY), Offset(size.width, baseY), strokeWidth = 1.dp.toPx())
                        drawArc(
                            trackColor, 180f, 180f, false, topLeft, arcSize,
                            style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))),
                        )
                        if (fraction != null) {
                            // Cover the dashed track where the sun has already been.
                            drawArc(cardColor, 180f, 180f * fraction, false, topLeft, arcSize, style = Stroke(width = 5.dp.toPx()))
                            drawArc(
                                Brush.horizontalGradient(listOf(sunColor.copy(alpha = 0.75f), sunColor)),
                                180f, 180f * fraction, false, topLeft, arcSize,
                                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
                            )
                            val angle = Math.PI * (1 - fraction)
                            val sun = Offset(cx + (rx * cos(angle)).toFloat(), baseY - (ry * sin(angle)).toFloat())
                            drawCircle(sunColor.copy(alpha = 0.25f), 14.dp.toPx(), sun)
                            drawCircle(sunColor, 8.dp.toPx(), sun)
                            if (cool) {
                                // Tiny sunglasses: two lenses and a bridge.
                                val lens = 3.6.dp.toPx()
                                drawCircle(Color(0xFF1B1B1B), lens, Offset(sun.x - lens * 1.15f, sun.y - 1.dp.toPx()))
                                drawCircle(Color(0xFF1B1B1B), lens, Offset(sun.x + lens * 1.15f, sun.y - 1.dp.toPx()))
                                drawLine(Color(0xFF1B1B1B), Offset(sun.x - lens * 0.3f, sun.y - 2.dp.toPx()), Offset(sun.x + lens * 0.3f, sun.y - 2.dp.toPx()), 1.5.dp.toPx())
                            }
                        } else {
                            // Night: the sun waits on the horizon at the sunrise end.
                            drawCircle(trackColor, 4.dp.toPx(), Offset(cx - rx, baseY))
                            drawCircle(trackColor, 4.dp.toPx(), Offset(cx + rx, baseY))
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.Top) {
                    SunLabel("Sunrise", rise?.let(time::time) ?: "--", Alignment.Start, Modifier.weight(1f))
                    val length = if (rise != null && set != null) Duration.between(rise, set) else null
                    val yLength = if (yRise != null && ySet != null) Duration.between(yRise, ySet) else null
                    val change = if (length != null && yLength != null) {
                        val delta = length.seconds - yLength.seconds
                        val minutes = (abs(delta) / 60.0).roundToInt()
                        when {
                            minutes == 0 -> "same as yesterday"
                            delta > 0 -> "+$minutes min vs yesterday"
                            else -> "−$minutes min vs yesterday"
                        }
                    } else {
                        null
                    }
                    SunLabel("Daylight", length?.let(::span) ?: "--", Alignment.CenterHorizontally, Modifier.weight(1f), change)
                    SunLabel("Sunset", set?.let(time::time) ?: "--", Alignment.End, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun SunLabel(label: String, value: String, align: Alignment.Horizontal, modifier: Modifier, note: String? = null) {
    Column(modifier, horizontalAlignment = align) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleSmall)
        note?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

private fun span(d: Duration): String {
    val total = d.toMinutes().coerceAtLeast(0)
    val h = total / 60
    val m = total % 60
    return if (h == 0L) "$m min" else "${h}h ${m}m"
}
