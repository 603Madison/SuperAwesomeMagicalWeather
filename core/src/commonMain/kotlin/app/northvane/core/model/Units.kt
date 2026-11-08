package app.northvane.core.model

import kotlinx.serialization.Serializable
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

@Serializable
enum class UnitSystem { IMPERIAL, METRIC }

/** Unit conversions. Data is stored in metric (°C, km/h, hPa, mm, km) internally. */
object Units {
    fun fToC(f: Double) = (f - 32.0) * 5.0 / 9.0
    fun cToF(c: Double) = c * 9.0 / 5.0 + 32.0
    fun mphToKph(mph: Double) = mph * 1.609344
    fun kphToMph(kph: Double) = kph / 1.609344
    fun msToKph(ms: Double) = ms * 3.6
    fun knotsToKph(kn: Double) = kn * 1.852
    fun inHgToHpa(inHg: Double) = inHg * 33.8638866667
    fun hpaToInHg(hpa: Double) = hpa / 33.8638866667
    fun inchesToMm(inches: Double) = inches * 25.4
    fun mmToInches(mm: Double) = mm / 25.4
    fun milesToKm(mi: Double) = mi * 1.609344
    fun kmToMiles(km: Double) = km / 1.609344

    private val COMPASS = listOf(
        "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
        "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW",
    )

    fun compass(degrees: Double): String {
        val normalized = ((degrees % 360.0) + 360.0) % 360.0
        return COMPASS[((normalized / 22.5).roundToInt()) % 16]
    }

    fun compassToDegrees(text: String?): Double? {
        val index = COMPASS.indexOf(text?.trim()?.uppercase() ?: return null)
        return if (index < 0) null else index * 22.5
    }

    /** Magnus-formula dew point. */
    fun dewpointC(tempC: Double, humidityPercent: Double): Double? {
        if (humidityPercent <= 0.0 || humidityPercent > 100.0) return null
        val a = 17.625
        val b = 243.04
        val gamma = ln(humidityPercent / 100.0) + a * tempC / (b + tempC)
        return b * gamma / (a - gamma)
    }

    /**
     * Apparent temperature using the NWS heat index (warm) and wind chill (cold)
     * formulas, matching what api.weather.gov reports for observations.
     */
    fun feelsLikeC(tempC: Double, humidityPercent: Double?, windKph: Double?): Double {
        val tempF = cToF(tempC)
        val windMph = windKph?.let(::kphToMph) ?: 0.0
        if (tempF <= 50.0 && windMph > 3.0) {
            val v = windMph.pow(0.16)
            return fToC(35.74 + 0.6215 * tempF - 35.75 * v + 0.4275 * tempF * v)
        }
        if (tempF >= 80.0 && humidityPercent != null) {
            val rh = humidityPercent
            var hi = -42.379 + 2.04901523 * tempF + 10.14333127 * rh - 0.22475541 * tempF * rh -
                0.00683783 * tempF * tempF - 0.05481717 * rh * rh + 0.00122874 * tempF * tempF * rh +
                0.00085282 * tempF * rh * rh - 0.00000199 * tempF * tempF * rh * rh
            if (rh < 13.0 && tempF in 80.0..112.0) {
                hi -= ((13.0 - rh) / 4.0) * sqrt((17.0 - kotlin.math.abs(tempF - 95.0)) / 17.0)
            } else if (rh > 85.0 && tempF in 80.0..87.0) {
                hi += ((rh - 85.0) / 10.0) * ((87.0 - tempF) / 5.0)
            }
            return fToC(hi)
        }
        return tempC
    }

    /** Relative humidity from temperature and dew point (inverse Magnus). */
    fun humidityPercent(tempC: Double, dewpointC: Double): Double {
        val a = 17.625
        val b = 243.04
        return (100.0 * exp(a * dewpointC / (b + dewpointC)) / exp(a * tempC / (b + tempC)))
            .coerceIn(0.0, 100.0)
    }
}
