package app.northvane.weather.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.speech.RecognizerIntent
import android.text.format.DateFormat
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

// Literal rather than Manifest.permission.ACCESS_LOCAL_NETWORK (API 37); only asked for where enforced.
private const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
private val LOCATION = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

private fun Context.granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

@Composable
actual fun rememberPermissionLauncher(onResult: (Boolean) -> Unit): PermissionLauncher {
    val single = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onResult(it) }
    val multiple = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result -> onResult(result.values.any { it }) }
    return remember(single, multiple) {
        PermissionLauncher { p ->
            when (p) {
                Permission.LOCATION -> multiple.launch(LOCATION)
                Permission.NOTIFICATIONS ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) single.launch(Manifest.permission.POST_NOTIFICATIONS) else onResult(true)
                Permission.LOCAL_NETWORK ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) single.launch(LOCAL_NETWORK) else onResult(true)
            }
        }
    }
}

@Composable
actual fun rememberPermissionCheck(): (Permission) -> Boolean {
    val context = LocalContext.current
    return remember(context) {
        { p ->
            when (p) {
                Permission.LOCATION -> context.granted(Manifest.permission.ACCESS_COARSE_LOCATION)
                Permission.NOTIFICATIONS -> Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || context.granted(Manifest.permission.POST_NOTIFICATIONS)
                Permission.LOCAL_NETWORK -> Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN || context.granted(LOCAL_NETWORK)
            }
        }
    }
}

@Composable
actual fun rememberPhotoPicker(onPicked: (ByteArray?) -> Unit): () -> Unit {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        onPicked(uri?.let { u ->
            runCatching {
                context.contentResolver.openInputStream(u)?.use { input ->
                    // A phone photo is a few MB; refuse anything absurd rather than run out of memory.
                    val bytes = input.readNBytes(MAX_PHOTO_BYTES + 1)
                    bytes.takeIf { it.size <= MAX_PHOTO_BYTES }
                }
            }.getOrNull()
        })
    }
    return { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
}

private const val MAX_PHOTO_BYTES = 40 * 1024 * 1024

@Composable
actual fun rememberSpeechInput(prompt: String, onHeard: (String) -> Unit): (() -> Unit)? {
    val context = LocalContext.current
    // The phone's own speech recognizer: no permission needed, and nothing is sent by this app.
    val intent = remember(prompt) {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
    }
    val available = remember { intent.resolveActivity(context.packageManager) != null }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val heard = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (result.resultCode == Activity.RESULT_OK && !heard.isNullOrBlank()) onHeard(heard)
    }
    return if (available) ({ runCatching { launcher.launch(intent) } }) else null
}

@Composable
actual fun rememberSystemSettingsOpener(): (SystemSettingsPage) -> Unit {
    val context = LocalContext.current
    return remember(context) {
        { page ->
            val uri = Uri.fromParts("package", context.packageName, null)
            val candidates = when (page) {
                SystemSettingsPage.NOTIFICATIONS -> listOf(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri),
                )
                SystemSettingsPage.DATA -> listOf(
                    Intent(Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS, uri),
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri),
                )
            }
            for (intent in candidates) {
                if (runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) break
            }
        }
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

@Composable
actual fun SecureWindow() {
    val activity = LocalContext.current.findActivity() ?: return
    DisposableEffect(activity) {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

@Composable
actual fun QrImage(text: String, contentDescription: String, modifier: Modifier) {
    val bitmap = remember(text) {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 720, 720, mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M))
        val pixels = IntArray(matrix.width * matrix.height) { i ->
            if (matrix.get(i % matrix.width, i / matrix.width)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        }
        android.graphics.Bitmap.createBitmap(pixels, matrix.width, matrix.height, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
    }
    Image(bitmap, contentDescription = contentDescription, modifier = modifier)
}

@Composable
actual fun platformColorScheme(dark: Boolean): ColorScheme? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    val context = LocalContext.current
    return if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
}

@Composable
actual fun is24HourClock(): Boolean = DateFormat.is24HourFormat(LocalContext.current)

actual fun decodePngPixels(bytes: ByteArray, maxEdge: Int): IntArray? {
    // Read the size first: a small file can claim a huge picture and exhaust memory when decoded.
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth !in 1..maxEdge || bounds.outHeight !in 1..maxEdge) return null
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
    return IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        bitmap.recycle()
    }
}
