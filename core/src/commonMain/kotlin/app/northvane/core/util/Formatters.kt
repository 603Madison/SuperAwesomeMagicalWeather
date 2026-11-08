package app.northvane.core.util

import app.northvane.core.model.UnitSystem
import app.northvane.core.model.Units
import kotlin.math.roundToInt
import app.northvane.core.util.fmt

/** Display formatting in the user's chosen unit system. Inputs are metric. */
class UnitFormatter(val system: UnitSystem) {
    fun temperature(c: Double?): String = c?.let {
        val v = if (system == UnitSystem.IMPERIAL) Units.cToF(it) else it
        "${v.roundToInt()}°"
    } ?: "--"

    fun temperatureWithUnit(c: Double?): String =
        if (c == null) "--" else temperature(c) + if (system == UnitSystem.IMPERIAL) "F" else "C"

    fun windSpeed(kph: Double?): String = kph?.let {
        if (system == UnitSystem.IMPERIAL) "${Units.kphToMph(it).roundToInt()} mph" else "${it.roundToInt()} km/h"
    } ?: "--"

    fun wind(kph: Double?, directionDeg: Double?): String {
        if (kph == null) return "--"
        if (kph < 1.0) return "Calm"
        val dir = directionDeg?.let { Units.compass(it) + " " }.orEmpty()
        return dir + windSpeed(kph)
    }

    fun pressure(hpa: Double?): String = hpa?.let {
        if (system == UnitSystem.IMPERIAL) "%.2f inHg".fmt(Units.hpaToInHg(it)) else "${it.roundToInt()} hPa"
    } ?: "--"

    fun visibility(km: Double?): String = km?.let {
        if (system == UnitSystem.IMPERIAL) {
            val mi = Units.kmToMiles(it)
            if (mi >= 10) "10+ mi" else "%.1f mi".fmt(mi)
        } else {
            if (it >= 16) "16+ km" else "%.1f km".fmt(it)
        }
    } ?: "--"

    fun precipitation(mm: Double?): String = mm?.let {
        if (system == UnitSystem.IMPERIAL) "%.2f in".fmt(Units.mmToInches(it)) else "%.1f mm".fmt(it)
    } ?: "--"

    fun percent(value: Double?): String = value?.let { "${it.roundToInt()}%" } ?: "--"

    /** A distance between places: whole miles or kilometres. */
    fun distance(km: Double?): String = km?.let {
        if (system == UnitSystem.IMPERIAL) "${(it * 0.621371).roundToInt().coerceAtLeast(1)} mi" else "${it.roundToInt().coerceAtLeast(1)} km"
    } ?: "--"

    /** A water level (tides, river gauges), from metres. */
    fun height(m: Double?): String = m?.let {
        if (system == UnitSystem.IMPERIAL) "%.1f ft".fmt(it * 3.28084) else "%.1f m".fmt(it)
    } ?: "--"

    /** River flow, from cubic feet per second (USGS's unit). */
    fun discharge(cfs: Double?): String = cfs?.let {
        if (system == UnitSystem.IMPERIAL) "${it.roundToInt()} ft³/s" else "%.1f m³/s".fmt(it * 0.0283168)
    } ?: "--"

    /** Speed as a bare number in the user's unit (for compact hourly cells). */
    fun windNumber(kph: Double?): String = kph?.let {
        (if (system == UnitSystem.IMPERIAL) Units.kphToMph(it) else it).roundToInt().toString()
    } ?: "--"

    val windUnit: String get() = if (system == UnitSystem.IMPERIAL) "mph" else "km/h"

    /** Snow depth: inches, or centimetres in metric. */
    fun snow(mm: Double?): String = mm?.let {
        if (system == UnitSystem.IMPERIAL) "%.1f in".fmt(Units.mmToInches(it)) else "%.1f cm".fmt(it / 10.0)
    } ?: "--"

    /** Compact rain amount for hourly cells: "0.12" in or "3.1" mm, or empty when negligible. */
    fun precipitationShort(mm: Double?): String {
        if (mm == null || mm < 0.05) return ""
        return if (system == UnitSystem.IMPERIAL) "%.2f\"".fmt(Units.mmToInches(mm)) else "%.1f mm".fmt(mm)
    }
}
