package app.northvane.weather.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.northvane.core.garden.GardenReport
import app.northvane.core.garden.HarvestAdvice
import app.northvane.core.garden.HarvestStatus
import app.northvane.core.garden.PlantingAdvice
import app.northvane.core.garden.PlantingStep
import app.northvane.core.garden.SoilState
import app.northvane.core.garden.StepStatus
import app.northvane.core.garden.WateringAction
import app.northvane.core.garden.Crop
import app.northvane.core.garden.GardenAdvisor
import app.northvane.core.model.Units
import app.northvane.core.outlook.DryingBlocker
import app.northvane.core.outlook.DryingReport
import app.northvane.core.outlook.HeatingPhase
import app.northvane.core.outlook.HeatingReport
import app.northvane.core.outlook.OutlookText
import app.northvane.core.util.UnitFormatter
import app.northvane.weather.data.AppSettings
import app.northvane.weather.ui.TimeFormat
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

@Composable
internal fun SeasonCard(content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() } }
}

@Composable
internal fun Note(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = color, modifier = modifier)

@Composable
internal fun StatusDot(color: Color) = Canvas(Modifier.padding(top = 5.dp).size(10.dp)) { drawCircle(color) }

internal val Good = Color(0xFF2E9E5B)
internal val Caution = Color(0xFFEF7D1A)
internal val Stop = Color(0xFFD32F2F)
internal val Neutral = Color(0xFF7A8794)

// --- Garden ---

/** One garden card: each plant with its current planting step and/or harvest outlook. */
@Composable
internal fun GardenCard(
    report: GardenReport,
    settings: AppSettings,
    f: UnitFormatter,
    time: TimeFormat,
    onMarkPlanted: (Crop) -> Unit,
) {
    val today = time.today()
    val plants = GardenAdvisor.plants(report, today, planting = settings.gardenPlanting, harvest = settings.gardenHarvest)
    if (plants.isEmpty() && !report.inGrowingSeason) return
    val c = report.climate
    Column {
        SectionTitle("Garden")
        SeasonCard {
            Note(
                "Last spring frost usually ${time.monthDay(c.lastFrost(today.year))} (1 year in 10: ${time.monthDay(c.lastFrostLate(today.year))}); " +
                    "first fall frost ${time.monthDay(c.firstFrost(today.year))}. From 30 years of NOAA data.",
            )
            var all by rememberSaveable { mutableStateOf(false) }
            val shown = if (all) plants else plants.take(8)
            shown.forEach { p ->
                Column(Modifier.padding(top = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p.crop.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        val step = p.planting
                        if (step != null && step.step != PlantingStep.START_INDOORS && step.step != PlantingStep.HARDEN_OFF &&
                            step.status in setOf(StepStatus.NOW, StepStatus.GOOD_DAY, StepStatus.LATE)
                        ) {
                            TextButton(onClick = { onMarkPlanted(p.crop) }) { Text("Planted") }
                        }
                    }
                    p.planting?.let { PlantingLine(it, f, time, today) }
                    p.harvest?.let { HarvestLine(it, f, time) }
                }
            }
            if (plants.size > shown.size) TextButton(onClick = { all = true }) { Text("Show ${plants.size - shown.size} more") }
            if (plants.any { it.harvest?.estimated == true }) Note("~ Estimated from the recommended planting date; mark crops planted for exact timing.")
            GardenExtras(report, settings, f, time)
        }
    }
}

@Composable
private fun PlantingLine(a: PlantingAdvice, f: UnitFormatter, time: TimeFormat, today: LocalDate) {
    val (color, detail) = when (a.status) {
        StepStatus.UPCOMING -> Neutral to "Starts ${time.monthDay(a.windowStart)} (${inDays(ChronoUnit.DAYS.between(today, a.windowStart))})"
        StepStatus.NOW -> Good to "Now through ${time.monthDay(a.windowEnd)}"
        StepStatus.GOOD_DAY -> Good to "${a.recommended?.let { if (it == today) "Today" else time.dayName(it) } ?: "Now"} looks good: no frost in the forecast"
        StepStatus.WAIT_FROST -> Caution to "Wait: ${a.blockingDate?.let(time::dayName) ?: "a"} night dips to ${a.blockingValue?.let { f.temperatureWithUnit(Units.fToC(it)) } ?: "freezing"}"
        StepStatus.WAIT_SOIL -> Caution to "Wait for warmer soil: about ${a.blockingValue?.let { f.temperatureWithUnit(Units.fToC(it)) } ?: "--"} now, needs ${f.temperatureWithUnit(Units.fToC(a.crop.minSoilF.toDouble()))}"
        StepStatus.LATE -> Stop to if (a.step == PlantingStep.START_INDOORS) "Late to start from seed: buy transplants instead" else "Past the usual window (${time.monthDay(a.windowEnd)})"
        StepStatus.DONE -> Neutral to "Done"
    }
    val action = when (a.step) {
        PlantingStep.START_INDOORS -> "Start indoors"
        PlantingStep.HARDEN_OFF -> "Harden off (a few hours outside daily, building up)"
        PlantingStep.TRANSPLANT -> "Plant out"
        PlantingStep.DIRECT_SOW -> "Sow outdoors"
        PlantingStep.FALL_PLANT -> "Plant cloves for next summer"
    }
    Row(verticalAlignment = Alignment.Top) {
        StatusDot(color)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(action, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Note(detail, color = if (color == Neutral) MaterialTheme.colorScheme.onSurfaceVariant else color)
        }
    }
}

