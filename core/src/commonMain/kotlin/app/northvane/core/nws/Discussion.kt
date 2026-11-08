package app.northvane.core.nws

import app.northvane.core.json.NorthvaneJson
import app.northvane.core.json.arr
import app.northvane.core.json.asObj
import app.northvane.core.json.str
import app.northvane.core.net.ParseException
import app.northvane.core.time.*

/**
 * An Area Forecast Discussion: the forecasters' own notes on the forecast,
 * issued by each NWS office two to four times a day. It is where the
 * reasoning lives (confidence, model disagreement, what could change), which
 * the forecast itself doesn't show.
 */
data class ForecastDiscussion(val id: String, val office: String, val issued: Instant, val text: String)

object Discussions {
    private val OFFICE = Regex("^[A-Z]{3}$")
    private val SECTION = Regex("(?m)^\\.([A-Z][A-Z0-9 /&()-]*?)\\.\\.\\.")

    /** The NWS office for a grid ID (e.g. GYX); null if it isn't one. */
    fun office(gridId: String?): String? = gridId?.trim()?.uppercase()?.takeIf { OFFICE.matches(it) }

    /** The newest discussion's ID and time from `/products/types/AFD/locations/{office}`. */
    fun parseLatest(json: String): Pair<String, Instant>? {
        val graph = runCatching { NorthvaneJson.parseToJsonElement(json).asObj()?.arr("@graph") }.getOrNull()
            ?: throw ParseException("Unexpected response from weather.gov")
        return graph.mapNotNull { item ->
            val o = item.asObj() ?: return@mapNotNull null
            val id = o.str("id")?.takeIf { it.matches(Regex("^[A-Za-z0-9-]{8,64}$")) } ?: return@mapNotNull null
            val time = o.str("issuanceTime")?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() } ?: return@mapNotNull null
            id to time
        }.maxByOrNull { it.second }
    }

    fun parseProduct(json: String, office: String): ForecastDiscussion {
        val o = runCatching { NorthvaneJson.parseToJsonElement(json).asObj() }.getOrNull()
            ?: throw ParseException("Unexpected response from weather.gov")
        val id = o.str("id") ?: throw ParseException("Forecast discussion has no ID")
        val issued = o.str("issuanceTime")?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() }
            ?: throw ParseException("Forecast discussion has no time")
        val text = o.str("productText")?.takeIf { it.isNotBlank() } ?: throw ParseException("Forecast discussion is empty")
        return ForecastDiscussion(id, office, issued, readable(text))
    }

    /**
     * The parts worth reading: everything from the first section on, without
     * the aviation section (written for pilots) or the sign-off, and capped
     * so an unusually long discussion stays a small request.
     */
    fun readable(text: String, maxChars: Int = 16_000): String {
        val body = text.replace("\r", "")
        val start = SECTION.find(body)?.range?.first ?: 0
        val sections = body.substring(start).substringBefore("\n$$").split(Regex("(?m)^&&\\s*$"))
        return sections.map { it.trim() }
            .filter { it.isNotEmpty() && SECTION.find(it)?.groupValues?.get(1)?.startsWith("AVIATION") != true }
            .joinToString("\n\n")
            .take(maxChars)
    }
}
