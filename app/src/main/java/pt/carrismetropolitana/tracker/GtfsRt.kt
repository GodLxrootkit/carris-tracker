package pt.carrismetropolitana.tracker

/**
 * Minimal GTFS-RT `VehiclePosition` decoder.
 *
 * CARRIS publishes live positions as protobuf at
 * `https://gateway.carris.pt/gateway/gtfs/api/v2.11/GTFS/realtime/vehiclepositions`, ~6 KB and
 * served with no authentication. Rather than take a protobuf-javalite dependency (which would
 * also need ProGuard rules, since the release build minifies), this reads the handful of wire
 * types the feed actually uses.
 *
 * Only `VehiclePosition` is handled. CARRIS publishes no `tripupdates`, `stopupdates` or
 * `alerts` entities, so those are not needed here — ETAs are derived from positions by [Eta].
 *
 * Field numbers come from gtfs-realtime.proto. Unknown fields are skipped by wire type, which
 * is what keeps this working when CARRIS adds fields.
 */
object GtfsRt {

    // FeedMessage
    private const val FEED_ENTITY = 2

    // FeedEntity
    private const val ENTITY_IS_DELETED = 2
    private const val ENTITY_VEHICLE = 4

    // VehiclePosition. Field numbers are not sequential, and were read off a live capture
    // rather than assumed: current_stop_sequence=3, current_status=4, timestamp=5,
    // vehicle=6, stop_id=7, position=8 in the .proto declaration order but the wire numbers
    // CARRIS actually sends are as below.
    private const val VP_TRIP = 1
    private const val VP_POSITION = 2
    private const val VP_STOP_ID = 7
private const val VP_CURRENT_STOP_SEQUENCE = 3
    private const val VP_CURRENT_STATUS = 4
    private const val VP_TIMESTAMP = 5
    private const val VP_VEHICLE = 8

    // TripDescriptor
    private const val TD_TRIP_ID = 1
    private const val TD_ROUTE_ID = 5
    private const val TD_DIRECTION_ID = 6

    // Position
    private const val POS_LAT = 1
    private const val POS_LON = 2
    private const val POS_BEARING = 3
    private const val POS_SPEED = 5

    // VehicleDescriptor
    private const val VD_ID = 1
    private const val VD_LICENSE_PLATE = 3

    /** GTFS-RT `VehicleStopStatus`, spelled exactly as the Carris Metropolitana API spells it. */
    private val STOP_STATUS = arrayOf(
        "INCOMING", "STOPPED", "IN_TRANSIT_TO", "INCOMING_AT",
        "OUT_OF_SERVICE", "UNKNOWN", "STOPPED_WITH_DOORS_OPEN"
    )

    fun statusName(raw: Int): String = STOP_STATUS.getOrElse(raw) { "UNKNOWN" }

    /** Parse a FeedMessage into vehicles. Entries without a usable position are dropped. */
    fun parse(bytes: ByteArray): List<RtVehicle> {
        val out = ArrayList<RtVehicle>()
        val feed = ProtoReader(bytes)
        while (true) {
            val tag = feed.readTag() ?: break
            if (tag ushr 3 == FEED_ENTITY) {
                parseEntity(feed.readBytes(), out)
            } else {
                feed.skip(tag and 0x07)
            }
        }
        return out
    }

    private fun parseEntity(buf: ByteArray, out: MutableList<RtVehicle>) {
        val r = ProtoReader(buf)
        var deleted = false
        var vehicle: ByteArray? = null
        while (true) {
            val tag = r.readTag() ?: break
            when (tag ushr 3) {
                ENTITY_IS_DELETED -> deleted = r.readVarint() != 0L
                ENTITY_VEHICLE -> vehicle = r.readBytes()
                else -> r.skip(tag and 0x07)
            }
        }
        if (deleted || vehicle == null) return
        parseVehicle(vehicle, out)?.let(out::add)
    }

    private fun parseVehicle(buf: ByteArray, out: MutableList<RtVehicle>): RtVehicle? {
        val r = ProtoReader(buf)
        var trip: ByteArray? = null
        var position: ByteArray? = null
        var stopId: String? = null
        var currentStopSequence: Int? = null
        var status = "UNKNOWN"
        var timestamp = 0L
        var descriptor: ByteArray? = null

        while (true) {
            val tag = r.readTag() ?: break
            when (tag ushr 3) {
                VP_TRIP -> trip = r.readBytes()
                VP_POSITION -> position = r.readBytes()
                // The only way to tell which lap of a circular pattern a bus is on. Without it a
                // stop the pattern visits twice cannot be resolved to the right occurrence.
                VP_CURRENT_STOP_SEQUENCE -> currentStopSequence = r.readVarint().toInt()
                VP_STOP_ID -> stopId = r.readString()
                VP_CURRENT_STATUS -> status = statusName(r.readVarint().toInt())
                VP_TIMESTAMP -> timestamp = r.readVarint()
                VP_VEHICLE -> descriptor = r.readBytes()
                else -> r.skip(tag and 0x07)
            }
        }

        // A position of (0, 0) is protobuf's default for absent and is in the Gulf of Guinea,
        // never on the CARRIS network, so it doubles as "no position".
        var lat = 0.0
        var lon = 0.0
        var bearing = 0
        var speed = 0
        if (position != null) {
            val p = ProtoReader(position)
            while (true) {
                val tag = p.readTag() ?: break
                when (tag ushr 3) {
                    POS_LAT -> lat = p.readFloat().toDouble()
                    POS_LON -> lon = p.readFloat().toDouble()
                    POS_BEARING -> bearing = p.readFloat().toInt()
                    POS_SPEED -> speed = p.readFloat().toInt()
                    else -> p.skip(tag and 0x07)
                }
            }
        }
        if (lat == 0.0 && lon == 0.0) return null

        var tripId = ""
        var routeId = ""
        var directionId = 0
        if (trip != null) {
            val t = ProtoReader(trip)
            while (true) {
                val tag = t.readTag() ?: break
                when (tag ushr 3) {
                    TD_TRIP_ID -> tripId = t.readString()
                    TD_ROUTE_ID -> routeId = t.readString()
                    TD_DIRECTION_ID -> directionId = t.readVarint().toInt()
                    else -> t.skip(tag and 0x07)
                }
            }
        }

        var vehicleId: String? = null
        var plate: String? = null
        if (descriptor != null) {
            val d = ProtoReader(descriptor)
            while (true) {
                val tag = d.readTag() ?: break
                when (tag ushr 3) {
                    VD_ID -> vehicleId = d.readString()
                    VD_LICENSE_PLATE -> plate = d.readString()
                    else -> d.skip(tag and 0x07)
                }
            }
        }

        return RtVehicle(
            tripId = tripId,
            routeId = routeId,
            directionId = directionId,
            lat = lat,
            lon = lon,
            bearing = bearing,
            speedKmh = speed,
            stopId = stopId,
            currentStopSequence = currentStopSequence,
            status = status,
            timestampSec = timestamp,
            vehicleId = vehicleId,
            licensePlate = plate
        )
    }
}

