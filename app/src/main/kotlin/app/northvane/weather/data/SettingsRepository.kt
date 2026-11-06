package app.northvane.weather.data

import app.northvane.core.model.AuroraView
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.northvane.core.model.AlertCategory
import app.northvane.core.model.UnitSystem
import app.northvane.core.model.Activity
import app.northvane.core.ai.AiProvider
import app.northvane.core.garden.GardenCrop
import app.northvane.core.json.NorthvaneJson
import app.northvane.core.outlook.HeatSource
import kotlinx.serialization.builtins.ListSerializer
import app.northvane.core.model.Allergen
import app.northvane.core.model.Sensitivity
import app.northvane.weather.ui.theme.AppTheme
import app.northvane.weather.ui.theme.CustomAccents
import app.northvane.weather.ui.theme.CustomBackground
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import app.northvane.core.radar.RadarSource
import app.northvane.core.radar.SatelliteImagery
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.util.Locale

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** When to apply satellite limits: automatically on satellite/constrained networks, always, or never. */
enum class DataSaverMode { AUTO, ALWAYS, OFF }

/**
 * Everything that can be individually allowed or blocked while on a satellite
 * (or otherwise limited) connection. Sizes are typical compressed transfers.
 */
enum class SatelliteFeature(val title: String, val description: String, val approxSize: String, val defaultOn: Boolean) {
    ALERTS("Weather alerts", "NWS watches, warnings and advisories", "2–20 KB", true),
    BACKGROUND_ALERTS("Background alert checks", "Periodic checks that trigger notifications", "2–20 KB", true),
    CURRENT_CONDITIONS("Current conditions", "Nearest NWS observation station", "~4 KB", true),
    LOCAL_STATION("WeeWX station", "Your station, if it's reachable from the internet", "~1 KB", true),
    DAILY_FORECAST("Daily forecast", "7-day NWS forecast", "~4 KB", true),
    FORECAST_DETAILS("Forecast details", "Gusts, rain/snow/ice amounts, clouds, outage risk inputs", "~10 KB", true),
    HOURLY_FORECAST("Hourly forecast", "Next 48 hours", "~6 KB", true),
    HISTORY("Recent weather (NOAA)", "Past rain and temperatures for mud, foliage and seasonal outage estimates; at most every 6 hours", "~3 KB", true),
    POLLEN("Pollen forecast", "Google Pollen API, only if you've added a key; at most every 6 hours", "~6 KB", false),
    RADAR("Radar", "Past hour of radar images", "0.5–3 MB per view", false),
    FUTURE_CAST("Radar future cast", "NOAA HRRR model radar, about 490 KB per 15 minutes ahead", "4–5 MB per view", false),
    SATELLITE_IMAGERY("Satellite imagery", "GOES cloud imagery under the radar", "0.3–2 MB per view", false),
    AIR_QUALITY("Air quality & UV", "AQI (EPA AirNow or Open-Meteo) and EPA UV index; at most every 30 minutes", "~3 KB", true),
    TRAFFIC("Traffic events", "Work zones, closures and incidents from state DOT feeds", "50–700 KB per view", false),
    GARDEN_CLIMATE("Garden & heating frost dates", "30 years of NOAA lows, downloaded once per place", "~40 KB once", true),
    AURORA("Aurora forecast", "NOAA's geomagnetic forecast for the aurora card; at most every 3 hours", "~7 KB", false),
    SMOKE("Wildfire smoke", "NOAA HRRR smoke forecast for the next day; at most every 6 hours", "~2 MB", false),
    WATER("Rivers & tides", "Nearest USGS river gauge and NOAA tide station; at most every hour", "~20 KB", true),
    RAIN_SOON("Rain-starting checks", "15-minute rain forecast for \"rain starting soon\" notifications", "~1 KB per check", true),
    AI_SUMMARY("AI weather summary", "Sends the forecast to your AI server and gets a short summary back", "~2–4 KB", false),
    TRAFFIC_FLOW("Traffic speed map", "TomTom flow tiles on the traffic map, if you've added a key", "0.3–1 MB per view", false),
    OWM_FALLBACK("OpenWeatherMap fallback", "Only if enabled in Data sources", "~5 KB", false);

