package pt.carrismetropolitana.tracker

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Distances must be kilometres everywhere.
 *
 * The two providers disagree and the app has to normalise: GTFS `shape_dist_traveled` is
 * metres, while the Carris Metropolitana API's `path[].distance` is kilometres.
 * `Eta.secondsToStop` divides that distance by a speed in km/h, so feeding it metres made
 * every 716 and 746 prediction 1000x too slow — line 164_0 records 7851.0, which read as km
 * is a bus arriving in 233 hours.
 *
 * Found while writing the bus-card tests, which is the only reason it surfaced now: the ETA
 * tests' own fixtures happened to be written in kilometres, so they agreed with the code
 * regardless. The bundled asset is guarded separately, by a range check in the generator,
 * which fails the weekly refresh job rather than shipping.
 */
class DistanceUnitTest {

    /** Recorded for reference; the real 716 is 7.851 km and CM's 1707_0_2 is 14.9 km. */
    private val kilometres = 7.851

    /**
     * 7 km remaining at 30 km/h is 14 minutes of driving, plus 19 stops of dwell.
     * With metres in the km field this came out as 233 hours.
     */
    @Test
    fun aSevenKilometreLegIsMinutesNotHours() {
        val pattern = PatternInfo(
            id = "p", lineId = "carris:164_0", headsign = "h", shapeId = null,
            stops = listOf(
                PatternStop("a", 1, 0.0),
                PatternStop("b", 20, kilometres)
            )
        )
        val seconds = Eta.secondsToStop(pattern, 1, "IN_TRANSIT_TO", 30, 20, kilometres)!!
        assertTrue("got ${seconds / 60} minutes", seconds / 60 in 19..21)
    }

    /** The same leg with the metre value, to show what the bug actually produced. */
    @Test
    fun theSameLegInMetresIsAbsurd() {
        val pattern = PatternInfo(
            id = "p", lineId = "carris:164_0", headsign = "h", shapeId = null,
            stops = listOf(PatternStop("a", 1, 0.0), PatternStop("b", 20, kilometres * 1000))
        )
        val seconds = Eta.secondsToStop(pattern, 1, "IN_TRANSIT_TO", 30, 20, kilometres * 1000)!!
        assertTrue("should be absurd, got ${seconds / 60} min", seconds / 60 > 10_000)
    }

    /** A route measured in metres must never be mistaken for a plausible one. */
    @Test
    fun metresAreOutsideAnyPlausibleRouteLength() {
        assertTrue(kilometres in 1.0..50.0)
        assertTrue(!(kilometres * 1000 in 1.0..50.0))
    }
}
