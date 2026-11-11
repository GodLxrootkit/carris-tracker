package pt.carrismetropolitana.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Scheduled departures from a pattern's own timetable.
 *
 * Bug this pins: a Carris Metropolitana stop showed "Sem passagens previstas" overnight.
 * `/arrivals/by_stop` is a rolling window, and at 03:46 on 2026-10-12 it returned 196 entries —
 * 188 from the day before, 8 from today up to 01:33 — and none in the future, so every entry
 * was filtered out. The timetable was never missing; it was sitting unused in `/patterns`,
 * which the app already downloads.
 */
class TimetableTest {

    private val zone = TimeZone.getTimeZone("Europe/Lisbon")

    /** 2026-10-12 03:46 Lisbon — the moment the window had fully elapsed. */
    private val overnight: Long = Calendar.getInstance(zone).apply {
        clear()
        set(2026, Calendar.OCTOBER, 12, 3, 46, 0)
    }.timeInMillis / 1000

    private fun at(hour: Int, minute: Int, day: Int = 12) = Calendar.getInstance(zone).apply {
        clear()
        set(2026, Calendar.OCTOBER, day, hour, minute, 0)
    }.timeInMillis / 1000

    private val pattern = PatternInfo(
        id = "cm:1717_0_2",
        lineId = "cm:1717",
        headsign = "Colégio Militar (Metro)",
        shapeId = "1717_0_2_shp",
        validOn = setOf("20261012", "20261013"),
        stops = listOf(
            // 02:10 has already gone by at 03:46, 06:40 onwards has not.
            PatternStop("060440", 1, 0.0, listOf(130, 400, 401)),
            PatternStop("030605", 33, 4210.0, listOf(317, 318, 351, 353, 356)),
            PatternStop("060500", 42, 6100.0, listOf(360, 361))
        )
    )

    @Test
    fun departuresAfterMidnightNightAreStillInTheFuture() {
        val arrivals = Timetable.arrivalsFromPatterns(
            listOf(pattern), "030605", overnight, "20261012", zone = zone
        )
        assertTrue("expected the 05:17 onward departures", arrivals.isNotEmpty())
        assertEquals(listOf("05:17", "05:18", "05:51", "05:53", "05:56"), arrivals.map { hhmm(it.scheduledUnix) })
    }

    /** The 02:10 run has gone; 06:40 onwards has not. */
    @Test
    fun elapsedDeparturesAreExcluded() {
        val arrivals = Timetable.arrivalsFromPatterns(
            listOf(pattern), "060440", overnight, "20261012", zone = zone
        )
        assertTrue(arrivals.all { it.scheduledUnix >= overnight })
        assertEquals(listOf("06:40", "06:41"), arrivals.map { hhmm(it.scheduledUnix) })
        assertTrue(arrivals.none { hhmm(it.scheduledUnix) == "02:10" })
    }

    @Test
    fun lateNightServiceBeforeMidnightIsStillShown() {
        // 00:20 tomorrow relative to the service day is 20 min away at 00:05.
        val justAfterMidnight = at(0, 5)
        val late = pattern.copy(stops = listOf(PatternStop("060440", 1, 0.0, listOf(20, 200))))
        val arrivals = Timetable.arrivalsFromPatterns(
            listOf(late), "060440", justAfterMidnight, "20261013", zone = zone
        )
        assertEquals(listOf("00:20", "03:20"), arrivals.map { hhmm(it.scheduledUnix) })
    }

    @Test
    fun patternNotValidTodayIsSkipped() {
        val arrivals = Timetable.arrivalsFromPatterns(
            listOf(pattern), "030605", overnight, "20261020", zone = zone
        )
        assertTrue(arrivals.isEmpty())
    }

    @Test
    fun patternValidTodayIsUsed() {
        assertEquals(1, Timetable.arrivalsFromPatterns(listOf(pattern), "030605", overnight, "20261012").size.coerceAtMost(1))
        assertTrue(Timetable.arrivalsFromPatterns(listOf(pattern), "030605", overnight, "20261013", zone = zone).isNotEmpty())
    }

    @Test
    fun unknownStopYieldsNothing() {
        assertTrue(Timetable.arrivalsFromPatterns(listOf(pattern), "does-not-exist", overnight, "20261012", zone = zone).isEmpty())
    }

    /**
     * The same departure from both sources carries an identical unix second, so exact matching
     * removes it. Deliberately not time-windowed: this network has real departures 20-60 s
     * apart and a window merged two different buses into one.
     */
    @Test
    fun mergeDropsTheSameDepartureListedTwice() {
        val live = Arrival("cm:1717", "h", "cm:1717_0_2", at(5, 17), null, null)
        val fromTimetable = Arrival("cm:1717", "h", "cm:1717_0_2", at(5, 17), null, null)

        val merged = Timetable.merge(listOf(listOf(live), listOf(fromTimetable)))
        assertEquals(1, merged.size)
        assertEquals(at(5, 17), merged.single().scheduledUnix)
    }

    /** 20 s apart on the same pattern is two buses, not one departure listed twice. */
    @Test
    fun mergeKeepsDeparturesSecondsApart() {
        val first = Arrival("cm:1717", "h", "cm:1717_0_2", at(5, 17), null, null)
        val second = Arrival("cm:1717", "h", "cm:1717_0_2", at(5, 17) + 20, null, null)
        assertEquals(2, Timetable.merge(listOf(listOf(first, second))).size)
    }

