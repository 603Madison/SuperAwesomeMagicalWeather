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
        // Data Saver is on whether or not SuperAwesomeMagicalWeather is set to "Unrestricted data".
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
    fun onlyAnExemptAppProvesDataSaverIsOn() {
        // Android reports "restricted" for Data Saver and for a switched-off Background data alike.
        assertTrue(restrictionOf(android.net.ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED) == (true to false))
        assertTrue(restrictionOf(android.net.ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED) == (false to true))
        assertTrue(restrictionOf(android.net.ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED) == (false to false))
        assertTrue(restrictionOf(null) == (false to false))
    }

    @Test
    fun restrictedDataCountsUnlessTheUserSaysOtherwise() {
        val restricted = NetworkStatus(metered = true, restricted = true)
        assertTrue(isLimited(auto, restricted))
        assertFalse(isLimited(auto.copy(followRestrictedData = false), restricted))
        // A certain Data Saver still counts with the switch off.
        assertTrue(isLimited(auto.copy(followRestrictedData = false), NetworkStatus(metered = true, dataSaver = true)))
        assertFalse(isLimited(auto, NetworkStatus(metered = false, restricted = true)))
    }

    @Test
    fun neverMeansNever() {
        assertFalse(isLimited(AppSettings(dataSaver = DataSaverMode.OFF), NetworkStatus(metered = true, dataSaver = true, satellite = true)))
    }
}