/** One live vehicle as decoded from the GTFS-RT feed. */
data class RtVehicle(
    val tripId: String,
    val routeId: String,
    val directionId: Int,
    val lat: Double,
    val lon: Double,
    val bearing: Int,
    val speedKmh: Int,
    val stopId: String?,
    /** Null when the feed omits it; Carris Metropolitana's `/vehicles` has no such field. */
    val currentStopSequence: Int?,
    val status: String,
    val timestampSec: Long,
    val vehicleId: String?,
    val licensePlate: String?
)

/**
 * Protobuf wire-format reader over a single buffer.
 *
 * Nested messages arrive as length-delimited fields, so each is parsed into its own reader
 * rather than the outer one, which keeps [parse] free of manual offset bookkeeping.
 */
internal class ProtoReader(private val buf: ByteArray, private var pos: Int = 0, private val end: Int = buf.size) {

    /**
     * True once the reader has consumed everything or bailed out.
     *
     * Every length check in here ends in `pos = end`, so this is how a test asserts that a
     * malformed length was rejected rather than half-applied.
     */
    val atEnd: Boolean get() = pos >= end

    /**
     * Reads a field key, or null at the end of the buffer.
     *
     * The value is the raw protobuf key: field number in the high bits, wire type in the low
     * three. Callers dispatch on `tag ushr 3` and pass `tag and 0x07` to [skip].
     */
    fun readTag(): Int? {
        if (pos >= end) return null
        return (readVarint() and 0xFFFFFFFFL).toInt()
    }

    fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (pos < end) {
            val b = buf[pos++].toInt()
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            if (shift > 63) break
        }
        return result
    }

    fun readFloat(): Float {
        if (pos + 4 > end) { pos = end; return 0f }
        // fixed32, little-endian.
        val bits = (buf[pos].toInt() and 0xFF) or
            ((buf[pos + 1].toInt() and 0xFF) shl 8) or
            ((buf[pos + 2].toInt() and 0xFF) shl 16) or
            ((buf[pos + 3].toInt() and 0xFF) shl 24)
        pos += 4
        return java.lang.Float.intBitsToFloat(bits)
    }

    /**
     * Reads a length-delimited payload.
     *
     * The length is a 64-bit varint, so it has to be compared as a `Long`: casting to `Int` first
     * turns 2^32 into 0, which passes the bounds check and then leaves `pos` unadvanced, so the
     * next field is read out of the middle of this one's payload. `end - pos` rather than
     * `pos + len` because the addition itself can overflow.
     */
    fun readBytes(): ByteArray {
        val len = readVarint()
        if (len < 0 || len > end - pos) { pos = end; return ByteArray(0) }
        val out = buf.copyOfRange(pos, pos + len.toInt())
        pos += len.toInt()
        return out
    }

    /** UTF-8 strings and bytes share wire type 2; protobuf guarantees valid UTF-8 here. */
    fun readString(): String = String(readBytes(), Charsets.UTF_8)

    /**
     * Advance past the current field's payload, given the wire type from the already-read tag.
     *
     * The length-delimited arm reads into a local on purpose. `pos += readVarint()` would
     * capture the old `pos` before `readVarint()` mutates it, landing one byte short and
     * corrupting every field after the first skipped one.
     *
     * It bounds-checks for the same reason [readBytes] does, and with the same `Long` arithmetic.
     * Unchecked, a corrupt length drives `pos` negative and the next `readTag` indexes
     * `buf[-1]`, throwing out of the whole parse - which the caller catches and turns into an
     * empty vehicle list, silently dropping every CARRIS bus for that poll.
     */
    fun skip(wireType: Int) {
        when (wireType) {
            0 -> readVarint()          // varint
            1 -> if (end - pos < 8) pos = end else pos += 8              // 64-bit
            2 -> {                     // length-delimited
                val len = readVarint()
                if (len < 0 || len > end - pos) pos = end else pos += len.toInt()
            }
            5 -> if (end - pos < 4) pos = end else pos += 4              // 32-bit
            else -> pos = end
        }
    }
}