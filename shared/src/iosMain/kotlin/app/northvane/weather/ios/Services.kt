@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package app.northvane.weather.ios

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import app.northvane.core.model.GeoPoint
import app.northvane.core.owm.OwmClient
import app.northvane.core.owm.PlaceResult
import app.northvane.weather.DeviceLocation
import app.northvane.weather.Notifications
import app.northvane.weather.PlaceSearch
import app.northvane.weather.SecretStore
import app.northvane.weather.ThemeBackground
import app.northvane.weather.data.SettingsRepository
import app.northvane.weather.net.Connectivity
import app.northvane.weather.net.NetworkStatus
import app.northvane.weather.work.AppNotification
import kotlinx.cinterop.useContents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.CoreLocation.CLAuthorizationStatus
import platform.CoreLocation.CLGeocoder
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.CLPlacemark
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.CoreLocation.kCLLocationAccuracyKilometer
import platform.Foundation.NSError
import platform.Foundation.NSUUID
import platform.Foundation.timeIntervalSinceNow
import platform.Network.nw_path_get_status
import platform.Network.nw_path_is_constrained
import platform.Network.nw_path_is_expensive
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_status_satisfied
import platform.UIKit.UIGraphicsBeginImageContextWithOptions
import platform.UIKit.UIGraphicsEndImageContext
import platform.UIKit.UIGraphicsGetImageFromCurrentImageContext
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusEphemeral
import platform.UserNotifications.UNAuthorizationStatusProvisional
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationInterruptionLevel
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNTimeIntervalNotificationTrigger
import platform.UserNotifications.UNUserNotificationCenter
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.coroutines.resume

/** Local notifications through UNUserNotificationCenter. */
object IosNotifications : Notifications {
    private val center get() = UNUserNotificationCenter.currentNotificationCenter()

    /** Last known permission; refreshed at launch, on foreground and after asking. */
    var authorized: Boolean = false
        private set

    fun refresh(done: () -> Unit = {}) {
        center.getNotificationSettingsWithCompletionHandler { settings ->
            val status = settings?.authorizationStatus
            authorized = status == UNAuthorizationStatusAuthorized || status == UNAuthorizationStatusProvisional || status == UNAuthorizationStatusEphemeral
            dispatch_async(dispatch_get_main_queue()) { done() }
        }
    }

    fun requestPermission(onResult: (Boolean) -> Unit) {
        center.requestAuthorizationWithOptions(UNAuthorizationOptionAlert or UNAuthorizationOptionSound or UNAuthorizationOptionBadge) { granted, _ ->
            authorized = granted
            dispatch_async(dispatch_get_main_queue()) { onResult(granted) }
        }
    }

    override fun canNotify(): Boolean = authorized

    override fun sendTest(locationId: String?, place: String?): Boolean {
        if (!authorized) return false
        show(
            AppNotification(
                id = "test-" + NSUUID().UUIDString,
                title = "Test Warning",
                text = listOfNotNull(place, "This is a test notification from SuperAwesomeMagicalWeather. No action is needed.").joinToString("\n\n"),
                locationId = locationId,
                kind = AppNotification.Kind.WARNING,
            ),
        )
        return true
    }

    /** Shows [n] now; the same id replaces an earlier one (an updated alert). */
    fun show(n: AppNotification, delaySeconds: Double = 1.0) {
        val content = UNMutableNotificationContent().apply {
            setTitle(n.title)
            setBody(n.text)
            setThreadIdentifier(n.kind.name.lowercase())
            n.locationId?.let { setUserInfo(mapOf("locationId" to it, "alerts" to (n.kind == AppNotification.Kind.WARNING || n.kind == AppNotification.Kind.ALERT))) }
            if (!n.quiet) setSound(UNNotificationSound.defaultSound)
            setInterruptionLevel(
                if (n.kind == AppNotification.Kind.WARNING) UNNotificationInterruptionLevel.UNNotificationInterruptionLevelTimeSensitive
                else UNNotificationInterruptionLevel.UNNotificationInterruptionLevelActive,
            )
        }
        val trigger = UNTimeIntervalNotificationTrigger.triggerWithTimeInterval(delaySeconds, repeats = false)
        center.addNotificationRequest(UNNotificationRequest.requestWithIdentifier(n.id, content, trigger), withCompletionHandler = null)
    }

