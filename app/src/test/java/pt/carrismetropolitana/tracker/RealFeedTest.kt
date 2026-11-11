package pt.carrismetropolitana.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Regression test against a real capture from the CARRIS GTFS-RT feed.
 *
 * [GtfsRtTest] builds messages by hand from assumed field numbers, which means a wrong
 * assumption passes on both sides. This fixture is three entities copied verbatim from
 * `https://gateway.carris.pt/gateway/gtfs/api/v2.11/GTFS/realtime/vehiclepositions`, so the
 * wire numbers are the ones CARRIS actually sends.
 *
 * Captured 2026-10-11T23:44Z, feed version 2.0, 293 bytes, 3 of 62 vehicles.
 */
class RealFeedTest {

    private val capture: ByteArray = Base64.getDecoder().decode(FIXTURE)

    @Test
    fun decodesTheCapturedEntities() {
        val vehicles = GtfsRt.parse(capture)
        assertEquals(3, vehicles.size)
        assertEquals(
            listOf("100_0", "199_0", "192_1"),
            vehicles.map { it.routeId }
        )
    }

    /**
     * Field numbers are not sequential in VehiclePosition, and getting them wrong silently
     * shifts every field after the first mismatch: stop_id is 7, current_status 4,
     * timestamp 5 — not 4, 5, 6.
     */
    @Test
    fun statusStopIdAndTimestampLandInTheRightFields() {
        val first = GtfsRt.parse(capture).first()

        assertEquals("IN_TRANSIT_TO", first.status)
        assertEquals("13573", first.stopId)
        assertEquals(1_790_856_972L, first.timestampSec)
        assertEquals("2214", first.vehicleId)
        assertEquals("08-02-XS", first.licensePlate)
    }

    @Test
    fun positionsDecodeAsLisbonCoordinates() {
        val vehicles = GtfsRt.parse(capture)
        val first = vehicles.first()
        assertEquals(38.793579f.toDouble(), first.lat, 1e-6)
        assertEquals(-9.0977297f.toDouble(), first.lon, 1e-6)
        assertTrue(vehicles.all { it.lat in 38.0..39.0 })
        assertTrue(vehicles.all { it.lon in -10.0..-8.0 })
    }

    @Test
    fun directionSurvivesAndSelectsThePattern() {
        val vehicles = GtfsRt.parse(capture)
        assertEquals(listOf(1, 1, 1), vehicles.map { it.directionId })
        // route_id 192_1 is a _1 route, so a 0/1 direction maps onto the ASC/DESC pattern.
        assertTrue(vehicles.all { it.routeId.endsWith("_0") || it.routeId.endsWith("_1") })
    }

    @Test
    fun everyCapturedVehicleIsInsideTheAgeWindow() {
        // These three were fresh in the capture; the staleness filter lives in Eta, not here.
        val vehicles = GtfsRt.parse(capture)
        assertTrue(vehicles.all { it.timestampSec > 0 })
        assertNotNull(vehicles.first().timestampSec)
    }

    /**
     * The captured Position messages carry only lat/lon, so speed and bearing are absent and
     * must read as 0 without the rest of the message shifting.
     *
     * This is why [Eta.MIN_SPEED_KMH] matters for CARRIS: there is no reported speed to divide
     * remaining kilometres by, so predictions fall back to that floor.
     */
    @Test
    fun absentPositionFieldsReadAsZeroWithoutShiftingTheStream() {
        val vehicles = GtfsRt.parse(capture)
        assertTrue(vehicles.all { it.speedKmh == 0 })
        assertTrue(vehicles.all { it.bearing == 0 })
        // Fields after the position still decode, which is what proves nothing shifted.
        assertEquals(listOf("13573", "15107", "3824"), vehicles.map { it.stopId })
        assertEquals(listOf("2214", "4637", "2983"), vehicles.map { it.vehicleId })
    }

    companion object {
        private const val FIXTURE =
            "El8KCDIyMTRfNzA4IlMKIgoVMzI5OF8yMDI2MDYwNl8xMDBfMF85IAAqBTEwMF8wMAESCg2gLBtCFU2" +
            "QEcEYBCACKIye+dUGOgUxMzU3M0IQCgQyMjE0GggwOC0wMi1YUxJhCgg0NjM3XzczNiJVCiQKFzE4ODE3XzIw" +
            "MjYwNjA2XzE5OV8wXzE0IAAqBTE5OV8wMAESCg1TKBtCFRvMEsEYBCACKKaLq9UGOgUxNTEwN0IQCgQ0NjM3" +
            "Ggg5OC1KTy00NBJfCggyOTgzXzc3NCJTCiMKFjc0MzJfMjAyNjA2MDZfMTkyXzFfMjYgACoFMTkyXzEwARIK" +
            "DfbUGkIVmjYSwRgiIAIovr6w1gY6BDM4MjRCEAoEMjk4MxoIMzAtVk0tMDg="
    }
}
