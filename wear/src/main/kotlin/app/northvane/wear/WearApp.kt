package app.northvane.wear

import android.app.Application
import app.northvane.core.eccc.EcccClient
import app.northvane.core.model.SavedLocation
import app.northvane.core.model.Section
import app.northvane.core.net.HttpClients
import app.northvane.core.nowcast.NowcastClient
import app.northvane.core.nws.NwsClient
import app.northvane.core.owm.OwmClient
import app.northvane.core.repo.FetchPlan
import app.northvane.core.repo.SourceConfig
import app.northvane.core.repo.WeatherRepository
import app.northvane.core.repo.attempt
import app.northvane.core.weewx.WeeWxClient
import app.northvane.wear.data.DeviceLocation
import app.northvane.wear.data.PlaceSearch
import app.northvane.wear.data.RainStep
import app.northvane.wear.data.WearForecast
import app.northvane.wear.data.WearStore
import app.northvane.wear.work.AlertNotifier
import app.northvane.wear.work.WearRefreshWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File

class WearApp : Application() {
    lateinit var container: WearContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = WearContainer(this)
        AlertNotifier.createChannel(this)
        container.scope.launch {
            container.store.ensureLoaded()
            runCatching { WearRefreshWorker.sync(this@WearApp, container.store.settings.value) }
        }
    }
}

/** Manual dependency container; one per process. Uses the same forecast engine as Mini and the main app. */
class WearContainer(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val store = WearStore(app)
    val deviceLocation = DeviceLocation(app)

    // Built like Mini's: core's cleartext guard on every hop, no http/https redirect switching.
    private val http: OkHttpClient by lazy {
        HttpClients.create(userAgent = "SuperAwesomeMagicalWeather-Wear/${BuildConfig.VERSION_NAME} (Wear OS)", cacheDir = File(app.cacheDir, "http"), cacheBytes = 2L * 1024 * 1024)
    }

    val repository: WeatherRepository by lazy {
        WeatherRepository(
            nws = NwsClient(http, { NwsClient.userAgent("wear-" + BuildConfig.VERSION_NAME, null) }),
            owm = OwmClient(http), // never used without a key
            weewx = WeeWxClient(HttpClients.localServerClients(http)), // never used: no local stations on the watch
            pointCache = store.pointCache,
            eccc = EcccClient(http),
        )
    }
    val nowcast: NowcastClient by lazy { NowcastClient(http) }
    val places: PlaceSearch by lazy { PlaceSearch(http) }

    /**
     * Downloads [place]'s forecast (and its next-hour rain, when that's on) and
     * saves it. A failed rain check keeps the forecast; a failed forecast fails.
     */
    suspend fun refresh(place: SavedLocation, withRain: Boolean): Result<WearForecast> = attempt {
        coroutineScope {
            val rain = if (withRain) async { attempt { nowcast.next(place.point) }.getOrNull() } else null
            val snapshot = repository.fetch(place, SourceConfig(), PLAN)
            val steps = rain?.await()?.map(RainStep::of) ?: emptyList()
            WearForecast(snapshot, steps).also { store.saveForecast(it) }
        }
    }

    companion object {
        /** Only what the watch shows, like Mini: about 25 KB per refresh, plus 1 KB for the rain. */
        val PLAN = FetchPlan(
            sections = setOf(Section.CURRENT_CONDITIONS, Section.ALERTS, Section.DAILY_FORECAST, Section.HOURLY_FORECAST, Section.FORECAST_DETAILS),
            observationStations = 2,
        )
    }
}
