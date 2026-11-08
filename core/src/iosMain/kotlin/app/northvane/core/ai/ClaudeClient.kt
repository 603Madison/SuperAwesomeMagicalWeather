package app.northvane.core.ai

import app.northvane.core.json.NorthvaneJson
import app.northvane.core.json.arr
import app.northvane.core.json.asObj
import app.northvane.core.json.obj
import app.northvane.core.json.str
import app.northvane.core.net.HttpEngine
import app.northvane.core.net.HttpResponse
import app.northvane.core.net.InvalidCredentialsException
import app.northvane.core.net.RateLimitedException
import app.northvane.core.net.Request
import app.northvane.core.net.WeatherApiException
import app.northvane.core.net.fetch
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Claude over HTTPS: the Messages API, as Anthropic documents it for
 * languages without an official SDK. Mirrors the Android client (the Java
 * SDK): the same request shape, retries on rate limits and server errors, and
 * the same short error messages.
 */
actual class ClaudeClient actual constructor(private val baseUrl: String) {
    private val http: HttpEngine by lazy { HttpEngine.create("SuperAwesomeMagicalWeather (iOS)") }

    actual suspend fun complete(apiKey: String, model: String, request: AiRequest): String {
        val fallbacks = ClaudeModels.supportsFallbacks(model)
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", MAX_TOKENS)
            put("system", request.system)
            putJsonArray("messages") {
                addJsonObject { put("role", "user"); put("content", request.user) }
            }
            if (ClaudeModels.supportsEffort(model)) {
                putJsonObject("output_config") { put("effort", if (request.effort == AiEffort.MEDIUM) "medium" else "low") }
            }
            // If a safety check declines the request, let the API retry it on a suitable model.
            if (fallbacks) put("fallbacks", "default")
        }.toString()
        val response = send(apiKey, "/v1/messages", body, beta = if (fallbacks) FALLBACK_BETA else null)
        val root = runCatching { NorthvaneJson.parseToJsonElement(response.body).asObj() }.getOrNull()
            ?: throw WeatherApiException("Unexpected response from Claude")
        val stop = root.str("stop_reason")
        if (stop == "refusal") throw WeatherApiException("Claude declined to answer this one")
        val text = root.arr("content").orEmpty().mapNotNull { it.asObj() }
            .filter { it.str("type") == "text" }.mapNotNull { it.str("text") }.joinToString("").trim()
        if (text.isEmpty()) {
            throw WeatherApiException(if (stop == "max_tokens") "Claude ran out of room before answering" else "Claude returned no text")
        }
        return text
    }

    actual suspend fun models(apiKey: String): List<String> {
        val ids = mutableListOf<String>()
        var after: String? = null
        while (true) {
            val response = send(apiKey, "/v1/models?limit=100" + (after?.let { "&after_id=$it" } ?: ""), null, null)
            val root = runCatching { NorthvaneJson.parseToJsonElement(response.body).asObj() }.getOrNull() ?: break
            root.arr("data").orEmpty().mapNotNullTo(ids) { it.asObj()?.str("id") }
            val more = (root["has_more"] as? JsonPrimitive)?.booleanOrNull == true
            after = root.str("last_id")
            if (!more || after == null || ids.size >= 200) break
        }
        return ids.distinct()
    }

    /** One API call, retried twice on rate limits, overload and server errors; failures as the app's own exceptions. */
    private suspend fun send(apiKey: String, path: String, body: String?, beta: String?): HttpResponse {
        var attempt = 0
        while (true) {
            val request = Request.Builder()
                .url(baseUrl.trimEnd('/') + path)
                .header("x-api-key", apiKey)
                .header("anthropic-version", API_VERSION)
                .header("Accept", "application/json")
                .apply {
                    beta?.let { header("anthropic-beta", it) }
                    if (body != null) post(body, "application/json")
                }
                .build()
            val response = http.fetch(request, maxBytes = 1024L * 1024)
            val retryable = response.code == 429 || response.code == 529 || response.code >= 500
            if (retryable && attempt < 2) {
                attempt++
                delay(1_000L * attempt)
                continue
            }
            when (response.code) {
                in 200..299 -> return response
                401, 403 -> throw InvalidCredentialsException("Claude")
                404 -> throw WeatherApiException("Claude doesn't have that model; load the list of models and pick one")
                429 -> throw RateLimitedException("Claude")
                529 -> throw WeatherApiException("Claude is busy right now; try again in a minute")
                400 -> {
                    val message = runCatching { NorthvaneJson.parseToJsonElement(response.body).asObj()?.obj("error")?.str("message") }.getOrNull()
                    throw WeatherApiException("Claude: " + (message?.take(200) ?: "the request was rejected"))
                }
                else -> throw WeatherApiException("Claude returned an error (HTTP ${response.code})")
            }
        }
    }

    private companion object {
        const val API_VERSION = "2026-10-21"
        const val FALLBACK_BETA = "server-side-fallback-2026-07-01"

        /** Room for the model's thinking as well as the short answer. */
        const val MAX_TOKENS = 16_000
    }
}
