package pt.carrismetropolitana.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Wire-format tests for the hand-rolled GTFS-RT decoder.
 *
 * Messages are assembled byte by byte rather than checked in as a capture, so these stay
 * readable against gtfs-realtime.proto and fail loudly if a field number is wrong.
 */
class GtfsRtTest {

    /* ---- protobuf writer ---- */

    private class Proto {
        private val out = ByteArrayOutputStream()

        fun varint(value: Long): Proto = apply {
            var v = value
            while (true) {
                val b = (v and 0x7F).toInt()
                v = v ushr 7
                out.write(if (v == 0L) b else b or 0x80)
                if (v == 0L) return@apply
            }
        }

        fun tag(field: Int, wire: Int): Proto = varint(((field.toLong() shl 3) or wire.toLong()))

        fun uint(field: Int, value: Long): Proto = apply {
            tag(field, 0); varint(value)
        }

        fun bytes(field: Int, value: ByteArray): Proto = apply {
            tag(field, 2); varint(value.size.toLong()); out.write(value)
        }

        fun string(field: Int, value: String): Proto = bytes(field, value.toByteArray(Charsets.UTF_8))

        fun float(field: Int, value: Float): Proto = apply {
            tag(field, 5)
            val bits = java.lang.Float.floatToRawIntBits(value)
            for (i in 0..3) out.write((bits ushr (i * 8)) and 0xFF)
        }

        fun build(): ByteArray = out.toByteArray()
    }

    /** One VehiclePosition wrapped in FeedEntity and FeedMessage. */
    private fun vehicleMessage(
        tripId: String = "18952_20260606_164_0_6",
        routeId: String = "164_0",
        directionId: Int = 1,
        lat: Float = 38.74556f,
        lon: Float = -9.181755f,
        bearing: Float = 87f,
        speed: Float = 31f,
        stopId: String? = "10701",
        status: Int = 2,
        timestamp: Long = 1_790_852_000L,
        vehicleId: String? = "2214",
        plate: String? = "08-02-XS",
        deleted: Boolean = false,
        extra: Proto.() -> Unit = {}
    ): ByteArray {
        val trip = Proto()
            .string(1, tripId)
            .uint(6, directionId.toLong())
            .apply { extra() }
            .string(5, routeId)
            .build()

        val position = Proto().float(1, lat).float(2, lon).float(3, bearing).float(5, speed).build()

        val vehicle = Proto()
            .bytes(1, trip)
            .bytes(2, position)
            // Wire numbers from a live capture, not declaration order: current_stop_sequence=3,
            // current_status=4, timestamp=5, stop_id=7, vehicle=8.
            .uint(3, 4L)
            .uint(4, status.toLong())
            .uint(5, timestamp)
            .apply { if (stopId != null) string(7, stopId) }
            .apply {
                if (vehicleId != null || plate != null) {
                    val d = Proto()
                    if (vehicleId != null) d.string(1, vehicleId)
                    if (plate != null) d.string(3, plate)
                    bytes(8, d.build())
                }
            }
            .build()

        val entity = Proto()
            .string(1, tripId)
            .apply { if (deleted) uint(2, 1L) }
            .bytes(4, vehicle)
            .build()

        return Proto().bytes(2, entity).build()
    }

    @Test
    fun parsesEveryFieldTheAppReads() {
        val vehicles = GtfsRt.parse(vehicleMessage())
        assertEquals(1, vehicles.size)
        val v = vehicles.single()

        assertEquals("164_0", v.routeId)
        assertEquals(1, v.directionId)
        assertEquals(38.74556f.toDouble(), v.lat, 1e-6)
        assertEquals(-9.181755f.toDouble(), v.lon, 1e-6)
        assertEquals(87, v.bearing)
        assertEquals(31, v.speedKmh)
        assertEquals("10701", v.stopId)
        assertEquals("IN_TRANSIT_TO", v.status)
        assertEquals(1_790_852_000L, v.timestampSec)
        assertEquals("2214", v.vehicleId)
        assertEquals("08-02-XS", v.licensePlate)
        assertEquals("18952_20260606_164_0_6", v.tripId)
        // The field used to be discarded. It is the only thing that says which lap of a circular
        // pattern a bus is on, and without it a stop the pattern visits twice resolves to the
        // occurrence behind the bus.
        assertEquals(4, v.currentStopSequence)
    }

    /**
     * Unknown fields must be skipped by wire type, not by field number: CARRIS adding a field
     * has to keep this decoder working. Field 99 (varint) and 98 (bytes) stand in.
     */
    @Test
    fun skipsUnknownFieldsOfEveryWireType() {
        val padded = vehicleMessage(
            extra = {
                uint(99, 42L)          // unknown varint
                string(98, "junk")     // unknown length-delimited
                float(97, 1.5f)        // unknown fixed32
            }
        )
        val v = GtfsRt.parse(padded).single()
        assertEquals("164_0", v.routeId)
        assertEquals(38.74556f.toDouble(), v.lat, 1e-6)
        assertEquals(31, v.speedKmh)
        assertEquals("10701", v.stopId)
    }

    @Test
    fun unknownFieldBetweenKnownFieldsDoesNotShiftTheStream() {
        // Field order in the message is arbitrary; a trailing unknown field must not truncate it.
        val v = GtfsRt.parse(vehicleMessage(extra = { uint(77, 7L) })).single()
        assertEquals("IN_TRANSIT_TO", v.status)
        assertEquals(1_790_852_000L, v.timestampSec)
    }

    @Test
    fun deletedEntitiesAreDropped() {
        assertTrue(GtfsRt.parse(vehicleMessage(deleted = true)).isEmpty())
    }

    /** (0, 0) is protobuf's default for an absent position, and is not in Lisbon. */
    @Test
    fun vehiclesWithoutAPositionAreDropped() {
        assertTrue(GtfsRt.parse(vehicleMessage(lat = 0f, lon = 0f)).isEmpty())
    }

    @Test
    fun stopIdAndVehicleDescriptorAreOptional() {
        val v = GtfsRt.parse(vehicleMessage(stopId = null, vehicleId = null, plate = null)).single()
        assertNull(v.stopId)
        assertNull(v.vehicleId)
        assertNull(v.licensePlate)
    }

    @Test
    fun stopStatusSpelledToMatchTheCmApi() {
        assertEquals("IN_TRANSIT_TO", GtfsRt.statusName(2))
        assertEquals("INCOMING_AT", GtfsRt.statusName(3))
        assertEquals("STOPPED_WITH_DOORS_OPEN", GtfsRt.statusName(6))
        assertEquals("UNKNOWN", GtfsRt.statusName(99))
    }

    @Test
    fun multipleEntitiesAreAllParsed() {
        val two = vehicleMessage() + vehicleMessage(routeId = "100_0", directionId = 0, tripId = "t2")
        val vehicles = GtfsRt.parse(two)
        assertEquals(2, vehicles.size)
        assertEquals(listOf("164_0", "100_0"), vehicles.map { it.routeId })
    }

    @Test
    fun emptyFeedIsNotAnError() {
        assertTrue(GtfsRt.parse(ByteArray(0)).isEmpty())
    }

    /** Direction must survive, since it is what selects the ASC/DESC pattern. */
    @Test
    fun directionZeroIsDistinctFromAbsent() {
        val v = GtfsRt.parse(vehicleMessage(directionId = 0)).single()
        assertEquals(0, v.directionId)
    }
}