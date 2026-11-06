package app.northvane.weather.data

import android.content.Context
import app.northvane.core.model.SavedLocation
import app.northvane.core.model.WeatherSnapshot
import app.northvane.core.repo.FetchPlan
import app.northvane.core.model.DetailSource
import app.northvane.core.model.Section
import app.northvane.core.outlook.Outlooks
import app.northvane.core.pollen.AndroidKeyRestriction
import app.northvane.core.pollen.PollenPricing
import app.northvane.core.weewx.WeeWxStation
import java.time.LocalDate
import java.time.ZoneId
import app.northvane.core.repo.SourceConfig
import app.northvane.core.repo.WeatherRepository
import app.northvane.core.weewx.WeeWxAuth
import app.northvane.core.weewx.WeeWxSecret
import app.northvane.weather.net.ConnectivityMonitor
import app.northvane.weather.net.LocalNetworkAccess
import app.northvane.weather.net.fetchPlanFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant

/** Glues user settings, secrets and network state to the platform-independent repository. */
class WeatherService(
    private val context: Context,
    private val repository: WeatherRepository,
    private val cache: WeatherCache,
    private val settings: SettingsRepository,
    private val secrets: SecretStore,
    private val locations: LocationStore,
    private val connectivity: ConnectivityMonitor,
    private val placeSearch: app.northvane.weather.location.PlaceSearch,
) {
    suspend fun cached(locationId: String): WeatherSnapshot? = cache.read(locationId)

    fun currentPlan(settings: AppSettings): FetchPlan = fetchPlanFor(settings, connectivity.status.value)

    /** How old cached data may be before opening the app triggers a refresh. */
    fun refreshInterval(plan: FetchPlan): Duration =
        if (plan.limited) Duration.ofMinutes(30) else Duration.ofMinutes(10)

    private val zipLookups = mutableSetOf<String>()

    /** Looks up (once per run) and saves the location's US ZIP code for EPA's UV forecast. */
    private suspend fun withPostalCode(location: SavedLocation): SavedLocation {
        if (location.postalCode != null || !zipLookups.add("${location.id}@${location.latitude},${location.longitude}")) return location
        val zip = placeSearch.postalCode(location.latitude, location.longitude) ?: return location
        locations.updateLocation(location.id) { it.copy(postalCode = zip) }
        return location.copy(postalCode = zip)
    }

    suspend fun refresh(requested: SavedLocation): WeatherSnapshot {
        val appSettings = settings.settings.first()
        val location = if (appSettings.uvEnabled) withPostalCode(requested) else requested
        val station = WeeWxStation.forLocation(locations.stations.first(), location)
        val config = withContext(Dispatchers.IO) {
            SourceConfig(
                weewxStation = station,
                weewxSecret = station?.takeIf { it.auth != WeeWxAuth.NONE }
                    ?.let { WeeWxSecret(secrets.get(SecretStore.weewxSecret(it.id))) },
                owmApiKey = if (appSettings.owmEnabled) secrets.get(SecretStore.OWM_API_KEY) else null,
                pollenApiKey = if (appSettings.pollenEnabled) secrets.get(SecretStore.POLLEN_API_KEY) else null,
                pollenRestriction = AppSignature.restriction(context),
                airNowApiKey = secrets.get(SecretStore.AIRNOW_API_KEY),
                aurora = appSettings.auroraEnabled,
                smoke = appSettings.smokeEnabled,
                water = appSettings.waterEnabled,
                // Fail fast with an explanation rather than waiting out a blocked connection.
                weewxBlockedReason = if (station != null && LocalNetworkAccess.needsPermissionFor(context, station.url)) {
                    LocalNetworkAccess.DENIED_MESSAGE
                } else {
                    null
                },
            )
        }
        val previous = cache.read(location.id)
        val plan = throttled(currentPlan(appSettings), previous).let { p ->
            if (!appSettings.airQualityEnabled && !appSettings.uvEnabled) p.copy(sections = p.sections - Section.AIR_QUALITY) else p
        }
        val fresh = repository.fetch(location, config, plan)
        val merged = Outlooks.annotate(
            withSlowSections(withCachedStandIns(fresh, previous), previous, pollenWanted = config.pollenApiKey != null),
            location.latitude,
            location.longitude,
        )
        cache.write(merged)
        app.northvane.weather.widget.WeatherWidget.requestUpdate(context)
        return merged
    }

    /**
     * Pollen and recent-weather history change once a day, so they're fetched
     * at most every [PollenPricing.MIN_REFRESH_HOURS] hours per location. This
     * also keeps a Google Pollen key well inside its free monthly allowance.
     */
    private fun throttled(plan: FetchPlan, previous: WeatherSnapshot?): FetchPlan {
        if (previous == null) return plan
        val now = Instant.now()
        val maxAge = Duration.ofHours(PollenPricing.MIN_REFRESH_HOURS)
        fun recent(t: Instant?) = t != null && Duration.between(t, now) < maxAge
        var sections = plan.sections
        if (recent(previous.history?.fetchedAt)) sections = sections - Section.HISTORY
        if (recent(previous.pollen?.fetchedAt)) sections = sections - Section.POLLEN
        // NOAA updates the geomagnetic forecast every few hours.
        previous.aurora?.fetchedAt?.let { if (Duration.between(it, now) < Duration.ofHours(3)) sections = sections - Section.AURORA }
        // HRRR's long runs come every 6 hours, and each smoke update is about 2 MB.
        previous.smoke?.fetchedAt?.let { if (Duration.between(it, now) < Duration.ofHours(6)) sections = sections - Section.SMOKE }
        previous.water?.fetchedAt?.let { if (Duration.between(it, now) < Duration.ofHours(1)) sections = sections - Section.WATER }
        val air = previous.airQuality?.fetchedAt ?: previous.uv?.fetchedAt
        if (air != null && Duration.between(air, now) < Duration.ofMinutes(30)) sections = sections - Section.AIR_QUALITY
        return plan.copy(sections = sections)
    }

    /** Keeps the last pollen forecast and weather history when this refresh didn't fetch them. */
    private fun withSlowSections(fresh: WeatherSnapshot, previous: WeatherSnapshot?, pollenWanted: Boolean): WeatherSnapshot {
        if (previous == null) return fresh
        val now = Instant.now()
        val zone = runCatching { ZoneId.of(fresh.timeZoneId) }.getOrDefault(ZoneId.systemDefault())
        val today = LocalDate.now(zone)
        val history = fresh.history ?: previous.history?.takeIf { Duration.between(it.fetchedAt, now) < Duration.ofDays(3) }
        val pollen = fresh.pollen ?: previous.pollen?.takeIf { pollenWanted }
            ?.let { p -> p.copy(days = p.days.filter { !it.date.isBefore(today) }) }
            ?.takeIf { it.days.isNotEmpty() }
        // Air quality and UV are short-lived: keep the last values for up to 3 hours.
        val airAge = Duration.ofHours(3)
        val airQuality = fresh.airQuality ?: previous.airQuality?.takeIf { Duration.between(it.fetchedAt, now) < airAge }
        val uv = fresh.uv ?: previous.uv?.takeIf { Duration.between(it.fetchedAt, now) < airAge }
        // The K-index forecast reaches three days ahead, so an older one still says something.
        val aurora = fresh.aurora ?: previous.aurora?.takeIf { Duration.between(it.fetchedAt, now) < Duration.ofDays(2) }
        val smoke = fresh.smoke ?: previous.smoke?.takeIf { Duration.between(it.fetchedAt, now) < Duration.ofHours(12) }
        val water = fresh.water ?: previous.water?.takeIf { Duration.between(it.fetchedAt, now) < Duration.ofHours(6) }
        val throttled = setOf(Section.HISTORY, Section.POLLEN, Section.AIR_QUALITY, Section.AURORA, Section.SMOKE, Section.WATER)
        // Sections kept from the previous download keep their original download time.
        val times = previous.sectionTimes.filterKeys { it !in fresh.sectionTimes } + fresh.sectionTimes
        return fresh.copy(
            history = history, pollen = pollen, airQuality = airQuality, uv = uv, aurora = aurora, smoke = smoke, water = water,
            skippedSections = fresh.skippedSections - throttled,
            sectionTimes = times,
        )
    }

    /**
     * Sections skipped by satellite settings keep their last downloaded data
     * (minus anything that has expired), so the screen never goes blank.
     */
    private fun withCachedStandIns(fresh: WeatherSnapshot, previous: WeatherSnapshot?): WeatherSnapshot {
        if (previous == null || fresh.skippedSections.isEmpty()) return fresh
        val now = Instant.now()
        val zone = runCatching { ZoneId.of(fresh.timeZoneId) }.getOrDefault(ZoneId.systemDefault())
        val skipped = fresh.skippedSections
        var result = fresh
        if (result.current == null && (Section.CURRENT_CONDITIONS in skipped || Section.LOCAL_STATION in skipped)) {
            result = result.copy(current = previous.current)
        }
        if (Section.ALERTS in skipped) {
            result = result.copy(alerts = previous.alerts.filter { (it.ends ?: it.expires)?.isAfter(now) != false })
        }
        if (Section.HOURLY_FORECAST in skipped) {
            val cutoff = now.minus(Duration.ofMinutes(59))
            result = result.copy(hourly = previous.hourly.filter { it.time.isAfter(cutoff) })
        }
        if (Section.DAILY_FORECAST in skipped) {
            val yesterday = LocalDate.now(zone).minusDays(1)
            result = result.copy(daily = previous.daily.filter { !it.date.isBefore(yesterday) }, forecastSource = previous.forecastSource)
        } else if (Section.FORECAST_DETAILS in skipped) {
            // Fresh forecast text, but keep earlier gridpoint numbers for the same days if we have them.
            val earlier = previous.daily.filter { it.detailSource == DetailSource.GRID }.associateBy { it.date }
            result = result.copy(
                daily = result.daily.map { d ->
                    val old = earlier[d.date] ?: return@map d
                    if (d.detailSource == DetailSource.GRID) d else d.copy(
                        windMaxKph = old.windMaxKph, gustMaxKph = old.gustMaxKph, windDirectionDeg = old.windDirectionDeg,
                        precipitationMm = old.precipitationMm, snowMm = old.snowMm, iceMm = old.iceMm,
                        humidityMin = old.humidityMin, humidityMax = old.humidityMax, thunderChance = old.thunderChance,
                        skyCoverPercent = old.skyCoverPercent, detailSource = old.detailSource,
                    )
                },
            )
        }
        return result
    }
}
