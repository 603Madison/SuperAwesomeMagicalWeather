package app.northvane.core

import app.northvane.core.model.PlacesCode
import app.northvane.core.model.SavedLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlacesCodeTest {
    @Test
    fun roundTripsPlaces() {
        val places = listOf(
            SavedLocation("a", "Portland, ME", 43.659123, -70.256789),
            SavedLocation("b", "Concord, NH", 43.2081, -71.5376, nickname = "Mom's"),
        )
        val code = PlacesCode.encode(places)
        val back = requireNotNull(PlacesCode.decode(code))
        assertEquals(listOf("Portland, ME", "Mom's"), back.map { it.name })
        assertEquals(43.6591, back[0].latitude, 1e-9)
        assertEquals(-70.2568, back[0].longitude, 1e-9)
        // Already saved (within about 1 km): not added again.
        assertEquals(listOf("Mom's"), PlacesCode.newOnes(back, listOf(places[0])).map { it.name })
        // Not one of ours.
        assertNull(PlacesCode.decode("https://example.com"))
        assertEquals(emptyList(), PlacesCode.decode("SAMW1:" + java.util.Base64.getUrlEncoder().encodeToString("x|999|0".toByteArray())))
        assertEquals(true, code.length < 200)
    }
}
