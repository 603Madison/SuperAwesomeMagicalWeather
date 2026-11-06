package app.northvane.weather.ui.home

import app.northvane.weather.ui.components.WeatherCard
import androidx.compose.animation.animateContentSize
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.northvane.core.model.AuroraReport
import app.northvane.core.model.AuroraView
import app.northvane.core.model.BurnRating
import app.northvane.core.model.BurnReason
import app.northvane.core.model.BurnReport
import app.northvane.core.model.Likelihood
import app.northvane.core.model.MorningIce
import app.northvane.core.model.OutageLevel
import app.northvane.core.model.PipeFactor
import app.northvane.core.model.PipeRisk
import app.northvane.core.model.SapRating
import app.northvane.core.model.SapReport
import app.northvane.core.outlook.HeatType
import app.northvane.core.outlook.OutlookText
import app.northvane.core.outlook.PipeFreeze
import app.northvane.core.util.UnitFormatter
import app.northvane.weather.ui.TimeFormat
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

// --- Panels inside an opened day of the 10-day forecast ---

/** A tinted box with a title, a one-line reason, and steps that open on tap. */
@Composable
private fun HazardPanel(
    title: String,
    color: Color,
    summary: String,
    tipsTitle: String,
    tips: List<String>,
    glyph: @Composable (Color, Modifier) -> Unit,
) {
    var open by rememberSaveable(title, summary) { mutableStateOf(false) }
    Surface(
        onClick = { open = !open },
        enabled = tips.isNotEmpty(),
        shape = RoundedCornerShape(14.dp),
        color = color.copy(alpha = 0.12f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp).animateContentSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                glyph(color, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(title, style = MaterialTheme.typography.titleSmall, color = color)
            }
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            if (tips.isNotEmpty() && !open) {
                Text("Tap for how to prepare", style = MaterialTheme.typography.labelMedium, color = color, modifier = Modifier.padding(top = 6.dp))
            }
            if (open) {
                Text(tipsTitle, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                tips.forEach { step ->
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

internal fun describe(factor: PipeFactor, f: UnitFormatter): String = when (factor.kind) {
    PipeFactor.Kind.LOW -> "Low near ${f.temperatureWithUnit(factor.value)}"
    PipeFactor.Kind.LONG_COLD -> "${factor.value?.roundToInt()} hours at or below ${f.temperatureWithUnit(PipeFreeze.THRESHOLD_C)}"
    PipeFactor.Kind.NO_THAW -> "Stays below freezing all day"
    PipeFactor.Kind.WIND -> "Wind ${f.windSpeed(factor.value)}"
    PipeFactor.Kind.OUTAGE -> "High power outage risk"
}

@Composable
internal fun PipePanel(risk: PipeRisk, f: UnitFormatter, heatTypes: List<HeatType>) {
    HazardPanel(
        title = "Frozen pipe risk: ${risk.level.label}",
        color = outageColor(risk.level),
        summary = risk.factors.joinToString(" · ") { describe(it, f) },
        tipsTitle = "Keep pipes from freezing",
        tips = OutlookText.pipePrep(risk, heatTypes, f),
        glyph = { c, m -> DropGlyph(c, m) },
    )
}

private val FrostBlue = Color(0xFF3B8EEA)

internal fun iceColor(ice: MorningIce): Color = when {
    ice.blackIce == Likelihood.LIKELY -> outageColor(OutageLevel.HIGH)
    ice.blackIce == Likelihood.POSSIBLE -> outageColor(OutageLevel.ELEVATED)
    else -> FrostBlue
}

internal fun iceTitle(ice: MorningIce): String {
    val parts = listOfNotNull(
        ice.blackIce?.let { "black ice ${it.label}" },
        ice.frost?.let { "frost ${it.label}" },
    )
    return "Morning: " + parts.joinToString(", ")
}

@Composable
internal fun IcePanel(ice: MorningIce, f: UnitFormatter, time: TimeFormat) {
    val reasons = ice.reasons.map {
        when (it) {
            MorningIce.Reason.CLEAR_CALM -> "Clear, calm night"
            MorningIce.Reason.FREEZING_RAIN -> "Freezing rain"
            MorningIce.Reason.RAIN_THEN_FREEZE -> "Wet roads freeze"
            MorningIce.Reason.MELT_REFREEZE -> "Snowmelt refreezes"
        }
    }
    val low = ice.lowC?.let { c -> "Low near ${f.temperatureWithUnit(c)}" + (ice.coldestAt?.let { " around ${time.hour(it)}" } ?: "") }
    HazardPanel(
        title = iceTitle(ice),
        color = iceColor(ice),
        summary = (reasons + listOfNotNull(low)).joinToString(" · "),
        tipsTitle = if (ice.blackIce != null) "Before you drive" else "Before you head out",
        tips = OutlookText.iceTips(ice),
        glyph = { c, m -> IceGlyph(c, m) },
    )
}

/** A water drop, for frozen pipes. */
@Composable
internal fun DropGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w / 2, 0f)
            cubicTo(w * 0.15f, h * 0.45f, w * 0.1f, h * 0.62f, w * 0.14f, h * 0.72f)
            cubicTo(w * 0.24f, h * 1.02f, w * 0.76f, h * 1.02f, w * 0.86f, h * 0.72f)
            cubicTo(w * 0.9f, h * 0.62f, w * 0.85f, h * 0.45f, w / 2, 0f)
            close()
        }
        drawPath(path, color)
    }
}

/** A six-armed ice crystal, for frost and black ice. */
@Composable
internal fun IceGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val c = Offset(size.width / 2, size.height / 2)
        val r = size.minDimension / 2
        val stroke = size.minDimension * 0.12f
        for (i in 0 until 6) {
            val a = Math.toRadians(90.0 + i * 60.0)
            val tip = Offset(c.x + (r * cos(a)).toFloat(), c.y - (r * sin(a)).toFloat())
            drawLine(color, c, tip, strokeWidth = stroke, cap = StrokeCap.Round)
        }
    }
}

