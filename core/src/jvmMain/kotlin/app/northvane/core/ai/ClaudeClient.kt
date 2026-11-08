package app.northvane.core.ai

import app.northvane.core.net.InvalidCredentialsException
import app.northvane.core.net.RateLimitedException
import app.northvane.core.net.WeatherApiException
import com.anthropic.client.AnthropicClientAsync
import com.anthropic.client.okhttp.AnthropicOkHttpClientAsync
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.NotFoundException
import com.anthropic.errors.OverloadedException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.beta.AnthropicBeta
import com.anthropic.models.beta.messages.BetaFallbacksParam
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.MessageCreateParams
import kotlinx.coroutines.future.await
import java.io.IOException
import java.security.MessageDigest
import java.time.Duration

/**
 * Claude through Anthropic's Java SDK. One SDK client is kept for the current
 * key and replaced when the key changes. Requests are short, single-turn and
 * non-streaming; the SDK retries rate limits and server errors itself.
 */
actual class ClaudeClient actual constructor(private val baseUrl: String) {
    private var current: Pair<String, AnthropicClientAsync>? = null

    @Synchronized
    private fun client(apiKey: String): AnthropicClientAsync {
        val fingerprint = sha256(apiKey)
        current?.let { (fp, client) -> if (fp == fingerprint) return client else client.close() }
        val client = AnthropicOkHttpClientAsync.builder()
            .apiKey(apiKey)
            .baseUrl(baseUrl)
            .timeout(Duration.ofSeconds(90))
            .maxRetries(2)
            .build()
        current = fingerprint to client
        return client
    }

    actual suspend fun complete(apiKey: String, model: String, request: AiRequest): String {
        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(MAX_TOKENS)
            .system(request.system)
            .addUserMessage(request.user)
            .apply {
                if (ClaudeModels.supportsEffort(model)) {
                    outputConfig(BetaOutputConfig.builder().effort(effortOf(request.effort)).build())
                }
                // If a safety check declines the request, let the API retry it on a suitable model.
                if (ClaudeModels.supportsFallbacks(model)) {
                    addBeta(AnthropicBeta.SERVER_SIDE_FALLBACK_2026_07_01)
                    fallbacks(BetaFallbacksParam.ofDefault())
                }
            }
            .build()
        val message = call { client(apiKey).beta().messages().create(params).await() }
        val stop = message.stopReason().orElse(null)
        if (stop == BetaStopReason.REFUSAL) throw WeatherApiException("Claude declined to answer this one")
        val text = message.content().mapNotNull { block -> block.text().orElse(null)?.text() }.joinToString("").trim()
        if (text.isEmpty()) {
            throw WeatherApiException(if (stop == BetaStopReason.MAX_TOKENS) "Claude ran out of room before answering" else "Claude returned no text")
        }
        return text
    }

    /** The account's models, newest first (the API's order). */
    actual suspend fun models(apiKey: String): List<String> = call {
        val ids = mutableListOf<String>()
        var page = client(apiKey).models().list().await()
        while (true) {
            page.data().mapTo(ids) { it.id() }
            if (!page.hasNextPage() || ids.size >= 200) break
            page = page.nextPage().await()
        }
        ids.distinct()
    }

    /** SDK errors as the app's own, with short messages that never include the key. */
    private suspend fun <T> call(block: suspend () -> T): T = try {
        block()
    } catch (e: UnauthorizedException) {
        throw InvalidCredentialsException("Claude")
    } catch (e: PermissionDeniedException) {
        throw InvalidCredentialsException("Claude")
    } catch (e: NotFoundException) {
        throw WeatherApiException("Claude doesn't have that model; load the list of models and pick one")
    } catch (e: RateLimitException) {
        throw RateLimitedException("Claude")
    } catch (e: OverloadedException) {
        throw WeatherApiException("Claude is busy right now; try again in a minute")
    } catch (e: BadRequestException) {
        throw WeatherApiException("Claude: " + (e.apiMessage().orElse(null)?.take(200) ?: "the request was rejected"))
    } catch (e: AnthropicServiceException) {
        throw WeatherApiException("Claude returned an error (HTTP ${e.statusCode()})")
    } catch (e: AnthropicIoException) {
        throw (e.cause as? IOException) ?: IOException("Couldn't reach Claude", e)
    }

    private companion object {
        /** Room for the model's thinking as well as the short answer. */
        const val MAX_TOKENS = 16_000L

        private fun effortOf(effort: AiEffort) = when (effort) {
            AiEffort.LOW -> BetaOutputConfig.Effort.LOW
            AiEffort.MEDIUM -> BetaOutputConfig.Effort.MEDIUM
        }

        private fun sha256(value: String): String =
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

