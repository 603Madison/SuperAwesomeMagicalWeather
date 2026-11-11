package app.northvane.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import app.northvane.wear.ui.WearRoot

class MainActivity : ComponentActivity() {
    private val vm: WearViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        var openAlert: String? = null
        // Only on a fresh start: after a configuration change the user may have moved on.
        if (savedInstanceState == null) {
            intent?.getStringExtra(EXTRA_PLACE_ID)?.takeIf { it.length <= 64 }?.let { vm.select(it) }
            openAlert = intent?.getStringExtra(EXTRA_ALERT_ID)?.takeIf { it.length <= 512 }
        }
        setContent { WearRoot(vm, openAlert) }
    }

    companion object {
        const val EXTRA_PLACE_ID = "place_id"
        const val EXTRA_ALERT_ID = "alert_id"
    }
}
