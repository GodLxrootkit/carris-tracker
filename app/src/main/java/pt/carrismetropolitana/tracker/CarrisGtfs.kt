package pt.carrismetropolitana.tracker

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * CARRIS (Lisbon municipal) data source: the bundled GTFS asset plus the live GTFS-RT feed.
 *
 * CARRIS publishes an open GTFS feed with no authentication, but the zip is 47 MB and its
 * `stop_times.txt` is 144 MB, so it cannot be parsed on a phone. [tools/build_carris_asset.py]
 * distils the tracked routes into a 180 KB asset under `res/raw/carris_lines.json`, which holds
 * the same shape [PatternInfo] expects from the Carris Metropolitana API — stop sequence plus
 * cumulative km — so [Eta] needs no changes to work on either provider.
 *
 * Live positions come from the GTFS-RT feed at runtime; see [GtfsRt] for the decoder.
 * CARRIS publishes no `tripupdates`, so arrivals are predicted, exactly as they already are for
 * Carris Metropolitana.
 */
/**
 * One calendar service, resolved by the asset generator from calendar.txt plus
 * calendar_dates.txt.
 *
 * A service is either a weekday mask within a date range, or a list of one-off dates. The
 * seasonal services in this feed (`Agosto_*`, `Verao_*`) have no weekday row at all, so a
 * mask of zero means "only on the dates listed".
 */
internal class ServiceRule(
    val days: Int,
    val start: String,
    val end: String,
    val dates: Set<String> = emptySet(),
    val removed: Set<String> = emptySet()
) {
    /** Whether this service runs on [date] (`yyyyMMdd`), [dow] being 0 = Monday. */
    fun activeOn(date: String, dow: Int): Boolean {
        if (date < start || date > end) return false
        if (date in removed) return false
        if (days == 0) return date in dates
        return date in dates || (days and (1 shl dow)) != 0
    }

    companion object {
        fun parse(o: JSONObject) = ServiceRule(
            days = o.optInt("days"),
            start = o.optString("start", "19700101"),
            end = o.optString("end", "20991231"),
            dates = o.optJSONArray("dates")?.mapStrings().orEmpty().toSet(),
            removed = o.optJSONArray("removed")?.mapStrings().orEmpty().toSet()
        )
    }
}

private fun JSONArray.mapStrings(): List<String> =
    (0 until length()).map { optString(it) }

object CarrisGtfs {

    private const val RT_URL =
        "https://gateway.carris.pt/gateway/gtfs/api/v2.11/GTFS/realtime/vehiclepositions"

    /**
     * The asset is three files, split by which screen needs them.
     *
     * At the full 176-route feed they are 1.4 MB, 4.1 MB and 2.7 MB. Parsing all of it at once
     * meant seconds of startup and a transient heap in the hundreds of megabytes through
     * `org.json`, which is an OOM risk on a small phone rather than an APK-size problem - so the
     * big two are loaded on first use: departures only when the Paragens tab needs a timetable,
     * geometry only when a selected line has to be drawn.
     */
    private const val LINES = "carris_lines"
    private const val TIMES = "carris_times"
    private const val SHAPES = "carris_shapes"

    /** Bare ids of the routes the app currently shows; [lines] narrows the asset to these. */
    private val TRACKED_BARE = CARRIS_TRACKED_LINE_IDS.map { bareOf(it) }.toSet()

    private var linesAsset: LinesAsset? = null
    private var stopTimes: Map<String, Map<String, List<Int>>>? = null
    private var shapePoints: Map<String, List<List<Double>>>? = null

    /**
     * Picks the pattern that represents a group of them.
     *
     * Some routes are split into a full run and a short turn that share one `shape_id`, so the
     * feed carries two patterns with the **same id** and different stop lists - `75_0` ASC is a
     * 35-stop 1004-trip run and a 21-stop 81-trip short turn. GTFS-RT reports only route and
     * direction, so nothing in the feed distinguishes them and every bus on that pair has to be
     * projected onto a single pattern.
     *
     * The fullest one wins, by trips first: in the shipped asset the short turn's stops are a
     * strict subset of the long run's, so the long pattern still has a distance for every stop a
     * short-turning bus serves, and it is what the overwhelming majority of trips run on. The id
     * is the final tie-break so the choice is stable rather than input-order.
     *
     * Both ways of reaching a pattern have to agree, or a bus gets measured against one geometry
     * here and a different one there. That was the bug: the live-feed lookup took the last match
     * and the by-id lookup took the first, and they disagreed for exactly the four route/direction
     * pairs that have duplicates.
     */
    private val REPRESENTATIVE =
        compareBy<PatternAsset>({ it.tripCount }, { it.stops.size }, { it.id })

