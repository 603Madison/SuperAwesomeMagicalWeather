package app.northvane.mini

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.northvane.core.model.AlertCategory
import app.northvane.core.model.AlertSeverity
import app.northvane.core.model.CurrentConditions
import app.northvane.core.model.DailyForecast
import app.northvane.core.model.DataSource
import app.northvane.core.model.HourlyForecast
import app.northvane.core.model.SavedLocation
import app.northvane.core.model.UnitSystem
import app.northvane.core.model.Units
import app.northvane.core.model.WeatherAlert
import app.northvane.core.model.WeatherCondition
import app.northvane.core.model.WeatherSnapshot
import app.northvane.mini.data.MiniSettings
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Drives SuperAwesomeMagicalWeather Mini with keys only, the way a flip phone without a
 * touchscreen does: d-pad, OK, soft keys and number keys. Screenshots are
 * rendered at a typical flip-phone size (480×640, 2.8").
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w320dp-h427dp-hdpi")
class MiniKeysTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private lateinit var scenario: ActivityScenario<MainActivity>
    private val zone = ZoneId.of("America/New_York")
    private val place = SavedLocation("home", "Concord, NH", 43.2081, -71.5376)

    private fun snapshot(): WeatherSnapshot {
        val now = Instant.now()
        val today = LocalDate.now(zone)
        return WeatherSnapshot(
            locationId = place.id,
            fetchedAt = now,
            timeZoneId = zone.id,
            placeName = "Concord, NH",
            current = CurrentConditions(
                observedAt = now, source = DataSource.NWS, description = "Light rain", condition = WeatherCondition.RAIN,
                temperatureC = Units.fToC(46.0), feelsLikeC = Units.fToC(41.0), windSpeedKph = 14.0, humidityPercent = 92.0,
            ),
            hourly = (0 until 24).map { h ->
                HourlyForecast(
                    now.plus(Duration.ofHours(h.toLong())), Units.fToC(46.0 + h % 6), if (h < 4) WeatherCondition.RAIN else WeatherCondition.MOSTLY_CLOUDY,
                    h in 6..17, if (h < 4) "Light Rain" else "Mostly Cloudy", if (h < 4) 80 else 20, 12.0, "NW", 85.0,
                )
            },
            daily = (0 until 7).map { d ->
                DailyForecast(
                    today.plusDays(d.toLong()), if (d == 0) "Today" else "Day $d", Units.fToC(52.0 + d), Units.fToC(38.0 + d),
                    if (d % 2 == 0) WeatherCondition.RAIN else WeatherCondition.PARTLY_CLOUDY, true,
                    if (d % 2 == 0) "Rain Likely" else "Partly Sunny", if (d % 2 == 0) 70 else 10,
                    dayDetail = "Rain likely, mainly before 3pm. Cloudy, with a high near ${52 + d}. Chance of precipitation is 70%.",
                    nightDetail = "Mostly cloudy, with a low around ${38 + d}.",
                    windMaxKph = 20.0, gustMaxKph = 35.0, precipitationMm = 6.0,
                )
            },
            alerts = listOf(
                WeatherAlert(
                    id = "a1", threadId = "a1", event = "Wind Advisory", headline = "Wind Advisory until 6 PM",
                    description = "Southwest winds 20 to 30 mph with gusts up to 50 mph. Gusty winds will blow around unsecured objects.",
                    instruction = "Use extra caution when driving, especially if operating a high profile vehicle.",
                    severity = AlertSeverity.MODERATE, urgency = null, certainty = null, category = AlertCategory.ADVISORY,
                    ends = Instant.now().plus(Duration.ofHours(6)),
                ),
            ),
        )
    }

    @Before
    fun seed() {
        val app = ApplicationProvider.getApplicationContext<MiniApp>()
        runBlocking {
            app.container.store.load()
            app.container.store.update { MiniSettings(places = listOf(place), selectedId = place.id, units = UnitSystem.IMPERIAL, alertsEnabled = false) }
            app.container.store.saveSnapshot(snapshot())
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitForIdle()
    }

    @After
    fun close() = scenario.close()

    private fun press(code: Int) {
        scenario.onActivity {
            it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }
        compose.waitForIdle()
    }

    private fun save(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = File("build/outputs/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun everythingWorksFromTheKeypad() {
        compose.onNodeWithText("Light rain").assertExists()
        save("mini_home")

        // ▼ to 7-day, OK opens it, OK on the first day opens its details.
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onAllNodesWithText("Rain Likely", substring = true)[0].assertExists()
        save("mini_days")
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithText("Rain likely, mainly before 3pm", substring = true).assertExists()
        save("mini_day")

        // Right soft key goes back, twice to Home; the number key 3 opens Alerts.
        press(KeyEvent.KEYCODE_SOFT_RIGHT)
        press(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithText("Light rain").assertExists()
        press(KeyEvent.KEYCODE_3)
        compose.onNodeWithText("Wind Advisory").assertExists()
        save("mini_alerts")
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithText("What to do").assertExists()
        save("mini_alert")
        press(KeyEvent.KEYCODE_BACK)
        press(KeyEvent.KEYCODE_BACK)

        // 1 opens Hourly; 5 opens Places, where the left soft key adds a place.
        press(KeyEvent.KEYCODE_1)
        compose.onAllNodesWithText("Light Rain")[0].assertExists()
        save("mini_hourly")
        press(KeyEvent.KEYCODE_BACK)
        press(KeyEvent.KEYCODE_5)
        compose.onNodeWithText("+ Add a place").assertExists()
        save("mini_places")
        press(KeyEvent.KEYCODE_SOFT_LEFT)
        compose.onNodeWithText("Use my location (GPS)").assertExists()
        save("mini_add")
        press(KeyEvent.KEYCODE_BACK)
        press(KeyEvent.KEYCODE_BACK)

        // 6 opens Settings; OK on the first row switches units.
        press(KeyEvent.KEYCODE_6)
        compose.onNodeWithText("Units: °F, mph, in").assertExists()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        // The setting is saved in the background; wait for the screen to show it.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("Units: °C, km/h, mm", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        save("mini_settings")
    }

    /** The light theme, as on the main app: hero card, key badges and range bars. */
    @Test
    fun lightTheme() {
        // 6 opens Settings; ▼ to Theme and OK switches Dark to Light.
        press(KeyEvent.KEYCODE_6)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("Theme: Light", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        press(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithText("Light rain").assertExists()
        save("mini_home_light")
        press(KeyEvent.KEYCODE_2)
        compose.onAllNodesWithText("Rain Likely", substring = true)[0].assertExists()
        save("mini_days_light")
    }

    @Test
    fun leftSoftKeyCanBeTheMenuKey() {
        // Many Android flip phones send MENU for the left soft key.
        press(KeyEvent.KEYCODE_5)
        press(KeyEvent.KEYCODE_MENU)
        compose.onNodeWithText("Use my location (GPS)").assertExists()
    }
}
