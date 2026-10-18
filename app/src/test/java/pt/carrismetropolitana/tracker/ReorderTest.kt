package pt.carrismetropolitana.tracker

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Reordering is a list operation on [AppState], but the only part that can be wrong in a way
 * that loses a stop is the index arithmetic, so that is what is pinned here.
 */
class ReorderTest {

    private fun reorder(stops: List<String>, id: String, delta: Int): List<String> {
        val from = stops.indexOf(id)
        if (from < 0) return stops
        val to = from + delta
        if (to !in stops.indices) return stops
        return stops.toMutableList().apply { add(to, removeAt(from)) }
    }

    private val stops = listOf("a", "b", "c")

    @Test
    fun movesUpAndDown() {
        assertEquals(listOf("b", "a", "c"), reorder(stops, "b", -1))
        assertEquals(listOf("a", "c", "b"), reorder(stops, "b", 1))
        assertEquals(listOf("b", "a", "c"), reorder(stops, "a", 1))
        assertEquals(listOf("a", "c", "b"), reorder(stops, "c", -1))
    }

    @Test
    fun outOfRangeMovesAreIgnoredRatherThanClamped() {
        assertEquals(stops, reorder(stops, "a", -1))
        assertEquals(stops, reorder(stops, "c", 1))
        // Clamping would duplicate the end stop instead of doing nothing.
        assertEquals(3, reorder(stops, "a", -1).distinct().size)
    }

    @Test
    fun unknownStopsAndNothingToDoAreNoOps() {
        assertEquals(stops, reorder(stops, "zzz", -1))
        assertEquals(stops, reorder(stops, "b", 0))
        assertEquals(emptyList<String>(), reorder(emptyList(), "b", 1))
    }
}