    /**
     * Serialises asset loading.
     *
     * The caches below are check-then-act and the check spans a suspension point, so without this
     * every concurrent caller sees a null cache and re-parses the whole file. That was happening on
     * every cold start: `refresh()` fans out one coroutine per saved stop, each of which reaches
     * [ensureTimes], and `loadShapes()` fans out one per tracked line, each reaching
     * [ensureShapes]. Three saved stops meant reading and parsing the 4.1 MB timetable three times
     * over, and the same for shapes.
     */
    private val loadLock = Mutex()

    private suspend fun readAsset(context: Context, name: String): JSONObject =
        withContext(Dispatchers.IO) {
            val text = context.resources.openRawResource(
                when (name) {
                    TIMES -> R.raw.carris_times
                    SHAPES -> R.raw.carris_shapes
                    else -> R.raw.carris_lines
                }
            ).bufferedReader().use { it.readText() }
            JSONObject(text)
        }

    /**
     * Reads the bundled route, pattern and stop data. Safe to call repeatedly and from any number
     * of coroutines; only the first does the work.
     */
    suspend fun ensureLoaded(context: Context): Unit = loadLock.withLock {
        linesAsset?.let { return@withLock }
        // The parse has to be inside the IO context, not just the file read: carris_lines.json
        // turns into 8393 PatternStop objects, and doing that on the main thread is a visible stall
        // on every cold start.
        linesAsset = withContext(Dispatchers.IO) { parseLines(readAsset(context, LINES)) }
    }

    /** Departures per stop then pattern. Only the Paragens tab needs this. */
    private suspend fun ensureTimes(context: Context): Map<String, Map<String, List<Int>>> =
        loadLock.withLock {
            stopTimes?.let { return@withLock it }
            // 946 291 boxed Integers: well past the point where this belongs off the main thread.
            withContext(Dispatchers.IO) {
                val byStop = readAsset(context, TIMES).optJSONObject("stopTimes")
                if (byStop == null) {
                    stopTimes = emptyMap()
                    return@withContext emptyMap()
                }
                val out = HashMap<String, Map<String, List<Int>>>()
                byStop.keys().forEach { stopId ->
                    val perPattern = byStop.optJSONObject(stopId) ?: return@forEach
                    val departures = HashMap<String, List<Int>>()
                    perPattern.keys().forEach { patternId ->
                        departures[patternId] = perPattern.optJSONArray(patternId)?.mapInts().orEmpty()
                    }
                    out[stopId] = departures
                }
                stopTimes = out
                out
            }
        }

    /** Polyline geometry. Only the map needs this, and only for a line that is selected. */
    private suspend fun ensureShapes(context: Context): Map<String, List<List<Double>>> =
        loadLock.withLock {
            shapePoints?.let { return@withLock it }
            // 141 862 points, each becoming a listOf(Double, Double).
            withContext(Dispatchers.IO) {
                val shapesObj = readAsset(context, SHAPES).optJSONObject("shapes")
                val out = HashMap<String, List<List<Double>>>()
                shapesObj?.keys()?.forEach { key ->
                    // Shape points are [lat, lon] arrays, not objects, so they need the array mapper.
                    val pts = shapesObj.optJSONArray(key) ?: return@forEach
                    out[key] = (0 until pts.length()).mapNotNull { i ->
                        val pt = pts.optJSONArray(i) ?: return@mapNotNull null
                        listOf(pt.getDouble(0), pt.getDouble(1))
                    }
                }
                shapePoints = out
                out
            }
        }

    /** Feed version the asset was generated from, e.g. `20261017`. */
    fun feedVersion(): String? = linesAsset?.feedVersion

    /** Routes the feed ships but whose distance data is unusable, so they cannot be shown. */
    fun excluded(): Set<String> = linesAsset?.excluded.orEmpty()

