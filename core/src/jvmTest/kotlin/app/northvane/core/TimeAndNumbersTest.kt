package app.northvane.core

import app.northvane.core.outlook.MyTime
import app.northvane.core.outlook.MyTimes
import app.northvane.core.time.*
import app.northvane.core.util.fixed
import app.northvane.core.util.plainDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

/** The java.time / java.text behaviours the multiplatform replacements must keep, checked against Java itself. */
class TimeAndNumbersTest {

    @Test
    fun fixedRoundsTheDecimalValueHalfUp() {
        val samples = listOf(1.005, 2.675, 44.92435, -71.06875, 0.5, 1.5, 2.5, 9.995, -0.001, 0.0004, 123.456, 1e-7, 1e13, 0.0, -0.0, 99.99, 0.045)
        for (v in samples) for (d in 0..4) {
            val expected = java.math.BigDecimal.valueOf(v).setScale(d, java.math.RoundingMode.HALF_UP).toPlainString()
                .let { if (it.startsWith("-") && it.trim('-', '0', '.').isEmpty()) it.drop(1) else it }
            assertEquals(expected, fixed(v, d), "$v to $d places")
        }
        assertEquals("44.9244", plainDecimal(44.92435, 4))
    }

    @Test
    fun twoDigitYearFieldsRejectFourDigitYears() {
        val f = DateTimeFormatter.ofPattern("dd/MM/yy")
        assertEquals(LocalDate.of(2026, 11, 6), LocalDate.parse("06/11/26", f))
        assertFails { LocalDate.parse("06/11/2026", f) }
        assertFails { java.time.LocalDate.parse("06/11/2026", java.time.format.DateTimeFormatter.ofPattern("dd/MM/yy")) }
        // One letter still reads one or two digits; a long year as many as it has.
        assertEquals(LocalDate.of(2026, 3, 5), LocalDate.parse("3/5/2026", DateTimeFormatter.ofPattern("M/d/yyyy")))
    }

    @Test
    fun offsetTimesWithoutSecondsParse() {
        val t = OffsetDateTime.parse("2026-11-06T10:00-05:00")
        assertEquals(java.time.OffsetDateTime.parse("2026-11-06T10:00-05:00").toEpochSecond(), t.toEpochSecond())
        assertEquals(java.time.OffsetDateTime.parse("2026-11-06T15:00Z").toEpochSecond(), OffsetDateTime.parse("2026-11-06T15:00Z").toEpochSecond())
        assertEquals(java.time.OffsetDateTime.parse("2026-03-08T02:30:15-05:00").toEpochSecond(), OffsetDateTime.parse("2026-03-08T02:30:15-05:00").toEpochSecond())
    }

    @Test
    fun repeatedHourKeepsItsOffset() {
        val ny = ZoneId.of("America/New_York")
        val jny = java.time.ZoneId.of("America/New_York")
        // 01:30 EDT on the morning clocks fall back, and 01:30 EST an hour later.
        for (epoch in listOf(1793511000L, 1793514600L)) {
            val ours = ZonedDateTime.ofInstant(app.northvane.core.time.Instant.fromEpochSeconds(epoch), ny)
            val expected = java.time.ZonedDateTime.ofInstant(java.time.Instant.ofEpochSecond(epoch), jny)
            assertEquals(expected.truncatedTo(java.time.temporal.ChronoUnit.HOURS).toEpochSecond(), ours.truncatedTo(ChronoUnit.HOURS).toEpochSecond())
            assertEquals(expected.withMinute(0).toEpochSecond(), ours.withMinute(0).toEpochSecond())
            assertEquals(expected.plusDays(1).toEpochSecond(), ours.plusDays(1).toEpochSecond())
        }
    }

    @Test
    fun wholeDaysBetweenZonedTimes() {
        val ny = ZoneId.of("America/New_York")
        val jny = java.time.ZoneId.of("America/New_York")
        val pairs = listOf(1793502000L to 1793509200L, 1793509200L to 1793502000L, 1793502000L to 1793595600L, 1793502000L to 1793588400L)
        for ((a, b) in pairs) {
            val ours = ChronoUnit.DAYS.between(
                ZonedDateTime.ofInstant(app.northvane.core.time.Instant.fromEpochSeconds(a), ny),
                ZonedDateTime.ofInstant(app.northvane.core.time.Instant.fromEpochSeconds(b), ZoneId.of("UTC")),
            )
            val expected = java.time.temporal.ChronoUnit.DAYS.between(
                java.time.ZonedDateTime.ofInstant(java.time.Instant.ofEpochSecond(a), jny),
                java.time.ZonedDateTime.ofInstant(java.time.Instant.ofEpochSecond(b), java.time.ZoneOffset.UTC),
            )
            assertEquals(expected, ours, "$a → $b")
        }
    }

    @Test
    fun myTimesKeepTheirClockTimeWhenClocksChange() {
        val ny = ZoneId.of("America/New_York")
        val drive = MyTime("Drive home", minutes = 17 * 60, durationMinutes = 30)
        for (date in listOf(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 3, 8))) {
            val noon = date.atTime(12, 0).atZone(ny).toInstant()
            val slot = MyTimes.upcoming(listOf(drive), noon, ny).first()
            val local = slot.start.atZone(ny)
            assertEquals(date, local.toLocalDate())
            assertEquals(17, local.hour, "on $date")
        }
    }
}
