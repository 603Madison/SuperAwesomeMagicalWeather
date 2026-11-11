package app.northvane.wear

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.northvane.core.model.SavedLocation
import app.northvane.core.model.UnitSystem
import app.northvane.wear.data.PlaceSearch
import app.northvane.wear.data.WearSettings
import app.northvane.wear.data.WearStore
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class WearStoreTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val here = SavedLocation("here", "Current location", 43.6, -70.2, isDeviceLocation = true)
    private val boston = SavedLocation("boston", "Boston, Massachusetts", 42.36, -71.06)

    @Test
    fun placesAndSettingsSurviveARestart() = runTest {
        WearStore(context).update {
            it.copy(places = listOf(here, boston), selectedId = "boston", tilePlaceId = "here", units = UnitSystem.METRIC, alertsEnabled = false)
        }
        val again = WearStore(context).apply { ensureLoaded() }.settings.value
        assertEquals(listOf(here, boston), again.places)
        assertEquals("boston", again.selected?.id)
        assertEquals("here", again.tilePlace?.id)
        assertEquals(UnitSystem.METRIC, again.units)
        assertEquals(false, again.alertsEnabled)
        assertEquals(here, again.deviceLocation)
    }

    @Test
    fun forecastsAreCachedPerPlace() = runTest {
        val store = WearStore(context)
        val forecast = Fixtures.forecast()
        store.saveForecast(forecast)
        assertEquals(forecast, WearStore(context).forecast(Fixtures.place.id))
        store.deleteForecast(Fixtures.place.id)
        assertNull(store.forecast(Fixtures.place.id))
    }

    @Test
    fun aDamagedFileIsKeptAside() = runTest {
        val dir = File(context.noBackupFilesDir, "wear").apply { mkdirs() }
        File(dir, "settings.json").writeText("{not json")
        val store = WearStore(context).apply { load() }
        assertEquals(WearSettings().places, store.settings.value.places)
        assertTrue(File(dir, "settings.json.bad").exists())
    }

    @Test
    fun removingThePlaceOnTheTileMovesTheTileBackToTheApp() {
        val s = WearSettings(places = listOf(here, boston), selectedId = "here", tilePlaceId = "boston").without("boston")
        assertEquals(listOf(here), s.places)
        assertNull(s.tilePlaceId)
        assertEquals("here", s.tilePlace?.id)
        val t = WearSettings(places = listOf(here, boston), selectedId = "here", tilePlaceId = "boston").without("here")
        assertEquals("boston", t.selected?.id)
        assertEquals("boston", t.tilePlace?.id)
    }

    @Test
    fun searchResultsBecomePlaces() {
        val results = PlaceSearch.parse(
            """{"results":[{"name":"Paris","latitude":48.85,"longitude":2.35,"admin1":"Île-de-France","country_code":"FR"},
               {"name":"Paris","latitude":33.66,"longitude":-95.55,"admin1":"Texas","country_code":"US"},
               {"name":"Nowhere","latitude":123.0,"longitude":2.0}]}""",
        )
        // US and Canadian places first (NWS covers them best); impossible coordinates dropped.
        assertEquals(listOf("Texas, US", "Île-de-France, FR"), results.map { it.detail })
        val place = PlaceSearch.toPlace(results.first())
        assertEquals("Paris, Texas", place.name)
        assertEquals(33.66, place.latitude)
    }
}
