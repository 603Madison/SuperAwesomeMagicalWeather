package app.northvane.weather

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import app.northvane.core.ai.AiClient
import app.northvane.core.air.AirNowClient
import app.northvane.core.air.EpaUvClient
import app.northvane.core.air.OpenMeteoAirClient
import app.northvane.core.aurora.AuroraClient
import app.northvane.core.eccc.EcccClient
import app.northvane.core.history.AcisClient
import app.northvane.core.nowcast.NowcastClient
import app.northvane.core.nws.NwsClient
import app.northvane.core.owm.OwmClient
import app.northvane.core.pollen.PollenClient
import app.northvane.core.radar.HrrrSmoke
import app.northvane.core.radar.RadarService
import app.northvane.core.repo.WeatherRepository
import app.northvane.core.water.WaterClient
import app.northvane.core.weewx.WeeWxClient
import app.northvane.weather.data.AiStore
import app.northvane.weather.data.AlertStateStore
import app.northvane.weather.data.ClimateStore
import app.northvane.weather.data.LocationStore
import app.northvane.weather.data.SettingsRepository
import app.northvane.weather.data.TextFiles
import app.northvane.weather.data.WeatherCache
import app.northvane.weather.data.WeatherService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okio.Path.Companion.toPath

/** Manual dependency container; one instance per process, built by the platform's app class. */
class AppContainer(val platform: Platform) {
    /**
     * For work that outlives a screen (widget and tile updates, background
     * refreshes). A failure there is dropped rather than taking the whole app
     * down: each job shows what it can and the next one tries again.
     */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + kotlinx.coroutines.CoroutineExceptionHandler { _, _ -> })

    /** Preferences files where Android's preferencesDataStore(name) keeps them, so updates keep the user's settings. */
    private fun dataStore(name: String): DataStore<Preferences> =
        PreferenceDataStoreFactory.createWithPath(
            // A file that can't be read at all (a crash mid-write on an old phone, a full disk) starts over
            // empty instead of making every later save throw and close the app.
            corruptionHandler = androidx.datastore.core.handlers.ReplaceFileCorruptionHandler { androidx.datastore.preferences.core.emptyPreferences() },
            produceFile = { "${platform.settingsDir}/datastore/$name.preferences_pb".toPath() },
        )

    val files = TextFiles(platform.privateDir)
    val settings = SettingsRepository(dataStore("settings"))
    val secrets: SecretStore get() = platform.secrets
    val locations = LocationStore(dataStore("locations"))
    val alertState = AlertStateStore(files)
    val weatherCache = WeatherCache(files)
    val connectivity get() = platform.connectivity
    val deviceLocation get() = platform.deviceLocation
    val themeBackground get() = platform.themeBackground
    val alertScheduler get() = platform.alertScheduler

    @kotlin.concurrent.Volatile
    private var nwsContact: String? = null

    private val http get() = platform.http

    val nws: NwsClient by lazy { NwsClient(http, { NwsClient.userAgent(platform.appVersion, nwsContact, platform.name) }) }
    val owm: OwmClient by lazy { OwmClient(http) }
    val weewx: WeeWxClient by lazy { WeeWxClient(platform.localServers) }
    val radar: RadarService by lazy { RadarService(http) }
    val pollen: PollenClient by lazy { PollenClient(http) }
    val airNow: AirNowClient by lazy { AirNowClient(http) }
    val acis: AcisClient by lazy { AcisClient(http) }
    val climate: ClimateStore by lazy { ClimateStore(files, acis) }
    val ai: AiClient by lazy { AiClient(platform.localServers) }
    val aiReplies: AiStore by lazy { AiStore(files) }

    val repository: WeatherRepository by lazy {
        WeatherRepository(nws, owm, weewx, weatherCache.pointCache, acis = acis, pollen = pollen,
            airNow = airNow, epaUv = EpaUvClient(http), openMeteoAir = OpenMeteoAirClient(http),
            eccc = EcccClient(http), aurora = aurora,
            smoke = HrrrSmoke(http), water = WaterClient(http),
        )
    }

    val weather: WeatherService by lazy {
        WeatherService(platform, repository, weatherCache, settings, secrets, locations, connectivity, placeSearch)
    }

    val placeSearch: PlaceSearch by lazy { platform.placeSearch(owm, secrets, settings) }

    val aurora: AuroraClient by lazy { AuroraClient(http) }
    val nowcast: NowcastClient by lazy { NowcastClient(http) }

    init {
        applicationScope.launch {
            settings.settings.collect { nwsContact = it.nwsContact }
        }
    }
}
