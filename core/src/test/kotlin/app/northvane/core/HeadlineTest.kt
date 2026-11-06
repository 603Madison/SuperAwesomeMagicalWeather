package app.northvane.core

import app.northvane.core.model.DailyForecast
import app.northvane.core.model.HourlyForecast
import app.northvane.core.model.UnitSystem
import app.northvane.core.model.WeatherCondition
import app.northvane.core.outlook.MinutePrecip
import app.northvane.core.outlook.TodayHeadline
import app.northvane.core.util.UnitFormatter
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HeadlineTest {
    private val zone = ZoneId.of("America/New_York")
    // Friday Nov 6, 2026, 10:00 AM local.
    private val now = Instant.parse("2026-11-06T15:00:00Z")
    private val f = UnitFormatter(UnitSystem.IMPERIAL)
    private val hourFmt = DateTimeFormatter.ofPattern("h a", Locale.US).withZone(zone)
    private val clockFmt = DateTimeFormatter.ofPattern("h:mm a", Locale.US).withZone(zone)

    private fun hours(n: Int = 30, temp: (Int) -> Double = { 10.0 }, chance: (Int) -> Int = { 5 }, mm: (Int) -> Double = { 0.0 }, gust: (Int) -> Double? = { null }) =
        (0 until n).map { i ->
            HourlyForecast(
                time = now.plus(Duration.ofHours(i.toLong())), temperatureC = temp(i), condition = WeatherCondition.CLEAR, isDaytime = true,
                shortForecast = null, precipitationChance = chance(i), windSpeedKph = 10.0, windDirection = "W", humidityPercent = 60.0,
                precipitationMm = mm(i), windGustKph = gust(i),
            )
        }

    private fun day(date: LocalDate, high: Double, low: Double = 2.0) =
        DailyForecast(date, date.dayOfWeek.name, high, low, WeatherCondition.CLEAR, true, null, null)

    private val today = LocalDate.of(2026, 11, 6)

    private fun write(hourly: List<HourlyForecast>, daily: List<DailyForecast> = listOf(day(today, 12.0), day(today.plusDays(1), 13.0)), nowcast: List<MinutePrecip> = emptyList()) =
        TodayHeadline.write(hourly, daily, now, zone, f, clockFmt::format, hourFmt::format, nowcast)

    @Test
    fun quietDay() {
        assertEquals(listOf("Dry for the next 18 hours."), write(hours()))
    }

    @Test
    fun rainWithTimeAndAmount() {
        val lines = write(hours(chance = { if (it in 5..8) 80 else 5 }, mm = { if (it in 5..8) 3.0 else 0.0 }))
        assertEquals("Rain from 3 PM, about 0.47 in.", lines.first())
    }

    @Test
    fun frostOnlyAfterAMildDay() {
        val cold = hours(temp = { if (it in 10..20) -1.0 else 8.0 })
        assertTrue(write(cold).any { it == "Frost tonight, low 30°." }, write(cold).toString())
        // Midwinter: a high below about 39 °F means every night freezes; not worth saying.
        val winter = write(cold, listOf(day(today, 2.0), day(today.plusDays(1), 2.0)))
        assertTrue(winter.none { it.startsWith("Frost") }, winter.toString())
    }

    @Test
    fun gustsAndBigChanges() {
        val lines = write(hours(gust = { if (it == 4) 70.0 else 20.0 }), listOf(day(today, 15.0), day(today.plusDays(1), 5.0)))
        assertTrue("Gusts to 43 mph around 2 PM." in lines, lines.toString())
        assertTrue("Much colder tomorrow, high 41°." in lines, lines.toString())
    }

    @Test
    fun fifteenMinuteRainComesFirst() {
        val steps = (0 until 8).map { i -> MinutePrecip(now.plus(Duration.ofMinutes(15L * i)), if (i >= 2) 0.6 else 0.0, 0.0) }
        val lines = write(hours(chance = { if (it in 0..3) 70 else 5 }), nowcast = steps)
        assertEquals("Rain in about 30 min.", lines.first())
        // The hourly sentence about the same rain is left out.
        assertTrue(lines.none { it.startsWith("Rain from") || it.startsWith("Rain until") }, lines.toString())
        val ending = (0 until 8).map { i -> MinutePrecip(now.plus(Duration.ofMinutes(15L * i)), if (i < 3) 1.0 else 0.0, 0.0) }
        assertEquals("Rain ending around 10:45 AM.", TodayHeadline.nearTerm(ending, now, clockFmt::format))
    }

    @Test
    fun yourTimesAndWhatToWear() {
        val times = listOf(app.northvane.core.outlook.MyTime("Drive home", 17 * 60), app.northvane.core.outlook.MyTime("Walk", 7 * 60))
        val slots = app.northvane.core.outlook.MyTimes.upcoming(times, now, zone)
        assertEquals("Drive home", slots.first().label)
        // Rain 4–7 PM: the drive home at 5 PM gets the warning, ahead of the general rain line.
        val wet = hours(chance = { if (it in 6..9) 80 else 5 }, mm = { if (it in 6..9) 2.0 else 0.0 })
        val lines = TodayHeadline.write(wet, listOf(day(today, 12.0), day(today.plusDays(1), 13.0)), now, zone, f, clockFmt::format, hourFmt::format, times = slots)
        assertEquals("Rain likely for your drive home (5 PM).", lines.first())
        val dry = TodayHeadline.write(hours(), emptyList(), now, zone, f, clockFmt::format, hourFmt::format, times = slots)
        assertEquals("Dry for your drive home (5 PM).", dry.first())

        val wear = app.northvane.core.outlook.WhatToWear.advise(wet, now, app.northvane.core.outlook.Comfort.NORMAL, hourFmt::format)
        assertEquals("Jacket · umbrella after 4 PM", wear)
        // At 13 °C (55 °F) a light jacket does; someone who runs cold gets the warmer suggestion.
        val mild = hours(temp = { 13.0 })
        assertEquals("Light jacket", app.northvane.core.outlook.WhatToWear.advise(mild, now, app.northvane.core.outlook.Comfort.NORMAL, hourFmt::format))
        assertEquals("Jacket", app.northvane.core.outlook.WhatToWear.advise(mild, now, app.northvane.core.outlook.Comfort.COLD, hourFmt::format))
    }

    @Test
    fun tonightDigestOnlyWhenThereIsSomething() {
        // 10 AM now; frost from 10 PM to 6 AM and rain overnight.
        val night = hours(temp = { if (it in 12..20) -1.0 else 8.0 }, chance = { if (it in 13..16) 60 else 5 })
        val snap = app.northvane.core.model.WeatherSnapshot(locationId = "p", fetchedAt = now, timeZoneId = zone.id, hourly = night)
        val lines = app.northvane.core.outlook.TonightDigest.write(
            snap, now, zone, f, hourFmt::format, app.northvane.core.outlook.TonightDigest.Options(garden = true, laundryOut = true),
        )
        assertEquals(listOf("Frost tonight (low 30°): cover tender plants.", "Your laundry is still out, and rain is likely overnight."), lines)
        val quiet = app.northvane.core.model.WeatherSnapshot(locationId = "p", fetchedAt = now, timeZoneId = zone.id, hourly = hours())
        assertTrue(app.northvane.core.outlook.TonightDigest.write(quiet, now, zone, f, hourFmt::format, app.northvane.core.outlook.TonightDigest.Options()).isEmpty())
    }
}