    @Test
    fun mergeKeepsTheSameTimeFromDifferentPatterns() {
        val a = Arrival("cm:1717", "h", "cm:1717_0_2", at(5, 17), null, null)
        val b = Arrival("cm:1717", "h", "cm:1717_1_2", at(5, 17), null, null)
        assertEquals(2, Timetable.merge(listOf(listOf(a, b))).size)
    }

    @Test
    fun mergeIsOrderedAcrossSources() {
        val later = Arrival("cm:1707", "h", "cm:1707_0_1", at(9, 0), null, null)
        val earlier = Arrival("carris:164_0", "Benfica", "carris:164_0_ASC_shp", at(5, 37), null, null)
        val merged = Timetable.merge(listOf(listOf(later), listOf(earlier)))
        assertEquals(listOf(at(5, 37), at(9, 0)), merged.map { it.scheduledUnix })
    }

    @Test
    fun dateAndMidnightAgreeWithTheCalendar() {
        assertEquals("20261012", Timetable.today(overnight, zone))
        assertEquals(overnight - 3 * 3600 - 46 * 60, Timetable.midnightUnix(overnight, "20261012", zone))
    }

    /**
     * The reported bug: only 1717 appeared at R Elias Garcia 103, where the four tracked lines
     * have 100 / 148 / 41 / 156 future departures and 1717 alone has 148 before 07:00.
     */
    @Test
    fun selectTakesTwoPerLineHoweverBusyEachLineIs() {
        val base = at(5, 0)
        fun departures(line: String, count: Int) = (0 until count).map { i ->
            Arrival(line, "h", "${line}_0_1", base + i * 60L, null, null)
        }
        val all = departures("cm:1717", 148) +
            departures("cm:1707", 100) +
            departures("cm:1720", 41) +
            departures("cm:1721", 156)

        val selected = Timetable.select(all, perLine = 2)

        assertEquals(8, selected.size)
        assertEquals(
            mapOf("cm:1707" to 2, "cm:1717" to 2, "cm:1720" to 2, "cm:1721" to 2),
            selected.groupingBy { it.lineId }.eachCount()
        )
        // 1717 has 148 departures and still gets exactly two.
        assertEquals(2, selected.count { it.lineId == "cm:1717" })
        assertTrue(selected.zipWithNext().all { (a, b) -> a.effectiveUnix <= b.effectiveUnix })
    }

    @Test
    fun selectTakesTheTwoSoonestOfEachLine() {
        val base = at(5, 0)
        val all = (0 until 5).map { Arrival("cm:1717", "h", "p", base + it * 60L, null, null) } +
            (0 until 5).map { Arrival("cm:1707", "h", "p", base + (10 + it) * 60L, null, null) }
        val selected = Timetable.select(all, perLine = 2)
        assertEquals(listOf(base, base + 60, base + 600, base + 660), selected.map { it.effectiveUnix })
    }

    /** A line with fewer departures than the allowance keeps all it has. */
    @Test
    fun selectDoesNotPadFromLaterLines() {
        val base = at(5, 0)
        val all = (0 until 2).map { Arrival("cm:1720", "h", "p", base + it * 60L, null, null) } +
            (0 until 9).map { Arrival("cm:1717", "h", "p", base + (10 + it) * 60L, null, null) }
        val selected = Timetable.select(all, perLine = 2)
        assertEquals(4, selected.size)
        assertEquals(2, selected.count { it.lineId == "cm:1720" })
    }

    /**
     * 746 is two route_ids under one short name. Grouping by id would give it four rows all
     * badged 746, the same duplicate-label problem the filter chips had.
     */
    @Test
    fun selectGroupsVariantsOfOneLineTogether() {
        val base = at(5, 0)
        val lines = listOf(
            LineInfo("carris:108_0", "746", "Marquês Pombal - Estação Damaia", "#1F5FA9", emptyList()),
            LineInfo("carris:108_3", "746", "Rossio - Sul e Sueste", "#0E7C7B", emptyList()),
            LineInfo("carris:164_0", "716", "Alameda D. A. Henriques - Benfica", "#C61D23", emptyList())
        )
        val all = (0 until 6).map { Arrival("carris:108_0", "h", "p", base + it * 60L, null, null) } +
            (0 until 6).map { Arrival("carris:108_3", "h", "p", base + (20 + it) * 60L, null, null) } +
            (0 until 6).map { Arrival("carris:164_0", "h", "p", base + (40 + it) * 60L, null, null) }

        val selected = Timetable.select(all, perLine = 2) { lines.shortNameOf(it.lineId) }

        // 746 gets one allowance for both variants, not one each: four rows badged 746 would
        // repeat the duplicate-label problem the filter chips had in 1.2.0.
        assertEquals(4, selected.size)
        assertEquals(2, selected.count { lines.shortNameOf(it.lineId) == "746" })
        assertEquals(2, selected.count { it.lineId == "carris:164_0" })
        // Both of 746's rows come from the sooner variant, since 108_0 runs first.
        assertEquals(0, selected.count { it.lineId == "carris:108_3" })
    }

    @Test
    fun mergeDoesNotTruncateBeforeSelection() {
        // 445 arrivals, as at R Elias Garcia 103. An upstream cap dropped three of four lines.
        val base = at(5, 0)
        val big = (0 until 445).map { Arrival("cm:1717", "h", "p", base + it * 30L, null, null) }
        assertEquals(445, Timetable.merge(listOf(big)).size)
    }

    private fun hhmm(unix: Long): String {
        val c = Calendar.getInstance(zone).apply { timeInMillis = unix * 1000 }
        return "%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    }
}
