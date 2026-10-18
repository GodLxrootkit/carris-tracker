package pt.carrismetropolitana.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EtaTest {

    /** 1 km legs, 3 km total, so every expected number below is checkable by hand. */
    private val pattern = PatternInfo(
        id = "1707_0_2",
        lineId = "1707",
        headsign = "Colégio Militar",
        shapeId = null,
        stops = (1..4).map { PatternStop("s$it", it, (it - 1).toDouble()) }
    )

    @Test
    fun stoppedBusIsAtItsStopKm() {
        assertEquals(1.0, Eta.progressKm(pattern, 2, "STOPPED_AT")!!, 0.001)
        assertEquals(1.0, Eta.progressKm(pattern, 2, "INCOMING_AT")!!, 0.001)
    }

    @Test
    fun transitIsHalfwayThroughTheLeg() {
        assertEquals(1.5, Eta.progressKm(pattern, 3, "IN_TRANSIT_TO")!!, 0.001)
    }

    @Test
    fun etaUsesSpeedPlusDwellPerIntermediateStop() {
        // 1.0 km left at 36 km/h = 100 s, no intermediate stop
        assertEquals(100, Eta.secondsToStop(pattern, 2, "STOPPED_AT", 36, 3, 2.0))
        // 2.0 km left at 36 km/h = 200 s, 1 intermediate stop = +20 s
        assertEquals(220, Eta.secondsToStop(pattern, 2, "STOPPED_AT", 36, 4, 3.0))
    }

    @Test
    fun slowBusFallsBackToMinimumSpeed() {
        // 1.0 km at the 12 km/h floor = 300 s
        assertEquals(300, Eta.secondsToStop(pattern, 2, "STOPPED_AT", 2, 3, 2.0))
    }

    @Test
    fun stopsBehindOrTooFarAwayAreNotPredicted() {
        assertNull(Eta.secondsToStop(pattern, 3, "STOPPED_AT", 30, 3, 2.0))
        assertNull(Eta.secondsToStop(pattern, 3, "STOPPED_AT", 30, 2, 1.0))
        assertNull(Eta.secondsToStop(pattern, 9, "STOPPED_AT", 30, 3, 2.0))
    }

    @Test
    fun staleVehiclesAreDropped() {
        val now = 1_700_000_000L
        val fresh = Vehicle(
            id = "1",
            lineId = "1707",
            patternId = pattern.id,
            stopId = "s1",
            lat = 0.0,
            lon = 0.0,
            speed = 20,
            status = "IN_TRANSIT_TO",
            timestampMs = now * 1000
        )
        val old = fresh.copy(timestampMs = (now - Eta.MAX_VEHICLE_AGE_SECONDS - 1) * 1000)
        assertEquals(false, Eta.isStale(fresh, now))
        assertEquals(true, Eta.isStale(old, now))
    }

    @Test
    fun catalogLineRoundTrips() {
        val stop = parseCatalogLine("030105\tAv Com Luis Antonio Silva 62B\t38.759686\t-9.229522\t1517,1707,1717")
        assertEquals("030105", stop!!.id)
        assertEquals("Av Com Luis Antonio Silva 62B", stop.name)
        assertEquals(38.759686, stop.lat, 1e-6)
        assertEquals(-9.229522, stop.lon, 1e-6)
        assertEquals(listOf("1517", "1707", "1717"), stop.lineIds)
    }

    @Test
    fun catalogLineRejectsGarbage() {
        assertNull(parseCatalogLine(""))
        assertNull(parseCatalogLine("030105\tname\t38.7\t-9.2"))
        assertNull(parseCatalogLine("030105\tname\tNaN\t-9.2\t1707"))
    }
}
