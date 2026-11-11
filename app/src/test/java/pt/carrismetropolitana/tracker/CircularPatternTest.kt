package pt.carrismetropolitana.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A circular pattern visits the same stop twice, once per lap.
 *
 * 29 of the 309 patterns in the bundled CARRIS asset do. Resolving a stop with `firstOrNull` always
 * returns the first occurrence, so on the second lap the chosen stop is *behind* the bus: the
 * remaining distance comes out negative and the prediction is silently dropped. A bus two minutes
 * from a saved stop showed no countdown, while the same bus on its first lap was fine.
 */
class CircularPatternTest {

    /**
     * A 6-stop circuit where stop "A" is both the origin and the terminus, so it appears at
     * sequence 0 and again at sequence 5 - the shape of every circular pattern in the asset.
     */
    private val circuit = PatternInfo(
        id = "c",
        lineId = "l",
        headsign = "Circular",
        shapeId = "s",
        stops = listOf(
            PatternStop("A", 0, 0.0),
            PatternStop("B", 1, 1.0),
            PatternStop("C", 2, 2.0),
            PatternStop("D", 3, 3.0),
            PatternStop("E", 4, 4.0),
            PatternStop("A", 5, 5.0)
        )
    )

    private fun vehicle(stopId: String?, sequence: Int? = null) = Vehicle(
        id = "v1", lineId = "l", patternId = "c", stopId = stopId,
        lat = 0.0, lon = 0.0, speed = 30, status = "IN_TRANSIT_TO",
        timestampMs = System.currentTimeMillis(), currentStopSequence = sequence
    )

    /** On the second lap the bus is past B, so B is ahead of it again. */
    @Test
    fun stopAheadReturnsTheNextOccurrenceNotTheFirst() {
        assertEquals(5, Eta.stopAhead(circuit, fromSequence = 3, stopId = "A")!!.sequence)
        assertEquals(1, Eta.stopAhead(circuit, fromSequence = 0, stopId = "B")!!.sequence)
    }

    @Test
    fun stopAheadReturnsNullForAStopAlreadyPassed() {
        // No occurrence of B after sequence 1, so this lap is over for B.
        assertNull(Eta.stopAhead(circuit, fromSequence = 1, stopId = "B"))
    }

    /** This is the actual regression: no ETA at all for a stop the bus is one hop away from. */
    @Test
    fun aBusOnItsSecondLapStillPredictsTheOrigin() {
        // The bus is at E, sequence 4. The origin A is next at sequence 5 - but A also appears at
        // sequence 0, which is behind the bus. Taking the first occurrence puts the target at 0,
        // `targetSequence <= stopSequence` rejects it, and the prediction disappears.
        val onSecondLap = Eta.vehicleStop(circuit, vehicle("E", sequence = 4))!!
        assertEquals(4, onSecondLap.sequence)

        val target = Eta.stopAhead(circuit, onSecondLap.sequence, "A")!!
        assertEquals("should take the lap-2 origin, not the one just served", 5, target.sequence)

        val seconds = Eta.secondsToStop(
            circuit, onSecondLap.sequence, "IN_TRANSIT_TO", 30, target.sequence, target.km
        )
        assertNotNull("a bus one stop from the origin got no prediction: $seconds", seconds)
    }

    /** Without the sequence field the id match falls back to the first occurrence, so document it. */
    @Test
    fun withoutTheSequenceTheFirstOccurrenceIsUsed() {
        assertEquals(0, Eta.vehicleStop(circuit, vehicle("A"))!!.sequence)
    }

    /**
     * The counter-case that proves the fix does not simply "always pick the later one": on the
     * first lap the origin A at sequence 0 is still ahead in the sense that matters, and the bus
     * heading to it gets the near one.
     */
    @Test
    fun firstLapBehaviourIsUnchanged() {
        val at = Eta.vehicleStop(circuit, vehicle("C", sequence = 2))!!
        val target = Eta.stopAhead(circuit, at.sequence, "A")!!
        assertEquals("should aim at the end of this lap, not the start", 5, target.sequence)
    }

    /** The tapped-bus card goes through the same resolution. */
    @Test
    fun busStopHintStillWorksOnASecondLap() {
        val saved = listOf(SavedStop("A", "Origem", 0.0, 0.0))
        val onSecondLap = vehicle("E", sequence = 4)

        val hint = busStopHint(onSecondLap, listOf(circuit), saved, System.currentTimeMillis() / 1000)
        assertNotNull("no hint on the second lap", hint)
        hint as BusStopHint.Arriving
        assertEquals("Origem", hint.stopName)
        assertTrue("seconds should be positive, was ${hint.seconds}", hint.seconds > 0)
    }

    /**
     * The sequence field is a hint, not gospel: a value that does not exist on the pattern must
     * fall back rather than return null and blank the bus out.
     */
    @Test
    fun anUnknownSequenceFallsBackToTheStopId() {
        val at = Eta.vehicleStop(circuit, vehicle("C", sequence = 999))
        assertNotNull("a bogus sequence blanked the bus", at)
        assertEquals(2, at!!.sequence)
    }
}