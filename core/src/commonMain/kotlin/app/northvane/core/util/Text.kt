package app.northvane.core.util

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * printf-style formatting that works on every platform, always with "." as
 * the decimal point: %d, %s, %f with precision (%.2f), zero padding (%02d),
 * hex (%02x, %X), %,d grouping and %%.
 */
fun String.fmt(vararg args: Any?): String {
    val out = StringBuilder()
    var argIndex = 0
    var i = 0
    while (i < length) {
        val c = this[i]
        if (c != '%') { out.append(c); i++; continue }
        i++
        require(i < length) { "Bad format: $this" }
        if (this[i] == '%') { out.append('%'); i++; continue }
        var zero = false
        var group = false
        var left = false
        var plus = false
        while (i < length && this[i] in "0,-+") {
            when (this[i]) { '0' -> zero = true; ',' -> group = true; '-' -> left = true; '+' -> plus = true }
            i++
        }
        var width = 0
        while (i < length && this[i].isDigit()) { width = width * 10 + (this[i] - '0'); i++ }
        var precision = -1
        if (i < length && this[i] == '.') {
            i++
            precision = 0
            while (i < length && this[i].isDigit()) { precision = precision * 10 + (this[i] - '0'); i++ }
        }
        val conv = this[i++]
        val arg = args.getOrNull(argIndex++)
        var text = when (conv) {
            'd' -> {
                val n = (arg as Number).toLong()
                val s = if (group) groupThousands(abs(n).toString()) else abs(n).toString()
                (if (n < 0) "-" else if (plus) "+" else "") + s
            }
            'f' -> {
                val d = (arg as Number).toDouble()
                val s = fixed(d, if (precision < 0) 6 else precision)
                if (plus && !s.startsWith("-")) "+$s" else s
            }
            'x' -> (arg as Number).toLong().let { if (arg is Byte) (it.toInt() and 0xFF).toString(16) else it.toString(16) }
            'X' -> (arg as Number).toLong().let { if (arg is Byte) (it.toInt() and 0xFF).toString(16) else it.toString(16) }.uppercase()
            's' -> arg.toString().let { if (precision >= 0) it.take(precision) else it }
            else -> error("Unsupported conversion %$conv")
        }
        if (text.length < width) {
            text = when {
                left -> text.padEnd(width)
                zero && conv != 's' -> {
                    val sign = if (text.startsWith("-") || text.startsWith("+")) text.take(1) else ""
                    sign + text.drop(sign.length).padStart(width - sign.length, '0')
                }
                else -> text.padStart(width)
            }
        }
        out.append(text)
    }
    return out.toString()
}

/** [value] with exactly [decimals] digits after the point, rounded half up. */
fun fixed(value: Double, decimals: Int): String {
    if (value.isNaN()) return "NaN"
    if (value.isInfinite()) return if (value > 0) "Infinity" else "-Infinity"
    val factor = 10.0.pow(decimals)
    val scaled = (abs(value) * factor).roundToLong()
    val neg = value < 0 && scaled != 0L
    val digits = scaled.toString().padStart(decimals + 1, '0')
    val whole = digits.dropLast(decimals)
    val frac = digits.takeLast(decimals)
    return (if (neg) "-" else "") + whole + if (decimals > 0) ".$frac" else ""
}

private fun groupThousands(s: String): String =
    s.reversed().chunked(3).joinToString(",").reversed()

fun toRadians(deg: Double): Double = deg / 180.0 * PI
fun toDegrees(rad: Double): Double = rad * 180.0 / PI
