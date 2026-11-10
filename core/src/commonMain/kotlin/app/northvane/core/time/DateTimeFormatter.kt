package app.northvane.core.time

import kotlinx.datetime.number
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * java.time-style patterns ("EEE h a", "MMM d", "yyyyMMddHH"), in English, on
 * every platform. Supports y, M, d, E, H, h, m, s, a and 'quoted text'.
 */
class DateTimeFormatter private constructor(private val tokens: List<Token>, private val zone: ZoneId?) {

    fun withZone(zone: ZoneId): DateTimeFormatter = DateTimeFormatter(tokens, zone)

    fun format(value: Instant): String =
        render(value.toLocalDateTime(requireNotNull(zone) { "A zone is needed to format an instant" }))

    fun format(value: ZonedDateTime): String = render(value.toLocalDateTime())
    fun format(value: LocalDateTime): String = render(value)
    fun format(value: LocalDate): String = render(LocalDateTime(value, LocalTime(0, 0)), dateOnly = true)
    fun format(value: LocalTime): String = render(LocalDateTime(LocalDate(2000, 1, 1), value), timeOnly = true)

    private fun render(t: LocalDateTime, dateOnly: Boolean = false, timeOnly: Boolean = false): String = buildString {
        for (tok in tokens) {
            when (tok) {
                is Token.Text -> append(tok.text)
                is Token.Field -> {
                    val n = tok.count
                    if (dateOnly && tok.letter in "Hhmsa") error("No time in a date")
                    if (timeOnly && tok.letter in "yMdE") error("No date in a time")
                    when (tok.letter) {
                        'y', 'u' -> if (n == 2) append(pad(t.year % 100, 2)) else append(pad(t.year, n))
                        'M', 'L' -> when {
                            n >= 4 -> append(t.month.getDisplayName(TextStyle.FULL))
                            n == 3 -> append(t.month.getDisplayName(TextStyle.SHORT))
                            else -> append(pad(t.month.number, n))
                        }
                        'd' -> append(pad(t.day, n))
                        'E' -> append(t.dayOfWeek.getDisplayName(if (n >= 4) TextStyle.FULL else TextStyle.SHORT))
                        'H' -> append(pad(t.hour, n))
                        'h' -> append(pad((t.hour + 11) % 12 + 1, n))
                        'm' -> append(pad(t.minute, n))
                        's' -> append(pad(t.second, n))
                        'a' -> append(if (t.hour < 12) "AM" else "PM")
                        else -> error("Unsupported pattern letter ${tok.letter}")
                    }
                }
            }
        }
    }

    internal fun parseDateTime(text: String): LocalDateTime {
        var pos = 0
        var year = 2000; var month = 1; var day = 1; var hour = 0; var minute = 0; var second = 0
        var pm: Boolean? = null
        var hour12 = false
        tokens.forEachIndexed { i, tok ->
            when (tok) {
                is Token.Text -> {
                    require(text.startsWith(tok.text, pos)) { "Unparseable date: $text" }
                    pos += tok.text.length
                }
                is Token.Field -> {
                    if (tok.letter == 'a') {
                        val s = text.substring(pos).take(2).uppercase()
                        pm = when (s) { "AM" -> false; "PM" -> true; else -> throw IllegalArgumentException("Unparseable date: $text") }
                        pos += 2
                        return@forEachIndexed
                    }
                    if ((tok.letter == 'M' || tok.letter == 'L') && tok.count >= 3) {
                        val names = Month.entries.map { if (tok.count == 3) it.getDisplayName(TextStyle.SHORT) else it.getDisplayName(TextStyle.FULL) }
                        val idx = names.indexOfFirst { text.regionMatches(pos, it, 0, it.length, ignoreCase = true) }
                        require(idx >= 0) { "Unparseable date: $text" }
                        month = idx + 1
                        pos += names[idx].length
                        return@forEachIndexed
                    }
                    if (tok.letter == 'E') {
                        val names = DayOfWeek.entries.map { it.getDisplayName(if (tok.count >= 4) TextStyle.FULL else TextStyle.SHORT) }
                        val idx = names.indexOfFirst { text.regionMatches(pos, it, 0, it.length, ignoreCase = true) }
                        require(idx >= 0) { "Unparseable date: $text" }
                        pos += names[idx].length
                        return@forEachIndexed
                    }
                    // Widths as java.time reads them: fixed when the next token is another number (yyyyMMdd)
                    // or the letter is doubled ("dd", "HH", and "yy" is exactly two digits); one letter
                    // takes one or two digits; a long year as many as there are. So a four-digit year
                    // in a "yy" field is rejected rather than read as 2000 + 2026.
                    val nextIsNumber = (tokens.getOrNull(i + 1) as? Token.Field)?.let { it.letter != 'a' } ?: false
                    val isLongYear = (tok.letter == 'y' || tok.letter == 'u') && tok.count != 2
                    fun digitsFrom(start: Int, max: Int): Int {
                        var e = start
                        while (e < text.length && e - start < max && text[e].isDigit()) e++
                        return e
                    }
                    val end = when {
                        nextIsNumber -> pos + tok.count
                        isLongYear -> digitsFrom(pos, 9).also { require(it - pos >= tok.count) { "Unparseable date: $text" } }
                        tok.count >= 2 -> pos + tok.count
                        else -> digitsFrom(pos, 2)
                    }
                    require(end > pos && end <= text.length) { "Unparseable date: $text" }
                    val v = text.substring(pos, end).toIntOrNull() ?: throw IllegalArgumentException("Unparseable date: $text")
                    pos = end
                    when (tok.letter) {
                        'y', 'u' -> year = if (tok.count == 2) 2000 + v else v
                        'M', 'L' -> month = v
                        'd' -> day = v
                        'H' -> hour = v
                        'h' -> { hour = v % 12; hour12 = true }
                        'm' -> minute = v
                        's' -> second = v
                    }
                }
            }
        }
        require(pos == text.length) { "Unparseable date: $text" }
        if (hour12 && pm == true) hour += 12
        return LocalDateTime(year, month, day, hour, minute, second)
    }

    internal fun parseDate(text: String): LocalDate = parseDateTime(text).date

    /** Parses [text] with this pattern in the formatter's zone. */
    fun parseInstant(text: String): Instant = parseDateTime(text).toInstant(requireNotNull(zone))

    private sealed interface Token {
        data class Text(val text: String) : Token
        data class Field(val letter: Char, val count: Int) : Token
    }

    companion object {
        fun ofPattern(pattern: String): DateTimeFormatter = DateTimeFormatter(tokenize(pattern), null)

        private fun pad(v: Int, width: Int): String = v.toString().padStart(width, '0')

        private fun tokenize(p: String): List<Token> {
            val out = mutableListOf<Token>()
            var i = 0
            while (i < p.length) {
                val c = p[i]
                when {
                    c == '\'' -> {
                        val end = p.indexOf('\'', i + 1).takeIf { it > 0 } ?: p.length
                        out += Token.Text(if (end == i + 1) "'" else p.substring(i + 1, end))
                        i = end + 1
                    }
                    c.isLetter() -> {
                        var j = i
                        while (j < p.length && p[j] == c) j++
                        out += Token.Field(c, j - i)
                        i = j
                    }
                    else -> {
                        out += Token.Text(c.toString())
                        i++
                    }
                }
            }
            return out
        }
    }
}

fun LocalDate.format(f: DateTimeFormatter): String = f.format(this)
fun LocalDateTime.format(f: DateTimeFormatter): String = f.format(this)
fun LocalTime.format(f: DateTimeFormatter): String = f.format(this)
