package app.northvane.weather.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.northvane.core.model.GeoPoint
import app.northvane.core.radar.RadarFrame

/*
 * The few things a screen needs straight from the phone: permission prompts,
 * the photo picker, voice input, system settings pages, the clock format,
 * the map. Android and iOS each implement these; the screens are shared.
 */

enum class Permission { NOTIFICATIONS, LOCATION, LOCAL_NETWORK }

class PermissionLauncher(private val request: (Permission) -> Unit) {
    fun launch(permission: Permission) = request(permission)
}

/** Asks the phone for a permission; [onResult] gets whether it was granted. */
@Composable
expect fun rememberPermissionLauncher(onResult: (Boolean) -> Unit): PermissionLauncher

/** Whether a permission is granted right now (true where the phone has no such permission). */
@Composable
expect fun rememberPermissionCheck(): (Permission) -> Boolean

/** A photo from the user's library, as image-file bytes (null if cancelled or unreadable). */
@Composable
expect fun rememberPhotoPicker(onPicked: (ByteArray?) -> Unit): () -> Unit

/** Speech to text through the phone's own recognizer, or null where there is none. */
@Composable
expect fun rememberSpeechInput(prompt: String, onHeard: (String) -> Unit): (() -> Unit)?

enum class SystemSettingsPage { NOTIFICATIONS, DATA }

/** Opens this app's page in the phone's settings. */
@Composable
expect fun rememberSystemSettingsOpener(): (SystemSettingsPage) -> Unit

/** Keeps the screen out of screenshots and the app switcher while shown (where keys are typed). */
@Composable
expect fun SecureWindow()

/** [text] as a QR code. */
@Composable
expect fun QrImage(text: String, contentDescription: String, modifier: Modifier = Modifier)

/** The wallpaper-based color scheme where the phone has one (Android 12+), else null. */
@Composable
expect fun platformColorScheme(dark: Boolean): ColorScheme?

/** Whether the phone shows 24-hour times. */
@Composable
expect fun is24HourClock(): Boolean

/** ARGB pixels of a PNG no larger than [maxEdge] on a side, or null. */
expect fun decodePngPixels(bytes: ByteArray, maxEdge: Int): IntArray?

/** The radar map: frames over a base map, with the place and any alert areas marked. */
@Composable
expect fun RadarMap(
    center: GeoPoint,
    frames: List<RadarFrame>,
    index: Int,
    dark: Boolean,
    modifier: Modifier = Modifier,
    /** Marker for the selected location. */
    pin: GeoPoint? = null,
    /** Alert areas to outline (rings and a #RRGGBB color), drawn over the radar. */
    alertAreas: List<Pair<List<List<GeoPoint>>, String>> = emptyList(),
    /** GOES satellite tiles drawn beneath the radar, or null for none. */
    satelliteTiles: String? = null,
)

/** The running [app.northvane.weather.Platform], for screens that only need a detail or two from it. */
val LocalPlatform = androidx.compose.runtime.staticCompositionLocalOf<app.northvane.weather.Platform> { error("No platform provided") }
