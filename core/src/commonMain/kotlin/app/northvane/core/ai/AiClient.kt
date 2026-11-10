package app.northvane.core.ai

import app.northvane.core.json.NorthvaneJson
import app.northvane.core.json.arr
import app.northvane.core.json.asObj
import app.northvane.core.json.obj
import app.northvane.core.json.str
import app.northvane.core.net.HttpStatusException
import app.northvane.core.net.InsecureConfigurationException
import app.northvane.core.net.InvalidCredentialsException
import app.northvane.core.net.LocalServerClients
import app.northvane.core.net.ParseException
import app.northvane.core.net.UrlPolicy
import app.northvane.core.net.WeatherApiException
import app.northvane.core.net.fetch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import app.northvane.core.net.HttpEngine
import app.northvane.core.net.HttpUrl
import app.northvane.core.net.Request
import app.northvane.core.net.withTimeouts

/**
 * Where the AI features send their requests. Most are servers that speak the
 * OpenAI chat-completions format, where [chatPath] and [modelsPath] are
 * appended to the user's base URL; [CLAUDE] is Anthropic's API, at a fixed
 * address ([hostedUrl]) and through Anthropic's own SDK ([ClaudeClient]).
 */
@Serializable
enum class AiProvider(
    val label: String,
    val example: String,
    val chatPath: String,
    val modelsPath: String?,
    val keyRequired: Boolean,
    val note: String,
    val hostedUrl: String? = null,
) {
    OLLAMA("Ollama", "http://192.168.1.20:11434", "/v1/chat/completions", "/api/tags", false,
        "Ollama's OpenAI-compatible API; no key needed. Pick a small model such as llama3.2 or qwen2.5."),
    OPEN_WEBUI("Open WebUI", "https://openwebui.example.com", "/api/chat/completions", "/api/models", true,
        "Create an API key under Settings › Account in Open WebUI."),
    LIBRECHAT("LibreChat", "https://librechat.example.com", "/api/agents/v1/chat/completions", null, true,
        "Uses LibreChat's Agents API (beta): enable remote agents, create an API key, and enter an agent ID as the model."),
    OPENAI_COMPATIBLE("OpenAI-compatible", "https://api.openai.com/v1", "/chat/completions", "/models", false,
        "Any server with /chat/completions (OpenAI, LM Studio, vLLM, llama.cpp, LiteLLM…). Hosted services bill per use."),
    CLAUDE("Claude (Anthropic)", "", "", "", true,
        "Anthropic's API. Create a key at console.anthropic.com; usage is billed to your Anthropic account. " +
            "Opus is the most capable; Haiku costs a small fraction as much and handles these short jobs well.",
        hostedUrl = ClaudeModels.BASE_URL),
    ;

    /** The address requests go to: fixed for hosted APIs, the user's own otherwise. */
    fun baseUrl(userUrl: String): String = hostedUrl ?: userUrl
}

data class AiConfig(val provider: AiProvider, val baseUrl: String, val model: String, val apiKey: String?) {
    /** Enough is set to send a request. */
    val isComplete: Boolean
        get() = model.isNotBlank() && (provider.hostedUrl != null || baseUrl.isNotBlank()) && (!provider.keyRequired || !apiKey.isNullOrBlank())
}

/**
 * One question for the model: the standing instructions ([system]), the
 * material and question ([user]), and how much the answer may say.
 */
data class AiRequest(
    val system: String,
    val user: String,
    /** Reply length for servers without separate reasoning (most local models). */
    val replyTokens: Int = 400,
    /** How hard the model should think, where the model supports it. */
    val effort: AiEffort = AiEffort.LOW,
)

enum class AiEffort { LOW, MEDIUM }

/**
 * Talks to the user's AI provider. Self-hosted servers on the LAN may use
 * plain HTTP, but never with an API key; Claude goes through [ClaudeClient].
 */
class AiClient(clients: LocalServerClients, private val claude: ClaudeClient = ClaudeClient()) {
    // Local models can take a while on modest hardware.
    private val https: HttpEngine = clients.https.withTimeouts(readSeconds = 120, callSeconds = 150)
    private val cleartext: HttpEngine = clients.cleartext.withTimeouts(readSeconds = 120, callSeconds = 150)

    private fun endpoint(config: AiConfig, path: String): Pair<HttpUrl, Boolean> {
        val base = config.baseUrl.trim().trimEnd('/')
        val url = base + path
        val validated = when (val v = UrlPolicy.validateUserServerUrl(url, sendsCredentials = !config.apiKey.isNullOrBlank())) {
            is UrlPolicy.Validation.Invalid -> throw InsecureConfigurationException(v.reason)
            is UrlPolicy.Validation.Valid -> v
        }
        return validated.url to validated.isCleartext
    }

    private fun request(config: AiConfig, url: HttpUrl, cleartext: Boolean): Request.Builder =
        Request.Builder().url(url).header("Accept", "application/json").header("Cache-Control", "no-store").apply {
            config.apiKey?.takeIf { it.isNotBlank() }?.let { secretHeader("Authorization", "Bearer $it") }
            if (cleartext) allowLanCleartext()
        }

