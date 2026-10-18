package pt.carrismetropolitana.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The GTFS-RT decoder reads the wire format by hand rather than depending on a protobuf runtime.
 *
 * That is a fair trade for keeping a minified build light, but it means every field boundary is
 * arithmetic on attacker-controlled bytes. A mis-step does not throw usefully: the caller wraps
 * the parse in `runCatching { }.getOrDefault(emptyList())`, so an exception quietly turns into
 * *every CARRIS bus vanishing from the map for that poll*.
 */
class ProtoReaderHardeningTest {

    private fun varint(value: Long): ByteArray {
        var v = value
        val out = ArrayList<Byte>()
        do {
            var b = (v and 0x7F).toInt()
            v = v ushr 7
            if (v != 0L) b = b or 0x80
            out.add(b.toByte())
        } while (v != 0L)
        return out.toByteArray()
    }

    private fun tag(field: Int, wire: Int) = varint(((field shl 3) or wire).toLong())

    /**
     * A length of 2^32 truncates to 0 when cast to Int. The old bounds check passed it, returned
     * an empty payload *and left `pos` where it was*, so parsing resumed inside the payload and
     * every subsequent field came out shifted - wrong values rather than an error.
     */
    @Test
    fun aLengthOfExactlyTwoToThe32DoesNotSilentlyTruncate() {
        val buf = tag(1, 2) + varint(1L shl 32) + byteArrayOf(0x0A, 0x2A)
        val r = ProtoReader(buf)
        r.readTag()
        r.readBytes()
        // Whatever it decides, the reader must not be left positioned inside the payload.
        assertTrue("reader did not bail out to the end", r.atEnd)
    }

    /**
     * A negative length (0xFFFFFFF9 as a varint is -7 as a Long) drove `pos` negative, and the next
     * `readTag` then indexed `buf[-1]`. Out of the whole parse, and therefore out of the feed.
     */
    @Test
    fun aNegativeLengthDoesNotThrowOutOfTheParse() {
        val buf = tag(1, 2) + byteArrayOf(0xF9.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x0F) +
            byteArrayOf(0x1A, 0x01, 0x07)
        val r = ProtoReader(buf)
        r.readTag()
        r.skip(2)
        assertTrue("reader did not bail out to the end", r.atEnd)
    }

    @Test
    fun skippingFixedWidthFieldsNearTheEndStaysInBounds() {
        // Claims an 8-byte and then a 4-byte field with fewer bytes remaining than that.
        val buf = tag(1, 1) + byteArrayOf(1, 2, 3)
        val r = ProtoReader(buf)
        r.readTag()
        r.skip(1)
        assertTrue(r.atEnd)

        val buf2 = tag(1, 5) + byteArrayOf(1, 2)
        val r2 = ProtoReader(buf2)
        r2.readTag()
        r2.skip(5)
        assertTrue(r2.atEnd)
    }

    /** Truncated payloads must degrade to empty, not to a crash the caller turns into "no buses". */
    @Test
    fun aTruncatedMessageYieldsNoVehiclesRatherThanThrowing() {
        val truncated = byteArrayOf(0x0A, 0x7F, 0x2A)
        assertEquals(emptyList<Vehicle>(), GtfsRt.parse(truncated))
    }

    @Test
    fun randomBytesNeverThrow() {
        val random = java.util.Random(42)
        repeat(500) {
            val buf = ByteArray(random.nextInt(64))
            random.nextBytes(buf)
            // The contract that matters: never propagate. An empty list is the safe answer.
            GtfsRt.parse(buf)
        }
    }

    /**
     * `current_stop_sequence` coverage lives in `GtfsRtTest`, which already builds a full
     * VehiclePosition message and sends that field.
 */
}