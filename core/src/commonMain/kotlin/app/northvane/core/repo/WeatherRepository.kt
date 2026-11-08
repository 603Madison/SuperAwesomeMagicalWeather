package app.northvane.core.repo

import app.northvane.core.model.CurrentConditions
import app.northvane.core.model.DailyForecast
import app.northvane.core.model.DataSource
import app.northvane.core.model.GeoPoint
import app.northvane.core.model.HourlyForecast
import app.northvane.core.model.SavedLocation
import app.northvane.core.model.Section
import app.northvane.core.model.SourceIssue
import app.northvane.core.model.WeatherAlert
import app.northvane.core.model.WeatherCondition
import app.northvane.core.model.WeatherSnapshot
import app.northvane.core.air.AirNowClient
import app.northvane.core.aurora.AuroraClient
import app.northvane.core.eccc.EcccClient
import app.northvane.core.eccc.EcccForecast
import app.northvane.core.eccc.OutsideEcccCoverageException
import app.northvane.core.air.EpaUvClient
import app.northvane.core.air.OpenMeteoAirClient
import app.northvane.core.history.AcisClient
import app.northvane.core.model.AirQuality
import app.northvane.core.model.UvForecast
import app.northvane.core.net.InvalidCredentialsException
import app.northvane.core.history.HistoryUnavailableException
import app.northvane.core.outlook.Outlooks
import app.northvane.core.outlook.Periods
import app.northvane.core.pollen.AndroidKeyRestriction
import app.northvane.core.pollen.PollenClient
import app.northvane.core.net.OutsideNwsCoverageException
import app.northvane.core.net.WeatherApiException
import app.northvane.core.net.toUserMessage
import app.northvane.core.nws.NwsClient
import app.northvane.core.nws.NwsPoint
import app.northvane.core.nws.NwsStation
import app.northvane.core.nws.ForecastDetails
import app.northvane.core.owm.OwmClient
import app.northvane.core.owm.OwmForecast
import app.northvane.core.util.Solar
import app.northvane.core.weewx.WeeWxClient
import app.northvane.core.weewx.WeeWxSecret
import app.northvane.core.weewx.WeeWxStation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import app.northvane.core.time.*

/**
 * What to download in one refresh. On unconstrained networks everything is
 * fetched; on satellite links the user chooses (see the app's satellite
 * settings). Skipped sections are reported so cached data can be kept.
 */
data class FetchPlan(
    val sections: Set<Section> = Section.entries.toSet(),
    /** Fewer stations means fewer requests when the nearest one has no fresh reading. */
    val observationStations: Int = 3,
    /** Set when the plan comes from satellite / data-saver limits (shown in the UI). */
    val limited: Boolean = false,
) {
    operator fun contains(section: Section) = section in sections

    companion object {
        val FULL = FetchPlan()
    }
}

/** Per-refresh view of the user's source configuration. */
data class SourceConfig(
    val weewxStation: WeeWxStation? = null,
    val weewxSecret: WeeWxSecret? = null,
    /** Null unless the user enabled OpenWeatherMap and entered a key. */
    val owmApiKey: String? = null,
    /** Set when the platform won't let us reach the station (e.g. local network permission denied). */
    val weewxBlockedReason: String? = null,
    /** Null unless the user enabled pollen and entered a Google Pollen API key. */
    val pollenApiKey: String? = null,
    val pollenRestriction: AndroidKeyRestriction? = null,
    /** Optional free EPA AirNow key; without it AQI comes from Open-Meteo's model. */
    val airNowApiKey: String? = null,
    /** Download NOAA's geomagnetic forecast for the aurora card. */
    val aurora: Boolean = false,
    /** Download NOAA HRRR's wildfire smoke forecast (about 2 MB). */
    val smoke: Boolean = false,
    /** Download the nearest river gauge and tide predictions. */
    val water: Boolean = false,
)

@Serializable
data class CachedNwsPoint(val point: NwsPoint, val stations: List<NwsStation>, val savedAtEpochMs: Long)

