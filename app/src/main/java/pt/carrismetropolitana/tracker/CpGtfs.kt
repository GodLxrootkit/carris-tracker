package pt.carrismetropolitana.tracker

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

object CpGtfs {

    private const val LINES = "cp_lines"
    private const val TIMES = "cp_times"

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
                id = nsId(PROVIDER_CP, o.getString("id")),
                shortName = o.getString("shortName"),
                longName = o.optString("longName"),
                color = o.optString("color", "#1F5FA9"),
                patternIds = o.getJSONArray("patternIds").let { a -> (0 until a.length()).map { j -> nsId(PROVIDER_CP, a.getString(j)) } }
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
                id = nsId(PROVIDER_CP, o.getString("id")),
                lineId = nsId(PROVIDER_CP, o.getString("lineId")),
                headsign = o.optString("headsign"),
                shapeId = null,
                validOn = o.strings("validOn"),
                stops = stops,
                validFrom = o.optString("validFrom").ifEmpty { null },
                validTo = o.optString("validTo").ifEmpty { null },
                validDays = o.strings("validDays"),
                validExcept = o.strings("validExcept"),
                validAdd = o.strings("validAdd"),
                dateOnly = o.optBoolean("dateOnly")
            )
        }
        null
    }

    suspend fun shape(context: Context, shapeId: String): List<Pair<Double, Double>> = emptyList()

    suspend fun vehicles(): List<Vehicle> = emptyList()

    private fun JSONObject.strings(name: String): Set<String> =
        optJSONArray(name)?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty()

    private fun runsOn(o: JSONObject, date: String, day: String): Boolean = serviceRunsOn(
        date = date,
        day = day,
        validOn = o.strings("validOn"),
        validFrom = o.optString("validFrom").ifEmpty { null },
        validTo = o.optString("validTo").ifEmpty { null },
        validDays = o.strings("validDays"),
        validExcept = o.strings("validExcept"),
        validAdd = o.strings("validAdd"),
        dateOnly = o.optBoolean("dateOnly")
    )

    suspend fun scheduledArrivals(context: Context, stopId: String, nowUnix: Long, limit: Int = 6): List<Arrival> = withContext(Dispatchers.IO) {
        if (linesJson == null) ensureLoaded(context)
        val timesRoot = timesJson!!.getJSONObject("stops")
        if (!timesRoot.has(stopId)) return@withContext emptyList()
        val perPattern = timesRoot.getJSONObject(stopId)
        val patternsArr = linesJson!!.getJSONArray("patterns")
        val midnight = java.util.Calendar.getInstance().apply {
            timeInMillis = nowUnix * 1000
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis / 1000
        val today = Timetable.today(nowUnix)
        val day = Timetable.dayName(nowUnix)
        val yesterday = java.util.Calendar.getInstance().apply {
            timeInMillis = nowUnix * 1000
            add(java.util.Calendar.DAY_OF_YEAR, -1)
        }.timeInMillis / 1000
        val yesterdayDate = Timetable.today(yesterday)
        val yesterdayDay = Timetable.dayName(yesterday)

        val out = ArrayList<Arrival>()
        for (i in 0 until patternsArr.length()) {
            val o = patternsArr.getJSONObject(i)
            val pid = o.getString("id")
            if (!perPattern.has(pid)) continue
            val minutes = perPattern.getJSONArray(pid)
            // A train at 24:06 belongs to the previous service day when seen after midnight.
            val serviceOffsets = ArrayList<Int>(2)
            if (runsOn(o, today, day)) serviceOffsets += 0
            if (runsOn(o, yesterdayDate, yesterdayDay)) serviceOffsets += -1440
            if (serviceOffsets.isEmpty()) continue
            for (offset in serviceOffsets) {
                for (j in 0 until minutes.length()) {
                    val m = minutes.getInt(j)
                    if (offset < 0 && m < 1440) continue
                    val at = midnight + (m + offset) * 60L
                    if (at < nowUnix - 30) continue
                    out += Arrival(
                        lineId = nsId(PROVIDER_CP, o.getString("lineId")),
                        headsign = o.optString("headsign"),
                        patternId = nsId(PROVIDER_CP, pid),
                        scheduledUnix = at,
                        estimatedUnix = null,
                        observedUnix = null,
                        predicted = false
                    )
                }
            }
        }
        out.distinctBy { Triple(it.lineId, it.headsign, it.scheduledUnix) }
            .groupBy { it.lineId to it.headsign }
            .values
            .flatMap { group -> group.sortedBy { it.scheduledUnix }.take(limit) }
            .sortedBy { it.scheduledUnix }
    }

    fun stops(): List<StopInfo> {
        val arr = linesJson?.getJSONArray("stops") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            StopInfo(
                id = o.getString("id"),
                name = o.getString("name"),
                lat = o.getDouble("lat"),
                lon = o.getDouble("lon"),
                lineIds = o.getJSONArray("lineIds").let { a -> (0 until a.length()).map { a.getString(it) }.map { nsId(PROVIDER_CP, it) } }
            )
        }
    }

    fun excluded(): Set<String> = emptySet()
}