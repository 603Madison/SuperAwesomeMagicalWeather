@file:Suppress("NOTHING_TO_INLINE", "unused")

package app.northvane.core.time

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.FixedOffsetTimeZone
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.daysUntil
import kotlinx.datetime.format.DateTimeComponents
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.number
import kotlinx.datetime.offsetAt
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/*
 * The app's date and time vocabulary, on every platform. It was written
 * against java.time; these names keep that vocabulary (kotlin.time.Clock.System.now(),
 * plusDays, isBefore, ZoneId.of…) on top of kotlin.time and kotlinx-datetime,
 * which also run on iOS. Semantics follow java.time: day arithmetic on zoned
 * times works on the local calendar, hour and minute arithmetic on the instant.
 */

typealias Instant = kotlin.time.Instant
typealias Duration = kotlin.time.Duration
typealias LocalDate = kotlinx.datetime.LocalDate
typealias LocalDateTime = kotlinx.datetime.LocalDateTime
typealias LocalTime = kotlinx.datetime.LocalTime
typealias DayOfWeek = kotlinx.datetime.DayOfWeek
typealias Month = kotlinx.datetime.Month
typealias ZoneId = kotlinx.datetime.TimeZone


// ---- Instant ----

fun kotlin.time.Instant.Companion.ofEpochSecond(seconds: Long): Instant = fromEpochSeconds(seconds)
fun kotlin.time.Instant.Companion.ofEpochMilli(millis: Long): Instant = fromEpochMilliseconds(millis)
val kotlin.time.Instant.Companion.EPOCH: Instant get() = fromEpochSeconds(0)

inline fun Instant.isAfter(other: Instant): Boolean = this > other
inline fun Instant.isBefore(other: Instant): Boolean = this < other
fun Instant.plusSeconds(s: Long): Instant = this + s.seconds
fun Instant.minusSeconds(s: Long): Instant = this - s.seconds
fun Instant.plusMillis(ms: Long): Instant = this + ms.milliseconds
val Instant.epochSecond: Long get() = epochSeconds
fun Instant.toEpochMilli(): Long = toEpochMilliseconds()
fun Instant.atZone(zone: ZoneId): ZonedDateTime = ZonedDateTime.ofInstant(this, zone)
fun Instant.atOffset(zone: ZoneId): ZonedDateTime = ZonedDateTime.ofInstant(this, zone)
fun Instant.truncatedTo(unit: ChronoUnit): Instant = when (unit) {
    ChronoUnit.DAYS -> Instant.fromEpochSeconds(epochSeconds - epochSeconds.mod(86_400L))
    else -> Instant.fromEpochMilliseconds(toEpochMilliseconds().let { it - it.mod(unit.millis) })
}

// ---- Duration ----

fun kotlin.time.Duration.Companion.ofDays(n: Long): Duration = n.days
fun kotlin.time.Duration.Companion.ofDays(n: Int): Duration = n.days
fun kotlin.time.Duration.Companion.ofHours(n: Long): Duration = n.hours
fun kotlin.time.Duration.Companion.ofHours(n: Int): Duration = n.hours
fun kotlin.time.Duration.Companion.ofMinutes(n: Long): Duration = n.minutes
fun kotlin.time.Duration.Companion.ofMinutes(n: Int): Duration = n.minutes
fun kotlin.time.Duration.Companion.ofSeconds(n: Long): Duration = n.seconds
fun kotlin.time.Duration.Companion.ofSeconds(n: Int): Duration = n.seconds
fun kotlin.time.Duration.Companion.ofMillis(n: Long): Duration = n.milliseconds
fun kotlin.time.Duration.Companion.between(start: Instant, end: Instant): Duration = end - start

fun Duration.toDays(): Long = inWholeDays
fun Duration.toHours(): Long = inWholeHours
fun Duration.toMinutes(): Long = inWholeMinutes
fun Duration.toSeconds(): Long = inWholeSeconds
fun Duration.toMillis(): Long = inWholeMilliseconds
val Duration.seconds: Long get() = inWholeSeconds
fun Duration.abs(): Duration = absoluteValue
fun Duration.multipliedBy(n: Long): Duration = this * n.toDouble()
fun Duration.dividedBy(n: Long): Duration = this / n.toDouble()
fun Duration.plusMinutes(n: Long): Duration = this + n.minutes
fun Duration.plusHours(n: Long): Duration = this + n.hours

// ---- Zones ----

fun kotlinx.datetime.TimeZone.Companion.systemDefault(): ZoneId = TimeZone.currentSystemDefault()

