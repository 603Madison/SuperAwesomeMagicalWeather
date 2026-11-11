package app.northvane.core.water

import app.northvane.core.time.*
import kotlin.math.PI
import kotlin.math.cos

/**
 * The tide between published highs and lows. Tide tables give only the
 * turning points; between them the water follows close to half a cosine
 * (the "rule of twelfths"), which is plenty for a curve and for "rising" or
 * "falling", though not for navigation.
 */
object Tides {
    data class Now(
        /** Estimated height now, or null outside the predictions. */
        val heightM: Double?,
        val rising: Boolean,
        val next: TideEvent,
        /** The one after [next]. */
        val following: TideEvent?,
    )

    /** Where the tide is at [now], or null when the predictions don't reach past it. */
    fun now(events: List<TideEvent>, now: Instant): Now? {
        val sorted = events.sortedBy { it.time }
        val nextIndex = sorted.indexOfFirst { it.time > now }
        if (nextIndex < 0) return null
        val next = sorted[nextIndex]
        return Now(heightAt(sorted, now), rising = next.high, next = next, following = sorted.getOrNull(nextIndex + 1))
    }

    /** Height at [t], interpolated between the high and low around it; null outside the predictions. */
    fun heightAt(events: List<TideEvent>, t: Instant): Double? {
        val sorted = events.sortedBy { it.time }
        val after = sorted.indexOfFirst { it.time >= t }
        if (after < 0) return null
        val b = sorted[after]
        if (b.time == t) return b.heightM
        val a = sorted.getOrNull(after - 1) ?: return null
        val span = (b.time.epochSeconds - a.time.epochSeconds).toDouble()
        if (span <= 0) return b.heightM
        val f = (t.epochSeconds - a.time.epochSeconds) / span
        return a.heightM + (b.heightM - a.heightM) * (1 - cos(PI * f)) / 2
    }

    /** Heights from [from] to [to] every [stepMinutes], skipping times outside the predictions. */
    fun curve(events: List<TideEvent>, from: Instant, to: Instant, stepMinutes: Int = 20): List<Pair<Instant, Double>> {
        val sorted = events.sortedBy { it.time }
        val out = mutableListOf<Pair<Instant, Double>>()
        var t = from
        while (t <= to) {
            heightAt(sorted, t)?.let { out += t to it }
            t = t.plusSeconds(stepMinutes * 60L)
        }
        return out
    }

    /**
     * Highs during [from]..[to] (a coastal flood alert's hours, say), which is
     * when the water comes up farthest.
     */
    fun highsBetween(events: List<TideEvent>, from: Instant, to: Instant): List<TideEvent> =
        events.filter { it.high && it.time >= from && it.time <= to }.sortedBy { it.time }
}
