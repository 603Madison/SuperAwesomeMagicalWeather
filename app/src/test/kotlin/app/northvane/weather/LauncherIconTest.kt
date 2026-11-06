package app.northvane.weather

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Renders the launcher icon as a 512 px PNG (used for the F-Droid listing in fastlane/). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LauncherIconTest {
    @Test
    fun rendersLauncherIcon() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val icon = requireNotNull(ContextCompat.getDrawable(context, R.mipmap.ic_launcher))
        val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, 512, 512)
        icon.draw(Canvas(bitmap))
        assertTrue("icon drew nothing", bitmap.getPixel(256, 256) != 0)
        val dir = File("build/outputs/icon").apply { mkdirs() }
        File(dir, "icon.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
