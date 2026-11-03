package app.northvane.core.model

import app.northvane.core.json.InstantSerializer
import app.northvane.core.json.LocalDateSerializer
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate

/** Where a piece of data came from, shown to the user next to the data. */
@Serializable
enum class DataSource(val displayName: String) {
    WEEWX("Local station (WeeWX)"),
    NWS("National Weather Service"),
    OWM("OpenWeatherMap"),
    NWS_FORECAST_ESTIMATE("NWS hourly forecast"),
    NOAA_ACIS("NOAA climate data (ACIS)"),
    NOAA_SWPC("NOAA Space Weather Prediction Center"),
    GOOGLE_POLLEN("Google Pollen API"),
    AIRNOW("EPA AirNow"),
    EPA_UV("EPA UV Index"),
    OPEN_METEO("Open-Meteo"),
    ECCC("Environment and Climate Change Canada"),
}

@Serializable
data class GeoPoint(val latitude: Double, val longitude: Double) {
    init {
        require(latitude in -90.0..90.0) { "Latitude out of range" }
        require(longitude in -180.0..180.0) { "Longitude out of range" }
    }

    /**
     * Four decimal places (~11 m). NWS redirects requests with more precision,
     * and there is no reason to send anyone a more precise position than this.
     */
    fun rounded(): GeoPoint = GeoPoint(round4(latitude).toDouble(), round4(longitude).toDouble())

    /** Great-circle distance in kilometres. */
    fun distanceKm(other: GeoPoint): Double {
        val dLat = Math.toRadians(other.latitude - latitude)
        val dLon = Math.toRadians(other.longitude - longitude)
        val a = kotlin.math.sin(dLat / 2).let { it * it } +
            kotlin.math.cos(Math.toRadians(latitude)) * kotlin.math.cos(Math.toRadians(other.latitude)) *
            kotlin.math.sin(dLon / 2).let { it * it }
        return 6371.0 * 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
    }

    /** Canonical "lat,lon" form expected by api.weather.gov (no trailing zeros). */
    fun nwsKey(): String = "${format(latitude)},${format(longitude)}"

    companion object {
        private fun round4(v: Double): BigDecimal =
            BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP)

        fun format(v: Double): String = round4(v).stripTrailingZeros().toPlainString()
    }
}

@Serializable
data class SavedLocation(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    /** True for the entry that tracks the device's position. */
    val isDeviceLocation: Boolean = false,
    /** User-chosen name shown instead of [name]; [name] stays the place name from search or geocoding. */
    val nickname: String? = null,
    /** Whether background alert checks (and notifications) cover this location. */
    val notify: Boolean = true,
    /** US ZIP code, looked up on the device, for EPA's UV index forecast. */
    val postalCode: String? = null,
) {
    val displayName: String get() = nickname?.takeIf { it.isNotBlank() } ?: name

    val point: GeoPoint get() = GeoPoint(latitude, longitude)
}

@Serializable
data class CurrentConditions(
    @Serializable(InstantSerializer::class) val observedAt: Instant?,
    val source: DataSource,
    val stationName: String? = null,
    val description: String? = null,
    val condition: WeatherCondition = WeatherCondition.UNKNOWN,
    val isDaytime: Boolean = true,
    val temperatureC: Double? = null,
    val feelsLikeC: Double? = null,
    val dewpointC: Double? = null,
    val humidityPercent: Double? = null,
    val windSpeedKph: Double? = null,
    val windGustKph: Double? = null,
    val windDirectionDeg: Double? = null,
    val pressureHpa: Double? = null,
    val visibilityKm: Double? = null,
    val rainRateMmPerHour: Double? = null,
    val rainTodayMm: Double? = null,
    val uvIndex: Double? = null,
    val solarRadiationWm2: Double? = null,
    /** When a local station is primary, fields it lacks are filled from here. */
    val supplementedFrom: DataSource? = null,
)

@Serializable
data class HourlyForecast(
    @Serializable(InstantSerializer::class) val time: Instant,
    val temperatureC: Double?,
    val condition: WeatherCondition,
    val isDaytime: Boolean,
    val shortForecast: String?,
    val precipitationChance: Int?,
    val windSpeedKph: Double?,
    val windDirection: String?,
    val humidityPercent: Double?,
    val dewpointC: Double? = null,
    // Quantitative values from the NWS gridpoint forecast (or OpenWeatherMap).
    val windGustKph: Double? = null,
    val windDirectionDeg: Double? = null,
    val apparentTemperatureC: Double? = null,
    val skyCoverPercent: Int? = null,
    val precipitationMm: Double? = null,
    val snowMm: Double? = null,
    val iceMm: Double? = null,
    val thunderChance: Int? = null,
)

@Serializable
data class DailyForecast(
    @Serializable(LocalDateSerializer::class) val date: LocalDate,
    val name: String,
    val highC: Double?,
    val lowC: Double?,
    val condition: WeatherCondition,
    val isDaytimeCondition: Boolean,
    val shortForecast: String?,
    val precipitationChance: Int?,
    val dayDetail: String? = null,
    val nightDetail: String? = null,
    val windSummary: String? = null,
    // Quantitative totals/extremes for the day (06:00 to 06:00 local, matching NWS day+night periods).
    val windMaxKph: Double? = null,
    val gustMaxKph: Double? = null,
    val windDirectionDeg: Double? = null,
    val precipitationMm: Double? = null,
    val snowMm: Double? = null,
    val iceMm: Double? = null,
    val humidityMin: Double? = null,
    val humidityMax: Double? = null,
    val thunderChance: Int? = null,
    val skyCoverPercent: Int? = null,
    /** Where the quantitative values came from: gridpoint numbers, or parsed from forecast text. */
    val detailSource: DetailSource? = null,
    val outageRisk: OutageRisk? = null,
    /** Daytime (08–18) and overnight (20–07 the next morning) summaries, for activity scores. */
    val daytime: PeriodStats? = null,
    val overnight: PeriodStats? = null,
    val mud: MudOutlook? = null,
    val activities: List<ActivityScore> = emptyList(),
    /** Frozen pipe risk for the day and the night after it. */
    val pipeRisk: PipeRisk? = null,
    /** Frost and black ice on the morning of this day. */
    val morningIce: MorningIce? = null,
)

