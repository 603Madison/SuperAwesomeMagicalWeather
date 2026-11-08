package app.northvane.core.ai

/**
 * Claude, Anthropic's API: through Anthropic's official Java SDK on Android,
 * and over HTTPS directly on iOS, where there is no official SDK. Requests are
 * short, single-turn and non-streaming; errors come back as the app's own
 * exceptions with short messages that never include the key.
 */
expect class ClaudeClient(baseUrl: String = ClaudeModels.BASE_URL) {
    suspend fun complete(apiKey: String, model: String, request: AiRequest): String

    /** The account's models, newest first (the API's order). */
    suspend fun models(apiKey: String): List<String>
}

/** What each Claude model accepts, from its ID (the user picks any model the account offers). */
object ClaudeModels {
    const val BASE_URL = "https://api.anthropic.com"

    /** The suggested model when the list hasn't been loaded. */
    const val DEFAULT = "claude-opus-5-5"

    private val ID = Regex("^claude-([a-z]+)-(\\d+)(?:-(\\d{1,2}))?(?:-\\d{8})?$")
    private val FALLBACKS = setOf("claude-fable-5-1", "claude-opus-5-5", "claude-opus-5", "claude-sonnet-5-5")

    /** The effort setting exists from Opus 4.5 and the 4.6 generation on; older models reject it. */
    fun supportsEffort(model: String): Boolean {
        val m = ID.matchEntire(model.trim()) ?: return false
        val family = m.groupValues[1]
        val major = m.groupValues[2].toInt()
        val minor = m.groupValues[3].toIntOrNull() ?: 0
        return major > 4 || (major == 4 && (minor >= 6 || (family == "opus" && minor == 5)))
    }

    /** Models that take the server-side fallback for declined requests. */
    fun supportsFallbacks(model: String): Boolean = model.trim() in FALLBACKS
}
