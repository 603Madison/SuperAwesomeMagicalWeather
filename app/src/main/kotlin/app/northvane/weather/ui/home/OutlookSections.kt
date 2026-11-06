package app.northvane.weather.ui.home

import app.northvane.weather.ui.components.WeatherCard
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.northvane.core.model.Activity
import app.northvane.core.model.ActivityScore
import app.northvane.core.model.DailyForecast
import app.northvane.core.model.FoliageReport
import app.northvane.core.model.FoliageStage
import app.northvane.core.model.MudLevel
import app.northvane.core.model.MudOutlook
import app.northvane.core.model.PollenForecast
import app.northvane.core.model.PollenReading
import app.northvane.core.model.ScoreBand
import app.northvane.core.model.Tip
import app.northvane.core.outlook.OutlookText
import app.northvane.core.util.UnitFormatter
import app.northvane.weather.ui.TimeFormat
import androidx.compose.runtime.remember
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.AlertDialog
import java.time.Instant
import java.time.ZoneId
import app.northvane.core.model.HourlyForecast
import app.northvane.core.outlook.BestWindow

internal fun bandColor(band: ScoreBand): Color = when (band) {
    ScoreBand.GREAT -> Color(0xFF2E9E5B)
    ScoreBand.GOOD -> Color(0xFF7CB342)
    ScoreBand.FAIR -> Color(0xFFE0A800)
    ScoreBand.POOR -> Color(0xFFEF6C00)
    ScoreBand.BAD -> Color(0xFFD32F2F)
}

internal fun mudColor(level: MudLevel): Color = when (level) {
    MudLevel.SNOW_COVERED -> Color(0xFF64B5F6)
    MudLevel.FROZEN -> Color(0xFF90A4AE)
    MudLevel.DRY -> Color(0xFF8BC34A)
    MudLevel.DAMP -> Color(0xFFC0A15A)
    MudLevel.MUDDY -> Color(0xFF8D6E43)
    MudLevel.VERY_MUDDY -> Color(0xFF5D4027)
}

internal fun pollenColor(index: Int?): Color = when (index) {
    null, 0 -> Color(0xFF9E9E9E)
    1 -> Color(0xFF4CAF50)
    2 -> Color(0xFF9CCC65)
    3 -> Color(0xFFFFC107)
    4 -> Color(0xFFFF7043)
    else -> Color(0xFFD32F2F)
}

@Composable
private fun OutlookCard(content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) { content() }
}

