package app.northvane.weather.data

import app.northvane.core.json.InstantSerializer
import app.northvane.core.json.NorthvaneJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import app.northvane.core.time.*

/** Something the AI wrote, and what it was written from. */
@Serializable
data class AiReply(
    val text: String,
    val model: String,
    @Serializable(InstantSerializer::class) val createdAt: Instant,
    /** The forecast discussion or alert it explains. */
    val sourceId: String,
    @Serializable(InstantSerializer::class) val sourceTime: Instant? = null,
)

/**
 * Keeps what the AI wrote about each forecast discussion and alert, so the
 * same text is never sent twice: a discussion is rewritten only when the
 * office issues a new one, an alert only when it's updated.
 */
class AiStore(private val files: TextFiles) {
    private val lock = Mutex()
    private val serializer = MapSerializer(String.serializer(), AiReply.serializer())

    private fun file(kind: String) = files.path("ai", "$kind.json")

    private fun load(kind: String): Map<String, AiReply> =
        runCatching { NorthvaneJson.decodeFromString(serializer, files.read(file(kind))!!) }.getOrDefault(emptyMap())

    suspend fun discussion(office: String): AiReply? = withContext(Dispatchers.Default) { lock.withLock { load(DISCUSSIONS)[office] } }

    suspend fun alert(alertId: String): AiReply? = withContext(Dispatchers.Default) { lock.withLock { load(ALERTS)[alertId] } }

    suspend fun putDiscussion(office: String, reply: AiReply) = put(DISCUSSIONS, office, reply)

    suspend fun putAlert(alertId: String, reply: AiReply) = put(ALERTS, alertId, reply)

    private suspend fun put(kind: String, key: String, reply: AiReply) = withContext(Dispatchers.Default) {
        lock.withLock {
            val cutoff = kotlin.time.Clock.System.now().minus(KEEP)
            val kept = (load(kind) + (key to reply)).filterValues { it.createdAt.isAfter(cutoff) }
                .entries.sortedByDescending { it.value.createdAt }.take(MAX_ENTRIES).associate { it.key to it.value }
            files.write(file(kind), NorthvaneJson.encodeToString(serializer, kept))
        }
    }

    companion object {
        private const val DISCUSSIONS = "discussions"
        private const val ALERTS = "alerts"
        private const val MAX_ENTRIES = 40
        private val KEEP: Duration = Duration.ofDays(4)
    }
}