    /** Tracked CARRIS lines, as [LineInfo] with namespaced ids. */
    fun lines(): List<LineInfo> = allRoutes()
        .filter { bareOf(it.id) in TRACKED_BARE }

    /**
     * Every route in the feed, not just the tracked ones.
     *
     * The asset holds all 176 routes; [lines] narrows that to the selection. Everything the
     * line picker will need comes from here.
     */
    fun allRoutes(): List<LineInfo> = linesAsset?.routes.orEmpty().map { r ->
        LineInfo(
            id = nsId(PROVIDER_CARRIS, r.id),
            shortName = r.shortName,
            longName = r.longName,
            color = r.color,
            patternIds = r.patternIds.map { nsId(PROVIDER_CARRIS, it) }
        )
    }

    /**
     * One [PatternInfo] per id, collapsed and mapped out of the asset.
     *
     * Cached against the asset instance it came from, rather than with `lazy`: [patterns] is called
     * by [pattern], which `AppState.loadPatterns` calls once per pattern id, and rebuilding all
     * 309 patterns on each of those is ~8 400 object allocations per lookup. Keying the cache on
     * the asset means a reload rebuilds it instead of serving the previous load's list forever.
     */
    @Volatile
    private var patternCache: Pair<LinesAsset, List<PatternInfo>>? = null

    /**
     * Collapses the duplicate ids rather than renaming them, so that everything downstream -
     * [pattern], `AppState.loadPatterns`, the shape lookup - sees exactly one pattern per id and
     * it is the same one the live feed resolves to via [patternFor]. Renaming the duplicates would
     * instead split the timetable, which is keyed on the shared id.
     */
    fun patterns(): List<PatternInfo> {
        val asset = linesAsset ?: return emptyList()
        patternCache?.let { (a, cached) -> if (a === asset) return cached }

        val mapped = asset.patterns
            .groupBy { it.id }
            .map { (_, group) -> group.maxWith(REPRESENTATIVE) }
            .map { p ->
                PatternInfo(
                    id = nsId(PROVIDER_CARRIS, p.id),
                    lineId = nsId(PROVIDER_CARRIS, p.lineId),
                    headsign = p.headsign,
                    shapeId = p.shapeId,
                    stops = p.stops.map { PatternStop(it.stopId, it.sequence, it.km) }
                )
            }
        patternCache = asset to mapped
        return mapped
    }

    /**
     * One pattern by its **bare** id, matching how `Api.pattern` is called for Carris
     * Metropolitana.
     *
     * [patterns] namespaces its ids, so comparing directly against the argument never matched
     * anything and every CARRIS pattern silently failed to load.
     */
    fun pattern(patternId: String): PatternInfo? =
        patterns().firstOrNull { bareOf(it.id) == patternId }

    /**
     * The pattern a live bus on [routeId] running in [directionId] is projected onto.
     *
     * Exposed because it has to agree with [pattern] and that is only checkable from outside. Both
     * go through the same representative choice, which is the whole point: when they disagreed,
     * a bus was measured against a different geometry depending on which one asked.
     */
    fun patternFor(routeId: String, directionId: Int): String? =
        linesAsset?.patternFor(routeId, directionId)

    suspend fun shape(context: Context, shapeId: String): List<Pair<Double, Double>> =
        ensureShapes(context)[shapeId].orEmpty().map { it[0] to it[1] }

    fun stops(): List<StopInfo> = linesAsset?.stops.orEmpty().map { s ->
        StopInfo(
            id = s.id,
            name = s.name,
            lat = s.lat,
            lon = s.lon,
            lineIds = s.lineIds.map { nsId(PROVIDER_CARRIS, it) }
        )
    }