object ZoneOffset {
    val UTC: ZoneId = TimeZone.UTC
    fun ofHours(hours: Int): ZoneId = FixedOffsetTimeZone(UtcOffset(hours = hours))
    fun ofTotalSeconds(seconds: Int): ZoneId = FixedOffsetTimeZone(UtcOffset(seconds = seconds))
}

fun ZoneId.offsetSecondsAt(instant: Instant): Int = offsetAt(instant).totalSeconds

// ---- LocalDate ----

fun kotlinx.datetime.LocalDate.Companion.of(year: Int, month: Int, day: Int): LocalDate = LocalDate(year, month, day)
fun kotlinx.datetime.LocalDate.Companion.of(year: Int, month: Month, day: Int): LocalDate = LocalDate(year, month, day)
fun kotlinx.datetime.LocalDate.Companion.ofEpochDay(day: Long): LocalDate = fromEpochDays(day)
fun kotlinx.datetime.LocalDate.Companion.now(zone: ZoneId = ZoneId.systemDefault()): LocalDate = kotlin.time.Clock.System.now().toLocalDateTime(zone).date
fun kotlinx.datetime.LocalDate.Companion.parse(text: String, format: DateTimeFormatter): LocalDate = format.parseDate(text)

fun LocalDate.plusDays(n: Long): LocalDate = plus(n, DateTimeUnit.DAY)
fun LocalDate.plusDays(n: Int): LocalDate = plus(n, DateTimeUnit.DAY)
fun LocalDate.minusDays(n: Long): LocalDate = minus(n, DateTimeUnit.DAY)
fun LocalDate.minusDays(n: Int): LocalDate = minus(n, DateTimeUnit.DAY)
fun LocalDate.plusWeeks(n: Long): LocalDate = plus(n * 7, DateTimeUnit.DAY)
fun LocalDate.minusWeeks(n: Long): LocalDate = minus(n * 7, DateTimeUnit.DAY)
fun LocalDate.plusMonths(n: Long): LocalDate = plus(n, DateTimeUnit.MONTH)
fun LocalDate.minusMonths(n: Long): LocalDate = minus(n, DateTimeUnit.MONTH)
fun LocalDate.plusYears(n: Long): LocalDate = plus(n, DateTimeUnit.YEAR)
fun LocalDate.minusYears(n: Long): LocalDate = minus(n, DateTimeUnit.YEAR)
fun LocalDate.withYear(year: Int): LocalDate {
    val last = lengthOfMonth(year, month.number)
    return LocalDate(year, month, minOf(day, last))
}
fun LocalDate.withDayOfMonth(d: Int): LocalDate = LocalDate(year, month, d)
inline fun LocalDate.isBefore(other: LocalDate): Boolean = this < other
inline fun LocalDate.isAfter(other: LocalDate): Boolean = this > other
inline fun LocalDate.isEqual(other: LocalDate): Boolean = this == other
val LocalDate.monthValue: Int get() = month.number
val LocalDate.dayOfMonth: Int get() = day
fun LocalDate.atTime(hour: Int, minute: Int, second: Int = 0): LocalDateTime = LocalDateTime(this, LocalTime(hour, minute, second))
fun LocalDate.atTime(time: LocalTime): LocalDateTime = LocalDateTime(this, time)
fun LocalDate.atStartOfDay(zone: ZoneId): ZonedDateTime = ZonedDateTime.ofInstant(atStartOfDayIn(zone), zone)
fun LocalDate.toEpochDay(): Long = toEpochDays()
fun LocalDate.lengthOfMonth(): Int = lengthOfMonth(year, month.number)
fun LocalDate.isLeapYear(): Boolean = isLeap(year)

private fun isLeap(year: Int) = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0
private fun lengthOfMonth(year: Int, month: Int) = when (month) {
    2 -> if (isLeap(year)) 29 else 28
    4, 6, 9, 11 -> 30
    else -> 31
}

val DayOfWeek.value: Int get() = isoDayNumber
fun DayOfWeek.plus(days: Long): DayOfWeek = DayOfWeek.entries[((ordinal + days) % 7 + 7).toInt() % 7]
fun DayOfWeek.minus(days: Long): DayOfWeek = plus(-days)
val Month.value: Int get() = number

enum class TextStyle { FULL, SHORT, NARROW }

fun DayOfWeek.getDisplayName(style: TextStyle): String {
    val full = name.lowercase().replaceFirstChar { it.uppercaseChar() }
    return when (style) {
        TextStyle.FULL -> full
        TextStyle.SHORT -> full.take(3)
        TextStyle.NARROW -> full.take(1)
    }
}