/** Persistent cache for NWS grid metadata, which almost never changes for a location. */
interface NwsPointCache {
    suspend fun get(key: String): CachedNwsPoint?
    suspend fun put(key: String, value: CachedNwsPoint)
}

class WeatherUnavailableException(val issues: List<SourceIssue>) :
    WeatherApiException(issues.firstOrNull()?.message ?: "Weather data is unavailable")

class WeatherRepository(
    private val nws: NwsClient,
    private val owm: OwmClient,
    private val weewx: WeeWxClient,
    private val pointCache: NwsPointCache,
    private val clock: () -> Instant = { kotlin.time.Clock.System.now() },
    private val acis: AcisClient? = null,
    private val pollen: PollenClient? = null,
    private val airNow: AirNowClient? = null,
    private val epaUv: EpaUvClient? = null,
    private val openMeteoAir: OpenMeteoAirClient? = null,
    private val eccc: EcccClient? = null,
    private val aurora: AuroraClient? = null,
    private val smoke: app.northvane.core.radar.HrrrSmoke? = null,
    private val water: app.northvane.core.water.WaterClient? = null,
) {
    /** Nearest Environment Canada city page per place; these never move. */
    private val ecccSites = app.northvane.core.util.SyncMap<String, String>()

    /** Active alerts for background checks: NWS in the US, Environment Canada for Canadian places. */
    suspend fun alerts(point: GeoPoint): List<WeatherAlert> {
        val p = point.rounded()
        // A place already known to be Canadian goes straight to Environment Canada.
        if (eccc != null) ecccSites[p.nwsKey()]?.let { return eccc.forecast(it, clock()).alerts }
        return try {
            nws.alerts(p)
        } catch (e: app.northvane.core.net.HttpStatusException) {
            // weather.gov answers 400 for points outside its area.
            if (e.code !in 400..499 || eccc == null || !EcccClient.mayBeInCanada(p)) throw e
            val site = ecccSites[p.nwsKey()] ?: eccc.nearestSite(p).also { ecccSites[p.nwsKey()] = it }
            eccc.forecast(site, clock()).alerts
        }
    }

    suspend fun fetch(location: SavedLocation, config: SourceConfig, plan: FetchPlan = FetchPlan.FULL): WeatherSnapshot = coroutineScope {
        val now = clock()
        val point = location.point.rounded()
        val issues = mutableListOf<SourceIssue>()
        val wants = { section: Section -> section in plan }
        val needsNws = listOf(Section.CURRENT_CONDITIONS, Section.ALERTS, Section.DAILY_FORECAST, Section.HOURLY_FORECAST, Section.FORECAST_DETAILS)
            .any(wants)

        // Grid metadata: fetched (or refreshed) only if some NWS section is wanted; otherwise cache only.
        val nwsMeta = if (needsNws) attempt { resolvePoint(point, now) } else Result.success(pointCache.get(point.nwsKey()))
        val meta = nwsMeta.getOrNull()
        val outsideNws = nwsMeta.exceptionOrNull() is OutsideNwsCoverageException
        nwsMeta.exceptionOrNull()?.let { issues += SourceIssue(DataSource.NWS, it.toUserMessage()) }
        val zone = meta?.let { NwsClient.zoneOf(it.point) }

        val linkedStation = config.weewxStation?.takeIf { it.enabled && it.covers(location) && wants(Section.LOCAL_STATION) }
        if (linkedStation != null && config.weewxBlockedReason != null) {
            issues += SourceIssue(DataSource.WEEWX, config.weewxBlockedReason)
        }
        val weewxStation = linkedStation?.takeIf { config.weewxBlockedReason == null }
        val weewxJob = weewxStation?.let { station ->
            async { attempt { weewx.fetch(station, config.weewxSecret, zone ?: ZoneId.systemDefault(), now) } }
        }
        val alertsJob = meta?.takeIf { wants(Section.ALERTS) }?.let { async { attempt { nws.alerts(point) } } }
        val dailyJob = meta?.takeIf { wants(Section.DAILY_FORECAST) }?.let { m -> async { attempt { nws.daily(m.point) } } }
        val hourlyJob = meta?.takeIf { wants(Section.HOURLY_FORECAST) }?.let { m -> async { attempt { nws.hourly(m.point) } } }
        val gridJob = meta?.takeIf { wants(Section.FORECAST_DETAILS) }?.let { m -> async { attempt { nws.gridData(m.point) } } }
        val observationJob = meta?.takeIf { wants(Section.CURRENT_CONDITIONS) }?.let { m ->
            async { attempt { latestObservation(m, plan.observationStations, now) } }
        }
        val today = now.atZone(zone ?: ZoneId.systemDefault()).toLocalDate()
        // ACIS gridded history covers the contiguous US only: don't spend a 150-day request elsewhere.
        val historyJob = acis?.takeIf { wants(Section.HISTORY) && !outsideNws && inContiguousUs(point) }?.let { client ->
            // Late winter to spring needs the whole winter's freeze for the thaw model.
            val sinceMarch = app.northvane.core.time.ChronoUnit.DAYS.between(LocalDate.of(today.year, 3, 1), today).toInt() + 1
            val days = when (today.monthValue) {
                in 2..5 -> 150
                in 6..10 -> maxOf(AcisClient.DEFAULT_DAYS + 15, sinceMarch) // growing degree days since March
                else -> AcisClient.DEFAULT_DAYS + 15
            }
            async { attempt { client.history(point, today, days, includeSnowDepth = today.monthValue !in 6..9, now = now) } }
        }
        val airJob = if (wants(Section.AIR_QUALITY) && (airNow != null || epaUv != null || openMeteoAir != null)) {
            async { airAndUv(location, point, config, zone ?: ZoneId.systemDefault(), today, now) }
        } else {
            null
        }
        val auroraJob = aurora?.takeIf { config.aurora && wants(Section.AURORA) }?.let { client -> async { attempt { client.forecast(now) } } }
        // HRRR covers the contiguous US only.
        val smokeJob = smoke?.takeIf { config.smoke && wants(Section.SMOKE) && inContiguousUs(point) }?.let { client ->
            async { attempt { client.outlook(point, now) } }
        }
        val waterJob = water?.takeIf { config.water && wants(Section.WATER) && !outsideNws }?.let { client ->
            async { attempt { client.report(point, now) } }
        }
        val pollenJob = config.pollenApiKey?.takeIf { wants(Section.POLLEN) }?.let { key ->
            pollen?.let { client -> async { attempt { client.forecast(point, key, now, config.pollenRestriction) } } }
        }

        val weewxResult = weewxJob?.await()
        val alertsResult = alertsJob?.await()
        val dailyResult = dailyJob?.await()
        val hourlyResult = hourlyJob?.await()
        val gridResult = gridJob?.await()
        val observationResult = observationJob?.await()

        weewxResult?.exceptionOrNull()?.let { issues += SourceIssue(DataSource.WEEWX, it.toUserMessage()) }
        alertsResult?.exceptionOrNull()?.let { issues += SourceIssue(DataSource.NWS, "Alerts: ${it.toUserMessage()}") }
        dailyResult?.exceptionOrNull()?.let { issues += SourceIssue(DataSource.NWS, "Forecast: ${it.toUserMessage()}") }
        hourlyResult?.exceptionOrNull()?.let { issues += SourceIssue(DataSource.NWS, "Hourly: ${it.toUserMessage()}") }
        gridResult?.exceptionOrNull()?.let { issues += SourceIssue(DataSource.NWS, "Forecast details: ${it.toUserMessage()}") }
        observationResult?.exceptionOrNull()?.let { issues += SourceIssue(DataSource.NWS, "Observations: ${it.toUserMessage()}") }
        val airResult = airJob?.await()
        airResult?.issues?.let { issues += it }
        val historyResult = historyJob?.await()
        val pollenResult = pollenJob?.await()
        historyResult?.exceptionOrNull()?.takeUnless { it is HistoryUnavailableException }
            ?.let { issues += SourceIssue(DataSource.NOAA_ACIS, "Recent weather: ${it.toUserMessage()}") }
        pollenResult?.exceptionOrNull()?.let { issues += SourceIssue(DataSource.GOOGLE_POLLEN, "Pollen: ${it.toUserMessage()}") }
        val auroraResult = auroraJob?.await()
        auroraResult?.exceptionOrNull()?.let { issues += SourceIssue(DataSource.NOAA_SWPC, "Aurora: ${it.toUserMessage()}") }
        val smokeResult = smokeJob?.await()
        smokeResult?.exceptionOrNull()?.let { issues += SourceIssue(DataSource.NOAA_HRRR, "Smoke: ${it.toUserMessage()}") }
        val waterResult = waterJob?.await()
        waterResult?.exceptionOrNull()?.let { issues += SourceIssue(DataSource.USGS, "Rivers and tides: ${it.toUserMessage()}") }

        val grid = gridResult?.getOrNull()
        var daily: List<DailyForecast> = dailyResult?.getOrNull().orEmpty()
        var hourly: List<HourlyForecast> = hourlyResult?.getOrNull().orEmpty()
        if (grid != null && zone != null) {
            hourly = ForecastDetails.enrichHourly(hourly, grid)
            daily = Periods.fill(ForecastDetails.enrichDaily(daily, grid, zone), hourly, grid, zone)
        }
        daily = ForecastDetails.enrichFromText(daily)
        var forecastSource: DataSource? = if (daily.isNotEmpty() || hourly.isNotEmpty()) DataSource.NWS else null
        val nwsObservation = observationResult?.getOrNull()
        val weewxCurrent = weewxResult?.getOrNull()

        // Canada: Environment and Climate Change Canada city pages, for places outside NWS coverage.
        var ecccForecast: EcccForecast? = null
        val wantsForecast = listOf(Section.CURRENT_CONDITIONS, Section.ALERTS, Section.DAILY_FORECAST, Section.HOURLY_FORECAST).any(wants)
        if (outsideNws && eccc != null && EcccClient.mayBeInCanada(point) && wantsForecast) {
            val result = attempt {
                val site = ecccSites[point.nwsKey()] ?: eccc.nearestSite(point).also { ecccSites[point.nwsKey()] = it }
                eccc.forecast(site, now)
            }
            result.exceptionOrNull()?.takeUnless { it is OutsideEcccCoverageException }
                ?.let { issues += SourceIssue(DataSource.ECCC, it.toUserMessage()) }
            ecccForecast = result.getOrNull()?.also { f ->
                if (wants(Section.DAILY_FORECAST)) daily = f.daily
                if (wants(Section.HOURLY_FORECAST)) hourly = f.hourly
                if (daily.isNotEmpty() || hourly.isNotEmpty()) forecastSource = DataSource.ECCC
            }
        }
        val forecastObservation = nwsObservation ?: ecccForecast?.current?.takeIf { wants(Section.CURRENT_CONDITIONS) }

        // OpenWeatherMap only fills gaps the free sources couldn't, and only for sections the plan wants.
        val needOwmForecast = (wants(Section.DAILY_FORECAST) && daily.isEmpty()) ||
            (wants(Section.HOURLY_FORECAST) && hourly.isEmpty())
        // A days-old or temperature-less station reading doesn't count: OWM (with a key) is better.
        val needOwmCurrent = wants(Section.CURRENT_CONDITIONS) && weewxCurrent == null &&
            (forecastObservation?.temperatureC == null || !forecastObservation.isFresh(now, Duration.ofHours(2)))
        var owmForecast: OwmForecast? = null
        var owmCurrent: CurrentConditions? = null
        val apiKey = config.owmApiKey?.takeIf { wants(Section.OWM_FALLBACK) }
        if (apiKey != null && (needOwmForecast || needOwmCurrent)) {
            val forecastJob = if (needOwmForecast) async { attempt { owm.forecast(point, apiKey) } } else null
            val currentJob = if (needOwmCurrent) async { attempt { owm.current(point, apiKey) } } else null
            forecastJob?.await()?.let { r ->
                r.exceptionOrNull()?.let { issues += SourceIssue(DataSource.OWM, it.toUserMessage()) }
                owmForecast = r.getOrNull()
            }
            currentJob?.await()?.let { r ->
                r.exceptionOrNull()?.let { issues += SourceIssue(DataSource.OWM, it.toUserMessage()) }
                owmCurrent = r.getOrNull()
            }
            owmForecast?.let { f ->
                if (daily.isEmpty() && wants(Section.DAILY_FORECAST) && f.daily.isNotEmpty()) {
                    daily = f.daily
                    forecastSource = DataSource.OWM
                }
                if (hourly.isEmpty() && wants(Section.HOURLY_FORECAST)) hourly = f.hourly
            }
        } else if (outsideNws && config.owmApiKey == null && ecccForecast == null) {
            issues += SourceIssue(
                DataSource.OWM,
                "Outside the US, add an OpenWeatherMap API key in Settings › Data sources to get weather here.",
            )
        }

        val upcomingHourly = hourly.filter { it.time.isAfter(now.minus(Duration.ofMinutes(59))) }
        val current = chooseCurrent(weewxCurrent, forecastObservation, owmCurrent, upcomingHourly.takeIf { wants(Section.CURRENT_CONDITIONS) }.orEmpty(), now)
            ?.let { withDaylight(it, location, now) }

        val resolvedZone = zone ?: ecccForecast?.zone ?: owmForecast?.zoneOffset ?: ZoneId.systemDefault()
        val (sunrise, sunset) = Solar.sunTimes(
            location.latitude, location.longitude, now.atZone(resolvedZone).toLocalDate(), resolvedZone,
        )
        val ecccAlerts = ecccForecast?.alerts?.takeIf { wants(Section.ALERTS) }
        val alerts = alertsResult?.getOrNull() ?: ecccAlerts.orEmpty()
        val skipped = Section.entries.filterNot(wants).toSet()

        val attemptedSomething = (Section.entries.toSet() - skipped - Section.OWM_FALLBACK - Section.HISTORY - Section.POLLEN - Section.AIR_QUALITY - Section.AURORA - Section.SMOKE - Section.WATER).isNotEmpty()
        val gotExtras = historyResult?.isSuccess == true || pollenResult?.isSuccess == true || airResult?.air != null
        if (attemptedSomething && current == null && daily.isEmpty() && upcomingHourly.isEmpty() && alertsResult?.isSuccess != true && ecccAlerts == null && !gotExtras) {
            throw WeatherUnavailableException(issues.ifEmpty { listOf(SourceIssue(DataSource.NWS, "No data available")) })
        }

        val snapshot = WeatherSnapshot(
            locationId = location.id,
            fetchedAt = now,
            timeZoneId = resolvedZone.id,
            placeName = meta?.point?.placeName ?: ecccForecast?.placeName ?: owmForecast?.cityName,
            current = current,
            hourly = upcomingHourly.take(48),
            daily = daily.filter { !it.date.isBefore(now.atZone(resolvedZone).toLocalDate().minusDays(1)) },
            alerts = alerts,
            alertsAvailable = meta != null || !outsideNws || ecccForecast != null,
            forecastSource = forecastSource,
            radarStation = meta?.point?.radarStation,
            issues = issues.distinct(),
            liteMode = plan.limited,
            sunrise = sunrise,
            sunset = sunset,
            skippedSections = skipped,
            history = historyResult?.getOrNull(),
            pollen = pollenResult?.getOrNull(),
            airQuality = airResult?.air,
            uv = airResult?.uv,
            aurora = auroraResult?.getOrNull(),
            smoke = smokeResult?.getOrNull(),
            water = waterResult?.getOrNull(),
            sectionTimes = buildMap {
                // Stamped with when it was measured, so an old reading shows (and ages) as old.
                if (current != null && current.source != DataSource.NWS_FORECAST_ESTIMATE) {
                    put(Section.CURRENT_CONDITIONS, current.observedAt?.coerceAtMost(now) ?: now)
                }
                if (weewxCurrent != null) put(Section.LOCAL_STATION, now)
                if (alertsResult?.isSuccess == true || ecccAlerts != null) put(Section.ALERTS, now)
                if (daily.isNotEmpty() && wants(Section.DAILY_FORECAST)) put(Section.DAILY_FORECAST, now)
                if (upcomingHourly.isNotEmpty() && wants(Section.HOURLY_FORECAST)) put(Section.HOURLY_FORECAST, now)
                if (grid != null) put(Section.FORECAST_DETAILS, now)
                historyResult?.getOrNull()?.let { put(Section.HISTORY, it.fetchedAt) }
                pollenResult?.getOrNull()?.let { put(Section.POLLEN, it.fetchedAt) }
                airResult?.air?.let { put(Section.AIR_QUALITY, it.fetchedAt) }
                auroraResult?.getOrNull()?.let { put(Section.AURORA, it.fetchedAt) }
                smokeResult?.getOrNull()?.let { put(Section.SMOKE, it.fetchedAt) }
                waterResult?.getOrNull()?.let { put(Section.WATER, it.fetchedAt) }
            },
        )
        Outlooks.annotate(snapshot, location.latitude, location.longitude, now)
    }

    private class AirResult(val air: AirQuality?, val uv: UvForecast?, val issues: List<SourceIssue>)

    /**
     * AQI from EPA AirNow monitors when the user has a key, UV from EPA's ZIP
     * code forecast; Open-Meteo's model fills whatever those can't (no key, no
     * monitor nearby, no US ZIP code).
     */
    private suspend fun airAndUv(
        location: SavedLocation,
        point: GeoPoint,
        config: SourceConfig,
        zone: ZoneId,
        today: LocalDate,
        now: Instant,
    ): AirResult = coroutineScope {
        val issues = mutableListOf<SourceIssue>()
        val airNowJob = config.airNowApiKey?.let { key -> airNow?.let { c -> async { attempt { c.airQuality(point, key, today, zone, now) } } } }
        val zip = location.postalCode?.takeIf { it.matches(Regex("^\\d{5}$")) }
        val uvJob = zip?.let { z -> epaUv?.let { c -> async { attempt { c.forecast(z, zone, now) } } } }
        val airNowResult = airNowJob?.await()
        val uvResult = uvJob?.await()
        var air = airNowResult?.getOrNull()
        var uv = uvResult?.getOrNull()
        val airNowError = airNowResult?.exceptionOrNull()
        if (airNowError is InvalidCredentialsException) issues += SourceIssue(DataSource.AIRNOW, airNowError.toUserMessage())
        if ((air == null || uv == null) && openMeteoAir != null) {
            val model = attempt { openMeteoAir.fetch(point, now) }
            model.getOrNull()?.let { (modelAir, modelUv) ->
                if (air == null) air = modelAir
                if (uv == null) uv = modelUv
            }
            model.exceptionOrNull()?.let { issues += SourceIssue(DataSource.OPEN_METEO, "Air quality: ${it.toUserMessage()}") }
        } else if (air == null && airNowError != null && airNowError !is InvalidCredentialsException) {
            issues += SourceIssue(DataSource.AIRNOW, airNowError.toUserMessage())
        }
        AirResult(air, uv, issues)
    }

    /**
     * Priority: local WeeWX station > NWS observation > OpenWeatherMap >
     * NWS hourly forecast (labelled as an estimate). A local station overrides
     * the measured values; anything it doesn't measure (sky condition,
     * visibility) is filled from the next source.
     */
    internal fun chooseCurrent(
        weewx: CurrentConditions?,
        nws: CurrentConditions?,
        owm: CurrentConditions?,
        hourly: List<HourlyForecast>,
        now: Instant,
    ): CurrentConditions? {
        val freshNws = nws?.takeIf { it.temperatureC != null && it.isFresh(now, Duration.ofHours(2)) }
        val estimate = hourly.firstOrNull()?.let { h ->
            CurrentConditions(
                observedAt = h.time,
                source = DataSource.NWS_FORECAST_ESTIMATE,
                description = h.shortForecast,
                condition = h.condition,
                isDaytime = h.isDaytime,
                temperatureC = h.temperatureC,
                humidityPercent = h.humidityPercent,
                dewpointC = h.dewpointC,
                windSpeedKph = h.windSpeedKph,
                windDirectionDeg = app.northvane.core.model.Units.compassToDegrees(h.windDirection),
            )
        }
        if (weewx != null) {
            val supplement = freshNws ?: owm ?: nws ?: estimate
            return if (supplement == null) weewx else weewx.copy(
                description = weewx.description ?: supplement.description,
                condition = weewx.condition.takeIf { it != WeatherCondition.UNKNOWN } ?: supplement.condition,
                visibilityKm = weewx.visibilityKm ?: supplement.visibilityKm,
                pressureHpa = weewx.pressureHpa ?: supplement.pressureHpa,
                windGustKph = weewx.windGustKph ?: supplement.windGustKph,
                uvIndex = weewx.uvIndex ?: supplement.uvIndex,
                supplementedFrom = supplement.source,
            )
        }
        // An older reading beats the estimate only while it's still reasonably recent.
        return freshNws ?: owm ?: nws?.takeIf { it.temperatureC != null && it.isFresh(now, STALE_OBSERVATION) } ?: estimate
    }

    private fun withDaylight(c: CurrentConditions, location: SavedLocation, now: Instant): CurrentConditions =
        if (c.source == DataSource.WEEWX || c.source == DataSource.NWS_FORECAST_ESTIMATE || c.condition == WeatherCondition.UNKNOWN) {
            c.copy(isDaytime = Solar.isDaylight(location.latitude, location.longitude, c.observedAt ?: now))
        } else {
            c
        }

    private fun inContiguousUs(p: GeoPoint) = p.latitude in 24.0..50.0 && p.longitude in -125.5..-66.5

    private fun CurrentConditions.isFresh(now: Instant, maxAge: Duration): Boolean =
        observedAt?.let { Duration.between(it, now) <= maxAge } ?: false

    private suspend fun resolvePoint(point: GeoPoint, now: Instant): CachedNwsPoint {
        val key = point.nwsKey()
        val cached = pointCache.get(key)
        if (cached != null && now.toEpochMilli() - cached.savedAtEpochMs < POINT_CACHE_MS) return cached
        return try {
            val fresh = nws.point(point)
            val stations = runCatchingNonCancel { nws.stations(fresh) }.getOrDefault(cached?.stations.orEmpty())
            CachedNwsPoint(fresh, stations, now.toEpochMilli()).also { pointCache.put(key, it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: OutsideNwsCoverageException) {
            throw e
        } catch (e: Exception) {
            // An old grid mapping is still far better than nothing.
            cached ?: throw e
        }
    }

    /** NWS stations sometimes report null temperature; try the next-nearest ones. */
    private suspend fun latestObservation(meta: CachedNwsPoint, maxStations: Int, now: Instant): CurrentConditions {
        var lastError: Throwable? = null
        var best: CurrentConditions? = null
        for (station in meta.stations.take(maxStations)) {
            try {
                val obs = nws.latestObservation(station)
                if (obs.temperatureC != null && obs.isFresh(now, Duration.ofHours(2))) return obs
                if (best == null) best = obs
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
        }
        return best ?: throw (lastError ?: WeatherApiException("No nearby observation stations"))
    }

    private companion object {
        const val POINT_CACHE_MS = 14L * 24 * 60 * 60 * 1000
        val STALE_OBSERVATION: Duration = Duration.ofHours(6)
    }
}

/** Like runCatching, but never swallows coroutine cancellation. */
suspend fun <T> attempt(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

private inline fun <T> runCatchingNonCancel(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
