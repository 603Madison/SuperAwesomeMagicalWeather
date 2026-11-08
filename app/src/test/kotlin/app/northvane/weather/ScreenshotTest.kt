package app.northvane.weather

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.northvane.core.model.DataSource
import app.northvane.core.model.SavedLocation
import app.northvane.core.model.Section
import app.northvane.core.model.UnitSystem
import app.northvane.core.model.Units
import app.northvane.core.model.WeatherSnapshot
import app.northvane.core.nws.ForecastDetails
import app.northvane.core.nws.NwsClient
import app.northvane.core.nws.NwsGridData
import app.northvane.core.nws.NwsParsers
import app.northvane.core.history.AcisClient
import app.northvane.core.garden.Crop
import app.northvane.core.garden.FrostClimate
import app.northvane.core.garden.GardenCrop
import app.northvane.core.outlook.HeatSource
import app.northvane.core.outlook.HeatType
import app.northvane.core.air.EpaUvClient
import app.northvane.core.air.OpenMeteoAirClient
import app.northvane.core.model.Allergen
import app.northvane.core.model.Sensitivity
import app.northvane.core.model.UvForecast
import app.northvane.core.model.WeatherCondition
import app.northvane.weather.ui.settings.AllergiesScreen
import app.northvane.weather.ui.settings.ThemesScreen
import app.northvane.weather.ui.theme.AppTheme
import app.northvane.weather.ui.theme.ThemeWeather
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import app.northvane.weather.ui.TimeFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import app.northvane.core.model.AlertCategory
import app.northvane.core.model.PollenForecast
import app.northvane.core.model.WeatherHistory
import app.northvane.core.outlook.Outlooks
import app.northvane.core.outlook.Periods
import app.northvane.core.pollen.PollenClient
import app.northvane.weather.ui.MainViewModel
import app.northvane.weather.ui.locations.LocationsScreen
import app.northvane.weather.ui.settings.DataSourcesScreen
import androidx.compose.ui.test.onAllNodesWithContentDescription
import app.northvane.core.util.Solar
import app.northvane.weather.data.AppSettings
import app.northvane.weather.data.DataSaverMode
import app.northvane.weather.data.SatelliteFeature
import app.northvane.weather.net.NetworkStatus
import app.northvane.weather.ui.MainUiState
import app.northvane.weather.ui.alerts.AlertsScreen
import app.northvane.weather.ui.home.HomeActions
import app.northvane.weather.ui.home.HomeScreen
import app.northvane.weather.ui.radar.RadarScreen
import app.northvane.weather.ui.settings.SettingsScreen
import app.northvane.weather.ui.settings.SettingsNav
import app.northvane.weather.ui.settings.SettingsPage
import app.northvane.weather.ui.outlook.OutlookScreen
import app.northvane.weather.ui.AiUiState
import app.northvane.weather.data.AiReply
import app.northvane.weather.ui.AiTask
import app.northvane.weather.ui.home.AskCard
import app.northvane.weather.ui.home.ForecastersNotesCard
import app.northvane.core.ai.AiProvider
import app.northvane.core.eccc.EcccClient
import app.northvane.weather.ui.theme.NorthvaneTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import app.northvane.core.time.*

