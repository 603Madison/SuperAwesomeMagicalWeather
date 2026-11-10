package app.northvane.weather.data

import app.northvane.core.ai.AiProvider
import app.northvane.core.net.HttpUrl
import app.northvane.weather.SecretStore

/**
 * The AI provider's API key. Claude's key only ever goes to Anthropic. A
 * self-hosted server's key is saved together with the server's address
 * (scheme, host and port) and only sent to that address, so changing the
 * address, or a typo in it, never hands the old key to another server.
 */
object AiKeys {
    private const val ORIGIN = "ai_api_key_origin"

    /** "https://host:443" for [url], or null when it isn't an http(s) URL. */
    fun origin(url: String): String? =
        HttpUrl.parse(url.trim())?.let { "${it.scheme}://${it.host.lowercase()}:${it.port}" }

    /** The key to send to [baseUrl], or null when none is saved for it. */
    fun get(secrets: SecretStore, provider: AiProvider, baseUrl: String): String? {
        val key = secrets.get(SecretStore.aiKeyName(provider))?.takeIf { it.isNotBlank() } ?: return null
        if (provider.hostedUrl != null) return key
        val saved = secrets.get(ORIGIN) ?: return null
        return key.takeIf { saved == origin(baseUrl) }
    }

    /** The address a self-hosted server's key was saved for, when there is one. */
    fun savedFor(secrets: SecretStore, provider: AiProvider): String? =
        if (provider.hostedUrl != null || !secrets.has(SecretStore.aiKeyName(provider))) null else secrets.get(ORIGIN)

    fun put(secrets: SecretStore, provider: AiProvider, baseUrl: String, key: String) {
        if (provider.hostedUrl == null) {
            val origin = origin(baseUrl) ?: return
            secrets.put(ORIGIN, origin)
        }
        secrets.put(SecretStore.aiKeyName(provider), key)
    }

    fun remove(secrets: SecretStore, provider: AiProvider) {
        secrets.remove(SecretStore.aiKeyName(provider))
        if (provider.hostedUrl == null) secrets.remove(ORIGIN)
    }

    /** A key saved before 2.2.1 has no address: it belongs to the server that was saved with it. */
    fun migrate(secrets: SecretStore, savedBaseUrl: String) {
        if (secrets.has(SecretStore.AI_API_KEY) && !secrets.has(ORIGIN)) origin(savedBaseUrl)?.let { secrets.put(ORIGIN, it) }
    }
}
