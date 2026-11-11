package app.northvane.weather.ui

import androidx.compose.foundation.Image
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toComposeImageBitmap
import app.northvane.weather.desktop.installed
import app.northvane.weather.desktop.run
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo

/*
 * The desktop has no permission prompts: notifications need nothing, there's
 * no location service, and the local network is always reachable.
 */

@Composable
actual fun rememberPermissionLauncher(onResult: (Boolean) -> Unit): PermissionLauncher =
    remember(onResult) { PermissionLauncher { permission -> onResult(permission != Permission.LOCATION) } }

@Composable
actual fun rememberPermissionCheck(): (Permission) -> Boolean = remember { { it != Permission.LOCATION } }

/** The system's file chooser, limited to pictures. */
@Composable
actual fun rememberPhotoPicker(onPicked: (ByteArray?) -> Unit): () -> Unit = remember(onPicked) {
    {
        val dialog = java.awt.FileDialog(null as java.awt.Frame?, "Choose a picture", java.awt.FileDialog.LOAD).apply {
            setFilenameFilter { _, name -> name.substringAfterLast('.').lowercase() in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp") }
            isVisible = true
        }
        val file = dialog.files.firstOrNull()
        // A huge file isn't a background photo; don't read it into memory.
        onPicked(file?.takeIf { it.length() in 1..(30L * 1024 * 1024) }?.let { runCatching { it.readBytes() }.getOrNull() })
    }
}

@Composable
actual fun rememberSpeechInput(prompt: String, onHeard: (String) -> Unit): (() -> Unit)? = null

/** GNOME's notification settings, where there's GNOME. */
@Composable
actual fun rememberSystemSettingsOpener(): (SystemSettingsPage) -> Unit = remember {
    { page ->
        if (page == SystemSettingsPage.NOTIFICATIONS && installed("gnome-control-center")) {
            Thread { run(listOf("gnome-control-center", "notifications"), timeoutSeconds = 2) }.apply { isDaemon = true }.start()
        }
    }
}

@Composable
actual fun SecureWindow() {}

@Composable
actual fun QrImage(text: String, contentDescription: String, modifier: Modifier) {
    val bitmap = remember(text) {
        val matrix = QRCodeWriter().encode(
            text, BarcodeFormat.QR_CODE, 720, 720,
            mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M),
        )
        val pixels = ByteArray(matrix.width * matrix.height * 4)
        for (i in 0 until matrix.width * matrix.height) {
            val v = if (matrix.get(i % matrix.width, i / matrix.width)) 0 else 255
            pixels[i * 4] = v.toByte(); pixels[i * 4 + 1] = v.toByte(); pixels[i * 4 + 2] = v.toByte(); pixels[i * 4 + 3] = 255.toByte()
        }
        org.jetbrains.skia.Image.makeRaster(ImageInfo.makeN32(matrix.width, matrix.height, ColorAlphaType.OPAQUE), pixels, matrix.width * 4)
            .toComposeImageBitmap()
    }
    Image(bitmap, contentDescription = contentDescription, modifier = modifier)
}

@Composable
actual fun platformColorScheme(dark: Boolean): ColorScheme? = null

@Composable
actual fun is24HourClock(): Boolean = remember { desktop24Hour() }

/** GNOME's clock setting when there is one, otherwise the locale's time format. */
internal fun desktop24Hour(): Boolean {
    if (installed("gsettings")) {
        run(listOf("gsettings", "get", "org.gnome.desktop.interface", "clock-format"), timeoutSeconds = 3)?.let { return "24h" in it }
    }
    val pattern = (java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT) as? java.text.SimpleDateFormat)?.toPattern().orEmpty()
    return 'H' in pattern || 'k' in pattern
}

actual fun decodePngPixels(bytes: ByteArray, maxEdge: Int): IntArray? {
    val image = runCatching { org.jetbrains.skia.Image.makeFromEncoded(bytes) }.getOrNull() ?: return null
    if (image.width !in 1..maxEdge || image.height !in 1..maxEdge) return null
    val bitmap = Bitmap()
    bitmap.allocPixels(ImageInfo(image.width, image.height, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL))
    if (!image.readPixels(bitmap, 0, 0)) return null
    val raw = bitmap.readPixels() ?: return null
    return IntArray(image.width * image.height) { i ->
        val b = raw[i * 4].toInt() and 0xFF
        val g = raw[i * 4 + 1].toInt() and 0xFF
        val r = raw[i * 4 + 2].toInt() and 0xFF
        val a = raw[i * 4 + 3].toInt() and 0xFF
        (a shl 24) or (r shl 16) or (g shl 8) or b
    }
}
