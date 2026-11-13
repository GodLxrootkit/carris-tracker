package pt.carrismetropolitana.tracker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CpFertagusAssetTest {

    @Test
    fun fertagusIsMergedIntoTheCpCatalog() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        CpGtfs.ensureLoaded(context)

        val lines = CpGtfs.allRoutes().map { it.id }
        assertTrue(lines.contains("cp:fert_A_0"))
        assertTrue(lines.contains("cp:fert_A_1"))
    }

    @Test
    fun sharedStationsCarryFertagusLinesAndArrivals() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        CpGtfs.ensureLoaded(context)

        val roma = CpGtfs.stops().single { it.name == "Roma - Areeiro" }
        assertTrue(roma.lineIds.any { it.startsWith("cp:fert_") })

        val weekday = patternsOf("cp:fert_A_0").first { p ->
            p.validDays.contains("monday") && p.stops.any { it.stopId == roma.id }
        }
        val arrivals = CpGtfs.scheduledArrivals(context, roma.id, firstRun(weekday, 6), 10)
        assertTrue(arrivals.any { it.lineId.startsWith("cp:fert_") })
    }

    @Test
    fun fertagusOnlyStationsAppearOnce() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        CpGtfs.ensureLoaded(context)

        val corroios = CpGtfs.stops().filter { it.name == "Corroios" }
        assertEquals(1, corroios.size)
        assertEquals(setOf("cp:fert_A_0", "cp:fert_A_1"), corroios.single().lineIds.toSet())
    }

    @Test
    fun weekdayAndWeekendServicesDoNotMix() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        CpGtfs.ensureLoaded(context)

        val roma = CpGtfs.stops().single { it.name == "Roma - Areeiro" }
        val patterns = patternsOf("cp:fert_A_0").filter { p -> p.stops.any { it.stopId == roma.id } }
        val weekday = patterns.first { "monday" in it.validDays && "saturday" !in it.validDays }
        val weekend = patterns.first { "saturday" in it.validDays && "monday" !in it.validDays }
        val weekdayArrivals = CpGtfs.scheduledArrivals(context, roma.id, firstRun(weekday, 6), 100)
        val weekendArrivals = CpGtfs.scheduledArrivals(context, roma.id, firstRun(weekend, 6), 100)

        assertTrue(weekdayArrivals.any { it.patternId == weekday.id })
        assertTrue(weekdayArrivals.none { it.patternId == weekend.id })
        assertTrue(weekendArrivals.any { it.patternId == weekend.id })
        assertTrue(weekendArrivals.none { it.patternId == weekday.id })
    }

    @Test
    fun longCpVariantKeepsTheStopsOnlyItServes() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        CpGtfs.ensureLoaded(context)

        val santarem = CpGtfs.stops().single { it.name == "Santarem" }
        val line = CpGtfs.allRoutes().single { it.id == "cp:21-94_2006-94_30007" }
        val patterns = line.patternIds.mapNotNull { CpGtfs.pattern(it) }

        assertTrue(patterns.any { p -> p.stops.size > 6 && p.stops.any { it.stopId == santarem.id } })
    }

    @Test
    fun afterMidnightArrivalComesFromThePreviousServiceDay() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        CpGtfs.ensureLoaded(context)

        val roma = CpGtfs.stops().single { it.name == "Roma - Areeiro" }
        val overnight = patternsOf("cp:fert_A_0").first { p ->
            p.validFrom != null &&
                p.stops.any { s -> s.stopId == roma.id && s.times.any { it >= 1440 } }
        }
        val serviceNoon = firstRun(overnight, 12)
        val serviceMidnight = Timetable.midnightUnix(serviceNoon, Timetable.today(serviceNoon))
        val nextMidnight = Calendar.getInstance().apply {
            timeInMillis = serviceMidnight * 1000
            add(Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis / 1000
        val lateMinute = overnight.stops.first { it.stopId == roma.id }.times.first { it >= 1440 }
        val expected = nextMidnight + (lateMinute - 1440) * 60L

        val arrivals = CpGtfs.scheduledArrivals(context, roma.id, nextMidnight, 100)
        assertTrue(arrivals.any { it.lineId == overnight.lineId && it.scheduledUnix == expected })
    }

    @Test
    fun badajozRoutesHaveReadableNames() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        CpGtfs.ensureLoaded(context)

        val lines = CpGtfs.allRoutes().filter { "71_37606" in it.id }
        assertEquals(2, lines.size)
        assertTrue(lines.all { "Entroncamento" in it.longName && "Badajoz" in it.longName })
    }

    private suspend fun patternsOf(lineId: String): List<PatternInfo> =
        CpGtfs.allRoutes().single { it.id == lineId }.patternIds.mapNotNull { CpGtfs.pattern(it) }

    /** First date from the asset on which [pattern] runs, at [hour]. */
    private fun firstRun(pattern: PatternInfo, hour: Int): Long {
        val from = pattern.validFrom ?: error("pattern has no validFrom: ${pattern.id}")
        val c = Calendar.getInstance().apply {
            clear()
            set(from.substring(0, 4).toInt(), from.substring(4, 6).toInt() - 1, from.substring(6, 8).toInt(), hour, 0, 0)
        }
        repeat(14) {
            val unix = c.timeInMillis / 1000
            if (pattern.runsOn(Timetable.today(unix), Timetable.dayName(unix))) return unix
            c.add(Calendar.DAY_OF_YEAR, 1)
        }
        error("no valid date for ${pattern.id}")
    }
}
