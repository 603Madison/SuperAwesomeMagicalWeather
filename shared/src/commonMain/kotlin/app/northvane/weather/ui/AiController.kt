package app.northvane.weather.ui

import app.northvane.core.ai.AiConfig
import app.northvane.core.ai.AiPrompts
import app.northvane.core.model.SavedLocation
import app.northvane.core.model.WeatherAlert
import app.northvane.core.model.WeatherSnapshot
import app.northvane.core.net.WeatherApiException
import app.northvane.core.net.toUserMessage
import app.northvane.core.nws.Discussions
import app.northvane.core.repo.attempt
import app.northvane.weather.AppContainer
import app.northvane.weather.data.AiReply
import app.northvane.weather.data.AppSettings
import app.northvane.weather.data.SatelliteFeature
import app.northvane.weather.SecretStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.northvane.core.time.*

/** One AI job's progress: the reply, or that it's being written, or what went wrong. */
data class AiTask(val reply: AiReply? = null, val loading: Boolean = false, val error: String? = null)

/** What the AI has written for one place. */
data class AiUiState(
    /** The forecasters' discussion in plain words (US places only). */
    val discussion: AiTask = AiTask(),
    val office: String? = null,
    /** Explanations by alert ID. */
    val alerts: Map<String, AiTask> = emptyMap(),
    val question: String? = null,
    val answer: AiTask = AiTask(),
)

/**
 * Runs the app's three AI jobs (see [AiPrompts]) against the user's chosen
 * provider. Nothing is sent unless AI is set up, the job is switched on, and
 * Backwoods mode allows it.
 */
class AiController(private val container: AppContainer, private val scope: CoroutineScope) {
    val states = MutableStateFlow<Map<String, AiUiState>>(emptyMap())
    private val checkedAt = mutableMapOf<String, Instant>()
    private var discussionJob: Job? = null
    private var askJob: Job? = null

    private fun update(locationId: String, change: (AiUiState) -> AiUiState) =
        states.update { it + (locationId to change(it[locationId] ?: AiUiState())) }

    /** The provider settings with the saved key, or null when AI isn't set up. */
    private suspend fun config(settings: AppSettings): AiConfig? {
        if (!settings.aiEnabled) return null
        val key = withContext(Dispatchers.Default) { container.secrets.get(SecretStore.aiKeyName(settings.aiProvider)) }
        return AiConfig(settings.aiProvider, settings.aiBaseUrl, settings.aiModel, key).takeIf { it.isComplete }
    }

    /**
     * The town to tell the AI about: the National Weather Service's place name
     * for the forecast point, else the saved name. Never a nickname ("Mom's")
     * or "Current location", which mean nothing to the model.
     */
    private fun placeFor(location: SavedLocation, snapshot: WeatherSnapshot?): String =
        snapshot?.placeName?.takeIf { it.isNotBlank() }
            ?: location.name.takeUnless { location.isDeviceLocation && it.equals("Current location", ignoreCase = true) }
            ?: "the forecast area"

    /** Why a request can't go out right now, or null if it can. */
    private suspend fun blocked(config: AiConfig, settings: AppSettings, liteMode: Boolean): String? = when {
        liteMode && SatelliteFeature.AI_SUMMARY !in settings.satelliteFeatures -> "AI is paused in Backwoods mode"
        container.platform.localNetworkBlocked(config.provider.baseUrl(config.baseUrl)) ->
            "Allow local network access for your AI server in Settings › AI"
        else -> null
    }

