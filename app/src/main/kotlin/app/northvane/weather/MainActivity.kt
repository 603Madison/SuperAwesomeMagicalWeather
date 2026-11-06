package app.northvane.weather

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.northvane.weather.ui.MainViewModel
import app.northvane.weather.ui.NavRequest
import app.northvane.weather.ui.NorthvaneRoot
import app.northvane.weather.ui.theme.NorthvaneTheme
import app.northvane.weather.ui.theme.ThemeWeather
import app.northvane.core.model.WeatherCondition
import app.northvane.weather.ui.theme.isAppInDarkTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val navRequests = MutableStateFlow<NavRequest?>(null)
    private var viewModel: MainViewModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Only on a fresh start: after rotation or a theme change the launching intent was already handled.
        if (savedInstanceState == null) handleIntent(intent)
        val container = (application as NorthvaneApp).container

        setContent {
            val vm: MainViewModel = viewModel(factory = MainViewModel.factory(container))
            viewModel = vm
            val state by vm.state.collectAsStateWithLifecycle()
            val dark = isAppInDarkTheme(state.settings.themeMode)

            // Keep status/navigation bar icons legible when the in-app theme differs from the system's.
            LaunchedEffect(dark) {
                val style = if (dark) {
                    SystemBarStyle.dark(Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }

            val settings = state.settings
            val current = state.snapshot?.current
            val weather = ThemeWeather(
                condition = current?.condition ?: state.snapshot?.daily?.firstOrNull()?.condition ?: WeatherCondition.CLEAR,
                isDay = current?.isDaytime ?: true,
                temperatureC = current?.temperatureC ?: state.snapshot?.hourly?.firstOrNull()?.temperatureC,
            )
            NorthvaneTheme(
                darkTheme = dark,
                dynamicColor = settings.dynamicColor,
                theme = settings.appTheme,
                weather = weather,
                customAccent = settings.customAccent,
                customBackground = settings.customBackground,
                customDimPercent = settings.customDimPercent,
            ) {
                NorthvaneRoot(
                    vm = vm,
                    container = container,
                    navRequests = navRequests,
                    onNavRequestHandled = { navRequests.value = null },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel?.onForeground()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        // Only accept a plain location id (letters, digits, dashes) from the intent.
        val locationId = intent?.getStringExtra(EXTRA_LOCATION_ID)
            ?.takeIf { id -> id.length <= 64 && id.all { it.isLetterOrDigit() || it == '-' } }
        when {
            intent?.getBooleanExtra(EXTRA_OPEN_ALERTS, false) == true -> navRequests.value = NavRequest.OpenAlerts(locationId)
            // e.g. an aurora notification: open on the place it was about.
            locationId != null -> navRequests.value = NavRequest.ShowLocation(locationId)
        }
    }

    companion object {
        const val EXTRA_OPEN_ALERTS = "app.northvane.weather.OPEN_ALERTS"
        const val EXTRA_LOCATION_ID = "app.northvane.weather.LOCATION_ID"
    }
}