    /**
     * Live vehicles, with namespaced ids so they merge cleanly with Carris Metropolitana.
     *
     * The RT feed identifies a vehicle by `route_id` + `direction_id`; the asset has one pattern
     * per direction, so the pair resolves to a pattern and [Eta] can place the bus on it.
     *
     * "One pattern per direction" holds only because [LinesAsset.REPRESENTATIVE] collapses the
     * duplicates. Without it a short-turning bus would be projected onto whichever variant the
     * lookup happened to return, which is not necessarily the one it is running.
     */
    suspend fun vehicles(): List<Vehicle> = withContext(Dispatchers.IO) {
        val a = linesAsset ?: return@withContext emptyList()
        val rt = runCatching { GtfsRt.parse(getBytes(RT_URL)) }.getOrDefault(emptyList())
        rt.mapNotNull { v ->
            val lineId = nsId(PROVIDER_CARRIS, v.routeId)
            val patternId = a.patternFor(v.routeId, v.directionId) ?: return@mapNotNull null
            Vehicle(
                id = v.vehicleId ?: v.tripId,
                lineId = lineId,
                patternId = nsId(PROVIDER_CARRIS, patternId),
                stopId = v.stopId,
                currentStopSequence = v.currentStopSequence,
                lat = v.lat,
                lon = v.lon,
                speed = v.speedKmh,
                status = v.status,
                timestampMs = v.timestampSec * 1000
            )
        }
    }

