package pt.carrismetropolitana.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionTest {

    private val t0 = 1_700_000_000_000L

    private fun vehicle(id: String, lat: Double, lon: Double, tMs: Long, speed: Int = 0) =
        Vehicle(
            id = id,
            lineId = "716",
            patternId = "p",
            stopId = null,
            lat = lat,
            lon = lon,
            speed = speed,
            status = "IN_TRANSIT_TO",
            timestampMs = tMs
        )

    @Test
    fun oneFixIsNotEnough() {
        assertNull(MotionEstimator.deriveSpeed(listOf(Sample(38.72, -9.14, t0))))
    }

    @Test
    fun speedIsDistanceOverTime() {
        // 0.001 deg latitude is ~111 m; over 40 s that is ~10 km/h.
        val speed = MotionEstimator.deriveSpeed(
            listOf(
                Sample(38.7200, -9.1400, t0),
                Sample(38.7205, -9.1400, t0 + 20_000),
                Sample(38.7209, -9.1400, t0 + 40_000)
            )
        )!!
        assertEquals(10.0, speed.toDouble(), 1.0)
    }

    @Test
    fun gpsNoiseDoesNotInventMovement() {
        // 2 m of jitter is below the noise floor and must not read as a bus crawling.
        assertNull(
            MotionEstimator.deriveSpeed(
                listOf(
                    Sample(38.72000, -9.14000, t0),
                    Sample(38.72001, -9.14000, t0 + 20_000),
                    Sample(38.72002, -9.14000, t0 + 40_000)
                )
            )
        )
    }

    @Test
    fun staleWindowIsRejected() {
        assertNull(
            MotionEstimator.deriveSpeed(
                listOf(
                    Sample(38.7200, -9.1400, t0),
                    Sample(38.7290, -9.1400, t0 + MotionEstimator.MAX_WINDOW_MS + 1)
                )
            )
        )
    }

    @Test
    fun feedSpeedWinsOverMeasuredMotion() {
        val tracker = MotionTracker()
        tracker.enrich(vehicle("a", 38.7200, -9.1400, t0, speed = 33))
        val measured = tracker.enrich(vehicle("a", 38.7250, -9.1400, t0 + 40_000, speed = 33))
        assertEquals(33, measured.speed)
    }

    @Test
    fun missingSpeedIsFilledInFromMovement() {
        val tracker = MotionTracker()
        // One fix is nothing to measure; the second poll already has a 20 s baseline.
        assertEquals(0, tracker.enrich(vehicle("a", 38.7200, -9.1400, t0)).speed)

        val second = tracker.enrich(vehicle("a", 38.7205, -9.1400, t0 + 20_000))
        assertTrue("expected a measured speed, got ${second.speed}", second.speed in 9..11)

        // 0.0009 deg is ~100 m over 40 s, so the wider window agrees.
        val third = tracker.enrich(vehicle("a", 38.7209, -9.1400, t0 + 40_000))
        assertTrue(third.speed in 9..11)
    }

    @Test
    fun windowsArePerVehicle() {
        val tracker = MotionTracker()
        tracker.enrich(vehicle("a", 38.7200, -9.1400, t0))
        tracker.enrich(vehicle("a", 38.7205, -9.1400, t0 + 20_000))
        // A second bus must not borrow A's window.
        val b = tracker.enrich(vehicle("b", 38.7500, -9.1400, t0 + 20_000))
        assertEquals(0, b.speed)
    }

    @Test
    fun vehiclesGoneFromTheFeedAreForgotten() {
        val tracker = MotionTracker()
        tracker.enrich(vehicle("a", 38.7200, -9.1400, t0))
        assertTrue(tracker.enrich(vehicle("a", 38.7205, -9.1400, t0 + 20_000)).speed > 0)

        // Age alone expires it, so nothing has to tell the tracker the bus left.
        tracker.retainFresh(t0 + 20_000 + MotionEstimator.MAX_WINDOW_MS + 1)
        assertEquals(0, tracker.enrich(vehicle("a", 38.7213, -9.1400, t0 + 60_000)).speed)
    }

    /**
     * The two providers share one tracker and are polled on different clocks. Pruning by
     * membership let each provider evict the other's windows, and since a window needs two
     * samples, a CARRIS bus polled every 15 s never kept one across a 9 s CM poll — so every
     * CARRIS speed estimate came back null, always.
     */
    @Test
    fun oneProvidersPollDoesNotEvictTheOthersWindow() {
        val tracker = MotionTracker()
        tracker.enrich(vehicle("carris-1", 38.7200, -9.1400, t0))
        // CM polls in between, and knows nothing about the CARRIS bus above.
        tracker.retainFresh(t0 + 9_000)
        tracker.enrich(vehicle("cm-1", 38.7500, -9.1400, t0 + 9_000))

        // Without the fix this is a fresh single-sample window, so it reads 0.
        val carris = tracker.enrich(vehicle("carris-1", 38.7205, -9.1400, t0 + 18_000))
        assertTrue("CARRIS window was evicted by the other provider", carris.speed > 0)
    }

    @Test
    fun vehiclesStillInTheFeedKeepTheirWindow() {
        val tracker = MotionTracker()
        tracker.enrich(vehicle("a", 38.7200, -9.1400, t0))
        tracker.enrich(vehicle("a", 38.7205, -9.1400, t0 + 20_000))
        tracker.retainFresh(t0 + 20_000)
        assertTrue(tracker.enrich(vehicle("a", 38.7209, -9.1400, t0 + 40_000)).speed > 0)
    }
}