    companion object {
        val DEFAULTS: Set<SatelliteFeature> = entries.filter { it.defaultOn }.toSet()
    }
}

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
    val units: UnitSystem = defaultUnits(),
    val selectedLocationId: String? = null,
    val alertsEnabled: Boolean = true,
    val alertCategories: Set<AlertCategory> = setOf(AlertCategory.WARNING, AlertCategory.WATCH, AlertCategory.ADVISORY),
    val alertCheckMinutes: Int = 30,
    val dataSaver: DataSaverMode = DataSaverMode.AUTO,
    val radarSource: RadarSource = RadarSource.NOAA_MRMS,
    val futureCastEnabled: Boolean = true,
    val futureCastMinutes: Int = 120,
    val nwsContact: String? = null,
    val owmEnabled: Boolean = false,
    val notificationPromptShown: Boolean = false,
    val satelliteFeatures: Set<SatelliteFeature> = SatelliteFeature.DEFAULTS,
    val outageRiskEnabled: Boolean = false,
    val pipeRiskEnabled: Boolean = false,
    val morningIceEnabled: Boolean = false,
    val auroraEnabled: Boolean = true,
    /** Storm totals card on the Today screen. */
    val stormEnabled: Boolean = false,
    /** Wildfire smoke card (NOAA HRRR, about 2 MB per update). */
    val smokeEnabled: Boolean = false,
    /** Rivers & tides card on the Outlook tab, which also feeds the paddling score. */
    val waterEnabled: Boolean = false,
    /** A morning briefing notification at [briefingMinutes] after midnight. */
    val briefingEnabled: Boolean = false,
    val briefingMinutes: Int = 7 * 60,
    /** "Rain starting soon" notifications for places with notifications on. */
    val rainSoonEnabled: Boolean = false,
    val auroraNotify: Boolean = true,
    /** The least you'd want a notification for. */
    val auroraNotifyView: AuroraView = AuroraView.HORIZON,
    val sapEnabled: Boolean = false,
    val burnEnabled: Boolean = false,
    val pollenEnabled: Boolean = false,
    val activitiesEnabled: Boolean = true,
    val activities: Set<Activity> = Activity.entries.toSet(),
    val mudEnabled: Boolean = false,
    val foliageEnabled: Boolean = true,
    val satelliteLayer: Boolean = false,
    val satelliteImagery: SatelliteImagery = SatelliteImagery.AUTO,
    val appTheme: AppTheme = AppTheme.NORTHVANE,
    val customAccent: Long = CustomAccents.first(),
    val customBackground: CustomBackground = CustomBackground.DUSK,
    val customDimPercent: Int = 35,
    /** Bumped whenever the custom background photo changes, so it's reloaded. */
    val customPhotoVersion: Long = 0,
    val airQualityEnabled: Boolean = true,
    val uvEnabled: Boolean = true,
    val allergyEnabled: Boolean = true,
    val allergies: Map<Allergen, Sensitivity> = emptyMap(),
    val trafficEnabled: Boolean = true,
    val trafficRadiusKm: Double = 40.0,
    val trafficFlowLayer: Boolean = true,
    val hourlyExpanded: Boolean = false,
    val dryingEnabled: Boolean = false,
    /** When the user said laundry is out on the line (epoch ms), for rain warnings. */
    val laundryOutSince: Long? = null,
    val laundryLocationId: String? = null,
    val gardenEnabled: Boolean = false,
    val gardenPlanting: Boolean = true,
    val gardenHarvest: Boolean = true,
    val gardenFrostAlerts: Boolean = true,
    val gardenWatering: Boolean = true,
    val gardenSoil: Boolean = true,
    val gardenGdd: Boolean = false,
    val gardenPlan: List<GardenCrop> = emptyList(),
    val heatingEnabled: Boolean = false,
    val heatSources: List<HeatSource> = emptyList(),
    val heatingAggressive: Boolean = false,
    /** On satellite limits, show cards whose data is over 12 hours old (or never downloaded). */
    val showStaleData: Boolean = false,
    /** Whether Backwoods mode also follows Android's "restricted background data" (see NetworkStatus.restricted). */
    val followRestrictedData: Boolean = true,
    val aiEnabled: Boolean = false,
    val aiProvider: AiProvider = AiProvider.OLLAMA,
    val aiBaseUrl: String = "",
    val aiModel: String = "",
) {
    companion object {
        private fun defaultUnits(): UnitSystem =
            if (Locale.getDefault().country in setOf("US", "LR", "MM")) UnitSystem.IMPERIAL else UnitSystem.METRIC
    }
}

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(context: Context) {
    private val store = context.applicationContext.settingsStore

    private object Keys {
        val theme = stringPreferencesKey("theme")
        val dynamicColor = booleanPreferencesKey("dynamic_color")
        val units = stringPreferencesKey("units")
        val selectedLocation = stringPreferencesKey("selected_location")
        val alertsEnabled = booleanPreferencesKey("alerts_enabled")
        val alertCategories = stringSetPreferencesKey("alert_categories")
        val alertInterval = intPreferencesKey("alert_interval")
        val dataSaver = stringPreferencesKey("data_saver")
        val radarSource = stringPreferencesKey("radar_source")
        val futureCast = booleanPreferencesKey("future_cast")
        val futureCastMinutes = intPreferencesKey("future_cast_minutes")
        val nwsContact = stringPreferencesKey("nws_contact")
        val owmEnabled = booleanPreferencesKey("owm_enabled")
        val notificationPrompt = booleanPreferencesKey("notification_prompt_shown")
        val satelliteFeatures = stringSetPreferencesKey("satellite_features")
        val outageRisk = booleanPreferencesKey("outage_risk")
        val pipeRisk = booleanPreferencesKey("pipe_risk")
        val morningIce = booleanPreferencesKey("morning_ice")
        val aurora = booleanPreferencesKey("aurora")
        val storm = booleanPreferencesKey("storm_enabled")
        val smoke = booleanPreferencesKey("smoke_enabled")
        val water = booleanPreferencesKey("water_enabled")
        val briefing = booleanPreferencesKey("briefing_enabled")
        val briefingMinutes = intPreferencesKey("briefing_minutes")
        val rainSoon = booleanPreferencesKey("rain_soon_enabled")
        val auroraNotify = booleanPreferencesKey("aurora_notify")
        val auroraNotifyView = stringPreferencesKey("aurora_notify_view")
        val sap = booleanPreferencesKey("sap_run")
        val burn = booleanPreferencesKey("burn_day")
        val pollen = booleanPreferencesKey("pollen")
        val activitiesEnabled = booleanPreferencesKey("activities_enabled")
        val activities = stringSetPreferencesKey("activities") // 1.1–1.2: enabled set (only the original six existed)
        val activitiesOff = stringSetPreferencesKey("activities_off")
        val mud = booleanPreferencesKey("mud")
        val foliage = booleanPreferencesKey("foliage")
        val satelliteLayer = booleanPreferencesKey("satellite_layer")
        val satelliteImagery = stringPreferencesKey("satellite_imagery")
        val appTheme = stringPreferencesKey("app_theme")
        val customAccent = longPreferencesKey("custom_accent")
        val customBackground = stringPreferencesKey("custom_background")
        val customDim = intPreferencesKey("custom_dim")
        val customPhotoVersion = longPreferencesKey("custom_photo_version")
        val airQuality = booleanPreferencesKey("air_quality")
        val uv = booleanPreferencesKey("uv")
        val allergy = booleanPreferencesKey("allergy")
        val allergies = stringSetPreferencesKey("allergies") // "BIRCH:SEVERE"
        val traffic = booleanPreferencesKey("traffic")
        val trafficRadius = doublePreferencesKey("traffic_radius_km")
        val trafficFlow = booleanPreferencesKey("traffic_flow")
        val hourlyExpanded = booleanPreferencesKey("hourly_expanded")
        val drying = booleanPreferencesKey("drying")
        val laundryOut = longPreferencesKey("laundry_out_since")
        val laundryLocation = stringPreferencesKey("laundry_location")
        val garden = booleanPreferencesKey("garden")
        val gardenPlanting = booleanPreferencesKey("garden_planting")
        val gardenHarvest = booleanPreferencesKey("garden_harvest")
        val gardenFrost = booleanPreferencesKey("garden_frost")
        val gardenWatering = booleanPreferencesKey("garden_watering")
        val gardenSoil = booleanPreferencesKey("garden_soil")
        val gardenGdd = booleanPreferencesKey("garden_gdd")
        val gardenPlan = stringPreferencesKey("garden_plan")
        val heating = booleanPreferencesKey("heating")
        val heatSources = stringPreferencesKey("heat_sources")
        val heatingAggressive = booleanPreferencesKey("heating_aggressive")
        val showStale = booleanPreferencesKey("show_stale_data")
        val followRestricted = booleanPreferencesKey("follow_restricted_data")
        val ai = booleanPreferencesKey("ai_enabled")
        val aiProvider = stringPreferencesKey("ai_provider")
        val aiBaseUrl = stringPreferencesKey("ai_base_url")
        val aiModel = stringPreferencesKey("ai_model")
    }

    private val gardenList = ListSerializer(GardenCrop.serializer())
    private val heatList = ListSerializer(HeatSource.serializer())

    private inline fun <reified T : Enum<T>> String?.toEnum(default: T): T =
        this?.let { name -> enumValues<T>().firstOrNull { it.name == name } } ?: default

    val settings: Flow<AppSettings> = store.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { p ->
            val d = AppSettings()
            AppSettings(
                themeMode = p[Keys.theme].toEnum(d.themeMode),
                dynamicColor = p[Keys.dynamicColor] ?: d.dynamicColor,
                units = p[Keys.units].toEnum(d.units),
                selectedLocationId = p[Keys.selectedLocation],
                alertsEnabled = p[Keys.alertsEnabled] ?: d.alertsEnabled,
                alertCategories = p[Keys.alertCategories]
                    ?.mapNotNull { name -> AlertCategory.entries.firstOrNull { it.name == name } }
                    ?.toSet() ?: d.alertCategories,
                alertCheckMinutes = p[Keys.alertInterval] ?: d.alertCheckMinutes,
                dataSaver = p[Keys.dataSaver].toEnum(d.dataSaver),
                radarSource = p[Keys.radarSource].toEnum(d.radarSource),
                futureCastEnabled = p[Keys.futureCast] ?: d.futureCastEnabled,
                futureCastMinutes = p[Keys.futureCastMinutes] ?: d.futureCastMinutes,
                nwsContact = p[Keys.nwsContact],
                owmEnabled = p[Keys.owmEnabled] ?: d.owmEnabled,
                notificationPromptShown = p[Keys.notificationPrompt] ?: d.notificationPromptShown,
                satelliteFeatures = p[Keys.satelliteFeatures]
                    ?.mapNotNull { name -> SatelliteFeature.entries.firstOrNull { it.name == name } }
                    ?.toSet() ?: d.satelliteFeatures,
                outageRiskEnabled = p[Keys.outageRisk] ?: d.outageRiskEnabled,
                pipeRiskEnabled = p[Keys.pipeRisk] ?: d.pipeRiskEnabled,
                morningIceEnabled = p[Keys.morningIce] ?: d.morningIceEnabled,
                auroraEnabled = p[Keys.aurora] ?: d.auroraEnabled,
                stormEnabled = p[Keys.storm] ?: d.stormEnabled,
                smokeEnabled = p[Keys.smoke] ?: d.smokeEnabled,
                waterEnabled = p[Keys.water] ?: d.waterEnabled,
                briefingEnabled = p[Keys.briefing] ?: d.briefingEnabled,
                briefingMinutes = p[Keys.briefingMinutes]?.coerceIn(0, 24 * 60 - 1) ?: d.briefingMinutes,
                rainSoonEnabled = p[Keys.rainSoon] ?: d.rainSoonEnabled,
                auroraNotify = p[Keys.auroraNotify] ?: d.auroraNotify,
                auroraNotifyView = p[Keys.auroraNotifyView].toEnum(d.auroraNotifyView),
                sapEnabled = p[Keys.sap] ?: d.sapEnabled,
                burnEnabled = p[Keys.burn] ?: d.burnEnabled,
                pollenEnabled = p[Keys.pollen] ?: d.pollenEnabled,
                activitiesEnabled = p[Keys.activitiesEnabled] ?: d.activitiesEnabled,
                activities = Activity.entries.toSet() - disabledActivities(p),
                mudEnabled = p[Keys.mud] ?: d.mudEnabled,
                foliageEnabled = p[Keys.foliage] ?: d.foliageEnabled,
                satelliteLayer = p[Keys.satelliteLayer] ?: d.satelliteLayer,
                satelliteImagery = p[Keys.satelliteImagery].toEnum(d.satelliteImagery),
                appTheme = p[Keys.appTheme].toEnum(d.appTheme),
                customAccent = p[Keys.customAccent] ?: d.customAccent,
                customBackground = p[Keys.customBackground].toEnum(d.customBackground),
                customDimPercent = p[Keys.customDim] ?: d.customDimPercent,
                customPhotoVersion = p[Keys.customPhotoVersion] ?: d.customPhotoVersion,
                airQualityEnabled = p[Keys.airQuality] ?: d.airQualityEnabled,
                uvEnabled = p[Keys.uv] ?: d.uvEnabled,
                allergyEnabled = p[Keys.allergy] ?: d.allergyEnabled,
                allergies = p[Keys.allergies]?.mapNotNull { entry ->
                    val (a, sens) = entry.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
                    val allergen = Allergen.entries.firstOrNull { it.name == a } ?: return@mapNotNull null
                    allergen to (Sensitivity.entries.firstOrNull { it.name == sens } ?: Sensitivity.MODERATE)
                }?.toMap() ?: d.allergies,
                trafficEnabled = p[Keys.traffic] ?: d.trafficEnabled,
                trafficRadiusKm = p[Keys.trafficRadius] ?: d.trafficRadiusKm,
                trafficFlowLayer = p[Keys.trafficFlow] ?: d.trafficFlowLayer,
                hourlyExpanded = p[Keys.hourlyExpanded] ?: d.hourlyExpanded,
                dryingEnabled = p[Keys.drying] ?: d.dryingEnabled,
                laundryOutSince = p[Keys.laundryOut],
                laundryLocationId = p[Keys.laundryLocation],
                gardenEnabled = p[Keys.garden] ?: d.gardenEnabled,
                gardenPlanting = p[Keys.gardenPlanting] ?: d.gardenPlanting,
                gardenHarvest = p[Keys.gardenHarvest] ?: d.gardenHarvest,
                gardenFrostAlerts = p[Keys.gardenFrost] ?: d.gardenFrostAlerts,
                gardenWatering = p[Keys.gardenWatering] ?: d.gardenWatering,
                gardenSoil = p[Keys.gardenSoil] ?: d.gardenSoil,
                gardenGdd = p[Keys.gardenGdd] ?: d.gardenGdd,
                gardenPlan = p[Keys.gardenPlan]?.let { runCatching { NorthvaneJson.decodeFromString(gardenList, it) }.getOrNull() } ?: d.gardenPlan,
                heatingEnabled = p[Keys.heating] ?: d.heatingEnabled,
                heatSources = p[Keys.heatSources]?.let { runCatching { NorthvaneJson.decodeFromString(heatList, it) }.getOrNull() } ?: d.heatSources,
                heatingAggressive = p[Keys.heatingAggressive] ?: d.heatingAggressive,
                showStaleData = p[Keys.showStale] ?: d.showStaleData,
                followRestrictedData = p[Keys.followRestricted] ?: d.followRestrictedData,
                aiEnabled = p[Keys.ai] ?: d.aiEnabled,
                aiProvider = p[Keys.aiProvider].toEnum(d.aiProvider),
                aiBaseUrl = p[Keys.aiBaseUrl] ?: d.aiBaseUrl,
                aiModel = p[Keys.aiModel] ?: d.aiModel,
            )
        }

    suspend fun setThemeMode(mode: ThemeMode) = store.edit { it[Keys.theme] = mode.name }
    suspend fun setDynamicColor(enabled: Boolean) = store.edit { it[Keys.dynamicColor] = enabled }
    suspend fun setUnits(units: UnitSystem) = store.edit { it[Keys.units] = units.name }
    suspend fun setSelectedLocation(id: String?) = store.edit {
        if (id == null) it.remove(Keys.selectedLocation) else it[Keys.selectedLocation] = id
    }
    suspend fun setAlertsEnabled(enabled: Boolean) = store.edit { it[Keys.alertsEnabled] = enabled }
    suspend fun setAlertCategory(category: AlertCategory, enabled: Boolean) = store.edit { p ->
        val current = p[Keys.alertCategories] ?: AppSettings().alertCategories.map { it.name }.toSet()
        p[Keys.alertCategories] = if (enabled) current + category.name else current - category.name
    }
    suspend fun setAlertInterval(minutes: Int) = store.edit { it[Keys.alertInterval] = minutes.coerceAtLeast(15) }
    suspend fun setDataSaver(mode: DataSaverMode) = store.edit { it[Keys.dataSaver] = mode.name }
    suspend fun setRadarSource(source: RadarSource) = store.edit { it[Keys.radarSource] = source.name }
    suspend fun setFutureCast(enabled: Boolean) = store.edit { it[Keys.futureCast] = enabled }
    suspend fun setFutureCastMinutes(minutes: Int) = store.edit { it[Keys.futureCastMinutes] = minutes.coerceIn(60, 360) }
    suspend fun setNwsContact(contact: String?) = store.edit {
        val clean = contact?.trim()?.take(100)
        if (clean.isNullOrEmpty()) it.remove(Keys.nwsContact) else it[Keys.nwsContact] = clean
    }
    suspend fun setOwmEnabled(enabled: Boolean) = store.edit { it[Keys.owmEnabled] = enabled }
    suspend fun setNotificationPromptShown() = store.edit { it[Keys.notificationPrompt] = true }
    suspend fun setSatelliteFeature(feature: SatelliteFeature, enabled: Boolean) = store.edit { p ->
        val current = p[Keys.satelliteFeatures] ?: SatelliteFeature.DEFAULTS.map { it.name }.toSet()
        p[Keys.satelliteFeatures] = if (enabled) current + feature.name else current - feature.name
    }
    suspend fun setOutageRisk(enabled: Boolean) = store.edit { it[Keys.outageRisk] = enabled }
    suspend fun setPipeRisk(enabled: Boolean) = store.edit { it[Keys.pipeRisk] = enabled }
    suspend fun setMorningIce(enabled: Boolean) = store.edit { it[Keys.morningIce] = enabled }
    suspend fun setAurora(enabled: Boolean) = store.edit { it[Keys.aurora] = enabled }
    suspend fun setStorm(enabled: Boolean) = store.edit { it[Keys.storm] = enabled }
    suspend fun setSmoke(enabled: Boolean) = store.edit { it[Keys.smoke] = enabled }
    suspend fun setWater(enabled: Boolean) = store.edit { it[Keys.water] = enabled }
    suspend fun setBriefing(enabled: Boolean) = store.edit { it[Keys.briefing] = enabled }
    suspend fun setBriefingMinutes(minutes: Int) = store.edit { it[Keys.briefingMinutes] = minutes.coerceIn(0, 24 * 60 - 1) }
    suspend fun setRainSoon(enabled: Boolean) = store.edit { it[Keys.rainSoon] = enabled }
    suspend fun setAuroraNotify(enabled: Boolean) = store.edit { it[Keys.auroraNotify] = enabled }
    suspend fun setAuroraNotifyView(view: AuroraView) = store.edit { it[Keys.auroraNotifyView] = view.name }
    suspend fun setSap(enabled: Boolean) = store.edit { it[Keys.sap] = enabled }
    suspend fun setBurn(enabled: Boolean) = store.edit { it[Keys.burn] = enabled }
    suspend fun setPollen(enabled: Boolean) = store.edit { it[Keys.pollen] = enabled }
    suspend fun setActivitiesEnabled(enabled: Boolean) = store.edit { it[Keys.activitiesEnabled] = enabled }
    /**
     * Activities are stored as the set the user turned *off*, so activities
     * added in later versions show up for existing users too.
     */
    private fun disabledActivities(p: Preferences): Set<Activity> {
        p[Keys.activitiesOff]?.let { off -> return off.mapNotNull { n -> Activity.entries.firstOrNull { it.name == n } }.toSet() }
        val oldEnabled = p[Keys.activities] ?: return emptySet()
        return ORIGINAL_ACTIVITIES.filterNot { it.name in oldEnabled }.toSet()
    }

    suspend fun setActivity(activity: Activity, enabled: Boolean) = store.edit { p ->
        val off = disabledActivities(p).map { it.name }.toSet()
        p[Keys.activitiesOff] = if (enabled) off - activity.name else off + activity.name
    }

    private companion object {
        val ORIGINAL_ACTIVITIES = listOf(
            Activity.HIKING, Activity.CAMPING, Activity.OFF_ROADING, Activity.MOUNTAIN_BIKING, Activity.PADDLING, Activity.STARGAZING,
        )
    }
    suspend fun setMud(enabled: Boolean) = store.edit { it[Keys.mud] = enabled }
    suspend fun setFoliage(enabled: Boolean) = store.edit { it[Keys.foliage] = enabled }
    suspend fun setSatelliteLayer(enabled: Boolean) = store.edit { it[Keys.satelliteLayer] = enabled }
    suspend fun setSatelliteImagery(imagery: SatelliteImagery) = store.edit { it[Keys.satelliteImagery] = imagery.name }
    suspend fun setAppTheme(theme: AppTheme) = store.edit { it[Keys.appTheme] = theme.name }
    suspend fun setCustomAccent(argb: Long) = store.edit { it[Keys.customAccent] = argb }
    suspend fun setCustomBackground(background: CustomBackground) = store.edit { it[Keys.customBackground] = background.name }
    suspend fun setCustomDim(percent: Int) = store.edit { it[Keys.customDim] = percent.coerceIn(0, 80) }
    suspend fun bumpCustomPhoto() = store.edit { it[Keys.customPhotoVersion] = System.currentTimeMillis() }
    suspend fun setAirQuality(enabled: Boolean) = store.edit { it[Keys.airQuality] = enabled }
    suspend fun setUv(enabled: Boolean) = store.edit { it[Keys.uv] = enabled }
    suspend fun setAllergy(enabled: Boolean) = store.edit { it[Keys.allergy] = enabled }
    /** Adds, changes ([sensitivity] non-null) or removes ([sensitivity] null) one allergen. */
    suspend fun setAllergen(allergen: Allergen, sensitivity: Sensitivity?) = store.edit { p ->
        val current = (p[Keys.allergies] ?: emptySet()).filterNot { it.substringBefore(':') == allergen.name }.toSet()
        p[Keys.allergies] = if (sensitivity == null) current else current + "${allergen.name}:${sensitivity.name}"
    }
    suspend fun setTraffic(enabled: Boolean) = store.edit { it[Keys.traffic] = enabled }
    suspend fun setTrafficRadius(km: Double) = store.edit { it[Keys.trafficRadius] = km.coerceIn(5.0, 160.0) }
    suspend fun setTrafficFlow(enabled: Boolean) = store.edit { it[Keys.trafficFlow] = enabled }
    suspend fun setHeatingAggressive(enabled: Boolean) = store.edit { it[Keys.heatingAggressive] = enabled }
    suspend fun setShowStaleData(enabled: Boolean) = store.edit { it[Keys.showStale] = enabled }
    suspend fun setFollowRestrictedData(enabled: Boolean) = store.edit { it[Keys.followRestricted] = enabled }
    suspend fun setAiEnabled(enabled: Boolean) = store.edit { it[Keys.ai] = enabled }
    suspend fun setAiServer(provider: AiProvider, baseUrl: String, model: String) = store.edit {
        it[Keys.aiProvider] = provider.name
        it[Keys.aiBaseUrl] = baseUrl.trim().take(500)
        it[Keys.aiModel] = model.trim().take(200)
    }
    /** Enables or disables a group of satellite features at once. */
    suspend fun setSatelliteFeatures(features: Collection<SatelliteFeature>, enabled: Boolean) = store.edit { p ->
        val current = p[Keys.satelliteFeatures] ?: SatelliteFeature.DEFAULTS.map { it.name }.toSet()
        p[Keys.satelliteFeatures] = if (enabled) current + features.map { it.name } else current - features.map { it.name }.toSet()
    }
    suspend fun setHourlyExpanded(expanded: Boolean) = store.edit { it[Keys.hourlyExpanded] = expanded }
    suspend fun setDrying(enabled: Boolean) = store.edit { it[Keys.drying] = enabled }
    suspend fun setLaundryOut(locationId: String?) = store.edit {
        if (locationId == null) {
            it.remove(Keys.laundryOut)
            it.remove(Keys.laundryLocation)
        } else {
            it[Keys.laundryOut] = System.currentTimeMillis()
            it[Keys.laundryLocation] = locationId
        }
    }
    suspend fun setGarden(enabled: Boolean) = store.edit { it[Keys.garden] = enabled }
    suspend fun setGardenOption(option: GardenOption, enabled: Boolean) = store.edit {
        it[when (option) {
            GardenOption.PLANTING -> Keys.gardenPlanting
            GardenOption.HARVEST -> Keys.gardenHarvest
            GardenOption.FROST_ALERTS -> Keys.gardenFrost
            GardenOption.WATERING -> Keys.gardenWatering
            GardenOption.SOIL -> Keys.gardenSoil
            GardenOption.GDD -> Keys.gardenGdd
        }] = enabled
    }
    suspend fun setGardenPlan(plan: List<GardenCrop>) = store.edit { it[Keys.gardenPlan] = NorthvaneJson.encodeToString(gardenList, plan) }
    suspend fun setHeating(enabled: Boolean) = store.edit { it[Keys.heating] = enabled }
    suspend fun setHeatSources(sources: List<HeatSource>) = store.edit { it[Keys.heatSources] = NorthvaneJson.encodeToString(heatList, sources) }
}

