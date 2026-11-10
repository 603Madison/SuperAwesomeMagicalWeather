package app.northvane.core.util

import kotlin.math.PI
import kotlin.math.abs

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

/**
 * [value] with exactly [decimals] digits after the point, rounded half up on
 * the decimal value as written (its shortest form), the way java's
 * `String.format` and `BigDecimal.valueOf` round: 1.005 → "1.01", not the
 * "1.00" that rounding the binary double gives.
 */
fun fixed(value: Double, decimals: Int): String {
    if (value.isNaN()) return "NaN"
    if (value.isInfinite()) return if (value > 0) "Infinity" else "-Infinity"
    // Digits and the position of the decimal point, from the shortest representation ("1.0E-5" included).
    val text = abs(value).toString()
    val mantissa = text.substringBefore('E').substringBefore('e')
    val exponent = text.substringAfter('E', text.substringAfter('e', "0")).toInt()
    var digits = mantissa.replace(".", "")
    var point = mantissa.substringBefore('.').length + exponent
    if (point < 0) {
        digits = "0".repeat(-point) + digits
        point = 0
    }
    if (digits.length < point) digits = digits.padEnd(point, '0')
    val keep = point + decimals
    var kept = digits.take(keep).padEnd(keep, '0')
    if (digits.length > keep && digits[keep] >= '5') {
        // Add one in the last kept place, carrying as needed.
        val chars = kept.toCharArray()
        var i = chars.lastIndex
        while (i >= 0 && chars[i] == '9') { chars[i] = '0'; i-- }
        if (i >= 0) chars[i] = chars[i] + 1 else { point++ }
        kept = (if (i < 0) "1" else "") + chars.concatToString()
    }
    val whole = kept.take(point).trimStart('0').ifEmpty { "0" }
    val frac = kept.drop(point)
    val neg = value < 0 && kept.any { it != '0' }
    return (if (neg) "-" else "") + whole + if (decimals > 0) ".$frac" else ""
}

private fun groupThousands(s: String): String =
    s.reversed().chunked(3).joinToString(",").reversed()

fun toRadians(deg: Double): Double = deg / 180.0 * PI
fun toDegrees(rad: Double): Double = rad * 180.0 / PI