@Composable
private fun HarvestLine(h: HarvestAdvice, f: UnitFormatter, time: TimeFormat) {
    val tilde = if (h.estimated) "~" else ""
    val frost = h.frostDate?.let { "${time.dayName(it)} (${f.temperatureWithUnit(h.frostLowC)})" } ?: ""
    val (color, detail) = when (h.status) {
        HarvestStatus.GROWING -> Neutral to "Ready around $tilde${time.monthDay(h.start)}"
        HarvestStatus.READY -> Good to "Ready now, through $tilde${time.monthDay(h.end)}"
        HarvestStatus.PICK_BEFORE_FROST -> Stop to "Pick before the frost $frost" + if (h.crop == Crop.TOMATO) "; green tomatoes ripen indoors" else ""
        HarvestStatus.COVER_FOR_FROST -> Caution to "Cover plants for the frost $frost"
        HarvestStatus.AFTER_FROST_SWEETER -> Good to "Frost $frost sweetens it, so harvest after"
        HarvestStatus.FINISHED -> Neutral to "Done for the season"
    }
    Row(verticalAlignment = Alignment.Top) {
        StatusDot(color)
        Spacer(Modifier.width(8.dp))
        Column {
            Text("Harvest", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Note(detail, color = if (color == Neutral) MaterialTheme.colorScheme.onSurfaceVariant else color)
        }
    }
}

@Composable
private fun GardenExtras(report: GardenReport, settings: AppSettings, f: UnitFormatter, time: TimeFormat) {
    val lines = buildList {
        if (settings.gardenFrostAlerts && report.frostNights.isNotEmpty()) {
            add(Stop to "Frost risk: " + report.frostNights.joinToString(", ") { "${time.dayName(it.date)} ${f.temperatureWithUnit(it.lowC)}" } +
                ". Cover tender plants or bring pots in.")
        }
        if (settings.gardenSoil) {
            val soil = report.soil?.let { s ->
                when (s) {
                    SoilState.FROZEN -> "Ground frozen"
                    SoilState.TOO_WET -> "Soil too wet to work: squeeze a handful; if it drips or stays in a ball, wait"
                    SoilState.WORKABLE -> "Soil is workable"
                    SoilState.DRY -> "Soil is dry: water before planting"
                }
            }
            val temp = report.soilTempF?.let { "soil about ${f.temperatureWithUnit(Units.fToC(it))} (estimated)" }
            listOfNotNull(soil, temp).takeIf { it.isNotEmpty() }?.let { add(Neutral to it.joinToString(" · ")) }
        }
        if (settings.gardenWatering) {
            report.watering?.let { w ->
                val past = "${f.precipitation(w.pastWeekMm)} of rain in the past week"
                add(
                    when (w.action) {
                        WateringAction.WATER -> Caution to "Water deeply: $past; aim for about an inch a week"
                        WateringAction.LIGHT_WATER -> Neutral to "Light watering: $past"
                        WateringAction.SKIP_RAIN_COMING -> Good to "Skip watering: ${f.precipitation(w.comingMm)} of rain expected"
                        WateringAction.NOT_NEEDED -> Good to "No watering needed: $past"
                    },
                )
            }
        }
        if (settings.gardenGdd) report.gddBase50?.let { add(Neutral to "Growing degree days since Mar 1: ${it.roundToInt()} (base 50°F)") }
    }
    lines.forEach { (color, text) ->
        Row(verticalAlignment = Alignment.Top) {
            StatusDot(color)
            Spacer(Modifier.width(8.dp))
            Note(text, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

// --- Clothes drying ---

private fun dryColor(index: Int) = when {
    index >= 70 -> Good
    index >= 45 -> Color(0xFFD4B000)
    index > 0 -> Caution
    else -> Neutral
}

@Composable
internal fun DryingSection(report: DryingReport, laundryOut: Boolean, f: UnitFormatter, time: TimeFormat, onLaundry: (Boolean) -> Unit) {
    Column {
        SectionTitle("Clothes drying")
        SeasonCard {
            val best = report.best
            val nowBlocker = report.hours.firstOrNull()?.blocker
            if (best != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ScoreRing(best.index, dryColor(best.index), Modifier.size(48.dp))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            "Best: ${time.smart(best.start)} – ${time.time(best.end)}",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Note("A load should dry in about ${"%.1f".format(best.hoursToDry)} h")
                    }
                }
            } else {
                Text(
                    when (nowBlocker) {
                        DryingBlocker.FREEZING -> "Too cold to line-dry: wet clothes freeze below 32°F"
                        DryingBlocker.RAIN -> "Rain: dry indoors today"
                        DryingBlocker.DAMP -> "Too damp for clothes to dry outside"
                        else -> "No good drying window in the next two days"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            report.nextRain?.let { Note("Rain likely around ${time.smart(it)}${report.nextRainChance?.let { c -> " ($c%)" } ?: ""}: have laundry in before then", color = Caution) }
            if (report.days.size > 1) {
                Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    report.days.forEach { d ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                            Text(time.weekday(d.date), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                when (d.blocker) {
                                    DryingBlocker.FREEZING -> "frz"
                                    DryingBlocker.RAIN -> "rain"
                                    else -> "${d.score}"
                                },
                                style = MaterialTheme.typography.labelLarge,
                                color = dryColor(d.score),
                            )
                        }
                    }
                }
            }
            if (laundryOut) {
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Watching for rain: you'll get a notification to bring it in",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { onLaundry(false) }) { Text("Brought in") }
                    }
                }
            } else if (nowBlocker != DryingBlocker.FREEZING && nowBlocker != DryingBlocker.RAIN) {
                OutlinedButton(onClick = { onLaundry(true) }) { Text("My laundry is out") }
            }
        }
    }
}

// --- Heating ---

@Composable
internal fun HeatingSection(report: HeatingReport, hasSources: Boolean, aggressive: Boolean, f: UnitFormatter, time: TimeFormat, onConfigure: () -> Unit) {
    Column {
        SectionTitle("Heating")
        SeasonCard {
            Text(report.phase.label, style = MaterialTheme.typography.titleMedium)
            val start = report.typicalStart
            val end = report.typicalEnd
            if (start != null && end != null) Note("Heating season here usually runs ${time.monthDay(start)} to ${time.monthDay(end)}.")
            if (hasSources && report.phase != HeatingPhase.OFF && report.recommended.isNotEmpty() && !aggressive) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Run: ", style = MaterialTheme.typography.bodyLarge)
                    Text(report.recommended.joinToString(" + ") { shortName(it) }, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                }
                report.outlookMeanC?.let { Note("Based on the two-week average of about ${f.temperatureWithUnit(it)} (past week plus forecast), so it won't flip between systems day to day.") }
                Note("Heating demand this week: ${report.weekHdd.roundToInt()} degree days" + (report.recentHdd?.let { ", last 30 days ${it.roundToInt()}" } ?: ""))
            }
            if (hasSources && report.phase != HeatingPhase.OFF && aggressive && report.days.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    report.days.forEach { d ->
                        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp).width(92.dp)) {
                                Text(time.dayName(d.date), style = MaterialTheme.typography.labelLarge)
                                Note("low ${f.temperature(d.lowC)}")
                                Text(
                                    d.sources.joinToString(" + ") { shortName(it) }.ifEmpty { "–" },
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                if (d.nightSources.isNotEmpty()) Note("night: " + d.nightSources.joinToString(" + ") { shortName(it) })
                            }
                        }
                    }
                }
                Note("Heating demand this week: ${report.weekHdd.roundToInt()} degree days" + (report.recentHdd?.let { ", last 30 days ${it.roundToInt()}" } ?: ""))
            }
            report.tasks.forEach { task ->
                Row(verticalAlignment = Alignment.Top) {
                    StatusDot(Caution)
                    Spacer(Modifier.width(8.dp))
                    Text(OutlookText.heatingTask(task, f), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (!hasSources) Button(onClick = onConfigure) { Text("Set up my heating") } else TextButton(onClick = onConfigure) { Text("Edit my heating") }
        }
    }
}

private fun inDays(n: Long) = when (n) {
    0L -> "today"
    1L -> "tomorrow"
    else -> "in $n days"
}

private fun shortName(label: String) = label.substringBefore(" (").substringBefore(" /").replace("Oil furnace", "Oil").replace("Heat pump", "Heat pump")
