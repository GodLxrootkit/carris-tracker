package pt.carrismetropolitana.tracker

import kotlin.math.roundToInt

/** One GPS fix for a vehicle. */
data class Sample(val lat: Double, val lon: Double, val tMs: Long)

/**
 * Speed derived from successive position fixes.
 *
 * CARRIS publishes no `speed` field at all — decoded live, 0 of 561 vehicles carry one, and
 * every one of them reports `IN_TRANSIT_TO`, so the feed cannot tell a bus crawling in traffic
 * from one stopped at a light. Carris Metropolitana reports speed, so this only ever runs
 * where the feed is silent.
 *
 * Speed is straight-line distance over the whole window rather than the last pair of fixes: GPS
 * noise on a single poll delta is comparable to the distance covered, and averages out across
 * the window. Measured against the live feed, 30 s of movement gives a median of 9 km/h and a
 * 90th percentile of 30 km/h, which is the band [Eta.MIN_SPEED_KMH] expects.
 *
 * `ponytail:` dead reckoning from a handful of fixes, no map matching. Upgrade path: project
 * each fix onto the pattern's shape for true route progress.
 */
object MotionEstimator {

    /** Fixes kept per vehicle. Three spans two poll intervals, which is what makes the average useful. */
    const val WINDOW = 3

    /** Below this the fixes are indistinguishable from GPS noise. */
    const val MIN_DISPLACEMENT_M = 8.0

    /** Longer than this and the window is stale; a bus that vanished and came back is not one bus. */
    const val MAX_WINDOW_MS = 120_000L

    /**
     * Speed in km/h across [samples], oldest first. Null when there is not enough movement or
     * too little time to measure.
     */
    fun deriveSpeed(samples: List<Sample>): Int? {
        if (samples.size < 2) return null
        val first = samples.first()
        val last = samples.last()
        val dtMs = last.tMs - first.tMs
        if (dtMs <= 0 || dtMs > MAX_WINDOW_MS) return null

        val metres = Eta.distanceMeters(first.lat, first.lon, last.lat, last.lon)
        if (metres < MIN_DISPLACEMENT_M) return null

        return (metres / (dtMs / 1000.0) * 3.6).roundToInt()
    }
}

/**
 * Per-vehicle sliding window of fixes, kept across polls.
 *
 * Vehicles are keyed by id, so a bus that drops out of the feed for a few minutes simply ages
 * out of [MotionEstimator.MAX_WINDOW_MS] and picks up again from one fix.
 */
class MotionTracker {

    private val windows = HashMap<String, ArrayDeque<Sample>>()

    /**
     * Records this fix and returns the vehicle with a measured speed, or unchanged when there
     * is nothing to measure yet. [Vehicle.speed] is only filled in when the feed leaves it at
     * zero, so Carris Metropolitana keeps its reported value untouched.
     */
    fun enrich(v: Vehicle): Vehicle {
        val window = windows.getOrPut(v.id) { ArrayDeque() }
        window.addLast(Sample(v.lat, v.lon, v.timestampMs))
        while (window.size > MotionEstimator.WINDOW) window.removeFirst()

        if (v.speed > 0) return v
        val speed = MotionEstimator.deriveSpeed(window.toList()) ?: return v
        return v.copy(speed = speed)
    }

    /**
 * Drops windows that have gone stale.
 *
 * Deliberately keyed on age only, never on which vehicles were seen this round. Both providers
 * share one tracker, and they are polled on different clocks, so pruning by membership meant each
 * provider deleted the other's windows — and since a window needs two samples, a bus polled every
 * 15 s never kept one across a 9 s poll of the other provider. Every CARRIS speed estimate came
 * back null, always.
 *
 * Age alone still bounds the map: [MotionEstimator.MAX_WINDOW_MS] expires a vehicle that vanishes.
 */
    fun retainFresh(nowMs: Long) {
        windows.keys.retainAll { id ->
            nowMs - (windows[id]?.last()?.tMs ?: 0L) <= MotionEstimator.MAX_WINDOW_MS
        }
    }
}