package app.northvane.core.json

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

// Small, null-safe accessors for hand-walking API responses. The weather APIs
// used here are large and loosely specified, so parsing only the fields we
// need (and tolerating any of them being absent) is more robust than strict
// data-class decoding.

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
internal fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

internal fun JsonObject.prim(key: String): JsonPrimitive? =
    (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }

internal fun JsonObject.str(key: String): String? = prim(key)?.content
internal fun JsonObject.dbl(key: String): Double? = prim(key)?.doubleOrNull?.takeIf { it.isFinite() }
internal fun JsonObject.int(key: String): Int? = prim(key)?.intOrNull
internal fun JsonObject.long(key: String): Long? = prim(key)?.longOrNull
internal fun JsonObject.bool(key: String): Boolean? = prim(key)?.booleanOrNull

internal fun JsonElement.asObj(): JsonObject? = this as? JsonObject

/** A WMO/NWS quantitative value: `{ "value": 12.3, "unitCode": "wmoUnit:degC" }`. */
internal data class QuantValue(val value: Double, val unit: String) {
    fun toCelsius(): Double? = when (unit) {
        "degC", "Cel" -> value
        "degF", "[degF]" -> (value - 32.0) * 5.0 / 9.0
        "K" -> value - 273.15
        else -> null
    }

    fun toKph(): Double? = when (unit) {
        "km_h-1", "km/h" -> value
        "m_s-1", "m/s" -> value * 3.6
        "mi_h-1", "[mi_i]/h", "mph" -> value * 1.609344
        "kn", "kt", "[kn_i]" -> value * 1.852
        else -> null
    }

    fun toHpa(): Double? = when (unit) {
        "Pa" -> value / 100.0
        "hPa", "mbar" -> value
        else -> null
    }

    fun toKm(): Double? = when (unit) {
        "m" -> value / 1000.0
        "km" -> value
        "mi", "[mi_i]" -> value * 1.609344
        else -> null
    }

    fun toMm(): Double? = when (unit) {
        "mm" -> value
        "m" -> value * 1000.0
        "in", "[in_i]" -> value * 25.4
        else -> null
    }

    fun toPercent(): Double? = if (unit == "percent" || unit == "%") value else null
    fun toDegrees(): Double? = if (unit.startsWith("degree")) value else null
}

internal fun JsonObject.quant(key: String): QuantValue? {
    val o = obj(key) ?: return null
    val value = o.dbl("value") ?: return null
    val unit = o.str("unitCode")?.substringAfter(':') ?: ""
    return QuantValue(value, unit)
}
