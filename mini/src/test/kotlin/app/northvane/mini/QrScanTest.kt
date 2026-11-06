package app.northvane.mini

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.northvane.core.model.PlacesCode
import app.northvane.core.model.SavedLocation
import app.northvane.mini.ui.QrScan
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals

/** The main app's QR code (as drawn there) read back the way Mini reads a photo of it. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class QrScanTest {
    @Test
    fun readsPlacesFromAPhotoOfTheCode() {
        val places = listOf(SavedLocation("a", "Portland, ME", 43.6591, -70.2568), SavedLocation("b", "Mom's", 43.2081, -71.5376))
        val code = PlacesCode.encode(places)
        val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(code, com.google.zxing.BarcodeFormat.QR_CODE, 360, 360)
        val qr = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) qr.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
        // A "photo": the code smaller, off-center, on a grey background.
        val photo = Bitmap.createBitmap(1200, 900, Bitmap.Config.ARGB_8888)
        Canvas(photo).apply {
            drawColor(Color.rgb(120, 120, 120))
            drawBitmap(Bitmap.createScaledBitmap(qr, 420, 420, false), 500f, 260f, null)
        }
        val text = QrScan.decode(photo)
        assertEquals(code, text)
        assertEquals(listOf("Portland, ME", "Mom's"), PlacesCode.decode(requireNotNull(text))!!.map { it.name })
    }
}
