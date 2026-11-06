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
        container = AppContainer(this)
        container.connectivity.start()
        AlertNotifier.createChannels(this)
        app.northvane.weather.work.LaundryWatchWorker.createChannel(this)
        AuroraWatchWorker.createChannel(this)
        container.applicationScope.launch {
            container.alertScheduler.sync()
            AuroraWatchWorker.sync(this@NorthvaneApp, container.settings.settings.first())
            val settings = container.settings.settings.first()
            app.northvane.weather.work.BriefingWorker.schedule(this@NorthvaneApp, settings)
            app.northvane.weather.work.RainSoonWorker.sync(this@NorthvaneApp, settings)
            app.northvane.weather.widget.WidgetRefreshWorker.sync(this@NorthvaneApp)
        }
    }
}