    suspend fun complete(config: AiConfig, request: AiRequest): String {
        require(config.model.isNotBlank()) { "Choose a model" }
        if (config.provider == AiProvider.CLAUDE) {
            val key = config.apiKey?.takeIf { it.isNotBlank() } ?: throw InvalidCredentialsException("Claude")
            return AiText.clean(claude.complete(key, config.model, request))
        }
        val (url, clear) = endpoint(config, config.provider.chatPath)
        val body = buildJsonObject {
            put("model", config.model)
            putJsonArray("messages") {
                addJsonObject { put("role", "system"); put("content", request.system) }
                addJsonObject { put("role", "user"); put("content", request.user) }
            }
            put("temperature", 0.3)
            put("max_tokens", request.replyTokens)
            put("stream", false)
        }.toString()
        val response = (if (clear) cleartext else https).fetch(
            request(config, url, clear).post(body, "application/json").build(),
            maxBytes = 512L * 1024,
        )
        when (response.code) {
            in 200..299 -> Unit
            401, 403 -> throw InvalidCredentialsException("Your AI server")
            404 -> throw WeatherApiException("Your AI server doesn't have that endpoint or model (HTTP 404)")
            else -> throw HttpStatusException(response.code, "Your AI server")
        }
        return parseCompletion(response.body)
    }

    suspend fun models(config: AiConfig): List<String> {
        if (config.provider == AiProvider.CLAUDE) {
            val key = config.apiKey?.takeIf { it.isNotBlank() } ?: throw InvalidCredentialsException("Claude")
            return claude.models(key)
        }
        val path = config.provider.modelsPath ?: return emptyList()
        val (url, clear) = endpoint(config, path)
        val response = (if (clear) cleartext else https).fetch(request(config, url, clear).build(), maxBytes = 1024L * 1024)
        if (response.code == 401 || response.code == 403) throw InvalidCredentialsException("Your AI server")
        if (response.code !in 200..299) throw HttpStatusException(response.code, "Your AI server")
        return parseModels(response.body)
    }

    companion object {
        /** The reply text from a chat-completions response. */
        fun parseCompletion(json: String): String {
            val root = runCatching { NorthvaneJson.parseToJsonElement(json).asObj() }.getOrNull()
                ?: throw ParseException("Unexpected response from your AI server")
            root.obj("error")?.str("message")?.let { throw WeatherApiException("AI server: ${it.take(200)}") }
            val content = root.arr("choices")?.firstOrNull()?.asObj()?.obj("message")?.str("content")
                ?: root.obj("message")?.str("content") // Ollama native /api/chat shape
                ?: throw ParseException("Your AI server returned no text")
            return AiText.clean(content)
        }

        /** OpenAI/Open WebUI `{"data":[{"id":…}]}` or Ollama `{"models":[{"name":…}]}`. */
        fun parseModels(json: String): List<String> {
            val root = runCatching { NorthvaneJson.parseToJsonElement(json).asObj() }.getOrNull() ?: return emptyList()
            val list = (root.arr("data") ?: root.arr("models")) as? JsonArray ?: return emptyList()
            return list.mapNotNull { it.asObj()?.let { o -> o.str("id") ?: o.str("name") ?: o.str("model") } }.distinct().sorted()
        }
    }
}

object AiText {
    private val THINK = Regex("(?s)<think>.*?</think>")

    /**
     * Plain text for the app's cards: any <think>…</think> reasoning (e.g.
     * DeepSeek-R1, Qwen3) and markdown marks removed, list dashes turned into
     * bullets, and the length capped.
     */
    fun clean(text: String, maxChars: Int = 1500): String =
        text.replace(THINK, "")
            .replace(Regex("(?m)^\\s*[-*]\\s+"), "• ")
            .replace(Regex("[*#`]+"), "")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
            .take(maxChars)
}

/**
 * A short AI reply in the app's at-a-glance shape: a headline, up to three
 * points and, for forecast discussions, a confidence level. Replies that
 * don't follow the shape (older stored ones, small local models) keep their
 * text as the headline.
 */
data class AiBrief(val headline: String, val points: List<String>, val confidence: Confidence? = null, val confidenceReason: String? = null) {
    enum class Confidence(val label: String) { HIGH("High"), MEDIUM("Medium"), LOW("Low") }

    companion object {
        private val HEADLINE = Regex("(?im)^\\s*HEADLINE\\s*:\\s*(.+)$")
        private val CONFIDENCE = Regex("(?im)^\\s*CONFIDENCE\\s*:\\s*(high|medium|low)\\b[\\s|:,.–-]*(.*)$")
        private val POINT = Regex("(?m)^\\s*(?:[-•*]|\\d+[.)])\\s+(.+)$")

        fun parse(text: String): AiBrief {
            val headline = HEADLINE.find(text)?.groupValues?.get(1)?.trim()
                ?: return AiBrief(text.trim(), emptyList())
            val confidence = CONFIDENCE.find(text)
            return AiBrief(
                headline = headline.trimEnd('.'),
                points = POINT.findAll(text).map { it.groupValues[1].trim().trimEnd('.') }
                    .filter { it.isNotEmpty() && !it.startsWith("(") }.take(3).toList(),
                confidence = confidence?.groupValues?.get(1)?.let { c -> Confidence.entries.firstOrNull { it.name.equals(c, ignoreCase = true) } },
                confidenceReason = confidence?.groupValues?.get(2)?.trim()?.trimEnd('.')?.takeIf { it.isNotEmpty() },
            )
        }
    }
}