fun Month.getDisplayName(style: TextStyle): String {
    val full = name.lowercase().replaceFirstChar { it.uppercaseChar() }
    return when (style) {
        TextStyle.FULL -> full
        TextStyle.SHORT -> full.take(3)
        TextStyle.NARROW -> full.take(1)
    }
}

// ---- LocalDateTime / LocalTime ----

fun kotlinx.datetime.LocalDateTime.Companion.of(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int = 0): LocalDateTime =
    LocalDateTime(year, month, day, hour, minute, second)
fun kotlinx.datetime.LocalDateTime.Companion.parse(text: String, format: DateTimeFormatter): LocalDateTime = format.parseDateTime(text)
fun LocalDateTime.atZone(zone: ZoneId): ZonedDateTime = ZonedDateTime.ofInstant(toInstant(zone), zone)
fun LocalDateTime.toInstant(offset: ZoneId): Instant = toInstant(timeZone = offset)
fun LocalDateTime.toLocalDate(): LocalDate = date
fun LocalDateTime.toLocalTime(): LocalTime = time
fun LocalDateTime.plusHours(n: Long): LocalDateTime = toInstant(TimeZone.UTC).plus(n.hours).toLocalDateTime(TimeZone.UTC)
fun LocalDateTime.plusMinutes(n: Long): LocalDateTime = toInstant(TimeZone.UTC).plus(n.minutes).toLocalDateTime(TimeZone.UTC)
fun LocalDateTime.plusDays(n: Long): LocalDateTime = LocalDateTime(date.plusDays(n), time)
inline fun LocalDateTime.isBefore(other: LocalDateTime): Boolean = this < other
inline fun LocalDateTime.isAfter(other: LocalDateTime): Boolean = this > other
val LocalDateTime.monthValue: Int get() = month.number
val LocalDateTime.dayOfMonth: Int get() = day

fun kotlinx.datetime.LocalTime.Companion.of(hour: Int, minute: Int, second: Int = 0): LocalTime = LocalTime(hour, minute, second)
fun kotlinx.datetime.LocalTime.Companion.ofSecondOfDay(s: Long): LocalTime = fromSecondOfDay(s.toInt())
val kotlinx.datetime.LocalTime.Companion.NOON: LocalTime get() = LocalTime(12, 0)
val kotlinx.datetime.LocalTime.Companion.MIDNIGHT: LocalTime get() = LocalTime(0, 0)
fun LocalTime.withSecond(s: Int): LocalTime = LocalTime(hour, minute, s)
fun LocalTime.withNano(n: Int): LocalTime = LocalTime(hour, minute, second, n)
inline fun LocalTime.isBefore(other: LocalTime): Boolean = this < other
inline fun LocalTime.isAfter(other: LocalTime): Boolean = this > other

// ---- ZonedDateTime ----

/** A moment as seen in a time zone (java.time's ZonedDateTime / OffsetDateTime). */
class ZonedDateTime private constructor(private val instant: Instant, val zone: ZoneId) : Comparable<ZonedDateTime> {
    private val local: LocalDateTime = instant.toLocalDateTime(zone)

    val year: Int get() = local.year
    val month: Month get() = local.month
    val monthValue: Int get() = local.month.number
    val dayOfMonth: Int get() = local.day
    val dayOfYear: Int get() = local.dayOfYear
    val dayOfWeek: DayOfWeek get() = local.dayOfWeek
    val hour: Int get() = local.hour
    val minute: Int get() = local.minute
    val second: Int get() = local.second
    val offsetSeconds: Int get() = zone.offsetAt(instant).totalSeconds

    fun toInstant(): Instant = instant
    fun toEpochSecond(): Long = instant.epochSeconds
    fun toLocalDate(): LocalDate = local.date
    fun toLocalTime(): LocalTime = local.time
    fun toLocalDateTime(): LocalDateTime = local

