package pt.carrismetropolitana.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Non-finite distances must never reach a prediction.
 *
 * `targetKm` comes from `JSONObject.optDouble`, which returns NaN for a missing or non-numeric
 * value rather than throwing. NaN fails every comparison, so the original `remainingKm <= 0` guard
 * let it straight through, and `NaN.toInt()` is 0 - so a bus 15 km and 30 stops away was reported
 * as arriving *now*, with `predicted = true` and no error anywhere. The codebase already guards
 * NaN in `parseCatalogLine`; this is the same hazard on the ETA path.
 */
class EtaNaNTest {

    private fun pattern(vararg km: Double) = PatternInfo(
        id = "p",
        lineId = "l",
        headsign = "h",
        shapeId = "s",
        stops = km.mapIndexed { i, v -> PatternStop("stop$i", i, v) }
    )

    private fun eta(pattern: PatternInfo, from: Int, to: Int, targetKm: Double, speed: Int = 30) =
        Eta.secondsToStop(
            pattern = pattern,
            stopSequence = from,
            status = "IN_TRANSIT_TO",
            speedKmh = speed,
            targetSequence = to,
            targetKm = targetKm
        )

    /** The headline failure: a distant bus must not be reported as arriving now. */
    @Test
    fun nanTargetDistanceYieldsNoPrediction() {
        assertNull(eta(pattern(0.0, 5.0, 15.0), from = 0, to = 2, targetKm = Double.NaN))
    }

    @Test
    fun infiniteTargetDistanceYieldsNoPrediction() {
        assertNull(eta(pattern(0.0, 5.0, 15.0), from = 0, to = 2, targetKm = Double.POSITIVE_INFINITY))
        assertNull(eta(pattern(0.0, 5.0, 15.0), from = 0, to = 2, targetKm = Double.NEGATIVE_INFINITY))
    }

    /** A NaN cumulative distance poisons progressKm, which poisons everything below it. */
    @Test
    fun nanProgressDistanceYieldsNoPrediction() {
        assertNull(eta(pattern(0.0, Double.NaN, 15.0), from = 1, to = 2, targetKm = 15.0))
        assertNull(
            Eta.progressKm(pattern(0.0, Double.NaN, 15.0), stopSequence = 1, status = "IN_TRANSIT_TO")
        )
    }

    /**
     * The regression that made this dangerous: NaN.toInt() is 0, and 0 seconds renders as "agora".
     * If the guard is ever removed, the ETA must at least not be zero.
     */
    @Test
    fun noPredictionIsEverZeroSeconds() {
        val distances = listOf(
            Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
            0.0, -1.0, Double.MIN_VALUE
        )
        for (d in distances) {
            val seconds = eta(pattern(0.0, 5.0, 15.0), from = 0, to = 2, targetKm = d)
            assertTrue(
                "targetKm=$d produced a prediction of $seconds seconds",
                seconds == null || seconds > 0
            )
        }
    }

    /** A normal, finite distance must still work, or the guard has gone too far. */
    @Test
    fun finiteDistancesStillPredict() {
        // 10 km remaining at 30 km/h is 1200 s, plus one 20 s dwell for the stop in between.
        val seconds = eta(pattern(0.0, 5.0, 10.0), from = 0, to = 2, targetKm = 10.0)
        assertEquals(1200L + 20L, seconds!!.toLong())
    }

    /**
     * Antipodal points make the haversine term exceed 1 by rounding. It has to clamp, or the
     * distance is NaN and Motion turns it into a zero speed estimate.
     */
    @Test
    fun haversineStaysFiniteForAntipodalPoints() {
        val d = Eta.distanceMeters(0.0, 0.0, 0.0, 180.0)
        assertTrue("antipodal distance was $d", d.isFinite())
        assertTrue(d > 0.0)
    }

    @Test
    fun haversineIsNeverNegative() {
        val d = Eta.distanceMeters(38.7, -9.1, 38.7, -9.1)
        assertEquals(0.0, d, 1e-6)
    }
}