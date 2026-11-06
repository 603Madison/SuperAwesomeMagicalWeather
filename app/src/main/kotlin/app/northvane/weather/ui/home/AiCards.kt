package app.northvane.weather.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.northvane.core.ai.AiPrompts
import app.northvane.weather.data.AiReply
import app.northvane.weather.ui.AiTask
import app.northvane.weather.ui.AiUiState
import app.northvane.weather.ui.TimeFormat
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import app.northvane.core.ai.AiBrief
import app.northvane.weather.ui.components.WeatherCard
import app.northvane.weather.ui.components.NorthvaneIcons
import androidx.compose.ui.platform.LocalContext
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import android.speech.RecognizerIntent
import android.content.Intent
import android.app.Activity

/**
 * The National Weather Service forecasters' own discussion, rewritten in
 * plain words: their confidence and what could change, which the forecast
 * numbers don't show.
 */
@Composable
internal fun ForecastersNotesCard(ai: AiUiState, time: TimeFormat, onRefresh: () -> Unit) {
    val task = ai.discussion
    val office = ai.office ?: return
    if (task.reply == null && !task.loading && task.error == null) return
    val uri = LocalUriHandler.current
    var more by rememberSaveable { mutableStateOf(false) }
    AiCardFrame(
        title = "Forecasters' notes",
        subtitle = task.reply?.sourceTime?.let { "NWS $office · issued ${time.smart(it)}" } ?: "NWS $office",
        action = { IconButton(onClick = onRefresh, enabled = !task.loading) { Icon(Icons.Filled.Refresh, contentDescription = "Check for a newer discussion") } },
    ) {
        val reply = task.reply
        if (reply == null) {
            TaskText(task, waiting = "Reading the forecasters' discussion…")
        } else {
            val brief = remember(reply.text) { AiBrief.parse(reply.text) }
            Column(Modifier.padding(end = 12.dp)) {
                Text(brief.headline, style = MaterialTheme.typography.titleMedium)
                brief.confidence?.let { c ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        ConfidenceChip(c)
                        brief.confidenceReason?.let {
                            Spacer(Modifier.width(8.dp))
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                        }
                    }
                }
                if (more) Points(brief.points, Modifier.padding(top = 8.dp))
                task.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp)) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (brief.points.isNotEmpty()) TextButton(onClick = { more = !more }, contentPadding = PaddingValues(horizontal = 0.dp)) { Text(if (more) "Less" else "More") }
                    TextButton(onClick = { runCatching { uri.openUri("https://forecast.weather.gov/product.php?site=$office&issuedby=$office&product=AFD") } }) {
                        Text("Original")
                    }
                    Spacer(Modifier.weight(1f))
                    AiCredit(reply)
                }
            }
        }
    }
}

@Composable
private fun ConfidenceChip(c: AiBrief.Confidence) {
    val color = when (c) {
        AiBrief.Confidence.HIGH -> Color(0xFF2E9D5B)
        AiBrief.Confidence.MEDIUM -> Color(0xFFD08A00)
        AiBrief.Confidence.LOW -> Color(0xFFD9534F)
    }
    Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = 0.16f)) {
        Text("${c.label} confidence", style = MaterialTheme.typography.labelMedium, color = color, modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp))
    }
}

@Composable
private fun Points(points: List<String>, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        points.forEach { p ->
            Row {
                Text("•", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(p, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** A question about the forecast, answered from the forecast. */
@Composable
internal fun AskCard(
    ai: AiUiState,
    place: String,
    onAsk: (String) -> Unit,
    onClear: () -> Unit,
    suggestions: List<String> = listOf("Will I need an umbrella today?", "Good day this week for yard work?"),
) {
    var text by rememberSaveable { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    // Speak the question: the phone's own speech recognizer (no permission needed; nothing sent by this app).
    val speechIntent = remember {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Ask about the forecast")
    }
    val canSpeak = remember { speechIntent.resolveActivity(context.packageManager) != null }
    fun send(q: String = text) {
        if (q.trim().length >= 3) {
            keyboard?.hide()
            onAsk(q)
        }
    }
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val heard = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.take(AiPrompts.MAX_QUESTION)
        if (result.resultCode == Activity.RESULT_OK && !heard.isNullOrBlank()) {
            text = heard
            send(heard)
        }
    }
    AiCardFrame(title = "Ask about the forecast", subtitle = null) {
        Column {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(AiPrompts.MAX_QUESTION) },
                placeholder = { Text("Best time for a run tomorrow?") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                trailingIcon = {
                    if (text.isBlank() && canSpeak) {
                        IconButton(onClick = { runCatching { speech.launch(speechIntent) } }) {
                            Icon(NorthvaneIcons.Mic, contentDescription = "Ask by voice")
                        }
                    } else {
                        IconButton(onClick = { send() }, enabled = text.trim().length >= 3 && !ai.answer.loading) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Ask")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(end = 12.dp),
            )
            if (ai.question == null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    suggestions.take(2).forEach { example ->
                        AssistChip(onClick = { text = example; send(example) }, label = { Text(example, style = MaterialTheme.typography.labelSmall, maxLines = 2) }, modifier = Modifier.weight(1f))
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Text("“${ai.question}”", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    IconButton(onClick = { text = ""; onClear() }) { Icon(Icons.Filled.Close, contentDescription = "Clear the answer") }
                }
                TaskText(ai.answer, waiting = "Looking at the forecast for $place…")
            }
        }
    }
}

@Composable
private fun AiCardFrame(title: String, subtitle: String?, action: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    WeatherCard("✦ $title", trailing = action) {
        subtitle?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp)) }
        content()
    }
}

/** The reply (as a headline and points when it has that shape), a spinner while it's written, and any error. */
@Composable
internal fun TaskText(task: AiTask, waiting: String) {
    Column(Modifier.padding(end = 12.dp)) {
        task.reply?.let { reply ->
            val brief = remember(reply.text) { AiBrief.parse(reply.text) }
            Text(brief.headline, style = if (brief.points.isEmpty()) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.titleSmall)
            if (brief.points.isNotEmpty()) Points(brief.points, Modifier.padding(top = 6.dp))
        }
        if (task.loading) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(waiting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        task.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp)) }
        task.reply?.let { Row(Modifier.padding(top = 6.dp)) { Spacer(Modifier.weight(1f)); AiCredit(it) } }
    }
}

/** Small and quiet: who wrote it and when. */
@Composable
private fun AiCredit(reply: AiReply) {
    Text(
        "✦ AI · ${TimeFormat.ago(reply.createdAt)}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { contentDescription = "Written by AI (${reply.model}) ${TimeFormat.ago(reply.createdAt)}; it can be wrong, and official alerts come first" },
    )
}
