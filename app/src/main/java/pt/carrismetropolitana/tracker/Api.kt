package pt.carrismetropolitana.tracker

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

const val API_BASE = "https://api.carrismetropolitana.pt/v2"

/**
 * Two providers feed the app: Carris Metropolitana (JSON API, the network outside Lisbon) and
 * CARRIS (GTFS, Lisbon itself). Line and pattern ids are namespaced on the way in, so an id from
 * one provider can never collide with the other and the `patterns` cache keyed by pattern id
 * stays unambiguous.
 */
const val PROVIDER_CM = "cm"
const val PROVIDER_CARRIS = "carris"

fun nsId(provider: String, id: String): String = "$provider:$id"

fun bareOf(id: String): String = id.substringAfter(':')

/** Carris Metropolitana lines, namespaced from their raw API ids. */
val CM_TRACKED_LINE_IDS = listOf("1707", "1717", "1720", "1721").map { nsId(PROVIDER_CM, it) }

/**
 * CARRIS route ids, as passed to `tools/build_carris_asset.py --routes`.
 *
 * One rider-facing line can be several route_ids — 746 is `108_0` (Marquês Pombal - Estação
 * Damaia) and `108_3` (Rossio - Sul e Sueste), both short name 746 — so both must be listed.
 * They share a short name and the UI groups them; see [shortNameOf] and the map filter.
 */
val CARRIS_TRACKED_LINE_IDS =
    listOf("164_0", "108_0", "108_3").map { nsId(PROVIDER_CARRIS, it) }

val TRACKED_LINE_IDS = CM_TRACKED_LINE_IDS + CARRIS_TRACKED_LINE_IDS

/**
 * Display name for a namespaced line id, e.g. `carris:164_0` -> `716`.
 *
 * Ids are namespaced internally but users only ever see short names. Falls back to the bare id
 * so a line missing from the catalog degrades to something recognisable.
 */
fun List<LineInfo>.shortNameOf(id: String): String =
    firstOrNull { it.id == id }?.shortName ?: bareOf(id)

data class LineInfo(
    val id: String,
    val shortName: String,
    val longName: String,
    val color: String,
    val patternIds: List<String>
)

data class StopInfo(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val lineIds: List<String>
)

data class Arrival(
    val lineId: String,
    val headsign: String,
    val patternId: String,
    val scheduledUnix: Long,
    val estimatedUnix: Long?,
    val observedUnix: Long?,
    /** Computed locally from the live vehicle position; the API sends no estimates. */
    val predicted: Boolean = false
) {
    val effectiveUnix: Long get() = estimatedUnix ?: observedUnix ?: scheduledUnix
    val realtime: Boolean get() = estimatedUnix != null || observedUnix != null || predicted
}

/**
 * One stop of a pattern, in route order. [km] is the cumulative distance from the pattern
 * origin.
 *
 * [times] are scheduled departures in minutes since midnight of the service day, ascending.
 * GTFS allows times past 24:00 for after-midnight service, so values above 1440 mean the
 * following morning relative to the service day. Only the CARRIS asset populates it — the
 * Carris Metropolitana API serves the timetable separately, from `/arrivals/by_stop`.
 */
data class PatternStop(
    val stopId: String,
    val sequence: Int,
    val km: Double,
    val times: List<Int> = emptyList()
)

data class PatternInfo(
    val id: String,
    val lineId: String,
    val headsign: String,
    val shapeId: String?,
    /** `yyyyMMdd` dates this pattern runs, from `valid_on`. Empty means "assume always". */
    val validOn: Set<String> = emptySet(),
    val stops: List<PatternStop>
)

data class Vehicle(
    val id: String,
    val lineId: String,
    val patternId: String,
    val stopId: String?,
    val lat: Double,
    val lon: Double,
    val speed: Int,
    val status: String,
    val timestampMs: Long,
    /**
     * Which occurrence of [stopId] the vehicle is at, on patterns that visit a stop more than
     * once. Null when the provider does not say - Carris Metropolitana's `/vehicles` has no such
     * field - in which case [Eta.vehicleStop] falls back to matching the stop id.
     */
    val currentStopSequence: Int? = null
)