// --- Aurora (Today) ---

/** Worth showing: something to see in the next nights, or a storm that matters on satellite. */
internal fun AuroraReport.worthShowing(liteMode: Boolean): Boolean =
    nowView >= AuroraView.CAMERA || nights.any { it.view >= AuroraView.CAMERA } || (gpsImpact && liteMode)

private val AuroraGreen = Color(0xFF2FBF71)
private val AuroraViolet = Color(0xFF8E6BE8)

private fun viewColor(view: AuroraView): Color = when (view) {
    AuroraView.NONE -> Neutral
    AuroraView.CAMERA -> Color(0xFF4FA3A5)
    AuroraView.HORIZON -> AuroraGreen
    AuroraView.OVERHEAD -> AuroraViolet
}

/** NOAA's G scale: Kp 5 is G1 (minor) through Kp 9 for G5 (extreme). */
private fun stormScale(kp: Double): String? = when {
    kp >= 9.0 -> "G5 extreme storm"
    kp >= 8.0 -> "G4 severe storm"
    kp >= 7.0 -> "G3 strong storm"
    kp >= 6.0 -> "G2 moderate storm"
    kp >= 5.0 -> "G1 minor storm"
    else -> null
}

@Composable
internal fun AuroraSection(report: AuroraReport, time: TimeFormat) {
    val best = report.nights.filter { it.view >= AuroraView.CAMERA }.maxByOrNull { it.view.ordinal * 10 + it.maxKp }
    val southern = report.geomagneticLatitude < 0
    WeatherCard("Aurora") {
        when {
            report.nowView >= AuroraView.CAMERA -> {
                Text("Aurora possible right now", style = MaterialTheme.typography.titleMedium, color = viewColor(report.nowView))
                Note(horizon(report.nowView, southern) + (report.nowKp?.let { " · Kp ${"%.1f".format(it)}" } ?: ""))
            }
            best != null -> {
                val day = if (best.date == time.today()) "tonight" else "${time.weekday(best.date)} night"
                Text("Northern lights possible $day", style = MaterialTheme.typography.titleMedium, color = viewColor(best.view))
                Note(
                    listOfNotNull(
                        horizon(best.view, southern),
                        best.peakAt?.let { "best around ${time.hour(it)}" },
                        stormScale(best.maxKp) ?: "Kp ${"%.1f".format(best.maxKp)}",
                    ).joinToString(" · "),
                )
                best.cloudPercent?.let { c ->
                    Note(
                        when {
                            c >= 75 -> "Clouds about $c%: likely hidden"
                            c >= 40 -> "Clouds about $c%: look for breaks"
                            else -> "Mostly clear (clouds about $c%)"
                        },
                        color = if (c >= 75) Caution else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            else -> Text("No aurora expected here", style = MaterialTheme.typography.titleMedium)
        }
        if (report.nights.size > 1) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                report.nights.forEach { n ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                        Text(
                            if (n.date == time.today()) "Tonight" else time.weekday(n.date),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            when (n.view) {
                                AuroraView.NONE -> "–"
                                AuroraView.CAMERA -> "camera"
                                AuroraView.HORIZON -> "low"
                                AuroraView.OVERHEAD -> "high"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Medium,
                            color = viewColor(n.view),
                        )
                        KpBar(n.maxKp, viewColor(n.view))
                    }
                }
            }
        }
        if (report.gpsImpact) {
            Note("Strong geomagnetic storm: GPS and satellite texting may be less reliable for a while", color = Caution)
        }
        Note("Get away from lights and look ${if (southern) "south" else "north"} after dark; let your eyes adjust for 15 minutes. A phone's night mode shows more than your eyes can.")
        Note("NOAA Space Weather Prediction Center")
    }
}

private fun horizon(view: AuroraView, southern: Boolean): String =
    if (southern && view == AuroraView.HORIZON) "Low on the southern horizon" else view.label

@Composable
private fun KpBar(kp: Double, color: Color) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    Canvas(Modifier.padding(top = 3.dp).width(30.dp).height(4.dp)) {
        val r = CornerRadius(size.height / 2, size.height / 2)
        drawRoundRect(track, cornerRadius = r)
        val fraction = (kp / 9.0).coerceIn(0.0, 1.0).toFloat()
        if (fraction > 0) drawRoundRect(color, size = Size(size.width * fraction, size.height), cornerRadius = r)
    }
}

