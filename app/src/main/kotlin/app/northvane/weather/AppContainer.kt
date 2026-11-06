package app.northvane.weather

import app.northvane.core.aurora.AuroraClient
import app.northvane.core.nowcast.NowcastClient
import app.northvane.core.radar.HrrrSmoke
import app.northvane.core.water.WaterClient
import android.app.Application
import app.northvane.core.net.HttpClients
import app.northvane.core.history.AcisClient
import app.northvane.core.air.AirNowClient
import app.northvane.core.air.EpaUvClient
import app.northvane.core.air.OpenMeteoAirClient
import app.northvane.weather.data.ThemeBackgroundStore
import app.northvane.weather.data.ClimateStore
import app.northvane.weather.data.AiStore
import app.northvane.core.ai.AiClient
import app.northvane.core.eccc.EcccClient
import app.northvane.core.nws.NwsClient
import app.northvane.core.pollen.PollenClient
import app.northvane.core.owm.OwmClient
import app.northvane.core.radar.RadarService
import app.northvane.core.repo.WeatherRepository
import app.northvane.core.weewx.WeeWxClient
import app.northvane.weather.data.AlertStateStore
import app.northvane.weather.data.LocationStore
import app.northvane.weather.data.SecretStore
import app.northvane.weather.data.SettingsRepository
import app.northvane.weather.data.WeatherCache
import app.northvane.weather.data.WeatherService
import app.northvane.weather.location.DeviceLocationProvider
import app.northvane.weather.location.PlaceSearch
import app.northvane.weather.net.ConnectivityMonitor
import app.northvane.weather.work.AlertScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File

/** Manual dependency container; one instance per process, owned by [NorthvaneApp]. */
class AppContainer(val app: Application) {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings = SettingsRepository(app)
    val secrets = SecretStore(app)
    val locations = LocationStore(app)
    val alertState = AlertStateStore(app)
    val weatherCache = WeatherCache(app)
    val connectivity = ConnectivityMonitor(app)
    val deviceLocation = DeviceLocationProvider(app)

    @Volatile
    private var nwsContact: String? = null

    private val http: OkHttpClient by lazy {
        HttpClients.create(
            userAgent = "SuperAwesomeMagicalWeather/${BuildConfig.VERSION_NAME} (Android)",
            cacheDir = File(app.cacheDir, "http"),
        )
    }

    val nws: NwsClient by lazy { NwsClient(http, { NwsClient.userAgent(BuildConfig.VERSION_NAME, nwsContact) }) }
    val owm: OwmClient by lazy { OwmClient(http) }
    val weewx: WeeWxClient by lazy { WeeWxClient(HttpClients.localServerClients(http)) }
    val radar: RadarService by lazy { RadarService(http) }
    val pollen: PollenClient by lazy { PollenClient(http) }
    val airNow: AirNowClient by lazy { AirNowClient(http) }
    val themeBackground = ThemeBackgroundStore(app)
    val acis: AcisClient by lazy { AcisClient(http) }
    val climate: ClimateStore by lazy { ClimateStore(app, acis) }
    val ai: AiClient by lazy { AiClient(HttpClients.localServerClients(http)) }
    val aiReplies: AiStore by lazy { AiStore(app) }

    val repository: WeatherRepository by lazy {
        WeatherRepository(nws, owm, weewx, weatherCache.pointCache, acis = acis, pollen = pollen,
            airNow = airNow, epaUv = EpaUvClient(http), openMeteoAir = OpenMeteoAirClient(http),
            eccc = EcccClient(http), aurora = aurora,
            smoke = HrrrSmoke(http), water = WaterClient(http),
        )
    }

    val weather: WeatherService by lazy {
        WeatherService(app, repository, weatherCache, settings, secrets, locations, connectivity, placeSearch)
    }

    val placeSearch: PlaceSearch by lazy { PlaceSearch(app, owm, secrets, settings) }

    val alertScheduler: AlertScheduler by lazy { AlertScheduler(app, settings) }
    val aurora: AuroraClient by lazy { AuroraClient(http) }
    val nowcast: NowcastClient by lazy { NowcastClient(http) }

    init {
        applicationScope.launch {
            settings.settings.collect { nwsContact = it.nwsContact }
        }
    }
}