    /** Books [n] for [epochSeconds] (the briefing and Tonight are written ahead and delivered on time). */
    fun schedule(n: AppNotification, epochSeconds: Long, nowSeconds: Long) {
        center.removePendingNotificationRequestsWithIdentifiers(listOf(n.id))
        val delay = (epochSeconds - nowSeconds).toDouble()
        if (delay < 60) return
        show(n, delay)
    }

    fun cancel(id: String) = center.removePendingNotificationRequestsWithIdentifiers(listOf(id))
}

/** The phone's position through Core Location ("while using the app"). */
object IosLocation : DeviceLocation {
    private val manager by lazy { CLLocationManager().apply { desiredAccuracy = kCLLocationAccuracyKilometer } }
    private var delegate: Delegate? = null

    private class Delegate(
        val onAuth: ((Boolean) -> Unit)?,
        val onLocation: ((CLLocation?) -> Unit)?,
    ) : NSObject(), CLLocationManagerDelegateProtocol {
        override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
            if (manager.authorizationStatus == kCLAuthorizationStatusNotDetermined) return
            onAuth?.invoke(authorized())
        }

        override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
            onLocation?.invoke(didUpdateLocations.lastOrNull() as? CLLocation)
        }

        override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) {
            onLocation?.invoke(null)
        }
    }

    private fun status(): CLAuthorizationStatus = manager.authorizationStatus

    fun authorized(): Boolean = status().let { it == kCLAuthorizationStatusAuthorizedWhenInUse || it == kCLAuthorizationStatusAuthorizedAlways }

    fun requestPermission(onResult: (Boolean) -> Unit) {
        if (authorized()) return onResult(true)
        if (status() != kCLAuthorizationStatusNotDetermined) return onResult(false)
        delegate = Delegate(onAuth = { ok -> onResult(ok); delegate = null }, onLocation = null)
        manager.delegate = delegate
        manager.requestWhenInUseAuthorization()
    }

    override fun hasPermission(): Boolean = authorized()

    override suspend fun current(): GeoPoint? {
        if (!authorized()) return null
        manager.location?.takeIf { -it.timestamp.timeIntervalSinceNow() < 10 * 60 }?.let { return it.toPoint() }
        return withTimeoutOrNull(20_000) {
            suspendCancellableCoroutine { cont ->
                delegate = Delegate(onAuth = null, onLocation = { loc ->
                    delegate = null
                    if (cont.isActive) cont.resume(loc?.toPoint())
                })
                manager.delegate = delegate
                manager.requestLocation()
            }
        }
    }

    private fun CLLocation.toPoint(): GeoPoint = coordinate.useContents { GeoPoint(latitude, longitude) }
}

/** Network state through Network.framework's path monitor. */
class IosConnectivity : Connectivity {
    private val _status = MutableStateFlow(NetworkStatus())
    override val status: StateFlow<NetworkStatus> = _status.asStateFlow()

    fun start() {
        val monitor = nw_path_monitor_create()
        nw_path_monitor_set_update_handler(monitor) { path ->
            _status.value = NetworkStatus(
                connected = nw_path_get_status(path) == nw_path_status_satisfied,
                // Low Data Mode and satellite links report as constrained; cellular and hotspots as expensive.
                constrained = nw_path_is_constrained(path),
                metered = nw_path_is_expensive(path),
            )
        }
        nw_path_monitor_set_queue(monitor, dispatch_get_main_queue())
        nw_path_monitor_start(monitor)
    }

    override fun current(): NetworkStatus = _status.value
}

