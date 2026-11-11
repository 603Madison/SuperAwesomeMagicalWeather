package app.northvane.core

import app.northvane.core.json.NorthvaneJson
import app.northvane.core.model.SavedLocation
import app.northvane.core.model.TripStop
import app.northvane.core.time.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TripTest {
    private val start = LocalDate.of(2026, 11, 14)
    private val stop = SavedLocation("a", "Bar Harbor, Maine", 44.39, -68.2, trip = TripStop("t", "Maine coast", start, start.plusDays(2)))

    @Test
    fun alertsFromTwoDaysBeforeUntilTheLastDay() {
        assertFalse(stop.alertsWanted(start.minusDays(3)))
        assertTrue(stop.alertsWanted(start.minusDays(2)))
        assertTrue(stop.alertsWanted(start.plusDays(2)))
        assertFalse(stop.alertsWanted(start.plusDays(3)))
        assertFalse(stop.copy(notify = false).alertsWanted(start))
        assertTrue(stop.copy(trip = null).alertsWanted(start.plusDays(30)))
    }

    @Test
    fun overTheDayAfter() {
        assertFalse(stop.tripOver(start.plusDays(2)))
        assertTrue(stop.tripOver(start.plusDays(3)))
        assertFalse(stop.copy(trip = null).tripOver(start.plusDays(300)))
    }

    @Test
    fun placesSavedBeforeTripsStillRead() {
        val old = """[{"id":"x","name":"Portland","latitude":43.66,"longitude":-70.25,"notify":true}]"""
        val list = NorthvaneJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(SavedLocation.serializer()), old)
        assertNull(list.single().trip)
        val round = NorthvaneJson.decodeFromString(SavedLocation.serializer(), NorthvaneJson.encodeToString(SavedLocation.serializer(), stop))
        assertEquals(stop, round)
    }
}