    fun plusDays(n: Long): ZonedDateTime = of(LocalDateTime(local.date.plusDays(n), local.time), zone)
    fun minusDays(n: Long): ZonedDateTime = plusDays(-n)
    fun plusWeeks(n: Long): ZonedDateTime = plusDays(n * 7)
    fun plusMonths(n: Long): ZonedDateTime = of(LocalDateTime(local.date.plusMonths(n), local.time), zone)
    fun plusHours(n: Long): ZonedDateTime = ofInstant(instant + n.hours, zone)
    fun minusHours(n: Long): ZonedDateTime = plusHours(-n)
    fun plusMinutes(n: Long): ZonedDateTime = ofInstant(instant + n.minutes, zone)
    fun minusMinutes(n: Long): ZonedDateTime = plusMinutes(-n)
    fun plusSeconds(n: Long): ZonedDateTime = ofInstant(instant + n.seconds, zone)
    fun withHour(h: Int): ZonedDateTime = of(LocalDateTime(local.date, LocalTime(h, local.minute, local.second, local.nanosecond)), zone)
    fun withMinute(m: Int): ZonedDateTime = of(LocalDateTime(local.date, LocalTime(local.hour, m, local.second, local.nanosecond)), zone)
    fun withSecond(s: Int): ZonedDateTime = of(LocalDateTime(local.date, LocalTime(local.hour, local.minute, s, local.nanosecond)), zone)
    fun withNano(n: Int): ZonedDateTime = of(LocalDateTime(local.date, LocalTime(local.hour, local.minute, local.second, n)), zone)
    fun withDayOfMonth(d: Int): ZonedDateTime = of(LocalDateTime(local.date.withDayOfMonth(d), local.time), zone)
    fun withZoneSameInstant(z: ZoneId): ZonedDateTime = ofInstant(instant, z)
    fun truncatedTo(unit: ChronoUnit): ZonedDateTime = when (unit) {
        ChronoUnit.DAYS -> of(LocalDateTime(local.date, LocalTime(0, 0)), zone)
        ChronoUnit.HOURS -> of(LocalDateTime(local.date, LocalTime(local.hour, 0)), zone)
        ChronoUnit.MINUTES -> of(LocalDateTime(local.date, LocalTime(local.hour, local.minute)), zone)
        else -> of(LocalDateTime(local.date, LocalTime(local.hour, local.minute, local.second)), zone)
    }
    fun isBefore(other: ZonedDateTime): Boolean = instant < other.instant
    fun isAfter(other: ZonedDateTime): Boolean = instant > other.instant

    override fun compareTo(other: ZonedDateTime): Int = instant.compareTo(other.instant)
    override fun equals(other: Any?): Boolean = other is ZonedDateTime && other.instant == instant && other.zone == zone
    override fun hashCode(): Int = instant.hashCode() * 31 + zone.hashCode()
    override fun toString(): String = "$local[${zone.id}]"

    companion object {
        fun ofInstant(instant: Instant, zone: ZoneId): ZonedDateTime = ZonedDateTime(instant, zone)
        fun of(local: LocalDateTime, zone: ZoneId): ZonedDateTime = ZonedDateTime(local.toInstant(zone), zone)
        fun of(date: LocalDate, time: LocalTime, zone: ZoneId): ZonedDateTime = of(LocalDateTime(date, time), zone)
        fun now(zone: ZoneId): ZonedDateTime = ofInstant(kotlin.time.Clock.System.now(), zone)
    }
}

/** ISO date-time with an offset ("2026-11-06T10:00:00-05:00"), keeping the offset. */
object OffsetDateTime {
    fun parse(text: String): ZonedDateTime {
        val c = DateTimeComponents.Formats.ISO_DATE_TIME_OFFSET.parse(text)
        return ZonedDateTime.ofInstant(c.toInstantUsingOffset(), FixedOffsetTimeZone(c.toUtcOffset()))
    }
}

/** Month and day without a year, for seasonal windows. */
data class MonthDay(val month: Int, val day: Int) : Comparable<MonthDay> {
    override fun compareTo(other: MonthDay): Int = compareValuesBy(this, other, { it.month }, { it.day })
    fun atYear(year: Int): LocalDate = LocalDate(year, month, if (month == 2 && day == 29 && !isLeap(year)) 28 else day)

    companion object {
        fun of(month: Int, day: Int) = MonthDay(month, day)
        fun from(date: LocalDate) = MonthDay(date.month.number, date.day)
    }
}

enum class ChronoUnit(internal val millis: Long) {
    MILLIS(1), SECONDS(1_000), MINUTES(60_000), HOURS(3_600_000), DAYS(86_400_000);

    fun between(start: Instant, end: Instant): Long = (end - start).inWholeMilliseconds / millis
    fun between(start: ZonedDateTime, end: ZonedDateTime): Long =
        if (this == DAYS) start.toLocalDate().daysUntil(end.toLocalDate()).toLong() else between(start.toInstant(), end.toInstant())
    fun between(start: LocalDate, end: LocalDate): Long = when (this) {
        DAYS -> start.daysUntil(end).toLong()
        else -> start.daysUntil(end).toLong() * (86_400_000 / millis)
    }
    fun between(start: LocalDateTime, end: LocalDateTime): Long = between(start.toInstant(TimeZone.UTC), end.toInstant(TimeZone.UTC))
}