/** Satellite settings are shown in these groups, each with one switch and an optional per-item list. */
enum class SatelliteGroup(val title: String, val description: String, val features: List<SatelliteFeature>) {
    ESSENTIALS(
        "Forecast & alerts", "Current conditions, forecasts and NWS/ECCC alerts. Small and recommended.",
        listOf(SatelliteFeature.ALERTS, SatelliteFeature.BACKGROUND_ALERTS, SatelliteFeature.CURRENT_CONDITIONS, SatelliteFeature.DAILY_FORECAST,
            SatelliteFeature.HOURLY_FORECAST, SatelliteFeature.FORECAST_DETAILS, SatelliteFeature.LOCAL_STATION),
    ),
    EXTRAS(
        "Extras", "Air quality, pollen, recent weather and frost dates, aurora, smoke, rivers & tides, rain notifications, AI summary, OpenWeatherMap.",
        listOf(SatelliteFeature.AIR_QUALITY, SatelliteFeature.POLLEN, SatelliteFeature.HISTORY, SatelliteFeature.GARDEN_CLIMATE,
            SatelliteFeature.AURORA, SatelliteFeature.SMOKE, SatelliteFeature.WATER, SatelliteFeature.RAIN_SOON,
            SatelliteFeature.AI_SUMMARY, SatelliteFeature.OWM_FALLBACK),
    ),
    MAPS(
        "Maps", "Radar, future cast, satellite imagery and traffic. These download the most.",
        listOf(SatelliteFeature.RADAR, SatelliteFeature.FUTURE_CAST, SatelliteFeature.SATELLITE_IMAGERY, SatelliteFeature.TRAFFIC, SatelliteFeature.TRAFFIC_FLOW),
    ),
}

enum class GardenOption(val title: String, val description: String) {
    PLANTING("Planting schedule", "When to start seeds indoors, harden off, and plant out, checked against the forecast"),
    HARVEST("Harvest schedule", "When crops should be ready, and what to pick or cover before frost"),
    FROST_ALERTS("Frost & freeze alerts", "Notify for NWS Frost Advisories and Freeze Warnings even if advisories are off, plus frost nights on the card"),
    WATERING("Watering advice", "About an inch a week: past week's rain from NOAA and rain in the forecast"),
    SOIL("Soil readiness", "Estimated soil temperature, and whether the ground is too wet or frozen to work"),
    GDD("Growing degree days", "Heat units since March 1 (base 50°F), for corn and pest timing"),
}
