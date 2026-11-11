package app.northvane.wear

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.wear.compose.material3.AppScaffold
import app.northvane.core.model.UnitSystem
import app.northvane.wear.data.WearSettings
import app.northvane.wear.glance.Glance
import app.northvane.wear.ui.AlertScreen
import app.northvane.wear.ui.HomeScreen
import app.northvane.wear.ui.WearActions
import app.northvane.wear.ui.WearTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.test.assertTrue

/**
 * Renders watch screens with a made-up forecast to round PNGs in
 * wear/build/outputs/screenshots (454 px, a 1.4" round watch), for visual
 * review. Not a pixel-diff test.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w227dp-h227dp-round-xhdpi")
class WearScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val forecast = Fixtures.forecast(
        rain = Fixtures.rain(0.0, 0.0, 0.4, 0.8, 0.5, 0.0, 0.0, 0.0),
        alerts = listOf(Fixtures.warning),
        hourly = Fixtures.hourly { if (it in 6..9) 60 else 10 },
    )
    private val state = WearState(
        ready = true,
        settings = WearSettings(places = listOf(Fixtures.place), units = UnitSystem.IMPERIAL),
        forecast = forecast,
    )
    private val glance = Glance.of(Fixtures.place, forecast, UnitSystem.IMPERIAL, true, Fixtures.now, is24 = false)

    private fun show(content: @Composable () -> Unit) = compose.setContent { WearTheme { AppScaffold { content() } } }

    @Test
    fun home() {
        show { HomeScreen(state, glance, Fixtures.now, WearActions(), onAddPlace = {}) }
        compose.onNodeWithText("Rain starting around 3:30").assertExists()
        save("wear-home")
    }

    @Test
    fun hoursAndDays() {
        show { HomeScreen(state, glance, Fixtures.now, WearActions(), onAddPlace = {}) }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Next 12 hours"))
        compose.waitForIdle()
        save("wear-hours")
    }

    @Test
    fun alert() {
        show { AlertScreen(Fixtures.warning, glance.clock) }
        compose.onNodeWithText("Winter Storm Warning").assertExists()
        save("wear-alert")
    }

    /** Saves the screen as a round watch face would show it. */
    private fun save(name: String) {
        val square = compose.onRoot().captureToImage().asAndroidBitmap()
        val round = Bitmap.createBitmap(square.width, square.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(round)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawCircle(square.width / 2f, square.height / 2f, square.width / 2f, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(square, 0f, 0f, paint)
        assertTrue(square.width in 400..500, "unexpected size ${square.width}")
        val dir = File("build/outputs/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { round.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
