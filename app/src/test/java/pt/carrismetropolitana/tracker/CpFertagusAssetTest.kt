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

        val sixAm = java.util.Calendar.getInstance().apply {
            set(2026, java.util.Calendar.NOVEMBER, 11, 6, 0, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis / 1000
        val arrivals = CpGtfs.scheduledArrivals(context, roma.id, sixAm, 10)
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
}