/** GTFS ids carry version prefixes like "[VNWG3][LA77N]1707_0_1"; strip them for matching. */
fun bareId(id: String): String = id.substringAfterLast(']')

object Api {

    /** The whole network's lines, cached per process; see [allLines]. */
    private var allLinesCache: List<LineInfo>? = null

suspend fun lines(ids: List<String>): List<LineInfo> = withContext(Dispatchers.IO) {
        allLines().filter { it.id in ids }
    }

    /**
     * Every line on the network, 717 of them.
     *
     * One request and 39 KB gzipped, and the Settings picker needs the whole list rather than a
     * hardcoded one, so this is cached per process alongside the line data rather than refetched
     * on every refresh.
     */
suspend fun allLines(): List<LineInfo> = withContext(Dispatchers.IO) {
        allLinesCache?.let { return@withContext it }
        val arr = JSONArray(get("$API_BASE/lines"))
        allLinesCache = (0 until arr.length()).mapNotNull { arr.getJSONObject(it) }.map {
            LineInfo(
                id = it.getString("id"),
                shortName = it.getString("short_name"),
                longName = it.getString("long_name"),
                color = it.getString("color"),
                patternIds = it.optJSONArray("pattern_ids")?.let { a -> (0 until a.length()).map { i -> a.getString(i) } }.orEmpty()
            )
        }
        allLinesCache!!
    }

    /** Every stop in the network (~12.7k). Fetched once per app run, used for search + nearest. */
    suspend fun allStops(): List<StopInfo> = withContext(Dispatchers.IO) {
        val arr = JSONArray(get("$API_BASE/stops"))
        (0 until arr.length()).mapNotNull { arr.getJSONObject(it) }.mapNotNull {
            if (it.isNull("lat") || it.isNull("lon")) null
            else StopInfo(
                id = it.getString("id"),
                name = it.getString("long_name"),
                lat = it.getDouble("lat"),
                lon = it.getDouble("lon"),
                lineIds = it.optJSONArray("line_ids")?.let { a -> (0 until a.length()).map { i -> a.getString(i) } }.orEmpty()
            )
        }
    }

    /** All arrivals of one stop for today (scheduled + estimates when available). */
    suspend fun arrivalsByStop(stopId: String): List<Arrival> = withContext(Dispatchers.IO) {
        val arr = JSONArray(get("$API_BASE/arrivals/by_stop/$stopId"))
        (0 until arr.length()).mapNotNull { arr.getJSONObject(it) }.map {
            Arrival(
                lineId = it.getString("line_id"),
                headsign = it.optString("headsign"),
                patternId = bareId(it.optString("pattern_id")),
                scheduledUnix = it.optLong("scheduled_arrival_unix"),
                estimatedUnix = it.optLong("estimated_arrival_unix").takeIf { v -> !it.isNull("estimated_arrival_unix") && v > 0 },
                observedUnix = it.optLong("observed_arrival_unix").takeIf { v -> !it.isNull("observed_arrival_unix") && v > 0 }
            )
        }
    }

    /** Stop sequence of a pattern, with cumulative km from its origin. */
    suspend fun patternStops(patternId: String): List<PatternStop> = withContext(Dispatchers.IO) {
        pattern(patternId)?.stops.orEmpty()
    }

    suspend fun vehicles(): List<Vehicle> = withContext(Dispatchers.IO) {
        val arr = JSONArray(get("$API_BASE/vehicles"))
        (0 until arr.length()).mapNotNull { arr.getJSONObject(it) }.map {
            Vehicle(
                id = bareId(it.getString("id")),
                lineId = it.getString("line_id"),
                patternId = bareId(it.optString("pattern_id")),
                stopId = it.optString("stop_id").ifEmpty { null },
                lat = it.optDouble("lat"),
                lon = it.optDouble("lon"),
                speed = it.optInt("speed"),
                status = it.optString("current_status"),
                timestampMs = it.optLong("timestamp")
            )
        }
    }

