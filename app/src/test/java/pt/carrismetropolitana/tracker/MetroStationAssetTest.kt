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
    }
}