/** Place names through Apple's geocoder, then OpenWeatherMap's if a key is set; "lat, lon" works too. */
class IosPlaceSearch(
    private val owm: OwmClient,
    private val secrets: SecretStore,
    private val settings: SettingsRepository,
) : PlaceSearch {
    private val coordinates = Regex("""^\s*(-?\d{1,2}(?:\.\d+)?)\s*[, ]\s*(-?\d{1,3}(?:\.\d+)?)\s*$""")

    override suspend fun search(query: String): List<PlaceResult> {
        val q = query.trim().take(100)
        if (q.length < 2) return emptyList()
        coordinates.matchEntire(q)?.let { m ->
            val lat = m.groupValues[1].toDouble()
            val lon = m.groupValues[2].toDouble()
            if (lat in -90.0..90.0 && lon in -180.0..180.0) return listOf(PlaceResult("${trim(lat)}, ${trim(lon)}", lat, lon))
        }
        val apple = geocode(q)
        if (apple.isNotEmpty()) return apple
        val key = if (settings.settings.first().owmEnabled) secrets.get(SecretStore.OWM_API_KEY) else null
        return if (key != null) runCatching { owm.geocode(q, key) }.getOrDefault(emptyList()) else emptyList()
    }

    private fun trim(v: Double) = app.northvane.core.util.plainDecimal(v, 4)

    private suspend fun geocode(q: String): List<PlaceResult> = withTimeoutOrNull(15_000) {
        suspendCancellableCoroutine { cont ->
            CLGeocoder().geocodeAddressString(q) { placemarks, _ ->
                val results = placemarks.orEmpty().mapNotNull { it as? CLPlacemark }.mapNotNull { p ->
                    val loc = p.location ?: return@mapNotNull null
                    val name = listOfNotNull(p.locality ?: p.name, p.administrativeArea ?: p.country).distinct().joinToString(", ")
                    loc.coordinate.useContents { PlaceResult(name.ifBlank { q }, latitude, longitude) }
                }
                if (cont.isActive) cont.resume(results)
            }
        }
    } ?: emptyList()

    override suspend fun postalCode(latitude: Double, longitude: Double): String? = withTimeoutOrNull(15_000) {
        suspendCancellableCoroutine { cont ->
            CLGeocoder().reverseGeocodeLocation(CLLocation(latitude = latitude, longitude = longitude)) { placemarks, _ ->
                val p = placemarks?.firstOrNull() as? CLPlacemark
                val zip = p?.postalCode?.takeIf { p.ISOcountryCode == "US" }?.take(5)?.takeIf { z -> z.length == 5 && z.all { it.isDigit() } }
                if (cont.isActive) cont.resume(zip)
            }
        }
    }
}

/** The custom theme's photo, scaled to at most 1600 px and kept as a JPEG in the app's private files. */
class IosThemeBackground(private val dir: String) : ThemeBackground {
    private val path get() = "$dir/theme/background.jpg".toPath()

    override suspend fun load(): ImageBitmap? = withContext(Dispatchers.Default) {
        runCatching {
            if (!FileSystem.SYSTEM.exists(path)) return@runCatching null
            val bytes = FileSystem.SYSTEM.read(path) { readByteArray() }
            org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
        }.getOrNull()
    }

    override suspend fun save(bytes: ByteArray): Boolean = withContext(Dispatchers.Main) {
        runCatching {
            val image = UIImage(data = bytes.toNSData())
            val (w, h) = image.size.useContents { width to height }
            if (w <= 0 || h <= 0) return@runCatching false
            val scale = minOf(1.0, 1600.0 / maxOf(w, h))
            val tw = (w * scale).toInt().toDouble()
            val th = (h * scale).toInt().toDouble()
            UIGraphicsBeginImageContextWithOptions(CGSizeMake(tw, th), true, 1.0)
            image.drawInRect(CGRectMake(0.0, 0.0, tw, th))
            val sized = UIGraphicsGetImageFromCurrentImageContext()
            UIGraphicsEndImageContext()
            val jpeg = sized?.let { UIImageJPEGRepresentation(it, 0.85) } ?: return@runCatching false
            path.parent?.let { FileSystem.SYSTEM.createDirectories(it) }
            FileSystem.SYSTEM.write(path) { write(jpeg.toByteArray()) }
            true
        }.getOrDefault(false)
    }

    override fun clear() {
        runCatching { FileSystem.SYSTEM.delete(path) }
    }
}
