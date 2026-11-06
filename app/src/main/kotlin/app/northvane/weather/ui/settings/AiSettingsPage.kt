package app.northvane.weather.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.northvane.core.ai.AiConfig
import app.northvane.core.ai.AiProvider
import app.northvane.core.ai.AiRequest
import app.northvane.core.ai.ClaudeModels
import app.northvane.core.net.UrlPolicy
import app.northvane.core.net.toUserMessage
import app.northvane.core.repo.attempt
import app.northvane.weather.AppContainer
import app.northvane.weather.data.SecretStore
import app.northvane.weather.net.LocalNetworkAccess
import app.northvane.weather.ui.MainUiState
import app.northvane.weather.ui.components.ChoiceDropdown
import app.northvane.weather.ui.components.LabeledSwitch
import app.northvane.weather.ui.components.SectionCard
import app.northvane.weather.ui.components.SecureWindow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AI: which jobs it does, and which provider does them (Claude, or an
 * Ollama, Open WebUI, LibreChat or OpenAI-compatible server).
 */
@Composable
internal fun AiSettingsPage(state: MainUiState, container: AppContainer, onBack: () -> Unit) {
    SecureWindow()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val s = state.settings
    var provider by remember(s.aiProvider) { mutableStateOf(s.aiProvider) }
    var baseUrl by remember(s.aiBaseUrl) { mutableStateOf(s.aiBaseUrl) }
    var model by remember(s.aiModel) { mutableStateOf(s.aiModel) }
    var newKey by remember { mutableStateOf("") }
    var hasKey by remember { mutableStateOf(false) }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var afterPermission by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) afterPermission?.invoke() else status = "✗ ${LocalNetworkAccess.DENIED_MESSAGE}"
        afterPermission = null
    }
    val hosted = provider.hostedUrl != null
    LaunchedEffect(provider) { hasKey = withContext(Dispatchers.IO) { container.secrets.has(SecretStore.aiKeyName(provider)) } }

    suspend fun config(): AiConfig {
        val key = newKey.ifBlank { withContext(Dispatchers.IO) { container.secrets.get(SecretStore.aiKeyName(provider)) }.orEmpty() }
        return AiConfig(provider, baseUrl, model, key.ifBlank { null })
    }

    fun problem(): String? {
        val sendsKey = newKey.isNotBlank() || hasKey
        if (provider.keyRequired && !sendsKey) return "Enter your API key"
        if (hosted) return null
        if (baseUrl.isBlank()) return "Enter your server's address"
        return (UrlPolicy.validateUserServerUrl(baseUrl.trim().trimEnd('/') + provider.chatPath, sendsKey) as? UrlPolicy.Validation.Invalid)?.reason
    }

    /** Runs [action] after local network permission, if the server is on the LAN (Android 17+). */
    fun withNetwork(action: () -> Unit) {
        scope.launch {
            if (!hosted && LocalNetworkAccess.needsPermissionFor(context, baseUrl)) {
                afterPermission = action
                permission.launch(LocalNetworkAccess.PERMISSION)
            } else {
                action()
            }
        }
    }

    fun save(enable: Boolean? = null) = scope.launch {
        container.settings.setAiServer(provider, baseUrl, model)
        if (newKey.isNotBlank()) {
            withContext(Dispatchers.IO) { container.secrets.put(SecretStore.aiKeyName(provider), newKey.trim()) }
            newKey = ""
            hasKey = true
        }
        enable?.let { container.settings.setAiEnabled(it) }
    }

    SettingsScaffoldPublic("AI", onBack) {
        SectionCard(null) {
            Hint(
                "AI only where it adds something the forecast can't show by itself. Everything is sent to the provider you choose below, " +
                    "and only weather text and the place's name are sent: never your location coordinates, other places or keys.",
            )
            LabeledSwitch(
                "Use AI", s.aiEnabled,
                { on ->
                    val issue = if (on) problem() ?: if (model.isBlank()) "Choose a model first" else null else null
                    if (issue != null) status = "✗ $issue" else save(on)
                },
            )
            if (s.aiEnabled) {
                LabeledSwitch("Forecasters' notes", s.aiDiscussion, { on -> scope.launch { container.settings.setAiDiscussion(on) } },
                    subtitle = "The National Weather Service forecasters' discussion in plain words: their confidence and what could change. " +
                        "Rewritten only when they issue a new one (2–4 times a day).")
                LabeledSwitch("Explain alerts", s.aiAlerts, { on -> scope.launch { container.settings.setAiAlerts(on) } },
                    subtitle = "A button on each alert that rewrites it for your place. Only when you tap it.")
                LabeledSwitch("Ask about the forecast", s.aiAsk, { on -> scope.launch { container.settings.setAiAsk(on) } },
                    subtitle = "A question box on Today, answered from the next 48 hours and 7 days of your forecast.")
            }
        }
        SectionCard("Provider") {
            ChoiceDropdown("Provider", AiProvider.entries, provider, { it.label }, { provider = it; models = emptyList(); newKey = ""; status = null })
            Hint(provider.note)
            if (!hosted) OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it.take(500); status = null },
                singleLine = true,
                label = { Text("Server address") },
                placeholder = { Text(provider.example) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                supportingText = { Text("http:// only for servers on your local network, and never with an API key.") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = newKey,
                onValueChange = { newKey = it.trim().take(300) },
                singleLine = true,
                label = { Text(if (hasKey) "Replace API key (saved)" else if (provider.keyRequired) "API key" else "API key (optional)") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
            if (models.isNotEmpty()) {
                ChoiceDropdown("Model", models, models.firstOrNull { it == model } ?: models.first(), { it }, { model = it })
            } else {
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it.take(200) },
                    singleLine = true,
                    label = { Text(if (provider == AiProvider.LIBRECHAT) "Agent ID" else "Model") },
                    placeholder = { Text(when (provider) { AiProvider.LIBRECHAT -> "agent_…"; AiProvider.CLAUDE -> ClaudeModels.DEFAULT; else -> "llama3.2" }) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (provider.modelsPath != null) {
                    OutlinedButton(enabled = !busy, onClick = {
                        problem()?.let { status = "✗ $it"; return@OutlinedButton }
                        withNetwork {
                            busy = true
                            scope.launch {
                                val result = attempt { container.ai.models(config()) }
                                result.onSuccess { list ->
                                    models = list
                                    if (model !in list) model = list.firstOrNull { it == ClaudeModels.DEFAULT } ?: list.firstOrNull().orEmpty()
                                    status = if (list.isEmpty()) "No models found on the server" else "✓ ${list.size} models"
                                }.onFailure { status = "✗ ${it.toUserMessage()}" }
                                busy = false
                            }
                        }
                    }) { Text("Load models") }
                }
                FilledTonalButton(enabled = !busy, onClick = {
                    problem()?.let { status = "✗ $it"; return@FilledTonalButton }
                    withNetwork {
                        busy = true
                        status = "Asking the server…"
                        scope.launch {
                            val result = attempt {
                                container.ai.complete(config(), AiRequest("Reply in one short sentence.", "Confirm you can help explain weather forecasts.", replyTokens = 60))
                            }
                            result.onSuccess { text -> status = "✓ $text"; save() }.onFailure { status = "✗ ${it.toUserMessage()}" }
                            busy = false
                        }
                    }
                }) { Text("Test & save") }
                if (hasKey) {
                    TextButton(onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { container.secrets.remove(SecretStore.aiKeyName(provider)) }
                            hasKey = false
                        }
                    }) { Text("Remove key") }
                }
            }
            status?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = if (it.startsWith("✗")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}
