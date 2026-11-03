package app.northvane.mini

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import app.northvane.mini.ui.KeyHub
import app.northvane.mini.ui.MiniRoot

/**
 * One activity. Keys the screen didn't use arrive here: the left soft key
 * (KEYCODE_SOFT_LEFT, or MENU on many phones) runs the screen's left action,
 * the right soft key goes back, and number, * and # keys go to the screen
 * (a focused text box keeps its digits). The d-pad moves focus through
 * Compose, and Back goes through the back dispatcher (predictive back).
 */
class MainActivity : ComponentActivity() {
    private val vm: MiniViewModel by viewModels()
    private val hub = KeyHub()
    /** Keys whose press we handled, so their release doesn't reach anything else. */
    private val consumed = mutableSetOf<Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val openAlerts = intent?.getBooleanExtra(EXTRA_OPEN_ALERTS, false) == true
        setContent { MiniRoot(vm, openAlerts, onExit = ::finish, hub = hub) }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.repeatCount == 0) {
            val handled = when (keyCode) {
                KeyEvent.KEYCODE_SOFT_LEFT, KeyEvent.KEYCODE_MENU -> hub.keys.left?.let { it.run(); true } ?: false
                KeyEvent.KEYCODE_SOFT_RIGHT -> { hub.back(); true }
                else -> hub.keys.onKey(keyCode)
            }
            if (handled) {
                consumed += keyCode
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        consumed.remove(keyCode) || super.onKeyUp(keyCode, event)

    companion object {
        const val EXTRA_OPEN_ALERTS = "open_alerts"
    }
}
