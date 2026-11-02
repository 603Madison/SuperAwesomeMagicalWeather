package app.northvane.weather.net

import app.northvane.weather.data.AppSettings
import app.northvane.weather.data.DataSaverMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackwoodsTriggerTest {
    private val auto = AppSettings(dataSaver = DataSaverMode.AUTO)

    @Test
    fun dataSaverOnMobileDataTurnsBackwoodsOn() {
        // Data Saver is on whether or not Northvane is set to "Unrestricted data".
        assertTrue(isLimited(auto, NetworkStatus(metered = true, dataSaver = true)))
    }

    @Test
    fun dataSaverOnUnmeteredWifiLeavesBackwoodsOff() {
        assertFalse(isLimited(auto, NetworkStatus(metered = false, dataSaver = true)))
    }

    @Test
    fun mobileDataWithoutDataSaverLeavesBackwoodsOff() {
        assertFalse(isLimited(auto, NetworkStatus(metered = true)))
    }

    @Test
    fun neverMeansNever() {
        assertFalse(isLimited(AppSettings(dataSaver = DataSaverMode.OFF), NetworkStatus(metered = true, dataSaver = true, satellite = true)))
    }
}
