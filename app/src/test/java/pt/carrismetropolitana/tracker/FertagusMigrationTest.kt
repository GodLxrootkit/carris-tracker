package pt.carrismetropolitana.tracker

import org.junit.Assert.assertEquals
import org.junit.Test

class FertagusMigrationTest {

    private val saved = listOf(
        SavedStop("94_66035", "Roma - Areeiro", networks = setOf(PROVIDER_CP)),
        SavedStop("94_32001", "Old Name", networks = setOf(PROVIDER_CP)),
        SavedStop("AM1", "Alameda", networks = setOf(PROVIDER_METRO))
    )
    private val catalog = listOf(
        StopInfo(
            "94_66035", "Roma - Areeiro", 38.7, -9.1,
            listOf("cp:fert_A_0", "cp:fert_A_1", "cp:line")
        ),
        StopInfo(
            "94_32001", "New Name", 38.8, -9.2,
            listOf("cp:fert_B_0")
        ),
        StopInfo("AM1", "Alameda", 38.7, -9.1, listOf("cp:fert_C_0"))
    )

    @Test
    fun addsOnlyMissingFertagusLinesAtSavedCpStops() {
        val wanted = missingFertagusLines(
            saved = saved,
            catalog = catalog,
            tracked = setOf("cp:fert_A_0", "cp:line"),
            networksOf = { id -> saved.single { it.id == id }.networks }
        )

        assertEquals(setOf("cp:fert_A_1", "cp:fert_B_0"), wanted)
    }

    @Test
    fun fallsBackToStopIdWhenTheSavedNameChanged() {
        val wanted = missingFertagusLines(
            saved = saved.take(2),
            catalog = catalog,
            tracked = emptySet(),
            networksOf = { setOf(PROVIDER_CP) }
        )

        assertEquals(setOf("cp:fert_A_0", "cp:fert_A_1", "cp:fert_B_0"), wanted)
    }
}
