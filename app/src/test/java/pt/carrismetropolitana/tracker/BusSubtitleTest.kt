package pt.carrismetropolitana.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The label drawn under a bus dot on the map.
 *
 * `stop_id` in both feeds is the stop the bus is heading to — the one it is at when stopped,
 * the one it is travelling towards otherwise — so the name is what tells you where a bus is
 * going, which the line number alone cannot.
 */
class BusSubtitleTest {

    private val names = mapOf(
        "10701" to "S. Domingos Benfica",
        "1505" to "Pç. Espanha",
        "030605" to "R Elias Garcia 103",
        "blank" to "   "
    )

    @Test
    fun showsTheNextStopWhenZoomedIn() {
        assertEquals("S. Domingos Benfica", busSubtitle("10701", names, 16.0))
    }

    /** Text under every dot is unreadable when zoomed out, so it is gated. */
    @Test
    fun hiddenBelowTheZoomThreshold() {
        assertNull(busSubtitle("10701", names, 12.0))
        assertNull(busSubtitle("10701", names, 14.9))
        assertEquals("S. Domingos Benfica", busSubtitle("10701", names, 15.0))
    }

    @Test
    fun hiddenWhenTheBusHasNoStopId() {
        assertNull(busSubtitle(null, names, 16.0))
    }

    /** 60 of 62 entities in a real CARRIS capture had one; the rest must not draw junk. */
    @Test
    fun hiddenWhenTheStopIsNotInTheCatalog() {
        assertNull(busSubtitle("99999", names, 16.0))
    }

    /** The catalog has not finished loading yet. */
    @Test
    fun hiddenWhenTheCatalogIsStillEmpty() {
        assertNull(busSubtitle("10701", emptyMap(), 16.0))
    }

    @Test
    fun blankNamesAreTreatedAsMissing() {
        assertNull(busSubtitle("blank", names, 16.0))
    }

    @Test
    fun longNamesAreTruncated() {
        val long = mapOf("x" to "Estação Ferroviária de Entrecampos Norte")
        val drawn = busSubtitle("x", long, 16.0)!!
        assertEquals(true, drawn.endsWith("…"))
        assertEquals(true, drawn.length <= 20)
    }

    /** A name that fits exactly is left alone — truncating at the boundary would be silly. */
    @Test
    fun shortNamesAreNotTruncated() {
        val exact = mapOf("x" to "S. Domingos Benfica")
        assertEquals("S. Domingos Benfica", busSubtitle("x", exact, 16.0))
    }

    @Test
    fun accentedNamesSurvive() {
        assertEquals("Pç. Espanha", busSubtitle("1505", names, 16.0))
    }
}