    /**
     * Shows the forecasters' notes for [location]: the stored rewrite if the
     * office hasn't issued a newer discussion, otherwise a new one. Checks for
     * a newer discussion (about 1 KB) at most every 30 minutes unless [force].
     */
    fun loadDiscussion(location: SavedLocation, settings: AppSettings, liteMode: Boolean, force: Boolean = false) {
        if (!settings.aiEnabled || !settings.aiDiscussion) return
        discussionJob?.cancel()
        discussionJob = scope.launch {
            val config = config(settings) ?: return@launch
            val office = container.weatherCache.pointCache.get(location.point.nwsKey())?.point?.gridId?.let(Discussions::office)
                ?: return@launch // Outside the US: no discussion.
            val snapshot = container.weatherCache.read(location.id)
            val place = placeFor(location, snapshot)
            // Stored per office and place: two places in one office's area get their own notes.
            val storeKey = "v2|$office|$place"
            val stored = container.aiReplies.discussion(storeKey)?.takeIf { it.model == config.model }
            update(location.id) { it.copy(office = office, discussion = it.discussion.copy(reply = it.discussion.reply ?: stored)) }
            val now = kotlin.time.Clock.System.now()
            val recent = checkedAt[office]?.let { Duration.between(it, now) < RECHECK } == true
            if (!force && recent && stored != null) return@launch
            blocked(config, settings, liteMode)?.let { reason ->
                if (stored == null || force) update(location.id) { it.copy(discussion = it.discussion.copy(error = reason)) }
                return@launch
            }
            update(location.id) { it.copy(discussion = it.discussion.copy(loading = true, error = null)) }
            val result = attempt {
                val (id, _) = container.nws.latestDiscussionId(office) ?: throw WeatherApiException("This weather office has no forecast discussion right now")
                checkedAt[office] = now
                if (stored != null && stored.sourceId == id) return@attempt stored
                val discussion = container.nws.discussion(office, id)
                val zone = snapshot?.timeZoneId?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
                val text = container.ai.complete(config, AiPrompts.discussion(discussion, place, zone))
                AiReply(text, config.model, kotlin.time.Clock.System.now(), id, discussion.issued).also { container.aiReplies.putDiscussion(storeKey, it) }
            }
            result.onSuccess { reply -> update(location.id) { it.copy(discussion = AiTask(reply)) } }
                .onFailure { e -> update(location.id) { it.copy(discussion = it.discussion.copy(loading = false, error = e.toUserMessage())) } }
        }
    }

    /** Rewrites one alert for [location]; reuses the stored rewrite unless the alert was updated. */
    fun explainAlert(location: SavedLocation, alert: WeatherAlert, snapshot: WeatherSnapshot, settings: AppSettings, liteMode: Boolean) {
        if (!settings.aiEnabled || !settings.aiAlerts) return
        if (states.value[location.id]?.alerts?.get(alert.id)?.loading == true) return
        scope.launch {
            val config = config(settings) ?: return@launch
            val place = placeFor(location, snapshot)
            val storeKey = "v2|${alert.id}|$place"
            container.aiReplies.alert(storeKey)?.takeIf { it.model == config.model }?.let { stored ->
                update(location.id) { it.copy(alerts = it.alerts + (alert.id to AiTask(stored))) }
                return@launch
            }
            blocked(config, settings, liteMode)?.let { reason ->
                update(location.id) { it.copy(alerts = it.alerts + (alert.id to AiTask(error = reason))) }
                return@launch
            }
            update(location.id) { it.copy(alerts = it.alerts + (alert.id to AiTask(loading = true))) }
            val zone = runCatching { ZoneId.of(snapshot.timeZoneId) }.getOrDefault(ZoneId.systemDefault())
            val result = attempt { container.ai.complete(config, AiPrompts.alert(alert, place, zone, kotlin.time.Clock.System.now())) }
            result.onSuccess { text ->
                val reply = AiReply(text, config.model, kotlin.time.Clock.System.now(), alert.id, alert.sent)
                container.aiReplies.putAlert(storeKey, reply)
                update(location.id) { it.copy(alerts = it.alerts + (alert.id to AiTask(reply))) }
            }.onFailure { e ->
                update(location.id) { it.copy(alerts = it.alerts + (alert.id to AiTask(error = e.toUserMessage()))) }
            }
        }
    }

    /** Answers a question about [location]'s forecast. Answers aren't stored. */
    fun ask(location: SavedLocation, question: String, snapshot: WeatherSnapshot, settings: AppSettings, liteMode: Boolean, extras: String = "") {
        val q = question.trim().take(AiPrompts.MAX_QUESTION)
        if (q.length < 3 || !settings.aiEnabled || !settings.aiAsk) return
        askJob?.cancel()
        askJob = scope.launch {
            val config = config(settings) ?: return@launch
            update(location.id) { it.copy(question = q, answer = AiTask()) }
            blocked(config, settings, liteMode)?.let { reason ->
                update(location.id) { it.copy(answer = AiTask(error = reason)) }
                return@launch
            }
            update(location.id) { it.copy(answer = AiTask(loading = true)) }
            val result = attempt { container.ai.complete(config, AiPrompts.question(q, snapshot, placeFor(location, snapshot), settings.units, kotlin.time.Clock.System.now(), location.longitude, extras)) }
            result.onSuccess { text -> update(location.id) { it.copy(answer = AiTask(AiReply(text, config.model, kotlin.time.Clock.System.now(), "question"))) } }
                .onFailure { e -> update(location.id) { it.copy(answer = AiTask(error = e.toUserMessage())) } }
        }
    }

    fun clearAnswer(locationId: String) {
        askJob?.cancel()
        update(locationId) { it.copy(question = null, answer = AiTask()) }
    }

    companion object {
        private val RECHECK: Duration = Duration.ofMinutes(30)
    }
}
