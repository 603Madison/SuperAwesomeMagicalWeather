package app.northvane.weather.data

import app.northvane.core.ai.AiProvider
import app.northvane.weather.SecretStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AiKeysTest {
    private class MemorySecrets : SecretStore {
        val map = mutableMapOf<String, String>()
        override fun get(name: String) = map[name]
        override fun has(name: String) = name in map
        override fun put(name: String, value: String?) { if (value == null) map.remove(name) else map[name] = value }
    }

    private val server = AiProvider.entries.first { it.hostedUrl == null }

    @Test
    fun aServersKeyOnlyGoesToThatServer() {
        val secrets = MemorySecrets()
        AiKeys.put(secrets, server, "https://ai.example.com/v1", "sk-1")
        assertEquals("sk-1", AiKeys.get(secrets, server, "https://AI.example.com:443/other/path"))
        assertNull(AiKeys.get(secrets, server, "https://ai.example.net/v1"))
        assertNull(AiKeys.get(secrets, server, "http://ai.example.com/v1"))
        assertNull(AiKeys.get(secrets, server, "https://ai.example.com:8443/v1"))
        assertEquals("https://ai.example.com:443", AiKeys.savedFor(secrets, server))
        AiKeys.remove(secrets, server)
        assertNull(AiKeys.savedFor(secrets, server))
        assertEquals(emptyMap(), secrets.map)
    }

    @Test
    fun keysFromBeforeAreTiedToTheServerSavedThen() {
        val secrets = MemorySecrets()
        secrets.put(SecretStore.AI_API_KEY, "old")
        assertNull(AiKeys.get(secrets, server, "https://box.lan"))
        AiKeys.migrate(secrets, "https://box.lan/")
        assertEquals("old", AiKeys.get(secrets, server, "https://box.lan"))
        assertNull(AiKeys.get(secrets, server, "https://elsewhere.example"))
    }

    @Test
    fun claudesKeyNeedsNoAddress() {
        val secrets = MemorySecrets()
        AiKeys.put(secrets, AiProvider.CLAUDE, "", "sk-ant")
        assertEquals("sk-ant", AiKeys.get(secrets, AiProvider.CLAUDE, "anything"))
    }
}
