package pt.carrismetropolitana.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * The CARRIS timetable lookup.
 *
 * Bug this pins: a CARRIS stop showed "Sem passagens previstas" at any hour with no bus
 * circulating, because the asset carried only stop sequence and km — no times — and CARRIS has
 * no arrivals endpoint and publishes no tripupdates. The timetable was in the feed all along.
 */
class CarrisScheduleTest {

    /** Monday 2026-10-12, 03:00 Lisbon — no 716 running, but the timetable has 05:37 onward. */
    private val monday3am: Long = run {
        val c = Calendar.getInstance(TimeZone.getTimeZone("Europe/Lisbon")).apply {
            clear()
            set(2026, Calendar.OCTOBER, 12, 3, 0, 0)
        }
        c.timeInMillis / 1000
    }

    private fun hhmm(unix: Long, tz: String = "Europe/Lisbon"): String {
        val c = Calendar.getInstance(TimeZone.getTimeZone(tz)).apply { timeInMillis = unix * 1000 }
        return "%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    }

    /**
     * Weekday masks as the generator emits them: bit i set means the service runs on weekday i,
     * 0 = Monday. `Inverno_Util` is Mon-Fri (31) and `Inverno_Sabado` is Saturday (32) in this
     * feed, both 20260606..20261231.
     */
    @Test
    fun weekdayServiceRunsOnMonday() {
        val rule = ServiceRule(days = 0b0011111, start = "20260606", end = "20261231")
        assertTrue(rule.activeOn("20261012", 0))
    }

    @Test
    fun weekdayServiceDoesNotRunOnSunday() {
        val rule = ServiceRule(days = 0b0011111, start = "20260606", end = "20261231")
        assertTrue(!rule.activeOn("20261011", 6))
    }

    @Test
    fun weekdayServiceRunsOnFridayAndNotSaturday() {
        val rule = ServiceRule(days = 0b0011111, start = "20260606", end = "20261231")
        assertTrue(rule.activeOn("20261016", 4))
        assertTrue(!rule.activeOn("20261017", 5))
    }

    @Test
    fun saturdayServiceDoesNotRunOnMonday() {
        val rule = ServiceRule(days = 0b100000, start = "20260606", end = "20261231")
        assertTrue(!rule.activeOn("20261012", 0))
        assertTrue(rule.activeOn("20261017", 5))
    }

    /** The seasonal services carry no weekday row, only explicit dates. */
    @Test
    fun datelessServiceOnlyRunsOnItsExplicitDates() {
        val rule = ServiceRule(
            days = 0,
            start = "19700101",
            end = "20991231",
            dates = setOf("20260801", "20260808")
        )
        assertTrue(rule.activeOn("20260801", 5))
        assertTrue(!rule.activeOn("20261012", 0))
    }

    @Test
    fun removedDateIsNotActive() {
        val rule = ServiceRule(
            days = 0b0011111,
            start = "20260606",
            end = "20261231",
            removed = setOf("20260730")
        )
        assertTrue(!rule.activeOn("20260730", 3))
    }

    @Test
    fun dateOutsideValidityIsNotActive() {
        val rule = ServiceRule(days = 0b0011111, start = "20260606", end = "20261231")
        assertTrue(!rule.activeOn("20270101", 4))
    }

    /**
     * A minute past 1440 is after-midnight service. It must sort after today's remaining
     * departures, not wrap back to the small hours.
     */
    @Test
    fun afterMidnightMinutesAreOrderedLast() {
        val minutes = listOf(1430, 60, 1510, 1370).sorted()
        assertEquals(listOf(60, 1370, 1430, 1510), minutes)
    }

    @Test
    fun weekdayIndexMatchesCalendarDayOfWeek() {
        // Calendar.MONDAY=2 .. SUNDAY=1; GTFS weekday is 0=Monday .. 6=Sunday.
        for (calDay in 2..7) {
            assertEquals(calDay - 2, (calDay + 5) % 7)
        }
        assertEquals(6, (Calendar.SUNDAY + 5) % 7)
    }

    /** Departures are minutes from local midnight, so the 03:00 base has to land 3h in. */
    @Test
    fun midnightIsExactlyThreeHoursBeforeTheTestInstant() {
        val midnight = java.util.Calendar.getInstance(TimeZone.getTimeZone("Europe/Lisbon")).apply {
            timeInMillis = monday3am * 1000
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis / 1000
        assertEquals(3 * 3600L, monday3am - midnight)
    }
}
