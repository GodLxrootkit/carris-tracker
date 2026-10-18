package pt.carrismetropolitana.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The map's "Todas" chip must mean *our* lines, not the whole network.
 *
 * Both feeds are network-wide: Carris Metropolitana served ~1 000 vehicles and CARRIS ~560 on a
 * quiet check, for every line on each network, and neither filters for us. Before this, choosing
 * "Todas" drew all of them — roughly 500 dots for lines the app does not track — while choosing
 * an individual line chip filtered correctly, which is what made it look like a chip problem
 * rather than a data problem.
 */
class TrackedVehiclesTest {

    private fun bus(id: String, lineId: String) = Vehicle(
        id = id,
        lineId = lineId,
        patternId = "p",
        stopId = null,
        lat = 38.75,
        lon = -9.14,
        speed = 0,
        status = "IN_TRANSIT_TO",
        timestampMs = 0
    )

    private fun keepTracked(vehicles: List<Vehicle>) =
        vehicles.filter { it.lineId in TRACKED_LINE_IDS }

    @Test
    fun theTrackedSetIsSevenRouteIdsBehindSixRiderFacingLines() {
        assertEquals(
            setOf("1707", "1717", "1720", "1721", "164_0", "108_0", "108_3"),
            TRACKED_LINE_IDS.map { it.substringAfter(':') }.toSet()
        )
        assertEquals(7, TRACKED_LINE_IDS.size)
        // 746 is two route_ids behind one short name, so six lines span seven ids.
        val lines = listOf(
            LineInfo("cm:1707", "1707", "1707", "#000", emptyList()),
            LineInfo("cm:1717", "1717", "1717", "#000", emptyList()),
            LineInfo("cm:1720", "1720", "1720", "#000", emptyList()),
            LineInfo("cm:1721", "1721", "1721", "#000", emptyList()),
            LineInfo("carris:164_0", "716", "716", "#000", emptyList()),
            LineInfo("carris:108_0", "746", "746", "#000", emptyList()),
            LineInfo("carris:108_3", "746", "746", "#000", emptyList())
        )
        assertEquals(
            setOf("1707", "1717", "1720", "1721", "716", "746"),
            TRACKED_LINE_IDS.map { lines.shortNameOf(it) }.toSet()
        )
    }

    @Test
    fun untrackedNetworkVehiclesAreDropped() {
        val all = listOf(
            bus("1", "cm:1707"),
            bus("2", "carris:164_0"),
            bus("3", "cm:1701"),
            bus("4", "carris:7001"),
            bus("5", "cm:9702")
        )
        assertEquals(listOf("1", "2"), keepTracked(all).map { it.id })
    }

    @Test
    fun anUnnamespacedIdIsNeverTracked() {
        // The namespaced form is what keeps 1707 on one network from matching a CARRIS route.
        assertTrue(keepTracked(listOf(bus("1", "1707"), bus("2", "164_0"))).isEmpty())
    }

    @Test
    fun both746VariantsSurvive() {
        val both = listOf(bus("1", "carris:108_0"), bus("2", "carris:108_3"))
        assertEquals(2, keepTracked(both).size)
    }
}