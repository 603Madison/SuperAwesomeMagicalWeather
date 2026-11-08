package app.northvane.weather

import androidx.compose.ui.graphics.ImageBitmap
import app.northvane.core.model.GeoPoint
import app.northvane.core.net.HttpEngine
import app.northvane.core.net.LocalServerClients
import app.northvane.core.owm.OwmClient
import app.northvane.core.owm.PlaceResult
import app.northvane.core.pollen.AndroidKeyRestriction
import app.northvane.weather.data.SettingsRepository
import app.northvane.weather.net.Connectivity

/**
 * What the shared app needs from the phone it runs on. Android and iOS each
 * provide one; everything else (screens, settings, caches, the forecast
 * engine) is the same code on both.
 */
interface Platform {
    /** "Android" or "iOS", for the User-Agent and a few labels. */
    val name: String
    val appVersion: String

    /** Application ID / bundle identifier. */
    val packageName: String
    val http: HttpEngine
    val localServers: LocalServerClients

    /** Where settings live (Android: filesDir, so existing DataStore files are kept). */
    val settingsDir: String

    /** App-private files that are never backed up (Android: noBackupFilesDir). */
    val privateDir: String

    val secrets: SecretStore
    val connectivity: Connectivity
    val deviceLocation: DeviceLocation
    val themeBackground: ThemeBackground
    val alertScheduler: AlertScheduler

    fun placeSearch(owm: OwmClient, secrets: SecretStore, settings: SettingsRepository): PlaceSearch

    /** This build's signing identity, for API keys restricted to the app (Google Pollen on Android). */
    fun pollenRestriction(): AndroidKeyRestriction? = null

    /** Whether the OS blocks talking to [url] on the local network until the user allows it. */
    suspend fun localNetworkBlocked(url: String): Boolean = false

    /** What to tell the user when [localNetworkBlocked]. */
    val localNetworkDeniedMessage: String get() = "Allow this app to use your local network to reach your own server."

    /** Something changed that the home-screen widgets show. */
    fun onWeatherUpdated() {}

    val notifications: Notifications

    /** (Re)schedules the reminder notifications (briefing, tonight, rain soon, northern lights) for [settings]. */
    fun syncReminders(settings: app.northvane.weather.data.AppSettings) {}

    /** Starts or stops watching for rain on laundry that's out. */
    fun laundryWatch(out: Boolean) {}

    /** The signing certificate's SHA-1, shown when setting up a key restricted to this app. */
    fun signingSha1(): String? = null

    /** Whether times show as 13:00 rather than 1 PM. */
    fun is24HourClock(): Boolean = false
}

/** Local notifications. */
interface Notifications {
    fun canNotify(): Boolean

    /** Sends a sample alert notification; false if notifications are off. */
    fun sendTest(locationId: String?, place: String?): Boolean
}

/** API keys and passwords, encrypted by the platform (Android Keystore, iOS Keychain). */
interface SecretStore {
    fun get(name: String): String?
    fun has(name: String): Boolean
    fun put(name: String, value: String?)
    fun remove(name: String) = put(name, null)

    companion object {
        const val OWM_API_KEY = "owm_api_key"
        const val POLLEN_API_KEY = "google_pollen_api_key"
        const val AIRNOW_API_KEY = "airnow_api_key"

        /** Keys for features that were removed (traffic, 2.0); deleted at startup. */
        val RETIRED = listOf("ne511_api_key", "tomtom_api_key")
        const val AI_API_KEY = "ai_api_key"
        const val CLAUDE_API_KEY = "claude_api_key"

        /** Each AI provider keeps its own key, so switching between them doesn't lose one. */
        fun aiKeyName(provider: app.northvane.core.ai.AiProvider): String =
            if (provider == app.northvane.core.ai.AiProvider.CLAUDE) CLAUDE_API_KEY else AI_API_KEY
        fun weewxSecret(stationId: String) = "weewx_secret_$stationId"
    }
}

/** The phone's position, when the user allowed it. */
interface DeviceLocation {
    fun hasPermission(): Boolean
    fun hasPrecise(): Boolean = hasPermission()
    suspend fun current(): GeoPoint?
}

/** Typed place names to coordinates, and coordinates to a ZIP code. */
interface PlaceSearch {
    suspend fun search(query: String): List<PlaceResult>
    suspend fun postalCode(latitude: Double, longitude: Double): String?
}

/** The user's own photo behind the custom theme. */
interface ThemeBackground {
    suspend fun load(): ImageBitmap?
    /** Stores [bytes] (an image file) scaled for the screen; false if it isn't a readable image. */
    suspend fun save(bytes: ByteArray): Boolean
    fun clear()
}

/** Background alert checks. */
interface AlertScheduler {
    suspend fun sync()
    fun checkNow()
}
