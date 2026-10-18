package pt.carrismetropolitana.tracker

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Predicted arrivals computed from the live `/vehicles` feed.
 *
 * The `arrivals/by_stop` endpoint always sends `estimated_arrival_unix: null`, so the only
 * way to know when a bus really shows up is to project each circulating vehicle onto its
 * pattern path and divide the remaining kilometres by its speed.
 *
 * `ponytail:` no traffic model, no historical profile. Ceiling: ±5 min in city traffic.
 * Upgrade path: feed `speed` history + dwell per stop into a rolling average.
 */
object Eta {

    /** Below this the bus is at a light, not representative of average city speed. */
    const val MIN_SPEED_KMH = 12.0

    /** Time lost per intermediate stop (braking, doors, boarding). */
    const val DWELL_SECONDS = 20

    /** Stop sequence difference past which a prediction is noise, not information. */
    const val MAX_STOPS_AHEAD = 40

    /** Vehicle positions older than this are dropped; the feed lags during incidents. */
    const val MAX_VEHICLE_AGE_SECONDS = 180

    fun isStale(vehicle: Vehicle, nowUnix: Long): Boolean =
        nowUnix - vehicle.timestampMs / 1000 > MAX_VEHICLE_AGE_SECONDS

    /**
     * Where the vehicle sits along its pattern, in cumulative km.
     *
     * [stopSequence] is the vehicle's next stop (GTFS-RT `stop_id`): the stop it is at when
     * stopped, the one it is heading to while in transit.
     */
    fun progressKm(pattern: PatternInfo, stopSequence: Int, status: String): Double? {
        val stops = pattern.stops
        val nextIndex = stops.indexOfFirst { it.sequence == stopSequence }
        if (nextIndex < 0) return null
        val nextKm = stops[nextIndex].km
        // A non-finite cumulative distance makes every projection downstream NaN, which compares
        // false against everything and then truncates to 0. Refuse it here, once.
        if (!nextKm.isFinite()) return null
        if (status != "IN_TRANSIT_TO") return nextKm
        // Travelling: somewhere in the leg behind it. Midpoint beats assuming it just left.
        val prevKm = stops.getOrNull(nextIndex - 1)?.km?.takeIf { it.isFinite() } ?: nextKm
        return (prevKm + nextKm) / 2
    }

    /**
     * Which stop on [pattern] the vehicle is at.
     *
     * [Vehicle.currentStopSequence] is authoritative and disambiguates circular patterns. Where
     * the provider does not send it, matching the stop id is the only option - and on a pattern
     * that visits a stop twice that returns the first occurrence, which may belong to the lap the
     * bus has already done.
     */
    fun vehicleStop(pattern: PatternInfo, vehicle: Vehicle): PatternStop? {
        vehicle.currentStopSequence?.let { seq ->
            pattern.stops.firstOrNull { it.sequence == seq }?.let { return it }
        }
        val id = vehicle.stopId ?: return null
        return pattern.stops.firstOrNull { it.stopId == id }
    }

    /**
     * The next time the bus reaches [stopId], given it is currently at [fromSequence].
     *
     * A circular pattern calls at the same stop once per lap, so taking the first occurrence
     * returns one from a lap already completed. On the second lap that stop sits *behind* the bus,
     * the remaining distance comes out negative, and the prediction is dropped - so a bus two
     * minutes away showed no ETA at all, while the same bus on its first lap was fine.
     *
     * Searching from [fromSequence] forward is what makes this correct on either lap, and it costs
     * nothing when the pattern is not circular.
     */
    fun stopAhead(pattern: PatternInfo, fromSequence: Int, stopId: String): PatternStop? =
        pattern.stops.firstOrNull { it.stopId == stopId && it.sequence > fromSequence }

    /**
     * Seconds until the vehicle reaches [targetSequence] on the same pattern.
     * Null when the vehicle is not going that way (already passed, or never served).
     */
    fun secondsToStop(
        pattern: PatternInfo,
        stopSequence: Int,
        status: String,
        speedKmh: Int,
        targetSequence: Int,
        targetKm: Double
    ): Int? {
        val from = progressKm(pattern, stopSequence, status) ?: return null
        if (targetSequence <= stopSequence) return null
        val stopsAhead = targetSequence - stopSequence
        if (stopsAhead > MAX_STOPS_AHEAD) return null
        val remainingKm = targetKm - from
        // `isFinite` first, and it has to come first: NaN fails every comparison, so a plain
        // `remainingKm <= 0` lets it through, and `NaN.toInt()` is 0 - which would report a bus
        // 15 km away as arriving now. `targetKm` comes from `JSONObject.optDouble`, which returns
        // NaN for a missing or non-numeric distance, so one pattern missing a `distance` is enough.
        if (!remainingKm.isFinite() || remainingKm <= 0) return null
        val speed = max(speedKmh.toDouble(), MIN_SPEED_KMH)
        val seconds = remainingKm / speed * 3600 + (stopsAhead - 1) * DWELL_SECONDS
        return if (seconds.isFinite()) seconds.toInt() else null
    }

    /**
     * Great-circle distance in **metres**.
     *
     * Named for its unit on purpose: this used to be called `distanceKm` while returning metres,
     * which had a caller scaling it by 1000 and reporting speeds 1000x too high.
     */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        // The haversine term is mathematically in [0,1], but rounding on two nearly antipodal
        // points can push it a hair past 1, and sqrt of that is NaN - which then propagates
        // through Motion into the ETA as a silently wrong number.
        return 2 * 6_371_000.0 * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }
}

/**
 * What the tapped-bus card should say about the user's saved stops.
 *
 * Only the countdown now. There used to be a third case for a bus running the opposite pattern
 * past a stop of the same name, but the card's header is now the vehicle's destination, which
 * says the same thing without the comparison — and in a form that also covers lines with no
 * saved stop nearby.
 */
sealed interface BusStopHint {
    /** The bus is not relevant to any saved stop. */
    data object None : BusStopHint

    /** The bus is heading to a saved stop, and this is how long that takes. */
    data class Arriving(val stopName: String, val seconds: Int) : BusStopHint
}

/**
 * Matches a live vehicle against the user's saved stops.
 *
 * Matching is by stop id within the vehicle's own pattern, so a bus is only ever reported as
 * approaching a stop it really will call at.
 *
 * Pure, and separate from the card so it can be tested without Android.
 */
fun busStopHint(
    vehicle: Vehicle,
    patterns: Collection<PatternInfo>,
    savedStops: List<SavedStop>,
    now: Long
): BusStopHint {
    if (Eta.isStale(vehicle, now)) return BusStopHint.None
    val pattern = patterns.firstOrNull { it.id == vehicle.patternId } ?: return BusStopHint.None
    val vehicleStop = Eta.vehicleStop(pattern, vehicle) ?: return BusStopHint.None

    val savedById = savedStops.associateBy { it.id }

    // Closest saved stop the bus is still approaching. Searching forward from where the bus
    // actually is matters on circular patterns, where the same stop appears once per lap.
    return pattern.stops
        .mapNotNull { s -> savedById[s.stopId]?.let { s to it } }
        .filter { it.first.sequence > vehicleStop.sequence }
        .mapNotNull { (stop, saved) ->
            Eta.secondsToStop(
                pattern = pattern,
                stopSequence = vehicleStop.sequence,
                status = vehicle.status,
                speedKmh = vehicle.speed,
                targetSequence = stop.sequence,
                targetKm = stop.km
            )?.let { BusStopHint.Arriving(saved.name, it) }
        }
        .minByOrNull { it.seconds }
        ?: BusStopHint.None
}
