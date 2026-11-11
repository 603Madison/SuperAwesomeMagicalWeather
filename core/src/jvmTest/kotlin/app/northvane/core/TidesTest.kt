package app.northvane.core

import app.northvane.core.time.*
import app.northvane.core.water.TideEvent
import app.northvane.core.water.Tides
import app.northvane.core.water.WaterClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TidesTest {
    private val t0 = Instant.fromEpochSeconds(1_794_000_000L)
    private fun at(hours: Double) = t0.plusSeconds((hours * 3600).toLong())
    private val events = listOf(
        TideEvent(at(0.0), high = false, heightM = 0.2),
        TideEvent(at(6.2), high = true, heightM = 3.0),
        TideEvent(at(12.4), high = false, heightM = 0.4),
        TideEvent(at(18.6), high = true, heightM = 2.8),
    )

    @Test
    fun heightFollowsHalfACosineBetweenTurns() {
        assertEquals(0.2, Tides.heightAt(events, at(0.0))!!, 1e-9)
        assertEquals(1.6, Tides.heightAt(events, at(3.1))!!, 1e-9) // halfway: the mean
        // Rule of twelfths: a quarter of the way in, about a seventh of the range has come in.
        val quarter = Tides.heightAt(events, at(6.2 / 4))!!
        assertEquals(0.2 + 2.8 * (1 - kotlin.math.cos(kotlin.math.PI / 4)) / 2, quarter, 1e-9)
        assertNull(Tides.heightAt(events, at(-1.0)))
        assertNull(Tides.heightAt(events, at(19.0)))
    }

    @Test
    fun nowSaysWhichWayTheWaterIsGoing() {
        val rising = Tides.now(events, at(2.0))!!
        assertTrue(rising.rising)
        assertEquals(at(6.2), rising.next.time)
        assertEquals(at(12.4), rising.following!!.time)
        val falling = Tides.now(events, at(7.0))!!
        assertFalse(falling.rising)
        assertNull(Tides.now(events, at(20.0)))
    }

    @Test
    fun curveStaysWithinTheTurns() {
        val curve = Tides.curve(events, at(-2.0), at(20.0), stepMinutes = 15)
        assertTrue(curve.first().first >= at(0.0))
        assertTrue(curve.all { it.second in 0.2..3.0 })
    }

    @Test
    fun highsDuringAnAlert() {
        assertEquals(listOf(at(18.6)), Tides.highsBetween(events, at(10.0), at(24.0)).map { it.time })
    }

    @Test
    fun canadianPredictionsAreLabelledHighOrLow() {
        val json = """[
            {"eventDate":"2026-11-11T00:40:00Z","value":4.246},
            {"eventDate":"2026-11-11T08:03:00Z","value":0.7},
            {"eventDate":"2026-11-11T15:40:00Z","value":4.675},
            {"eventDate":"2026-11-11T20:49:00Z","value":3.59}
        ]"""
        val parsed = WaterClient.parseCanadianPredictions(json)
        assertEquals(listOf(true, false, true, false), parsed.map { it.high })
        assertEquals(4.675, parsed[2].heightM)
    }

    @Test
    fun canadianStationsNeedHexIdsAndPositions() {
        val json = """[
            {"id":"5cebf1df3d0f4a073c4bbcbb","officialName":"HALIFAX","latitude":44.659,"longitude":-63.583},
            {"id":"../etc","officialName":"Bad","latitude":44.0,"longitude":-63.0},
            {"id":"abc123","officialName":"No position"}
        ]"""
        val stations = WaterClient.parseCanadianStations(json)
        assertEquals(listOf("Halifax"), stations.map { it.name })
    }
}
