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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MetroStationAssetTest {

    @Test
    fun alamedaIsOneStationWithBothLinesAndArrivals() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        MetroGtfs.ensureLoaded(context)

        val alameda = MetroGtfs.stops().single { it.name == "Alameda" }
        assertEquals("AM", alameda.id)
        assertEquals(setOf("metro:C", "metro:D"), alameda.lineIds.toSet())

        val eightAm = java.util.Calendar.getInstance().apply {
            set(2026, java.util.Calendar.NOVEMBER, 11, 8, 0, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis / 1000
        val arrivals = MetroGtfs.scheduledArrivals(context, alameda.id, eightAm, 10)
        assertEquals(setOf("metro:C", "metro:D"), arrivals.map { it.lineId }.toSet())
        assertEquals(arrivals.size, arrivals.distinctBy { Triple(it.lineId, it.headsign, it.scheduledUnix) }.size)
    }

    @Test
    fun everyLineHasGeometryBuiltFromItsStations() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        MetroGtfs.ensureLoaded(context)

        for (pattern in listOf("A_Reboleira", "B_Rato", "C_Cais_Do_Sodré", "D_Aeroporto")) {
            val shape = MetroGtfs.shape(context, pattern)
            assertTrue("$pattern should have points", shape.size >= 10)
            assertTrue(
                "$pattern should stay in greater Lisbon",
                shape.all { (lat, lon) -> lat in 38.6..38.85 && lon in -9.3..-9.0 }
            )
        }
    }

    @Test
    fun pracaDeEspanhaHasAzulDepartures() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        MetroGtfs.ensureLoaded(context)

        val praca = MetroGtfs.stops().single { it.name == "Praça de Espanha" }
        assertEquals(setOf("metro:A"), praca.lineIds.toSet())

        val eightAm = java.util.Calendar.getInstance().apply {
            set(2026, java.util.Calendar.NOVEMBER, 11, 8, 0, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis / 1000
        val arrivals = MetroGtfs.scheduledArrivals(context, praca.id, eightAm, 10)
        assertTrue(arrivals.any { it.lineId == "metro:A" })
        assertEquals(arrivals.size, arrivals.distinctBy { Triple(it.lineId, it.headsign, it.scheduledUnix) }.size)
    }
}
