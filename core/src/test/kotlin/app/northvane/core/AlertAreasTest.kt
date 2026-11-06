package app.northvane.core

import app.northvane.core.alerts.AlertAreas
import app.northvane.core.alerts.AlertSpot
import app.northvane.core.model.GeoPoint
import app.northvane.core.nws.NwsParsers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AlertAreasTest {
    private fun resource(name: String) = requireNotNull(javaClass.getResource(name)).readText()

    @Test
    fun readsStormBasedAreas() {
        val alerts = NwsParsers.parseAlerts(resource("/alerts_polygon.json"))
        val flood = alerts.first { it.event == "Flood Advisory" }
        val ring = assertNotNull(flood.area).single()
        assertTrue(ring.size >= 4)
        // A point at the middle of the drawn area is inside it.
        val centre = GeoPoint(ring.map { it.latitude }.average(), ring.map { it.longitude }.average())
        assertEquals(AlertSpot.Inside, AlertAreas.spot(flood, centre))
        // Albuquerque is well east of the Cibola County advisory.
        val abq = assertIs<AlertSpot.Outside>(AlertAreas.spot(flood, GeoPoint(35.08, -106.65)))
        assertTrue(abq.km in 20.0..150.0, "${abq.km}")
        assertTrue(abq.compass.startsWith("W") || abq.compass.startsWith("S"), abq.compass)
        // Zone-based alerts have no area, so there's nothing more precise to say.
        assertNull(AlertAreas.spot(flood.copy(area = null), centre))
    }

    @Test
    fun squareChecks() {
        val square = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 1.0), GeoPoint(1.0, 1.0), GeoPoint(1.0, 0.0), GeoPoint(0.0, 0.0))
        assertTrue(AlertAreas.contains(square, GeoPoint(0.5, 0.5)))
        val east = assertIs<AlertSpot.Outside>(AlertAreas.spot(sample(square), GeoPoint(0.5, 1.5)))
        assertEquals(55.6, east.km, 1.5)
        assertEquals("W", east.compass)
    }

    private fun sample(ring: List<GeoPoint>) = NwsParsers.parseAlerts(resource("/alerts_polygon.json")).first().copy(area = listOf(ring))
}
