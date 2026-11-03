package app.northvane.mini

import android.app.Application
import app.northvane.core.eccc.EcccClient
import app.northvane.core.net.HttpClients
import app.northvane.core.nws.NwsClient
import app.northvane.core.owm.OwmClient
import app.northvane.core.repo.WeatherRepository
import app.northvane.core.weewx.WeeWxClient
import app.northvane.mini.data.DeviceLocation
import app.northvane.mini.data.MiniStore
import app.northvane.mini.data.PlaceSearch
import app.northvane.mini.data.RadarImages
import app.northvane.mini.work.MiniAlertWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File

class MiniApp : Application() {
    lateinit var container: MiniContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = MiniContainer(this)
        MiniAlertWorker.createChannel(this)
        container.scope.launch {
            container.store.load()
            runCatching { MiniAlertWorker.sync(this@MiniApp, container.store.settings.value) }
        }
    }
}

/** Manual dependency container; one per process. Uses the same forecast engine as Northvane. */
class MiniContainer(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val store = MiniStore(app)
    val deviceLocation = DeviceLocation(app)

    private val http: OkHttpClient by lazy {
        HttpClients.create(userAgent = "Northvane-Mini/${BuildConfig.VERSION_NAME} (Android)", cacheDir = File(app.cacheDir, "http"))
    }

    val repository: WeatherRepository by lazy {
        WeatherRepository(
            nws = NwsClient(http, { NwsClient.userAgent("mini-" + BuildConfig.VERSION_NAME, null) }),
            owm = OwmClient(http), // never used without a key
            weewx = WeeWxClient(HttpClients.localServerClients(http)), // never used: no local stations in Mini
            pointCache = store.pointCache,
            eccc = EcccClient(http),
        )
    }
    val places: PlaceSearch by lazy { PlaceSearch(http) }
    val radar: RadarImages by lazy { RadarImages(http) }
}
