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
    AiCardFrame(
        title = "Forecasters' notes",
        subtitle = task.reply?.sourceTime?.let { "NWS $office · issued ${time.smart(it)}" } ?: "NWS $office",
        action = { IconButton(onClick = onRefresh, enabled = !task.loading) { Icon(Icons.Filled.Refresh, contentDescription = "Check for a newer discussion") } },
    ) {
        TaskText(task, waiting = "Reading the forecasters' discussion…")
        if (task.reply != null) {
            TextButton(onClick = { runCatching { uri.openUri("https://forecast.weather.gov/product.php?site=$office&issuedby=$office&product=AFD") } }) {
                Text("Read the original")
            }
        }
    }
}

/** A question about the forecast, answered from the forecast. */
@Composable
internal fun AskCard(ai: AiUiState, place: String, onAsk: (String) -> Unit, onClear: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    fun send(q: String = text) {
        if (q.trim().length >= 3) {
            keyboard?.hide()
            onAsk(q)
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
                    IconButton(onClick = { send() }, enabled = text.trim().length >= 3 && !ai.answer.loading) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Ask")
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(end = 12.dp),
            )
            if (ai.question == null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    listOf("Will I need an umbrella today?", "Good day this week for yard work?").forEach { example ->
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
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 4.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("✦ $title", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    subtitle?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                action()
            }
            Spacer(Modifier.size(6.dp))
            content()
        }
    }
}

/** The reply, a spinner while it's written, and any error; with a note on who wrote it. */
@Composable
internal fun TaskText(task: AiTask, waiting: String) {
    Column(Modifier.padding(end = 12.dp)) {
        task.reply?.let { Text(it.text, style = MaterialTheme.typography.bodyMedium) }
        if (task.loading) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(waiting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        task.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp)) }
        task.reply?.let { AiCredit(it) }
    }
}

@Composable
private fun AiCredit(reply: AiReply) {
    Text(
        "Written by AI (${reply.model}) · ${TimeFormat.ago(reply.createdAt)}. It can be wrong; official alerts come first.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp),
    )
}