/**
 * Renders real screens with real NWS data for Portland, ME (captured
 * responses) to PNGs in app/build/outputs/screenshots, for visual review.
 * Not a pixel-diff test.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w400dp-h880dp-xxhdpi")
class ScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    /** One container per test, on the test's own Application and files. */
    private val testContainer: AppContainer by lazy {
        lateinit var c: AppContainer
        c = AppContainer(AndroidPlatform(ApplicationProvider.getApplicationContext()) { c.settings })
        c
    }

    /** Screens read platform details (version, clock format) from [LocalPlatform], as under [NorthvaneRoot]. */
    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.setAppContent(content: @Composable () -> Unit) = setContent {
        androidx.compose.runtime.CompositionLocalProvider(
            app.northvane.weather.ui.LocalPlatform provides testContainer.platform,
            content = content,
        )
    }

    private fun resource(name: String) = requireNotNull(javaClass.getResource("/live/$name")).readText()

    private val location = SavedLocation("home", "Portland, ME", 43.6591, -70.2568)

    private fun snapshot(): WeatherSnapshot {
        val point = NwsParsers.parsePoint(resource("me_point.json"))
        val zone = NwsClient.zoneOf(point)
        val grid = NwsGridData.parse(resource("me_grid.json"))
        val now = kotlin.time.Clock.System.now()
        val (sunrise, sunset) = Solar.sunTimes(location.latitude, location.longitude, now.atZone(zone).toLocalDate(), zone)
        val daily = ForecastDetails.enrichDaily(NwsParsers.parseDaily(resource("me_daily.json"), zone), grid, zone)
        return WeatherSnapshot(
            locationId = location.id,
            fetchedAt = now,
            timeZoneId = point.timeZone,
            placeName = point.placeName,
            current = NwsParsers.parseObservation(resource("me_obs.json"), null),
            hourly = ForecastDetails.enrichHourly(NwsParsers.parseHourly(resource("me_hourly.json")), grid),
            daily = Periods.fill(daily, emptyList(), grid, zone),
            alerts = emptyList(),
            forecastSource = DataSource.NWS,
            radarStation = point.radarStation,
            sunrise = sunrise,
            sunset = sunset,
            history = WeatherHistory(AcisClient.parseGrid(resource("me_acis.json")).first, elevationM = 20.0, fetchedAt = now),
            pollen = pollen(now.atZone(zone).toLocalDate()),
            airQuality = OpenMeteoAirClient.parse(resource("aq.json"), now).first,
            uv = uvToday(now, zone),
        ).let { Outlooks.annotate(it, location.latitude, location.longitude, now) }
    }

    /** Captured EPA hourly UV forecast, moved to today. */
    private fun uvToday(now: Instant, zone: app.northvane.core.time.ZoneId): UvForecast {
        val hours = EpaUvClient.parseHourly(resource("uv.json"), zone)
        val shift = app.northvane.core.time.Duration.between(hours.first().time.atZone(zone).toLocalDate().atStartOfDay(zone), now.atZone(zone).toLocalDate().atStartOfDay(zone))
        val moved = hours.map { it.copy(time = it.time.plus(shift)) }
        return UvForecast(moved, moved.maxOf { it.index }, source = "EPA UV Index", fetchedAt = now)
    }

    /** Captured Google Pollen API response, moved to today's dates. */
    private fun pollen(today: app.northvane.core.time.LocalDate): PollenForecast {
        val days = PollenClient.parse(resource("pollen.json"))
        return PollenForecast(days.mapIndexed { i, d -> d.copy(date = today.plusDays(i.toLong())) }, kotlin.time.Clock.System.now())
    }

    /** Demo only: turns the third day into a late-October nor'easter to show the outage outlook. */
    private fun withNorEaster(s: WeatherSnapshot): WeatherSnapshot {
        val zone = app.northvane.core.time.ZoneId.of(s.timeZoneId)
        val days = s.daily.toMutableList()
        days[2] = days[2].copy(
            gustMaxKph = Units.mphToKph(55.0), windMaxKph = Units.mphToKph(30.0), windDirectionDeg = 45.0,
            precipitationMm = Units.inchesToMm(1.8), precipitationChance = 100, thunderChance = 0,
        )
        return Outlooks.annotate(s.copy(daily = days.map { it.copy(daytime = null, overnight = null) }), location.latitude, location.longitude)
    }

    private fun state(
        snapshot: WeatherSnapshot? = snapshot(),
        settings: AppSettings = AppSettings(
            units = UnitSystem.IMPERIAL,
            pollenEnabled = true,
            outageRiskEnabled = true,
            allergies = mapOf(Allergen.BIRCH to Sensitivity.SEVERE, Allergen.GRASS to Sensitivity.MILD, Allergen.MOLD to Sensitivity.MODERATE),
            gardenEnabled = true,
            gardenPlan = listOf(
                GardenCrop(Crop.TOMATO, plantedOn = app.northvane.core.time.LocalDate.now().withMonth(5).withDayOfMonth(20)),
                GardenCrop(Crop.KALE, plantedOn = app.northvane.core.time.LocalDate.now().withMonth(4).withDayOfMonth(25)),
                GardenCrop(Crop.WINTER_SQUASH, plantedOn = app.northvane.core.time.LocalDate.now().withMonth(5).withDayOfMonth(25)),
                GardenCrop(Crop.GARLIC),
            ),
            heatingEnabled = true,
            heatSources = listOf(
                HeatSource("hp", HeatType.HEAT_PUMP, minF = 20),
                HeatSource("wood", HeatType.WOOD, maxF = 32),
                HeatSource("oil", HeatType.OIL, maxF = 5),
            ),
        ),
        network: NetworkStatus = NetworkStatus(),
        liteMode: Boolean = false,
    ) = MainUiState(
        ready = true,
        locations = listOf(location, SavedLocation("nh", "Concord, NH", 43.2081, -71.5376, nickname = "Mom's", notify = false)),
        selected = location,
        snapshot = snapshot,
        settings = settings.copy(notificationPromptShown = true),
        network = network,
        liteMode = liteMode,
        pollenKeySet = settings.pollenEnabled,
        frostClimate = climate,
    )

    private val climate by lazy { FrostClimate.compute(AcisClient.parseDailyLows(resource("mint30.json")), kotlin.time.Clock.System.now()) }

    private val noActions = HomeActions({}, {}, {}, {}, {}, {})

    private fun render(dark: Boolean, content: @Composable () -> Unit) {
        compose.setAppContent {
            NorthvaneTheme(darkTheme = dark, dynamicColor = false) {
                Box {
                    Backdrop()
                    content()
                }
            }
        }
        compose.waitForIdle()
    }

    private fun save(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = File("build/outputs/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    @Config(qualifiers = "w400dp-h4600dp-xxhdpi")
    fun todayFullLight() {
        render(dark = false) { HomeScreen(state(), noActions, {}, {}, {}) }
        save("today_full_light")
        compose.onAllNodesWithText("Tap for wind, humidity, clouds and more")[0].performClick()
    }

    @Test
    @Config(qualifiers = "w400dp-h1300dp-xxhdpi")
    fun hourlyExpanded() {
        val st = state()
        render(dark = false) { HomeScreen(st.copy(settings = st.settings.copy(hourlyExpanded = true)), noActions, {}, {}, {}) }
        save("today_hourly_expanded")
    }

    @Test
    @Config(qualifiers = "w400dp-h1400dp-xxhdpi")
    fun warningTurnsTodayRed() {
        val base = snapshot()
        val warning = NwsParsers.parseAlerts(resource("nws_alerts_tx.json")).first().copy(
            event = "High Wind Warning", category = AlertCategory.WARNING,
            ends = kotlin.time.Clock.System.now().plusSeconds(6 * 3600), expires = kotlin.time.Clock.System.now().plusSeconds(6 * 3600),
        )
        render(dark = false) { HomeScreen(state(base.copy(alerts = listOf(warning))), noActions, {}, {}, {}) }
        save("today_warning_light")
    }

    @Test
    @Config(qualifiers = "w400dp-h1400dp-xxhdpi")
    fun warningTurnsTodayRedDark() {
        val base = snapshot()
        val warning = NwsParsers.parseAlerts(resource("nws_alerts_tx.json")).first().copy(
            event = "Winter Storm Warning", category = AlertCategory.WARNING,
            ends = kotlin.time.Clock.System.now().plusSeconds(6 * 3600), expires = kotlin.time.Clock.System.now().plusSeconds(6 * 3600),
        )
        render(dark = true) { HomeScreen(state(base.copy(alerts = listOf(warning))), noActions, {}, {}, {}) }
        save("today_warning_dark")
    }

    @Test
    fun watchStaysNormal() {
        val base = snapshot()
        val watch = NwsParsers.parseAlerts(resource("nws_alerts_tx.json")).first().copy(
            event = "Winter Storm Watch", category = AlertCategory.WATCH,
            ends = kotlin.time.Clock.System.now().plusSeconds(6 * 3600), expires = kotlin.time.Clock.System.now().plusSeconds(6 * 3600),
        )
        render(dark = false) { HomeScreen(state(base.copy(alerts = listOf(watch))), noActions, {}, {}, {}) }
        save("today_watch_light")
    }

    @Test
    @Config(qualifiers = "w400dp-h1600dp-xxhdpi")
    fun themesGallery() {
        val container = testContainer
        var current by androidx.compose.runtime.mutableStateOf(AppTheme.HARBOR to false)
        var showPicker by androidx.compose.runtime.mutableStateOf(false)
        val st = state()
        compose.setAppContent {
            val (theme, dark) = current
            NorthvaneTheme(darkTheme = dark, dynamicColor = false, theme = theme, weather = ThemeWeather(WeatherCondition.CLEAR, true, 12.0)) {
                Box {
                    Backdrop()
                    if (showPicker) ThemesScreen(st.copy(settings = st.settings.copy(appTheme = theme)), container, onBack = {}) else HomeScreen(st, noActions, {}, {}, {})
                }
            }
        }
        for (theme in listOf(AppTheme.HARBOR, AppTheme.MAPLE, AppTheme.LUPINE, AppTheme.AURORA, AppTheme.LIVE_SKY, AppTheme.TEMPERATURE)) {
            for (dark in listOf(false, true)) {
                current = theme to dark
                save("theme_${theme.name.lowercase()}_${if (dark) "dark" else "light"}")
            }
        }
        current = AppTheme.EVERGREEN to false
        showPicker = true
        save("themes_screen")
    }

    @Composable
    private fun Backdrop() {
        val b = app.northvane.weather.ui.theme.LocalBackdrop.current
        Box(Modifier.fillMaxSize().background(b.brush))
    }

    @Test
    @Config(qualifiers = "w400dp-h2600dp-xxhdpi")
    fun allergiesEditor() {
        val container = testContainer
        render(dark = false) { AllergiesScreen(state(), container, onBack = {}) }
        save("allergies_editor")
    }

    @Test
    fun locationsWithNicknames() {
        val container = testContainer
        val vm = MainViewModel(container)
        render(dark = false) { LocationsScreen(state(), vm, container, onBack = {}) }
        compose.onAllNodesWithContentDescription("Options for Mom's")[0].performClick()
        save("locations_menu")
    }

    @Test
    @Config(qualifiers = "w400dp-h3200dp-xxhdpi")
    fun dataSourcesPollen() {
        val container = testContainer
        render(dark = false) { DataSourcesScreen(state(), container, onBack = {}, onSourcesChanged = {}) }
        save("data_sources")
    }

    @Test
    @Config(qualifiers = "w400dp-h3400dp-xxhdpi")
    fun noreasterOutageDark() {
        val storm = withNorEaster(snapshot())
        render(dark = true) { HomeScreen(state(storm), noActions, {}, {}, {}) }
        val name = app.northvane.core.time.TextStyle.SHORT.let { storm.daily[2].date.dayOfWeek.getDisplayName(it) }
        compose.onAllNodesWithText(name)[0].performClick()
        save("today_noreaster_dark")
        compose.onAllNodesWithText("Tap for how to prepare")[0].performClick()
        save("today_noreaster_prepare")
    }

    @Test
    fun todayDarkWithLocalStation() {
        val base = snapshot()
        val local = base.current!!.copy(
            source = DataSource.WEEWX, stationName = "Backyard", temperatureC = 11.4, feelsLikeC = 10.2,
            humidityPercent = 71.0, windSpeedKph = 9.0, windGustKph = 24.0, windDirectionDeg = 290.0,
            observedAt = kotlin.time.Clock.System.now().minusSeconds(90), supplementedFrom = DataSource.NWS,
        )
        render(dark = true) { HomeScreen(state(base.copy(current = local)), noActions, {}, {}, {}) }
        save("today_dark_weewx")
    }

    @Test
    fun todaySatelliteMetric() {
        val settings = AppSettings(
            units = UnitSystem.METRIC,
            dataSaver = DataSaverMode.AUTO,
            satelliteFeatures = SatelliteFeature.DEFAULTS - SatelliteFeature.HOURLY_FORECAST,
        )
        val lite = snapshot().copy(hourly = emptyList(), liteMode = true, skippedSections = setOf(Section.HOURLY_FORECAST))
        render(dark = false) {
            HomeScreen(state(lite, settings, NetworkStatus(satellite = true, constrained = true), liteMode = true), noActions, {}, {}, {})
        }
        save("today_satellite_metric")
    }

    /** Android says "restricted" without saying whether that's Data Saver or Background data switched off. */
    @Test
    fun todayRestrictedData() {
        val settings = AppSettings(dataSaver = DataSaverMode.AUTO)
        render(dark = false) {
            HomeScreen(state(snapshot(), settings, NetworkStatus(metered = true, restricted = true), liteMode = true), noActions, {}, {}, {})
        }
        save("today_restricted_data")
    }

    @Test
    fun onboarding() {
        render(dark = false) { HomeScreen(MainUiState(ready = true), noActions, {}, {}, {}) }
        save("onboarding")
    }

    @Test
    fun alertsDark() {
        val withAlerts = snapshot().copy(alerts = NwsParsers.parseAlerts(resource("nws_alerts_tx.json")))
        render(dark = true) { AlertsScreen(state(withAlerts), onRefresh = {}, onOpenSettings = {}) }
        save("alerts_dark")
    }

    @Test
    fun radarPausedOnSatellite() {
        val container = testContainer
        render(dark = false) {
            RadarScreen(state(liteMode = true, network = NetworkStatus(satellite = true)), container, onOpenSettings = {})
        }
        save("radar_satellite")
    }

    @Test
    @Config(qualifiers = "w400dp-h1600dp-xxhdpi")
    fun settingsPages() {
        val container = testContainer
        var page by androidx.compose.runtime.mutableStateOf<SettingsPage?>(null)
        val st = state()
        compose.setAppContent {
            NorthvaneTheme(darkTheme = false, dynamicColor = false) {
                Box {
                    Backdrop()
                    SettingsScreen(st, container, onBack = {}, nav = SettingsNav(), page = page)
                }
            }
        }
        save("settings_index")
        for (p in listOf(SettingsPage.SATELLITE, SettingsPage.OUTLOOK, SettingsPage.TODAY, SettingsPage.AI, SettingsPage.ALERTS)) {
            page = p
            save("settings_${p.route}")
        }
        page = SettingsPage.SATELLITE
        compose.onAllNodesWithText("Forecast & alerts")[0].performClick()
        save("settings_satellite_expanded")
    }

    /** Demo: a nor'easter in the hourly forecast, wildfire smoke, and the water card. */
    private fun withStormSmokeAndWater(s: WeatherSnapshot): WeatherSnapshot {
        val now = kotlin.time.Clock.System.now()
        // The recorded forecast is from late October: start its hours now so the storm is ahead.
        val start = now.truncatedTo(app.northvane.core.time.ChronoUnit.HOURS)
        val hourly = s.hourly.mapIndexed { i, h0 ->
            val h = h0.copy(time = start.plusSeconds(i * 3600L))
            when (i) {
                in 8..15 -> h.copy(condition = app.northvane.core.model.WeatherCondition.SNOW, precipitationChance = 95, precipitationMm = 2.5, snowMm = 25.0)
                in 16..20 -> h.copy(condition = app.northvane.core.model.WeatherCondition.SNOW, precipitationChance = 80, precipitationMm = 1.0, snowMm = 8.0)
                else -> h
            }
        }
        val smoke = app.northvane.core.radar.SmokeOutlook(
            now, now.minusSeconds(4 * 3600),
            listOf(3L, 9L, 15L, 24L).zip(listOf(14.0, 42.0, 28.0, 8.0)).map { (h, v) -> app.northvane.core.radar.SmokeHour(now.plusSeconds(h * 3600), v) },
        )
        val water = app.northvane.core.water.WaterReport(
            now,
            app.northvane.core.water.RiverGauge("USGS-01064140", "Presumpscot River near West Falmouth, Maine", 8.0, 448.0, 2.75, 7.5, now.minusSeconds(900), 0.6),
            app.northvane.core.water.TideOutlook(
                "8418150", "Portland", 1.3,
                listOf(2L, 8L, 14L, 20L).mapIndexed { i, h -> app.northvane.core.water.TideEvent(now.plusSeconds(h * 3600), i % 2 == 0, if (i % 2 == 0) 2.9 else 0.1) },
                waterTempC = 11.5,
            ),
        )
        return s.copy(hourly = hourly, smoke = smoke, water = water)
    }

    @Test
    @Config(qualifiers = "w400dp-h2600dp-xxhdpi")
    fun stormSmokeAndWater() {
        val st = state(withStormSmokeAndWater(snapshot()))
        val settings = st.settings.copy(stormEnabled = true, smokeEnabled = true, waterEnabled = true)
        render(dark = false) { HomeScreen(st.copy(settings = settings), noActions, {}, {}, {}) }
        save("today_storm_smoke")
    }

    @Test
    @Config(qualifiers = "w400dp-h3200dp-xxhdpi")
    fun outlookWithWater() {
        val st = state(withStormSmokeAndWater(snapshot()))
        render(dark = false) { OutlookScreen(st.copy(settings = st.settings.copy(waterEnabled = true)), {}, {}, {}, {}) }
        save("outlook_water")
    }

    /** Every card the app can show reaches the AI's question context. */
    @Test
    fun questionsCarryEverythingTheAppKnows() {
        val snap = withStormSmokeAndWater(snapshot())
        val settings = state(snap).settings.copy(laundryOutSince = null)
        val now = kotlin.time.Clock.System.now()
        val extras = app.northvane.weather.ui.AiContext.extras(snap, settings, null, emptyList(), snap.latitude ?: location.latitude, now)
        val q = app.northvane.core.ai.AiPrompts.question("How muddy are the trails?", snap, "Portland, ME", settings.units, now, location.longitude, extras)
        File("build/outputs/ai_question_context.txt").apply { parentFile.mkdirs() }.writeText(q.system + "\n\n" + q.user)
        listOf("Hourly", "Daily:", "Storm:", "Wildfire smoke", "River:", "Tides", "Air quality now", "UV index", "Pollen:", "Sun today",
            "Clothes drying", "Fall foliage", "headline")
            .forEach { assertTrue(it in q.user, "missing \"$it\"") }
        assertTrue("trail mud" in q.system && "never say the app doesn't track" in q.system)
    }

    /** The largest system font size: nothing on Today should clip or overlap. */
    @Test
    @Config(qualifiers = "w400dp-h2400dp-xxhdpi", fontScale = 1.6f)
    fun todayLargeFont() {
        render(dark = false) { HomeScreen(state(withStormSmokeAndWater(snapshot())), noActions, {}, {}, {}) }
        save("today_large_font")
    }

    /** The home-screen widget in its three sizes, drawn by Android from its RemoteViews. */
    @Test
    fun homeScreenWidget() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val st = state(withStormSmokeAndWater(snapshot()))
        val dir = File("build/outputs/screenshots").apply { mkdirs() }
        listOf(Triple("widget_small", 130, 60), Triple("widget", 250, 140), Triple("widget_large", 320, 220)).forEach { (name, wDp, hDp) ->
            val views = app.northvane.weather.widget.WeatherWidget.render(context, wDp, hDp, location, st.snapshot, st.settings)
            val view = views.apply(context, android.widget.FrameLayout(context))
            val density = context.resources.displayMetrics.density
            val w = (wDp * density).toInt()
            val h = (hDp * density).toInt()
            view.measure(android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(h, android.view.View.MeasureSpec.EXACTLY))
            view.layout(0, 0, w, h)
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        assertEquals(app.northvane.weather.widget.WeatherWidget.Companion.Size.SMALL, app.northvane.weather.widget.WeatherWidget.sizeFor(110, 60))
        assertEquals(app.northvane.weather.widget.WeatherWidget.Companion.Size.LARGE, app.northvane.weather.widget.WeatherWidget.sizeFor(320, 220))
    }

    /** Demo only: a cold snap on the second day with black ice that morning and a hard freeze that night. */
    private fun withColdSnap(s: WeatherSnapshot): WeatherSnapshot {
        val days = s.daily.toMutableList()
        val zone = app.northvane.core.time.ZoneId.of(s.timeZoneId)
        days[1] = days[1].copy(
            highC = Units.fToC(17.0), lowC = Units.fToC(-2.0),
            pipeRisk = app.northvane.core.model.PipeRisk(
                app.northvane.core.model.OutageLevel.VERY_HIGH, Units.fToC(-2.0), 15,
                listOf(
                    app.northvane.core.model.PipeFactor(app.northvane.core.model.PipeFactor.Kind.LOW, Units.fToC(-2.0)),
                    app.northvane.core.model.PipeFactor(app.northvane.core.model.PipeFactor.Kind.NO_THAW, Units.fToC(17.0)),
                    app.northvane.core.model.PipeFactor(app.northvane.core.model.PipeFactor.Kind.LONG_COLD, 15.0),
                ),
            ),
            morningIce = app.northvane.core.model.MorningIce(
                frost = null, blackIce = app.northvane.core.model.Likelihood.LIKELY,
                reasons = listOf(app.northvane.core.model.MorningIce.Reason.RAIN_THEN_FREEZE),
                lowC = Units.fToC(24.0), coldestAt = days[1].date.atTime(6, 0).atZone(zone).toInstant(),
            ),
        )
        return s.copy(daily = days)
    }

    @Test
    @Config(qualifiers = "w400dp-h2400dp-xxhdpi")
    fun coldSnapPipesAndIceDark() {
        val snap = withColdSnap(snapshot())
        val st = state(snap)
        render(dark = true) { HomeScreen(st.copy(settings = st.settings.copy(pipeRiskEnabled = true, morningIceEnabled = true)), noActions, {}, {}, {}) }
        val name = app.northvane.core.time.TextStyle.SHORT.let { snap.daily[1].date.dayOfWeek.getDisplayName(it) }
        compose.onAllNodesWithText(name)[0].performClick()
        compose.onAllNodesWithText("Tap for how to prepare").let { it[it.fetchSemanticsNodes().size - 1] }.performClick()
        save("today_cold_snap_dark")
    }

    @Test
    @Config(qualifiers = "w400dp-h900dp-xxhdpi")
    fun auroraCard() {
        val snap = snapshot()
        val zone = app.northvane.core.time.ZoneId.of(snap.timeZoneId)
        val now = kotlin.time.Clock.System.now()
        // A G3 storm peaking after dark tonight.
        val start = app.northvane.core.time.LocalDate.now(zone).atStartOfDay(app.northvane.core.time.ZoneOffset.UTC).toInstant()
        val kps = listOf(2.0, 2.33, 3.0, 4.0, 4.67, 5.33, 6.67, 7.33, 6.0, 5.0, 4.0, 3.33, 3.0, 2.67, 2.33, 2.0, 4.33, 5.0, 3.0, 2.0, 2.0, 2.0, 2.0, 2.0)
        val kp = app.northvane.core.model.KpForecast(
            kps.mapIndexed { i, k -> app.northvane.core.model.KpPoint(start.plus(app.northvane.core.time.Duration.ofHours(3L * i)), k, app.northvane.core.model.KpPoint.Kind.PREDICTED) },
            now,
        )
        val evening = app.northvane.core.time.LocalDate.now(zone).atTime(15, 0).atZone(zone).toInstant()
        val report = app.northvane.core.aurora.AuroraAdvisor.report(kp, location.latitude, location.longitude, snap.hourly, zone, evening)
        val time = app.northvane.weather.ui.TimeFormat(zone, false)
        render(dark = true) { androidx.compose.foundation.layout.Column(Modifier.padding(16.dp)) { app.northvane.weather.ui.home.AuroraSection(report, time) } }
        save("aurora_card")
    }

    @Test
    @Config(qualifiers = "w400dp-h1400dp-xxhdpi")
    fun sapAndBurnCards() {
        val snap = snapshot()
        val zone = app.northvane.core.time.ZoneId.of(snap.timeZoneId)
        val time = app.northvane.weather.ui.TimeFormat(zone, false)
        val today = app.northvane.core.time.LocalDate.now(zone)
        // A late-winter week for the sap card; the real forecast for burn days.
        val march = listOf(24.0 to 43.0, 27.0 to 46.0, 33.0 to 41.0, 18.0 to 30.0, 22.0 to 40.0, 35.0 to 52.0, 38.0 to 55.0)
        val sap = app.northvane.core.model.SapReport(march.mapIndexed { i, (low, high) ->
            val (rating, score) = app.northvane.core.outlook.SapAdvisor.rate(Units.fToC(low), Units.fToC(high), skyCover = 30)
            app.northvane.core.model.SapDay(today.plusDays(i.toLong()), rating, score, Units.fToC(low), Units.fToC(high))
        })
        val burn = app.northvane.core.outlook.BurnAdvisor.report(snap.daily, snap.history, snap.alerts, today, zone)
        val f = app.northvane.core.util.UnitFormatter(UnitSystem.IMPERIAL)
        render(dark = false) {
            androidx.compose.foundation.layout.Column(Modifier.padding(16.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp)) {
                app.northvane.weather.ui.home.SapSection(sap, f, time)
                app.northvane.weather.ui.home.BurnSection(burn, f, time)
            }
        }
        save("sap_burn_cards")
    }

    /** Dark theme: group headings and card text must stay light on the dark backdrop. */
    @Test
    @Config(qualifiers = "w400dp-h2200dp-xxhdpi")
    fun outlookDark() {
        val st = state()
        render(dark = true) { OutlookScreen(st.copy(settings = st.settings.copy(dryingEnabled = true, burnEnabled = true)), {}, {}, {}, {}) }
        save("outlook_dark")
    }

    @Test
    @Config(qualifiers = "w400dp-h3000dp-xxhdpi")
    fun outlookTab() {
        val st = state()
        val settings = st.settings.copy(dryingEnabled = true)
        render(dark = false) { OutlookScreen(st.copy(settings = settings), {}, {}, {}, {}) }
        save("outlook_tab")
    }

    @Test
    @Config(qualifiers = "w400dp-h900dp-xxhdpi")
    fun aiCards() {
        val now = kotlin.time.Clock.System.now()
        val ai = AiUiState(
            office = "GYX",
            discussion = AiTask(
                AiReply(
                    "HEADLINE: Soaking rain tonight, 1.5 to 2 inches near the coast\n" +
                        "- Thunder possible this evening\n- Cooler and drier by Sunday afternoon\n- Snow only on the highest peaks\n" +
                        "CONFIDENCE: medium | front position still uncertain",
                    "claude-opus-5-5", now.minusSeconds(900), "afd-1", now.minusSeconds(3 * 3600),
                ),
            ),
            question = "Can I paint the deck Saturday afternoon?",
            answer = AiTask(
                AiReply(
                    "Not Saturday: showers most of the afternoon. Monday after 1 PM looks dry and 54°F.",
                    "claude-opus-5-5", now.minusSeconds(30), "question",
                ),
            ),
        )
        render(dark = false) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val time = TimeFormat(ZoneId.of("America/New_York"), false)
                ForecastersNotesCard(ai, time, onRefresh = {})
                AskCard(ai, "Portland, ME", onAsk = {}, onClear = {})
            }
        }
        compose.onNodeWithText("Forecasters' notes", substring = true).assertExists()
        compose.onNodeWithText("Original").assertExists()
        save("today_ai_cards")
    }

    @Test
    @Config(qualifiers = "w400dp-h1000dp-xxhdpi")
    fun alertExplained() {
        val withAlerts = snapshot().copy(alerts = NwsParsers.parseAlerts(resource("nws_alerts_tx.json")))
        val alert = withAlerts.alerts.first()
        val st = state(withAlerts).let {
            it.copy(
                settings = it.settings.copy(aiEnabled = true, aiProvider = AiProvider.CLAUDE, aiModel = "claude-opus-5-5"),
                ai = AiUiState(alerts = mapOf(alert.id to AiTask(AiReply(
                    "HEADLINE: Flooding possible from Thursday 11 AM to Friday afternoon\n- Avoid low spots and flooded roads\n- Turn around if water covers the road",
                    "claude-opus-5-5", kotlin.time.Clock.System.now(), alert.id,
                )))),
            )
        }
        render(dark = false) { AlertsScreen(st, onRefresh = {}, onOpenSettings = {}) }
        compose.onAllNodesWithText(alert.event)[0].performClick()
        compose.onNodeWithText("Flooding possible", substring = true).assertExists()
        save("alert_explained")
    }

    @Test
    fun noExplainButtonWithoutAi() {
        val withAlerts = snapshot().copy(alerts = NwsParsers.parseAlerts(resource("nws_alerts_tx.json")))
        val alert = withAlerts.alerts.first()
        render(dark = false) { AlertsScreen(state(withAlerts), onRefresh = {}, onOpenSettings = {}) }
        compose.onAllNodesWithText(alert.event)[0].performClick()
        compose.onNodeWithText("Explain in plain words", substring = true).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w400dp-h1300dp-xxhdpi")
    fun staleDataHiddenOnSatellite() {
        val now = kotlin.time.Clock.System.now()
        val old = now.minusSeconds(20 * 3600)
        val snap = snapshot().copy(
            sectionTimes = mapOf(
                Section.CURRENT_CONDITIONS to now, Section.ALERTS to now, Section.DAILY_FORECAST to now,
                Section.HOURLY_FORECAST to old, Section.AIR_QUALITY to old,
            ),
        )
        val settings = AppSettings(units = UnitSystem.IMPERIAL, satelliteFeatures = SatelliteFeature.DEFAULTS - SatelliteFeature.HOURLY_FORECAST)
        render(dark = false) {
            HomeScreen(state(snap, settings, NetworkStatus(satellite = true, constrained = true), liteMode = true), noActions, {}, {}, {})
        }
        save("today_satellite_stale_hidden")
    }

    @Test
    @Config(qualifiers = "w400dp-h1500dp-xxhdpi")
    fun canadianForecast() {
        val f = EcccClient.parse(resource("eccc_nb29.json"), Instant.parse("2026-10-29T20:10:00Z"))
        val snap = WeatherSnapshot(
            locationId = "fr", fetchedAt = kotlin.time.Clock.System.now(), timeZoneId = f.zone.id, placeName = f.placeName,
            current = f.current, hourly = f.hourly, daily = f.daily, alerts = f.alerts, forecastSource = DataSource.ECCC,
            sunrise = f.sunrise, sunset = f.sunset,
        )
        val fred = SavedLocation("fr", "Fredericton, NB", 45.96, -66.64)
        val st = state(snap, AppSettings(units = UnitSystem.METRIC)).copy(selected = fred, locations = listOf(fred))
        render(dark = false) { HomeScreen(st, noActions, {}, {}, {}) }
        save("today_canada_metric")
    }
}