// --- Maple sap and burn days (Outlook) ---

private fun sapColor(rating: SapRating): Color = when (rating) {
    SapRating.NONE -> Neutral
    SapRating.LIGHT -> Color(0xFFC9A227)
    SapRating.GOOD -> Color(0xFFD9822B)
    SapRating.GREAT -> Color(0xFF2E9E5B)
}

@Composable
internal fun SapSection(report: SapReport, f: UnitFormatter, time: TimeFormat) {
    val best = report.days.filter { it.rating >= SapRating.GOOD }
    WeatherCard("Maple sap") {
        Text(
            when {
                best.isEmpty() && report.days.any { it.rating == SapRating.LIGHT } -> "Light runs at best this week"
                best.isEmpty() -> "No sap runs in the forecast"
                else -> "Best runs: " + best.sortedByDescending { it.score }.take(3).sortedBy { it.date }
                    .joinToString(", ") { if (it.date == time.today()) "today" else time.weekday(it.date) }
            },
            style = MaterialTheme.typography.titleMedium,
        )
        when (report.note) {
            SapReport.Note.WARM_NIGHTS -> Note("Several nights above freezing: flow slows, and buds may end the season soon", color = Caution)
            SapReport.Note.DEEP_FREEZE -> Note("Trees stay frozen: no flow until days get above freezing")
            null -> {}
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            report.days.take(7).forEach { d ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                    Text(time.weekday(d.date), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        when (d.rating) {
                            SapRating.NONE -> "–"
                            SapRating.LIGHT -> "light"
                            SapRating.GOOD -> "good"
                            SapRating.GREAT -> "great"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = sapColor(d.rating),
                    )
                    Text(
                        "${f.temperature(d.nightLowC)}/${f.temperature(d.highC)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Note("Sap runs best after a night in the 20s °F and a sunny day in the 40s. Each day shows the night before's low and the day's high.")
    }
}

private fun burnColor(rating: BurnRating): Color = when (rating) {
    BurnRating.GOOD -> Good
    BurnRating.CAUTION -> Caution
    BurnRating.NO_BURN -> Stop
}

private fun describe(reason: BurnReason, f: UnitFormatter): String = when (reason.kind) {
    BurnReason.Kind.FIRE_ALERT -> reason.detail ?: "NWS fire weather alert"
    BurnReason.Kind.GUSTS -> "Gusts to ${f.windSpeed(reason.value)}"
    BurnReason.Kind.WIND -> "Wind to ${f.windSpeed(reason.value)}"
    BurnReason.Kind.DRY_AIR -> "Humidity down to ${reason.value?.roundToInt()}%"
    BurnReason.Kind.DRY_SPELL -> "${reason.value?.roundToInt()} days without rain"
    BurnReason.Kind.SNOW_COVER -> "Snow on the ground"
    BurnReason.Kind.LIGHT_WIND -> "Light wind"
    BurnReason.Kind.RECENT_RAIN -> "Recent rain"
    BurnReason.Kind.RAIN_LIKELY -> "Rain likely (${reason.value?.roundToInt()}%)"
}

@Composable
internal fun BurnSection(report: BurnReport, f: UnitFormatter, time: TimeFormat) {
    WeatherCard("Burn days") {
        val today = report.days.firstOrNull()
        if (today != null) {
            Text(
                (if (today.date == time.today()) "Today: " else "${time.weekday(today.date)}: ") + today.rating.label.lowercase(),
                style = MaterialTheme.typography.titleMedium,
                color = burnColor(today.rating),
            )
            if (today.rating != BurnRating.GOOD) {
                val next = report.days.drop(1).firstOrNull { it.rating == BurnRating.GOOD }
                Note(next?.let { "Next good day: ${time.dayName(it.date)}" } ?: "No good burn days in the forecast")
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 2.dp)) {
            report.days.forEach { d ->
                Row {
                    StatusDot(burnColor(d.rating))
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            "${time.dayName(d.date)} · ${d.rating.label}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (d.reasons.isNotEmpty()) Note(d.reasons.joinToString(" · ") { describe(it, f) })
                    }
                }
            }
        }
        Note("Rules vary by town and state: many need a burn permit (in New Hampshire, unless the ground is snow-covered). Keep water and a rake nearby, and never leave a fire alone.")
    }
}
