package app.northvane.weather.net

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import app.northvane.core.net.UrlPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.InetAddress

/**
 * Android 17 blocks connections to devices on the local network unless the
 * app holds ACCESS_LOCAL_NETWORK ("Nearby devices"). SuperAwesomeMagicalWeather only needs it to
 * reach a WeeWX server on the user's own LAN.
 */
object LocalNetworkAccess {
    // Literal rather than Manifest.permission.ACCESS_LOCAL_NETWORK (API 37); only used when isEnforced().
    const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

    const val DENIED_MESSAGE =
        "Your station is on the local network. Allow \"Nearby devices\" for SuperAwesomeMagicalWeather to use it."

    fun isEnforced(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN

    fun isGranted(context: Context): Boolean =
        !isEnforced() || ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    /**
     * True when [url] points at a LAN address, either by name/literal or because
     * the host resolves to one (split DNS). DNS lookups are allowed without the
     * permission.
     */
    suspend fun targetsLocalNetwork(url: String): Boolean {
        val host = url.trim().toHttpUrlOrNull()?.host ?: return false
        if (UrlPolicy.looksLikeLocalNetworkHost(host)) return true
        if (UrlPolicy.isIpLiteral(host)) return false
        return withContext(Dispatchers.IO) {
            withTimeoutOrNull(3_000) {
                runCatching { InetAddress.getAllByName(host).any(UrlPolicy::isLocalNetworkAddress) }.getOrDefault(false)
            } ?: false
        }
    }

    /** Whether the app must ask before it can reach [url]. */
    suspend fun needsPermissionFor(context: Context, url: String): Boolean =
        isEnforced() && !isGranted(context) && targetsLocalNetwork(url)
}
