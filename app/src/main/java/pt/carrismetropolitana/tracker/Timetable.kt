package pt.carrismetropolitana.tracker

import java.util.Calendar
import java.util.TimeZone

/**
 * Scheduled departures derived from a pattern's own timetable.
 *
 * Carris Metropolitana's `/arrivals/by_stop` is a rolling window, and overnight it can be
 * entirely in the past: queried at 03:46 on 2026-10-12 it returned 196 entries — 188 from the
 * previous day and 8 from today up to 01:33 — and **none** in the future. Every one was then
 * filtered out and the stop read "Sem passagens previstas" even though the service day had not
 * started.
 *
 * `/patterns/{id}` carries the whole timetable in `trips[].schedule[]` together with `valid_on`,
 * and the app already downloads that payload at startup for its stop sequence and distances —
 * it was simply being discarded. Parsing it costs no extra network.
 */
object Timetable {

    /**
     * Scheduled departures at [stopId] from every pattern that runs on [date].
     *
     * Times come from `PatternStop.times`, in minutes since midnight; values past 1440 would
     * be after-midnight service, though the Carris Metropolitana API normalises those to
     * `00`–`02` instead of `24`–`26`, so they land on the following morning correctly.
     */
    fun arrivalsFromPatterns(
        patterns: Collection<PatternInfo>,
        stopId: String,
        nowUnix: Long,
        date: String,
        limit: Int = 6,
        zone: TimeZone = TimeZone.getDefault()
    ): List<Arrival> {
        val midnight = midnightUnix(nowUnix, date, zone)
        val out = ArrayList<Arrival>()
        for (pattern in patterns) {
            if (pattern.validOn.isNotEmpty() && date !in pattern.validOn) continue
            val stop = pattern.stops.firstOrNull { it.stopId == stopId } ?: continue
            for (minute in stop.times) {
                val at = midnight + minute * 60L
                if (at < nowUnix - 30) continue
                out += Arrival(
                    lineId = pattern.lineId,
                    headsign = pattern.headsign,
                    patternId = pattern.id,
                    scheduledUnix = at,
                    estimatedUnix = null,
                    observedUnix = null,
                    predicted = false
                )
            }
        }
        return out.sortedBy { it.scheduledUnix }.take(limit)
    }

    /**
     * Combines arrival sources, dropping the same departure listed twice.
     *
     * Exact match on line, pattern and scheduled second, not a fuzzy time window: both sources
     * derive from the same GTFS schedule, so the same bus carries the identical unix time in
     * each. A window would be wrong here — this network has genuinely distinct departures
     * 20-60 s apart (05:37:49 and 05:38:28 on line 716), and a 90 s window merged those two
     * buses into one.
     *
     * Earlier sources win, so a live arrival is never replaced by the timetable's copy.
     *
     * No size cap: this deduplicates, it does not choose. Deciding what the user sees is
     * [select]'s job, and it needs the full set to guarantee every tracked line is represented.
     * A cap here dropped three of the four lines at R Elias Garcia 103, where the four have
     * 445 departures between them and 1717 alone has 148 before 07:00.
     */
    fun merge(sources: List<List<Arrival>>): List<Arrival> {
        val sorted = sources.flatten().sortedBy { it.scheduledUnix }
        val seen = HashSet<String>()
        val kept = ArrayList<Arrival>()
        for (a in sorted) {
            if (!seen.add("${a.lineId}|${a.patternId}|${a.scheduledUnix}")) continue
            kept += a
        }
        return kept
    }

    /**
     * Picks what the user sees: the next [perLine] departures for every tracked line.
     *
     * Per line rather than a global cap, because a global one is wrong on this network: at
     * R Elias Garcia 103 the four lines have 445 departures between them and 1717 alone has
     * 148 before 07:00, so "soonest N overall" showed 1717 and nothing else.
     *
     * [lineKey] groups arrivals for one rider-facing line. It defaults to `lineId`, but the
     * app passes the short name because one line can be several route_ids — 746 is `108_0`
     * and `108_3` — and grouping by id would show four rows all badged 746.
     *
     * Pure, and separate from [AppState.upcoming] so it can be tested without a ViewModel.
     */
    fun select(
        arrivals: List<Arrival>,
        perLine: Int = 2,
        lineKey: (Arrival) -> String = { it.lineId }
    ): List<Arrival> {
        val soonestFirst = arrivals.sortedBy { it.effectiveUnix }
        return soonestFirst
            .groupBy(lineKey)
            .values
            .flatMap { it.take(perLine) }
            .sortedBy { it.effectiveUnix }
    }

    /** Today as `yyyyMMdd` in [zone], defaulting to the device's own. */
    fun today(nowUnix: Long, zone: TimeZone = TimeZone.getDefault()): String {
        val c = Calendar.getInstance(zone).apply { timeInMillis = nowUnix * 1000 }
        return "%04d%02d%02d".format(
            c.get(Calendar.YEAR),
            c.get(Calendar.MONTH) + 1,
            c.get(Calendar.DAY_OF_MONTH)
        )
    }

    /** Start of [date] in [zone], as unix seconds. */
    fun midnightUnix(nowUnix: Long, date: String, zone: TimeZone = TimeZone.getDefault()): Long {
        // clear() before setting, and it matters: Calendar is lenient, so setting MONTH on a day
        // that does not exist in the target month rolls over first. From 31 March, asking for
        // February resolves set(MONTH, 1) to 3 March and then the day lands on 28 March - a 28-day
        // error that silently shifts every departure built on it. Only reachable if a caller ever
        // passes a date other than today, which is exactly why it is worth being correct for.
        val c = Calendar.getInstance(zone).apply {
            timeInMillis = nowUnix * 1000
            clear()
            set(date.substring(0, 4).toInt(), date.substring(4, 6).toInt() - 1, date.substring(6, 8).toInt())
        }
        return c.timeInMillis / 1000
    }
}
