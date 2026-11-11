package pt.carrismetropolitana.tracker

import org.junit.Assert.assertEquals
import org.junit.Test

class ScheduleTest {

    @Test
    fun countdownFormats() {
        val now = 1_700_000_000L
        assertEquals("agora", Schedule.countdown(now, now))
        assertEquals("agora", Schedule.countdown(now - 30, now))
        assertEquals("1 min", Schedule.countdown(now + 60, now))
        assertEquals("5 min", Schedule.countdown(now + 5 * 60, now))
        assertEquals("1 h 05", Schedule.countdown(now + 65 * 60, now))
    }

    @Test
    fun hhMmFormats() {
        val dayStart = 1_700_000_000L - (1_700_000_000L % 86_400)
        assertEquals("08:30", Schedule.hhMm(dayStart + 8 * 3600 + 30 * 60))
        assertEquals("00:00", Schedule.hhMm(dayStart))
    }
}