    /**
     * Pattern metadata: stop sequence, cumulative km, shape id, and the timetable.
     *
     * `trips[].schedule[]` carries every scheduled arrival at every stop and was previously
     * discarded, leaving `/arrivals/by_stop` as the only source of scheduled times — and that
     * window can be entirely in the past overnight. Parsed here so the payload already being
     * downloaded does the work.
     */
    suspend fun pattern(patternId: String): PatternInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val arr = JSONArray(get("$API_BASE/patterns/$patternId"))
            val o = arr.optJSONObject(0) ?: return@runCatching null

            // Minutes-of-day per stop, from every trip on this pattern.
            val timesByStop = HashMap<String, MutableSet<Int>>()
            o.optJSONArray("trips")?.let { trips ->
                for (i in 0 until trips.length()) {
                    val schedule = trips.optJSONObject(i)?.optJSONArray("schedule") ?: continue
                    for (j in 0 until schedule.length()) {
                        val e = schedule.optJSONObject(j) ?: continue
                        // arrival_time_24h keeps after-midnight service as 00-02 rather than
                        // 24-26, which is what we want: it belongs to the following morning.
                        val hhmmss = e.optString("arrival_time_24h").ifEmpty { e.optString("arrival_time") }
                        if (hhmmss.isEmpty()) continue
                        val parts = hhmmss.split(':')
                        if (parts.size < 2) continue
                        val minute = (parts[0].toIntOrNull() ?: continue) * 60 +
                            (parts[1].toIntOrNull() ?: continue)
                        timesByStop.getOrPut(e.optString("stop_id")) { HashSet() } += minute
                    }
                }
            }

            PatternInfo(
                id = o.optString("id"),
                lineId = o.optString("line_id"),
                headsign = o.optString("headsign"),
                shapeId = o.optString("shape_id").ifEmpty { null },
                validOn = o.optJSONArray("valid_on")?.let { a ->
                    (0 until a.length()).mapNotNull { a.optString(it).ifEmpty { null } }.toSet()
                }.orEmpty(),
                stops = o.optJSONArray("path")?.let { a ->
                    (0 until a.length()).mapNotNull { i ->
                        val e = a.optJSONObject(i) ?: return@mapNotNull null
                        val stopId = e.optString("stop_id")
                        PatternStop(
                            stopId = stopId,
                            sequence = e.optInt("stop_sequence"),
                            km = e.optDouble("distance"),
                            times = timesByStop[stopId]?.toList()?.sorted().orEmpty()
                        )
                    }
                }.orEmpty()
            )
        }.getOrNull()
    }

    /** Shape geometry as (lat, lon) pairs, in route order. */
    suspend fun shape(shapeId: String): List<Pair<Double, Double>> = withContext(Dispatchers.IO) {
        val o = JSONObject(get("$API_BASE/shapes/$shapeId"))
        val coords = o.getJSONObject("geojson").getJSONObject("geometry").getJSONArray("coordinates")
        (0 until coords.length()).map {
            val c = coords.getJSONArray(it)
            c.getDouble(1) to c.getDouble(0)
        }
    }

    /**
     * Fetches [url] as text, asking for gzip.
     *
     * `/vehicles` is ~790 KB of JSON and the map polls it often, so the compression is worth
     * 7x the bytes — measured: 792 KB raw, 109 KB with `Accept-Encoding: gzip`. It has to be
     * asked for; `HttpURLConnection` does not send the header on its own.
     */
    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 25_000
            conn.setRequestProperty("Accept-Encoding", "gzip")
            val code = conn.responseCode
            // No URL in the message: this text is surfaced to the user in the error banner, and a
            // raw endpoint URL is noise there. The host and path are not secret either way.
            if (code !in 200..299) error("HTTP $code")
            val stream = conn.inputStream.let { if (conn.contentEncoding == "gzip") GZIPInputStream(it) else it }
            return stream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
