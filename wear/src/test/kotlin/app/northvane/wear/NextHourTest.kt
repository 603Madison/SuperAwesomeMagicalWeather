package app.northvane.wear

import app.northvane.core.time.*
import app.northvane.wear.glance.NextHour
import app.northvane.wear.glance.WatchClock
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NextHourTest {
    private val clock = WatchClock(Fixtures.zone, is24 = false)
    private fun text(vararg mm: Double, snowCm: Double = 0.0, now: Instant = Fixtures.now) =
        NextHour.text(Fixtures.rain(*mm, snowCm = snowCm).map { it.toMinutePrecip() }, now, clock::soon)

    @Test
    fun dryHour() = assertEquals("Dry for the next hour", text(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0))

    @Test
    fun rainStarting() = assertEquals("Rain starting around 3:30", text(0.0, 0.0, 0.4, 0.8, 0.5, 0.0, 0.0, 0.0))

    @Test
    fun drizzleBelowTheThresholdIsDry() = assertEquals("Dry for the next hour", text(0.0, 0.05, 0.05, 0.0, 0.0, 0.0, 0.0, 0.0))

    @Test
    fun heavyRainStarting() = assertEquals("Heavy rain starting around 3:45", text(0.0, 0.0, 0.0, 3.0, 4.0, 1.0, 0.0, 0.0))

    @Test
    fun snowStarting() = assertEquals("Snow starting around 3:15", text(0.0, 0.3, 0.4, 0.4, 0.4, 0.4, 0.4, 0.4, snowCm = 0.5))

    @Test
    fun rainStopping() = assertEquals("Rain stopping around 3:45", text(1.0, 0.6, 0.2, 0.0, 0.0, 0.0, 0.0, 0.0))

    @Test
    fun rainAllHour() = assertEquals("Rain for the next hour", text(1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0))

    @Test
    fun twentyFourHourClock() {
        val steps = Fixtures.rain(0.0, 0.0, 0.4, 0.8, 0.5, 0.0, 0.0, 0.0).map { it.toMinutePrecip() }
        assertEquals("Rain starting around 15:30", NextHour.text(steps, Fixtures.now, WatchClock(Fixtures.zone, is24 = true)::soon))
    }

    @Test
    fun oldDataSaysNothing() {
        // Downloaded at 3:00; by 4:50 the steps no longer cover the next hour.
        assertNull(text(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, now = Fixtures.now.plus(Duration.ofMinutes(105))))
    }

    @Test
    fun stepsFromTheFutureSayNothing() {
        // Nothing for the current quarter hour (e.g. a clock set wrong): no claim either way.
        assertNull(text(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, now = Fixtures.now.minus(Duration.ofMinutes(30))))
    }

    @Test
    fun noStepsSayNothing() = assertNull(NextHour.text(emptyList(), Fixtures.now, clock::soon))
}