    /**
     * Scheduled departures at [stopId] from now on, for patterns running today.
     *
     * CARRIS publishes no `tripupdates` and has no arrivals endpoint, so without this the
     * Paragens tab could only ever show a bus that happened to be circulating — at 03:00 that
     * is nothing, and every CARRIS stop read "Sem passagens previstas" even though the
     * timetable had five o'clock departures all along.
     *
     * These are plain scheduled times, so `predicted` stays false and the UI labels them
     * "horário" exactly as it does for the Carris Metropolitana timetable.
     */
    suspend fun scheduledArrivals(
        context: Context,
        stopId: String,
        nowUnix: Long,
        limit: Int = 6
    ): List<Arrival> {
        val a = linesAsset ?: return emptyList()
        if (!a.assets.contains(stopId)) return emptyList()
        // Only now is the 4 MB of departures worth opening, and only for a stop the user saved.
        val perPattern = ensureTimes(context)[stopId] ?: return emptyList()

        val cal = java.util.Calendar.getInstance().apply { timeInMillis = nowUnix * 1000 }
        val date = "%04d%02d%02d".format(
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH)
        )
        // Calendar.SUNDAY is 1; GTFS weekdays are 0 = Monday.
        val dow = (cal.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7

        val midnight = java.util.Calendar.getInstance().apply {
            timeInMillis = nowUnix * 1000
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis / 1000

        val out = ArrayList<Arrival>()
        for (pattern in a.patterns) {
            if (pattern.rules.none { it.activeOn(date, dow) }) continue
            // Departures live in their own file, keyed by stop then pattern, so that a stop
            // served by two lines keeps both sets and "only my selected lines" stays possible.
            val minutes = perPattern[pattern.id] ?: continue
            if (pattern.stops.none { it.stopId == stopId }) continue
            for (minute in minutes) {
                // Values above 1440 are after-midnight service and land tomorrow naturally.
                val at = midnight + minute * 60L
                if (at < nowUnix - 30) continue
                out += Arrival(
                    lineId = nsId(PROVIDER_CARRIS, pattern.lineId),
                    headsign = pattern.headsign,
                    patternId = nsId(PROVIDER_CARRIS, pattern.id),
                    scheduledUnix = at,
                    estimatedUnix = null,
                    observedUnix = null,
                    predicted = false
                )
            }
        }
        return out.sortedBy { it.scheduledUnix }.take(limit)
    }

    private fun getBytes(url: String): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 25_000
            val code = conn.responseCode
            if (code !in 200..299) error("HTTP $code em $url")
            return conn.inputStream.readBytes()
        } finally {
            conn.disconnect()
        }
    }

    /* ---------------- asset model + parsing ---------------- */

    private class LinesAsset(
        val feedVersion: String,
        val routes: List<RouteAsset>,
        val patterns: List<PatternAsset>,
        val stops: List<StopAsset>,
        /** Route ids whose distance data the feed gets wrong; shown as unavailable, never used. */
        val excluded: Set<String>
    ) {
        private val tracked = routes.map(RouteAsset::id).toSet()

        /** `(routeId, directionId)` to pattern id, built once rather than per vehicle. */
        private val byRouteDirection = patterns
            .filter { it.lineId in tracked }
            .groupBy { it.lineId to it.direction }
            .mapValues { (_, group) -> group.maxWith(REPRESENTATIVE) }

        /** Stops any tracked pattern calls at — the guard against answering for CM stops. */
        val assets: Set<String> = patterns.filter { it.lineId in tracked }
            .flatMap { p -> p.stops.map { it.stopId } }
            .toSet()

        fun patternFor(routeId: String, directionId: Int): String? =
            byRouteDirection[routeId to directionId]?.id
    }

    private class RouteAsset(
        val id: String,
        val shortName: String,
        val longName: String,
        val color: String,
        val patternIds: List<String>
    )

    private class PatternAsset(
        val id: String,
        val lineId: String,
        val headsign: String,
        val shapeId: String?,
        val direction: Int,
        val tripCount: Int,
        val rules: List<ServiceRule>,
        val stops: List<PatternStop>
    )

    /**
     * Picks the pattern to represent a group of them.
     *
     * Some routes are split into a full run and a short turn that share one `shape_id`, so the
     * feed carries two patterns with the **same id** and different stop lists - `75_0` ASC is a
     * 35-stop 1004-trip run and a 21-stop 81-trip short turn. GTFS-RT reports only route and
     * direction, so nothing in the feed distinguishes them and every bus on that pair has to be
     * projected onto one pattern.
     *
     * The fullest one wins, by trips first: in the shipped asset the short turn's stops are a
     * strict subset of the long run's, so choosing the long pattern still leaves a distance for
     * every stop a short-turning bus serves, and it is what the overwhelming majority of trips
     * run on. The id is the final tie-break so the choice is stable rather than input-order.
     *
     * Both lookups that can reach a pattern - by route and direction from the live feed, and by
     * id from the asset - have to agree, or a bus ends up measured against a different pattern
     * depending on which one asked. That was the bug: one took the first match and the other the
     * last, and they disagreed for exactly these four route/direction pairs.
     */
    private class StopAsset(
        val id: String,
        val name: String,
        val lat: Double,
        val lon: Double,
        val lineIds: List<String>
    )

    private fun parseLines(o: JSONObject): LinesAsset {
        val patterns = o.getJSONArray("patterns").mapObjects { p ->
            PatternAsset(
                id = p.getString("id"),
                lineId = p.getString("lineId"),
                headsign = p.optString("headsign"),
                shapeId = p.optString("shapeId").ifEmpty { null },
                direction = p.optInt("direction"),
                tripCount = p.optInt("tripCount"),
                rules = p.optJSONArray("rules").mapObjects { ServiceRule.parse(it) },
                // Departures are no longer here; they live in carris_times.json.
                stops = p.getJSONArray("stops").mapObjects {
                    PatternStop(
                        stopId = it.getString("stopId"),
                        sequence = it.getInt("sequence"),
                        km = it.getDouble("km")
                    )
                }
            )
        }

        val routes = o.getJSONArray("routes").mapObjects { r ->
            val id = r.getString("id")
            RouteAsset(
                id = id,
                shortName = r.optString("shortName"),
                longName = r.optString("longName"),
                // MainActivity parses this straight into a Compose Color; an empty string
                // would throw at runtime rather than fail visibly.
                color = r.optString("color").ifEmpty { "#888888" },
                patternIds = patterns.filter { it.lineId == id }.map { it.id }
            )
        }

        return LinesAsset(
            feedVersion = o.optString("feedVersion"),
            routes = routes,
            patterns = patterns.filter { it.lineId in routes.map(RouteAsset::id) },
            stops = o.optJSONArray("stops").mapObjects {
                StopAsset(
                    id = it.getString("id"),
                    name = it.optString("name"),
                    lat = it.getDouble("lat"),
                    lon = it.getDouble("lon"),
                    lineIds = it.optJSONArray("lineIds")?.mapStrings().orEmpty()
                )
            },
            excluded = o.optJSONArray("excluded").mapObjects { it.optString("lineId") }
                .filter { it.isNotEmpty() }
                .toSet()
        )
    }

    private fun <T> JSONArray.mapObjects(f: (JSONObject) -> T): List<T> =
        (0 until length()).mapNotNull { optJSONObject(it) }.map(f)

    private fun JSONArray.mapInts(): List<Int> =
        (0 until length()).map { optInt(it) }
}