/** Parts of a refresh that can be switched on or off individually (e.g. over satellite). */
@Serializable
enum class Section {
    CURRENT_CONDITIONS, LOCAL_STATION, ALERTS, DAILY_FORECAST, HOURLY_FORECAST, FORECAST_DETAILS, OWM_FALLBACK,
    /** Recent observed weather (NOAA ACIS), for mud, foliage and seasonal outage estimates. */
    HISTORY,
    POLLEN,
    /** Air quality index and UV index. */
    AIR_QUALITY,
    /** Geomagnetic activity forecast (NOAA SWPC), for the aurora. */
    AURORA,
}

@Serializable
enum class DetailSource { GRID, TEXT, OWM }

@Serializable
enum class OutageLevel(val label: String) { LOW("Low"), ELEVATED("Elevated"), HIGH("High"), VERY_HIGH("Very high") }

/** What drove an outage estimate; [value] is in metric units where relevant (km/h, mm). */
@Serializable
data class OutageFactor(val kind: Kind, val value: Double? = null, val detail: String? = null) {
    @Serializable
    enum class Kind {
        GUSTS, LEAVES_ON, ICE, WET_SNOW, SNOW, THUNDER, SATURATED_GROUND, ALERT, HEAT, SUSTAINED_WIND,
        /** Explanatory: bare trees catch less wind (late fall to spring). */
        LEAVES_OFF,
        /** Explanatory: frozen ground anchors roots, so wind uproots fewer trees. */
        FROZEN_GROUND,
        /** Thawing or rain-soaked ground lets wind uproot trees more easily. */
        SOFT_GROUND,
    }
}

@Serializable
data class OutageRisk(val level: OutageLevel, val score: Int, val factors: List<OutageFactor>, val leafCover: Double? = null)

@Serializable
enum class AlertSeverity { EXTREME, SEVERE, MODERATE, MINOR, UNKNOWN }

/** Coarse NWS product family, used for notification channels and filtering. */
@Serializable
enum class AlertCategory { WARNING, WATCH, ADVISORY, STATEMENT, OTHER }

@Serializable
data class WeatherAlert(
    val id: String,
    /** Identity shared by an alert and all of its updates (oldest message in the chain). */
    val threadId: String,
    val event: String,
    val headline: String?,
    val description: String?,
    val instruction: String?,
    val severity: AlertSeverity,
    val urgency: String?,
    val certainty: String?,
    val category: AlertCategory,
    @Serializable(InstantSerializer::class) val sent: Instant? = null,
    @Serializable(InstantSerializer::class) val effective: Instant? = null,
    @Serializable(InstantSerializer::class) val onset: Instant? = null,
    @Serializable(InstantSerializer::class) val expires: Instant? = null,
    @Serializable(InstantSerializer::class) val ends: Instant? = null,
    val senderName: String? = null,
    val areaDesc: String? = null,
    val messageType: String? = null,
) {
    val isUpdate: Boolean get() = messageType.equals("Update", ignoreCase = true)
}

@Serializable
data class SourceIssue(val source: DataSource, val message: String)

/** Everything the UI shows for one location, merged across sources. */
@Serializable
data class WeatherSnapshot(
    val locationId: String,
    @Serializable(InstantSerializer::class) val fetchedAt: Instant,
    val timeZoneId: String,
    val placeName: String? = null,
    val current: CurrentConditions? = null,
    val hourly: List<HourlyForecast> = emptyList(),
    val daily: List<DailyForecast> = emptyList(),
    val alerts: List<WeatherAlert> = emptyList(),
    /** False when the location is outside NWS coverage, where no alert feed exists. */
    val alertsAvailable: Boolean = true,
    val forecastSource: DataSource? = null,
    val radarStation: String? = null,
    val issues: List<SourceIssue> = emptyList(),
    /** True when fetched in reduced-data (satellite) mode, so some sections may be missing. */
    val liteMode: Boolean = false,
    @Serializable(InstantSerializer::class) val sunrise: Instant? = null,
    @Serializable(InstantSerializer::class) val sunset: Instant? = null,
    /** Sections this refresh deliberately didn't download (satellite settings); cached data may stand in. */
    val skippedSections: Set<Section> = emptySet(),
    val history: WeatherHistory? = null,
    val pollen: PollenForecast? = null,
    val foliage: FoliageReport? = null,
    val latitude: Double? = null,
    val elevationM: Double? = null,
    val airQuality: AirQuality? = null,
    val uv: UvForecast? = null,
    val aurora: KpForecast? = null,
    /** When each section's data was last downloaded (for hiding stale data on satellite). */
    val sectionTimes: Map<Section, @Serializable(InstantSerializer::class) Instant> = emptyMap(),
) {
    /** Age of a section's data, or null if it was never downloaded. */
    fun ageOf(section: Section, now: Instant): java.time.Duration? = sectionTimes[section]?.let { java.time.Duration.between(it, now) }

    /** True while an NWS warning (not a watch, advisory or statement) is in effect. */
    fun hasActiveWarning(now: Instant): Boolean = alerts.any { a ->
        a.category == AlertCategory.WARNING && (a.ends ?: a.expires)?.isAfter(now) != false
    }
}
