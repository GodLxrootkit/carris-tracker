package pt.carrismetropolitana.tracker

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

object MetroGtfs {

    private const val LINES = "metro_lines"
    private const val TIMES = "metro_times"

    private var linesJson: JSONObject? = null
    private var timesJson: JSONObject? = null

    suspend fun ensureLoaded(context: Context) = withContext(Dispatchers.IO) {
        if (linesJson != null) return@withContext
        val res = context.resources
        val linesId = res.getIdentifier(LINES, "raw", context.packageName)
        val timesId = res.getIdentifier(TIMES, "raw", context.packageName)
        linesJson = JSONObject(res.openRawResource(linesId).bufferedReader().use { it.readText() })
        timesJson = JSONObject(res.openRawResource(timesId).bufferedReader().use { it.readText() })
    }

    suspend fun allRoutes(): List<LineInfo> = withContext(Dispatchers.IO) {
        val json = linesJson ?: return@withContext emptyList()
        val arr = json.getJSONArray("routes")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            LineInfo(
                id = nsId(PROVIDER_METRO, o.getString("id")),
                shortName = o.getString("shortName"),
                longName = o.optString("longName"),
                color = o.optString("color", "#1F5FA9"),
                patternIds = o.getJSONArray("patternIds").let { a -> (0 until a.length()).map { j -> nsId(PROVIDER_METRO, a.getString(j)) } }
            )
        }
    }


    suspend fun pattern(id: String): PatternInfo? = withContext(Dispatchers.IO) {
        if (linesJson == null) return@withContext null
        val bare = bareOf(id)
        val arr = linesJson!!.getJSONArray("patterns")
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.getString("id") != bare) continue
            val stopsArr = o.getJSONArray("stops")
            val stops = (0 until stopsArr.length()).map { j ->
                val s = stopsArr.getJSONObject(j)
                PatternStop(
                    stopId = s.getString("stopId"),
                    sequence = s.getInt("sequence"),
                    km = s.getDouble("km"),
                    times = s.optJSONArray("times")?.let { t -> (0 until t.length()).map { t.getInt(it) } }.orEmpty()
                )
            }
            return@withContext PatternInfo(
                id = nsId(PROVIDER_METRO, o.getString("id")),
                lineId = nsId(PROVIDER_METRO, o.getString("lineId")),
                headsign = o.optString("headsign"),
                shapeId = null,
                validOn = emptySet(),
                stops = stops
            )
        }
        null
    }

    suspend fun shape(context: Context, shapeId: String): List<Pair<Double, Double>> = emptyList()

    suspend fun vehicles(): List<Vehicle> = emptyList()

    suspend fun scheduledArrivals(context: Context, stopId: String, nowUnix: Long, limit: Int = 6): List<Arrival> = withContext(Dispatchers.IO) {
        if (linesJson == null) ensureLoaded(context)
        val timesRoot = timesJson!!.getJSONObject("stops")
        val platforms = platformsOf(stopId)
        if (platforms.none { timesRoot.has(it) }) return@withContext emptyList()
        val patternsArr = linesJson!!.getJSONArray("patterns")
        val midnight = java.util.Calendar.getInstance().apply {
            timeInMillis = nowUnix * 1000
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis / 1000

        val out = ArrayList<Arrival>()
        for (platform in platforms) {
            if (!timesRoot.has(platform)) continue
            val perPattern = timesRoot.getJSONObject(platform)
            for (i in 0 until patternsArr.length()) {
                val o = patternsArr.getJSONObject(i)
                val pid = o.getString("id")
                if (!perPattern.has(pid)) continue
                val minutes = perPattern.getJSONArray(pid)
                val stopsArr = o.getJSONArray("stops")
                var hasStop = false
                for (j in 0 until stopsArr.length()) if (stopsArr.getJSONObject(j).getString("stopId") == platform) { hasStop = true; break }
                if (!hasStop) continue
                for (j in 0 until minutes.length()) {
                    val m = minutes.getInt(j)
                    val at = midnight + m * 60L
                    if (at < nowUnix - 30) continue
                    out += Arrival(
                        lineId = nsId(PROVIDER_METRO, o.getString("lineId")),
                        headsign = o.optString("headsign"),
                        patternId = nsId(PROVIDER_METRO, pid),
                        scheduledUnix = at,
                        estimatedUnix = null,
                        observedUnix = null,
                        predicted = false
                    )
                }
            }
        }
        // Limit per line, not overall: a busy line at an interchange must not crowd the other
        // line out of the card before `Timetable.select` gets to group them.
        out.distinctBy { it.patternId to it.scheduledUnix }
            .groupBy { it.lineId }
            .values
            .flatMap { line -> line.sortedBy { it.scheduledUnix }.take(limit) }
            .sortedBy { it.scheduledUnix }
    }

    /**
     * Every station, with one entry per name rather than one per platform.
     *
     * Metro's GTFS gives an interchange a parent station (no line ids) plus a stop per platform,
     * so Alameda arrives as AM, AM1, AM2, AM3 and AM4. Returning those raw made the picker show
     * five "Alameda" rows, and saving the parent produced a card with no times.
     */
    fun stops(): List<StopInfo> = mergeMetroStations(rawStops())

    /** True for a platform too, so saved stops from before the merge still resolve their network. */
    fun hasStop(id: String, name: String): Boolean {
        val bare = bareOf(id)
        return rawStops().any { it.id == bare && it.name == name }
    }

    private fun rawStops(): List<StopInfo> {
        val arr = linesJson?.getJSONArray("stops") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            StopInfo(
                id = o.getString("id"),
                name = o.getString("name"),
                lat = o.getDouble("lat"),
                lon = o.getDouble("lon"),
                lineIds = o.getJSONArray("lineIds").let { a -> (0 until a.length()).map { a.getString(it) }.map { nsId(PROVIDER_METRO, it) } }
            )
        }
    }

    /** Platform stop ids that make up the station the user picked. */
    private fun platformsOf(stopId: String): Set<String> {
        val all = rawStops()
        val bare = bareOf(stopId)
        val station = all.firstOrNull { it.id == bare } ?: return setOf(bare)
        return all.filter { it.name == station.name }.mapTo(LinkedHashSet()) { it.id }
    }

    fun excluded(): Set<String> = emptySet()
}

/**
 * One picker row per station; see [MetroGtfs.stops].
 *
 * shortcut: grouped by name because the bundled asset drops GTFS `parent_station`; switch to
 * that column if two stations ever share a name.
 */
internal fun mergeMetroStations(stops: List<StopInfo>): List<StopInfo> =
    stops.groupBy { it.name }.map { (_, sameName) ->
        val parent = sameName.firstOrNull { it.lineIds.isEmpty() } ?: sameName.first()
        parent.copy(lineIds = sameName.flatMap { it.lineIds }.distinct())
    }
