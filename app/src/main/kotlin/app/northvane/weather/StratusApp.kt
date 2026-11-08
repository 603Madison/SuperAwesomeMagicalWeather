package app.northvane.weather

import android.app.Application
import app.northvane.weather.notify.AlertNotifier
import app.northvane.weather.work.AuroraWatchWorker
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class NorthvaneApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        val platform = AndroidPlatform(this) { container.settings }
        container = AppContainer(platform)
        platform.connectivityMonitor.start()
        AlertNotifier.createChannels(this)
        app.northvane.weather.work.LaundryWatchWorker.createChannel(this)
        AuroraWatchWorker.createChannel(this)
        container.applicationScope.launch {
            removeRetiredData()
            container.alertScheduler.sync()
            platform.syncReminders(container.settings.settings.first())
            app.northvane.weather.widget.WidgetRefreshWorker.sync(this@NorthvaneApp)
        }
    }

    /** Keys and caches left by removed features (traffic, before 2.0). */
    private fun removeRetiredData() {
        SecretStore.RETIRED.forEach { name ->
            if (container.secrets.has(name)) container.secrets.remove(name)
        }
        java.io.File(noBackupFilesDir, "traffic").takeIf { it.exists() }?.deleteRecursively()
    }
}
