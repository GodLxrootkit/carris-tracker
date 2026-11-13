package pt.carrismetropolitana.tracker

import org.junit.Assert.assertEquals
import org.junit.Test

class MetroStationTest {

    private fun stop(id: String, name: String, vararg lines: String) =
        StopInfo(id, name, 0.0, 0.0, lines.toList())

    @Test
    fun interchangePlatformsCollapseIntoTheParentWithEveryLine() {
        val merged = mergeMetroStations(
            listOf(
                stop("AM", "Alameda"),
                stop("AM1", "Alameda", "metro:C"),
                stop("AM2", "Alameda", "metro:C"),
                stop("AM3", "Alameda", "metro:D"),
                stop("AM4", "Alameda", "metro:D")
            )
        )
        assertEquals(1, merged.size)
        assertEquals("AM", merged.single().id)
        assertEquals(setOf("metro:C", "metro:D"), merged.single().lineIds.toSet())
    }

    @Test
    fun singlePlatformStationIsUnchanged() {
        val one = stop("RA", "Rato", "metro:A")
        assertEquals(listOf(one), mergeMetroStations(listOf(one)))
    }

    @Test
    fun platformWithoutParentStillGetsEveryLine() {
        val merged = mergeMetroStations(
            listOf(
                stop("X1", "X", "metro:A"),
                stop("X2", "X", "metro:B")
            )
        )
        assertEquals(1, merged.size)
        assertEquals(setOf("metro:A", "metro:B"), merged.single().lineIds.toSet())
    }

    @Test
    fun sameNameStationsFarApartStaySeparate() {
        val merged = mergeMetroStations(
            listOf(
                StopInfo("X1", "X", 0.0, 0.0, listOf("metro:A")),
                StopInfo("X2", "X", 0.01, 0.0, listOf("metro:B"))
            )
        )
        assertEquals(2, merged.size)
    }
}
