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

/** Connect an Ollama, Open WebUI, LibreChat or OpenAI-compatible server for the Today summary. */
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
    LaunchedEffect(Unit) { hasKey = withContext(Dispatchers.IO) { container.secrets.has(SecretStore.AI_API_KEY) } }

    suspend fun config(): AiConfig {
        val key = newKey.ifBlank { withContext(Dispatchers.IO) { container.secrets.get(SecretStore.AI_API_KEY) }.orEmpty() }
        return AiConfig(provider, baseUrl, model, key.ifBlank { null })
    }

    fun problem(): String? {
        if (baseUrl.isBlank()) return "Enter your server's address"
        val sendsKey = newKey.isNotBlank() || hasKey
        return (UrlPolicy.validateUserServerUrl(baseUrl.trim().trimEnd('/') + provider.chatPath, sendsKey) as? UrlPolicy.Validation.Invalid)?.reason
    }

    /** Runs [action] after local network permission, if the server is on the LAN (Android 17+). */
    fun withNetwork(action: () -> Unit) {
        scope.launch {
            if (LocalNetworkAccess.needsPermissionFor(context, baseUrl)) {
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
            withContext(Dispatchers.IO) { container.secrets.put(SecretStore.AI_API_KEY, newKey.trim()) }
            newKey = ""
            hasKey = true
        }
        enable?.let { container.settings.setAiEnabled(it) }
    }

    SettingsScaffoldPublic("AI summary", onBack) {
        SectionCard(null) {
            Hint(
                "A short summary of the forecast, written by your own AI server, at the top of the Today screen. " +
                    "SuperAwesomeMagicalWeather sends only the place name and the forecast numbers. Summaries are refreshed at most every 45 minutes.",
            )
            LabeledSwitch(
                "AI summary on Today", s.aiEnabled,
                { on ->
                    val issue = if (on) problem() ?: if (model.isBlank()) "Choose a model first" else null else null
                    if (issue != null) status = "✗ $issue" else save(on)
                },
            )
        }
        SectionCard("Server") {
            ChoiceDropdown("Server type", AiProvider.entries, provider, { it.label }, { provider = it; models = emptyList() })
            Hint(provider.note)
            OutlinedTextField(
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
                    placeholder = { Text(if (provider == AiProvider.LIBRECHAT) "agent_…" else "llama3.2") },
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
                                    if (model.isBlank()) model = list.firstOrNull().orEmpty()
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
                            val result = attempt { container.ai.complete(config(), listOf("user" to "Reply with one short friendly sentence confirming you can write weather summaries."), maxTokens = 60) }
                            result.onSuccess { text -> status = "✓ $text"; save() }.onFailure { status = "✗ ${it.toUserMessage()}" }
                            busy = false
                        }
                    }
                }) { Text("Test & save") }
                if (hasKey) {
                    TextButton(onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { container.secrets.remove(SecretStore.AI_API_KEY) }
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