@Composable
private fun Muted(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

// --- Activities ---

/**
 * The user's activities for a day they pick: when to go (the best few hours,
 * where the hourly forecast reaches), the score, and what decides it. Tap a
 * row for all the tips; "Choose activities" adds or removes them.
 */
@Composable
internal fun ActivitiesSection(
    days: List<DailyForecast>,
    hourly: List<HourlyForecast>,
    zone: ZoneId,
    activities: Set<Activity>,
    f: UnitFormatter,
    time: TimeFormat,
    onSetActivity: (Activity, Boolean) -> Unit = { _, _ -> },
) {
    val scored = days.filter { d -> d.activities.any { it.activity in activities } }.take(7)
    var picking by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableIntStateOf(0) }
    WeatherCard("Outdoor outlook", inset = false) {
        if (scored.isEmpty()) {
            Muted("None of your activities are in season right now.", Modifier.padding(horizontal = 16.dp))
        } else {
            val index = selected.coerceIn(0, scored.lastIndex)
            val day = scored[index]
            Column(Modifier.padding(bottom = 4.dp)) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    scored.forEachIndexed { i, d ->
                        val label = when {
                            d.date == time.today() -> "Today"
                            d.date == time.today().plusDays(1) -> "Tomorrow"
                            else -> time.dayName(d.date)
                        }
                        FilterChip(selected = i == index, onClick = { selected = i }, label = { Text(label) })
                    }
                }
                val rows = day.activities.filter { it.activity in activities }
                val now = Instant.now()
                rows.forEachIndexed { i, score ->
                    val window = remember(hourly, day.date, score.activity) { BestWindow.find(score.activity, hourly, day.date, zone, now) }
                    ActivityRow(score, window, f, time)
                    if (i < rows.lastIndex) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
        TextButton(onClick = { picking = true }, modifier = Modifier.padding(start = 4.dp)) { Text("Choose activities") }
    }
    if (picking) {
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text("Your activities") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Activity.entries.forEach { a ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable { onSetActivity(a, a !in activities) },
                        ) {
                            Checkbox(checked = a in activities, onCheckedChange = { onSetActivity(a, it) })
                            Text(a.label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    Muted("Snow and ice activities show up only when there's snow or ice.", Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = { TextButton(onClick = { picking = false }) { Text("Done") } },
        )
    }
}

/** "Best 1–4 PM · dry, 59°", or "Clearest 11 PM–1 AM · 5% cloud" for stargazing. */
private fun windowText(w: BestWindow.Window, activity: Activity, f: UnitFormatter, time: TimeFormat): String {
    val span = "${time.hour(w.start)}–${time.hour(w.end)}"
    return if (activity == Activity.STARGAZING) {
        "Clearest $span" + (w.cloudPercent?.let { " · $it% cloud" } ?: "")
    } else {
        val wet = if (w.chance >= 30) "${w.chance}% chance of rain" else "dry"
        "Best $span · $wet" + (w.tempC?.let { ", ${f.temperature(it)}" } ?: "")
    }
}

@Composable
private fun ActivityRow(score: ActivityScore, window: BestWindow.Window?, f: UnitFormatter, time: TimeFormat) {
    var open by rememberSaveable(score.activity) { mutableStateOf(false) }
    val color = bandColor(score.band)
    // What decides the score: the main problem, only when there is one.
    val decider = score.tips.firstOrNull()?.takeIf { score.band != ScoreBand.GREAT }?.let { OutlookText.tip(it, f) }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { open = !open }
            .animateContentSize()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ScoreRing(score.score, color, Modifier.size(44.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(score.label, style = MaterialTheme.typography.titleSmall)
                    if (score.activity.overnight) {
                        Spacer(Modifier.width(6.dp))
                        Text("overnight", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                val line = listOfNotNull(window?.let { windowText(it, score.activity, f, time) }, decider)
                    .ifEmpty { listOf(score.band.label) }
                    .joinToString(" · ")
                Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = if (open) 4 else 2)
            }
        }
        if (open && score.tips.isNotEmpty()) {
            Column(Modifier.padding(start = 58.dp, top = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                score.tips.forEach { tip -> TipLine(tip, f) }
            }
        }
    }
}

@Composable
private fun TipLine(tip: Tip, f: UnitFormatter) {
    Row(verticalAlignment = Alignment.Top) {
        Text("•", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        Text(OutlookText.tip(tip, f), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun ScoreRing(score: Int, color: Color, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val stroke = size.minDimension * 0.11f
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            drawArc(color, -90f, 360f * score / 100f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Text("$score", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
}

/** Compact row of activity scores for the expanded daily forecast. */
@Composable
internal fun ActivityStrip(scores: List<ActivityScore>) {
    if (scores.isEmpty()) return
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        scores.forEach { s ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ScoreRing(s.score, bandColor(s.band), Modifier.size(34.dp))
                Text(s.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun PollenSection(
    pollen: PollenForecast?,
    hasKey: Boolean,
    time: TimeFormat,
    onOpenSources: () -> Unit,
    allergyContent: (@Composable () -> Unit)? = null,
) {
    WeatherCard("Pollen", inset = false) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
            val today = pollen?.days?.firstOrNull()
            when {
                !hasKey -> {
                    Muted("Pollen forecasts need a free Google Pollen API key. No NOAA source covers pollen.")
                    TextButton(onClick = onOpenSources) { Text("Add a key in Data sources") }
                }
                today == null -> Muted("Pollen forecast isn't available yet. Pull to refresh.")
                else -> {
                    val worst = today.worst
                    Text(
                        worst?.let { "${OutlookText.pollenCategory(it.index)} · ${it.name.lowercase()} pollen" } ?: "No pollen in season",
                        style = MaterialTheme.typography.titleMedium,
                        color = pollenColor(worst?.index),
                    )
                    Spacer(Modifier.height(10.dp))
                    today.types.forEach { PollenBar(it) }
                    val plants = today.topPlants.take(4)
                    if (plants.isNotEmpty()) {
                        Muted("Main sources: " + plants.joinToString(", ") { "${it.name} (${OutlookText.pollenCategory(it.index).lowercase()})" }, Modifier.padding(top = 6.dp))
                    }
                    today.tips.firstOrNull()?.let { Muted(it, Modifier.padding(top = 4.dp)) }
                    if (pollen.days.size > 1) {
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth()) {
                            pollen.days.forEach { d ->
                                val w = d.worst
                                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                                    Text(time.weekday(d.date), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(
                                        OutlookText.pollenCategory(w?.index).replace("Very ", "V. "),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = pollenColor(w?.index),
                                        textAlign = TextAlign.Center,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                    allergyContent?.let {
                        HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        it()
                    }
                    Muted("Google Pollen API", Modifier.padding(top = 10.dp))
                }
            }
        }
    }
}

@Composable
private fun PollenBar(reading: PollenReading) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val color = pollenColor(reading.index)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
        Text(reading.name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(56.dp))
        Canvas(Modifier.weight(1f).height(8.dp)) {
            val r = CornerRadius(size.height / 2, size.height / 2)
            drawRoundRect(track, cornerRadius = r)
            val v = (reading.index ?: 0) / 5f
            if (v > 0) drawRoundRect(color, size = Size(size.width * v, size.height), cornerRadius = r)
        }
        Text(
            if (reading.inSeason) OutlookText.pollenCategory(reading.index) else "Out of season",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.width(92.dp),
        )
    }
}

// --- Foliage ---

private val FoliageGradient = listOf(Color(0xFF4F8F3A), Color(0xFFC9B23A), Color(0xFFE8892B), Color(0xFFC0392B), Color(0xFF8D6E63))

@Composable
internal fun FoliageSection(report: FoliageReport, time: TimeFormat) {
    val today = report.today ?: return
    val uri = LocalUriHandler.current
    WeatherCard("Fall foliage", inset = false) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
            Text(today.stage.label, style = MaterialTheme.typography.titleMedium)
            val peak = report.estimatedPeak
            val peakText = when {
                peak.isAfter(time.today()) -> "Estimated peak around ${time.monthDay(peak)}"
                peak == time.today() -> "Estimated peak is about now"
                else -> "Peaked around ${time.monthDay(peak)}"
            }
            Muted("$peakText · about ${today.colorPercent}% color")
            Spacer(Modifier.height(12.dp))
            FoliageBar(today.stage)
            Spacer(Modifier.height(4.dp))
            Row {
                Text("Green", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text("Peak", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                Text("Bare", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
            }
            val drops = report.days.filter { it.leafDrop }
            if (drops.isNotEmpty()) {
                Muted(
                    "Wind or heavy rain " + drops.joinToString(" and ") { time.dayName(it.date) } + " will bring down many leaves.",
                    Modifier.padding(top = 8.dp),
                )
            }
            report.notes.forEach { Muted(OutlookText.foliageNote(it), Modifier.padding(top = 4.dp)) }
            Muted(
                "Estimated from location, elevation and recent NOAA temperatures. Check the official reports:",
                Modifier.padding(top = 8.dp),
            )
            Row {
                TextButton(onClick = { uri.openUri("https://mainefoliage.com/") }) { Text("Maine") }
                TextButton(onClick = { uri.openUri("https://www.visitnh.gov/") }) { Text("New Hampshire") }
            }
        }
    }
}

@Composable
private fun FoliageBar(stage: FoliageStage) {
    val marker = MaterialTheme.colorScheme.onSurface
    val position = when (stage) {
        FoliageStage.GREEN -> 0.04f
        FoliageStage.TURNING -> 0.2f
        FoliageStage.MODERATE -> 0.35f
        FoliageStage.NEAR_PEAK -> 0.45f
        FoliageStage.PEAK -> 0.55f
        FoliageStage.PAST_PEAK -> 0.75f
        FoliageStage.BARE -> 0.95f
    }
    Canvas(Modifier.fillMaxWidth().height(14.dp)) {
        val barTop = size.height * 0.3f
        val barHeight = size.height * 0.4f
        drawRoundRect(
            Brush.horizontalGradient(FoliageGradient),
            topLeft = Offset(0f, barTop),
            size = Size(size.width, barHeight),
            cornerRadius = CornerRadius(barHeight / 2, barHeight / 2),
        )
        val x = size.width * position
        drawCircle(Color.White, size.height / 2, Offset(x, size.height / 2))
        drawCircle(marker, size.height / 2, Offset(x, size.height / 2), style = Stroke(2.dp.toPx()))
    }
}
