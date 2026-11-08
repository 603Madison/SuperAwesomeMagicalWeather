@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package app.northvane.weather.ui

import androidx.compose.foundation.Image
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import app.northvane.weather.ios.IosLocation
import app.northvane.weather.ios.IosNotifications
import app.northvane.weather.ios.toByteArray
import app.northvane.weather.ios.toNSData
import app.northvane.weather.ios.topViewController
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import platform.CoreGraphics.CGAffineTransformMakeScale
import platform.CoreImage.CIContext
import platform.CoreImage.CIFilter
import platform.CoreImage.createCGImage
import platform.CoreImage.filterWithName
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.currentLocale
import platform.Foundation.dataUsingEncoding
import platform.Foundation.setValue
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerFilter
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.UIKit.UIImage
import platform.UIKit.UIImagePNGRepresentation
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

@Composable
actual fun rememberPermissionLauncher(onResult: (Boolean) -> Unit): PermissionLauncher = remember(onResult) {
    PermissionLauncher { p ->
        when (p) {
            Permission.NOTIFICATIONS -> IosNotifications.requestPermission(onResult)
            Permission.LOCATION -> IosLocation.requestPermission(onResult)
            // iOS asks by itself the first time the app talks to the local network.
            Permission.LOCAL_NETWORK -> onResult(true)
        }
    }
}

@Composable
actual fun rememberPermissionCheck(): (Permission) -> Boolean = remember {
    { p ->
        when (p) {
            Permission.NOTIFICATIONS -> IosNotifications.authorized
            Permission.LOCATION -> IosLocation.authorized()
            Permission.LOCAL_NETWORK -> true
        }
    }
}

/** Keeps the picker's delegate alive while the sheet is open (UIKit holds delegates weakly). */
private var activePicker: PhotoPickerDelegate? = null

private class PhotoPickerDelegate(private val onPicked: (ByteArray?) -> Unit) : NSObject(), PHPickerViewControllerDelegateProtocol {
    override fun picker(picker: PHPickerViewController, didFinishPicking: List<*>) {
        picker.dismissViewControllerAnimated(true, null)
        val result = didFinishPicking.firstOrNull() as? PHPickerResult
        if (result == null) {
            onPicked(null)
            activePicker = null
            return
        }
        result.itemProvider.loadDataRepresentationForTypeIdentifier("public.image") { data, _ ->
            val bytes = data?.toByteArray()
            dispatch_async(dispatch_get_main_queue()) {
                onPicked(bytes)
                activePicker = null
            }
        }
    }
}

@Composable
actual fun rememberPhotoPicker(onPicked: (ByteArray?) -> Unit): () -> Unit = remember(onPicked) {
    {
        val config = PHPickerConfiguration().apply {
            filter = PHPickerFilter.imagesFilter
            selectionLimit = 1
        }
        val picker = PHPickerViewController(configuration = config)
        val delegate = PhotoPickerDelegate(onPicked)
        activePicker = delegate
        picker.delegate = delegate
        topViewController()?.presentViewController(picker, animated = true, completion = null)
    }
}

/** The iPhone keyboard has dictation built in (the mic key), so the app adds no button of its own. */
@Composable
actual fun rememberSpeechInput(prompt: String, onHeard: (String) -> Unit): (() -> Unit)? = null

@Composable
actual fun rememberSystemSettingsOpener(): (SystemSettingsPage) -> Unit = remember {
    { _ ->
        NSURL.URLWithString(UIApplicationOpenSettingsURLString)?.let { url ->
            UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any>(), completionHandler = null)
        }
    }
}

/** iOS can't keep a screen out of screenshots; the key fields are masked instead. */
@Composable
actual fun SecureWindow() {}

@Composable
actual fun QrImage(text: String, contentDescription: String, modifier: Modifier) {
    val image = remember(text) { qrBitmap(text) }
    if (image != null) Image(image, contentDescription = contentDescription, modifier = modifier)
}

private fun qrBitmap(text: String): ImageBitmap? {
    val filter = CIFilter.filterWithName("CIQRCodeGenerator") ?: return null
    val data = text.encodeToByteArray().toNSData()
    filter.setValue(data, forKey = "inputMessage")
    filter.setValue("M", forKey = "inputCorrectionLevel")
    val output = filter.outputImage?.imageByApplyingTransform(CGAffineTransformMakeScale(12.0, 12.0)) ?: return null
    val cg = CIContext().createCGImage(output, fromRect = output.extent) ?: return null
    val png = UIImagePNGRepresentation(UIImage.imageWithCGImage(cg)) ?: return null
    return runCatching { org.jetbrains.skia.Image.makeFromEncoded(png.toByteArray()).toComposeImageBitmap() }.getOrNull()
}

@Composable
actual fun platformColorScheme(dark: Boolean): ColorScheme? = null

@Composable
actual fun is24HourClock(): Boolean = remember { is24Hour() }

internal fun is24Hour(): Boolean =
    NSDateFormatter.dateFormatFromTemplate("j", 0u, NSLocale.currentLocale)?.contains('a') == false

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
