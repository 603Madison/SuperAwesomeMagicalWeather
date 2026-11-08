package app.northvane.weather

import android.app.Application
import android.text.format.DateFormat
import app.northvane.core.net.HttpClients
import app.northvane.core.net.HttpEngine
import app.northvane.core.net.LocalServerClients
import app.northvane.core.owm.OwmClient
import app.northvane.weather.data.AndroidSecretStore
import app.northvane.weather.data.AppSettings
import app.northvane.weather.data.AppSignature
import app.northvane.weather.data.SettingsRepository
import app.northvane.weather.data.ThemeBackgroundStore
import app.northvane.weather.location.AndroidPlaceSearch
import app.northvane.weather.location.DeviceLocationProvider
import app.northvane.weather.net.ConnectivityMonitor
import app.northvane.weather.net.LocalNetworkAccess
import app.northvane.weather.notify.AlertNotifier
import app.northvane.weather.widget.WeatherWidget
import app.northvane.weather.work.AndroidAlertScheduler
import app.northvane.weather.work.AuroraWatchWorker
import app.northvane.weather.work.BriefingWorker
import app.northvane.weather.work.LaundryWatchWorker
import app.northvane.weather.work.RainSoonWorker
import app.northvane.weather.work.TonightWorker
import java.io.File

/** The shared app's view of Android. */
class AndroidPlatform(private val app: Application, settings: () -> SettingsRepository) : Platform {
    override val name = "Android"
    override val appVersion: String = BuildConfig.VERSION_NAME
    override val packageName: String = app.packageName

    override val http: HttpEngine by lazy {
        HttpClients.create(userAgent = "SuperAwesomeMagicalWeather/$appVersion (Android)", cacheDir = File(app.cacheDir, "http"))
    }
    override val localServers: LocalServerClients by lazy { HttpClients.localServerClients(http) }

    override val settingsDir: String = app.filesDir.path
    override val privateDir: String = app.noBackupFilesDir.path

    override val secrets = AndroidSecretStore(app)
    val connectivityMonitor = ConnectivityMonitor(app)
    override val connectivity get() = connectivityMonitor
    override val deviceLocation = DeviceLocationProvider(app)
    override val themeBackground = ThemeBackgroundStore(app)
    override val alertScheduler = AndroidAlertScheduler(app, settings)

    override fun placeSearch(owm: OwmClient, secrets: SecretStore, settings: SettingsRepository): PlaceSearch =
        AndroidPlaceSearch(app, owm, secrets, settings)

    override fun pollenRestriction() = AppSignature.restriction(app)
    override fun signingSha1() = AppSignature.displaySha1(app)

    override suspend fun localNetworkBlocked(url: String) = LocalNetworkAccess.needsPermissionFor(app, url)
    override val localNetworkDeniedMessage: String = LocalNetworkAccess.DENIED_MESSAGE

    override fun onWeatherUpdated() = WeatherWidget.requestUpdate(app)

    override val notifications = object : Notifications {
        override fun canNotify() = AlertNotifier.canNotify(app)
        override fun sendTest(locationId: String?, place: String?) = AlertNotifier.sendTest(app, locationId, place)
    }

    override fun syncReminders(settings: AppSettings) {
        BriefingWorker.schedule(app, settings)
        TonightWorker.schedule(app, settings)
        RainSoonWorker.sync(app, settings)
        AuroraWatchWorker.sync(app, settings)
    }

    override fun laundryWatch(out: Boolean) = if (out) LaundryWatchWorker.start(app) else LaundryWatchWorker.stop(app)

    override fun is24HourClock(): Boolean = DateFormat.is24HourFormat(app)
}
